package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.AgentMessage;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationConfig;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用讨论策略
 * 合并了原 DebateStrategy、RolePlayStrategy、ConsensusStrategy 三种模式。
 * 三者本质相同：多 Agent 轮流发言若干轮，最后汇总结论。
 * 差异仅体现在提示词风格和是否开启投票，通过 CollaborationConfig.Mode 区分。
 */
public class DiscussionStrategy implements CollaborationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    /** 投票选项提取正则（共识模式使用） */
    private static final Pattern VOTE_PATTERN = Pattern.compile("\\[投票[:：]?\\s*([^\\]]+)\\]");

    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        if (agents.isEmpty()) {
            return "讨论至少需要 1 个参与者";
        }
        if (config.getMode() == CollaborationConfig.Mode.DEBATE && agents.size() < 2) {
            return "辩论至少需要 2 个参与者";
        }

        logger.info("开始讨论", Map.of(
                "mode", config.getMode().name(),
                "topic", context.getTopic(),
                "participants", agents.size(),
                "maxRounds", config.getMaxRounds()
        ));

        // 统一由 shouldTerminate 控制循环终止，不在循环体内 break
        while (!shouldTerminate(context, config)) {
            context.nextRound();

            // 确定本轮参与发言的 Agent 范围（辩论模式最后一个是裁判，不参与轮次发言）
            int speakerCount = isDebateMode(config) && agents.size() > 2
                    ? agents.size() - 1
                    : agents.size();

            for (int i = 0; i < speakerCount; i++) {
                AgentExecutor speaker = agents.get(i);
                String prompt = buildRoundPrompt(config, context, speaker, i, speakerCount);
                String response = speaker.speak(context, prompt);
                context.addMessage(speaker.getAgentId(), speaker.getRoleName(), response);

                logger.info("Agent 发言", Map.of(
                        "round", context.getCurrentRound(),
                        "speaker", speaker.getRoleName(),
                        "responseLength", response.length()
                ));

                // 角色扮演模式：检测主动结束标记，写入类型安全字段
                if (isRolePlayMode(config) && isConversationEnded(response)) {
                    context.markEndedBy(speaker.getRoleName());
                    break;
                }
            }

            // 共识模式：每轮结束后收集投票，检查是否达成共识
            if (isConsensusMode(config) && !context.isEndedByRole()) {
                Map<String, List<String>> votes = collectVotes(agents, context);
                context.setVotes(context.getCurrentRound(), votes);
                if (checkConsensus(votes, agents.size(), config.getConsensusThreshold())) {
                    String majorityOption = getMajorityOption(votes);
                    context.markConsensusReached(majorityOption);
                    logger.info("达成共识", Map.of("option", majorityOption));
                }
            }
        }

        String conclusion = buildConclusion(context, agents, config);
        context.setFinalConclusion(conclusion);

        logger.info("讨论结束", Map.of(
                "totalRounds", context.getCurrentRound(),
                "totalMessages", context.getHistory().size()
        ));

        return conclusion;
    }

    // -------------------------------------------------------------------------
    // 提示词构建
    // -------------------------------------------------------------------------

    private String buildRoundPrompt(CollaborationConfig config, SharedContext context,
                                    AgentExecutor speaker, int speakerIndex, int totalSpeakers) {
        return switch (config.getMode()) {
            case DEBATE -> buildDebatePrompt(context, speaker, speakerIndex);
            case ROLEPLAY -> buildRolePlayPrompt(speaker);
            case CONSENSUS -> buildConsensusPrompt(context);
            default -> "请基于以上信息给出你的观点。";
        };
    }

    private String buildDebatePrompt(SharedContext context, AgentExecutor speaker, int speakerIndex) {
        if (context.getCurrentRound() == 1 && context.getHistory().isEmpty()) {
            return "这是一场辩论，请先阐述你的核心观点和主要论据。";
        }
        return "这是一场辩论，请针对对方的观点进行反驳，并进一步强化你的论点。";
    }

    private String buildRolePlayPrompt(AgentExecutor speaker) {
        return "这是一个角色扮演场景，你扮演的角色是：" + speaker.getRoleName() + "\n\n" +
                "请完全代入这个角色进行对话，保持角色的语气和立场。\n" +
                "如果对话已达到自然结束点，可在回复末尾加上 [对话结束] 标记。";
    }

    private String buildConsensusPrompt(SharedContext context) {
        StringBuilder prompt = new StringBuilder("这是一个需要达成共识的决策讨论。\n\n");
        if (context.getCurrentRound() == 1) {
            prompt.append("请先分析问题，阐述你的观点和建议方案。\n");
        } else {
            prompt.append("基于之前的讨论，请补充你的观点或调整你的立场。\n");
        }
        prompt.append("\n讨论结束后，请在回复末尾用 [投票:你的选择] 格式表明最终选择。\n");
        prompt.append("例如：[投票:方案A] 或 [投票:同意] 或 [投票:反对]");
        return prompt.toString();
    }

    // -------------------------------------------------------------------------
    // 结论构建
    // -------------------------------------------------------------------------

    private String buildConclusion(SharedContext context, List<AgentExecutor> agents,
                                   CollaborationConfig config) {
        return switch (config.getMode()) {
            case DEBATE -> buildDebateConclusion(context, agents);
            case ROLEPLAY -> buildRolePlayConclusion(context);
            case CONSENSUS -> buildConsensusConclusion(context, agents.size());
            default -> "讨论完成。";
        };
    }

    private String buildDebateConclusion(SharedContext context, List<AgentExecutor> agents) {
        // 如果有裁判（第 3 个及以后的 Agent），让裁判总结
        if (agents.size() > 2) {
            AgentExecutor judge = agents.get(agents.size() - 1);
            String judgePrompt = "你是辩论的裁判。请根据以上双方的辩论内容，做出公正的评判和总结，" +
                    "说明哪一方论据更有说服力，并给出最终结论。";
            String judgeConclusion = judge.speak(context, judgePrompt);
            context.addMessage(judge.getAgentId(), judge.getRoleName(), judgeConclusion);
            return judgeConclusion;
        }
        return "=== 辩论总结 ===\n\n主题：" + context.getTopic() +
                "\n\n共进行了 " + context.getCurrentRound() + " 轮辩论，以上是双方的辩论内容，请用户自行判断。";
    }

    private String buildRolePlayConclusion(SharedContext context) {
        StringBuilder conclusion = new StringBuilder("=== 角色扮演对话记录 ===\n\n");
        conclusion.append("场景：").append(context.getTopic()).append("\n\n");
        for (AgentMessage msg : context.getHistory()) {
            conclusion.append("【").append(msg.getAgentRole()).append("】\n");
            conclusion.append(msg.getContent()).append("\n\n");
        }
        conclusion.append("---\n共 ").append(context.getCurrentRound()).append(" 轮对话，");
        conclusion.append(context.getHistory().size()).append(" 条消息。");
        if (context.isEndedByRole()) {
            conclusion.append("\n由【").append(context.getEndedByRole()).append("】主动结束对话。");
        }
        return conclusion.toString();
    }

    private String buildConsensusConclusion(SharedContext context, int totalVoters) {
        StringBuilder conclusion = new StringBuilder("=== 共识决策结果 ===\n\n");
        conclusion.append("议题：").append(context.getTopic()).append("\n");
        conclusion.append("参与人数：").append(totalVoters).append("\n");
        conclusion.append("讨论轮次：").append(context.getCurrentRound()).append("\n\n");

        if (context.isConsensusReached()) {
            conclusion.append("【结论】达成共识\n共识选项：").append(context.getConsensusOption()).append("\n");
        } else {
            conclusion.append("【结论】未达成共识\n");
        }

        Map<String, List<String>> lastVotes = context.getLatestVotes();
        if (lastVotes != null && !lastVotes.isEmpty()) {
            conclusion.append("\n最终投票分布：\n");
            for (Map.Entry<String, List<String>> entry : lastVotes.entrySet()) {
                conclusion.append("  ").append(entry.getKey()).append(": ");
                conclusion.append(String.join(", ", entry.getValue()));
                conclusion.append(" (").append(entry.getValue().size()).append("票)\n");
            }
        }
        return conclusion.toString();
    }

    // -------------------------------------------------------------------------
    // 共识投票辅助方法
    // -------------------------------------------------------------------------

    private Map<String, List<String>> collectVotes(List<AgentExecutor> agents, SharedContext context) {
        Map<String, List<String>> votes = new HashMap<>();
        List<AgentMessage> recentMessages = context.getRecentMessages(agents.size());
        for (AgentMessage msg : recentMessages) {
            String vote = extractVote(msg.getContent());
            if (vote != null) {
                votes.computeIfAbsent(vote, k -> new ArrayList<>()).add(msg.getAgentRole());
            }
        }
        return votes;
    }

    private String extractVote(String content) {
        if (content == null) return null;
        Matcher matcher = VOTE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean checkConsensus(Map<String, List<String>> votes, int totalVoters, double threshold) {
        return votes.values().stream()
                .anyMatch(voters -> (double) voters.size() / totalVoters >= threshold);
    }

    private String getMajorityOption(Map<String, List<String>> votes) {
        return votes.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse("无");
    }

    // -------------------------------------------------------------------------
    // 角色扮演结束检测
    // -------------------------------------------------------------------------

    private boolean isConversationEnded(String response) {
        return response != null && (
                response.contains("[对话结束]") ||
                response.contains("[END]") ||
                response.contains("[结束]")
        );
    }

    // -------------------------------------------------------------------------
    // 模式判断辅助
    // -------------------------------------------------------------------------

    private boolean isDebateMode(CollaborationConfig config) {
        return config.getMode() == CollaborationConfig.Mode.DEBATE;
    }

    private boolean isRolePlayMode(CollaborationConfig config) {
        return config.getMode() == CollaborationConfig.Mode.ROLEPLAY;
    }

    private boolean isConsensusMode(CollaborationConfig config) {
        return config.getMode() == CollaborationConfig.Mode.CONSENSUS;
    }

    // -------------------------------------------------------------------------
    // CollaborationStrategy 接口实现
    // -------------------------------------------------------------------------

    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        if (context.getCurrentRound() >= config.getMaxRounds()) {
            return true;
        }
        if (config.getTimeoutMs() > 0 && context.getElapsedTime() > config.getTimeoutMs()) {
            return true;
        }
        // 角色扮演：被主动结束
        if (isRolePlayMode(config) && context.isEndedByRole()) {
            return true;
        }
        // 共识模式：已达成共识
        if (isConsensusMode(config) && context.isConsensusReached()) {
            return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return "Discussion";
    }

    @Override
    public String getDescription() {
        return "通用讨论策略：支持辩论、角色扮演、共识决策三种讨论形式";
    }
}
