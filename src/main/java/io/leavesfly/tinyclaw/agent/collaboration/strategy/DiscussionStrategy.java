package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用讨论策略
 * <p>统一处理所有 DISCUSS 模式下的四种风格：
 * <ul>
 *   <li>DEBATE — 辩论式，正反方观点对决</li>
 *   <li>ROLEPLAY — 角色扮演式对话</li>
 *   <li>CONSENSUS — 共识投票式决策</li>
 *   <li>DYNAMIC — 由 Router Agent 动态选择下一个发言者</li>
 * </ul>
 */
public class DiscussionStrategy extends AbstractCollaborationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    /** 投票选项提取正则（共识模式使用） */
    private static final Pattern VOTE_PATTERN = Pattern.compile("\\[投票[:：]?\\s*([^\\]]+)\\]");

    /** 路由指令提取正则：[NEXT:角色名]（动态路由使用） */
    private static final Pattern NEXT_PATTERN = Pattern.compile("\\[NEXT[:：]\\s*([^\\]]+)\\]");

    /** 结束标记（动态路由使用） */
    private static final Pattern CONCLUDE_PATTERN = Pattern.compile("\\[CONCLUDE\\]");

    /** 执行上下文（动态路由风格需要用来创建 Router Agent，可为 null） */
    private ExecutionContext executionContext;

    // -------------------------------------------------------------------------
    // 策略内部状态（仅 DISCUSS 模式使用，不暴露到 SharedContext）
    // -------------------------------------------------------------------------

    /** 角色扮演模式：主动结束对话的角色名称 */
    private volatile String endedByRole;

    /** 共识模式：是否已达成共识 */
    private volatile boolean consensusReached;

    /** 共识模式：达成共识的选项 */
    private volatile String consensusOption;

    /** 共识模式：各轮投票结果（轮次 → 选项 → 投票者列表） */
    private final Map<Integer, Map<String, List<String>>> votesByRound = new HashMap<>();

    /** 最新投票结果缓存（用于结论生成） */
    private Map<String, List<String>> latestVotes;

    /**
     * 注入执行上下文（动态路由风格需要）
     */
    public void setExecutionContext(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    /**
     * 重置策略内部状态（每次执行前调用，避免上次执行的状态残留）
     */
    private void resetState() {
        this.endedByRole = null;
        this.consensusReached = false;
        this.consensusOption = null;
        this.votesByRound.clear();
        this.latestVotes = null;
    }

    @Override
    public String execute(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        if (agents.isEmpty()) {
            return "讨论至少需要 1 个参与者";
        }

        // 重置策略内部状态
        resetState();

        // 动态路由风格走独立的执行路径
        if (isDynamicStyle(config)) {
            return executeDynamic(context, agents, config);
        }

        if (isDebateStyle(config) && agents.size() < 2) {
            return "辩论至少需要 2 个参与者";
        }

        logger.info("开始讨论", Map.of(
                "style", String.valueOf(config.getDiscussStyle()),
                "topic", context.getTopic(),
                "participants", agents.size(),
                "maxRounds", config.getMaxRounds()
        ));

        while (!shouldTerminate(context, config)) {
            context.nextRound();

            int speakerCount = isDebateStyle(config) && agents.size() > 2
                    ? agents.size() - 1
                    : agents.size();

            for (int i = 0; i < speakerCount; i++) {
                RoleAgent speaker = agents.get(i);
                String prompt = buildRoundPrompt(config, context, speaker, i, speakerCount);
                String response = speakWithStream(speaker, context, prompt);
                addMessageWithStream(context, speaker.getAgentId(), speaker.getRoleName(), response);

                logger.info("Agent 发言", Map.of(
                        "round", context.getCurrentRound(),
                        "speaker", speaker.getRoleName(),
                        "responseLength", response.length()
                ));

                if (isRolePlayStyle(config) && isConversationEnded(response)) {
                    endedByRole = speaker.getRoleName();
                    break;
                }
            }

            if (isConsensusStyle(config) && endedByRole == null) {
                Map<String, List<String>> votes = collectVotes(agents, context);
                votesByRound.put(context.getCurrentRound(), votes);
                latestVotes = votes;
                if (checkConsensus(votes, agents.size(), config.getConsensusThreshold())) {
                    String majorityOption = getMajorityOption(votes);
                    consensusReached = true;
                    consensusOption = majorityOption;
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
                                    RoleAgent speaker, int speakerIndex, int totalSpeakers) {
        CollaborationConfig.DiscussStyle style = config.getDiscussStyle();
        if (style == null) {
            return "请基于以上信息给出你的观点。";
        }
        return switch (style) {
            case DEBATE -> buildDebatePrompt(context, speaker, speakerIndex);
            case ROLEPLAY -> buildRolePlayPrompt(speaker);
            case CONSENSUS -> buildConsensusPrompt(context);
            case DYNAMIC -> "请基于以上信息给出你的观点。";
        };
    }

    private String buildDebatePrompt(SharedContext context, RoleAgent speaker, int speakerIndex) {
        if (context.getCurrentRound() == 1 && context.getHistory().isEmpty()) {
            return "这是一场辩论，请先阐述你的核心观点和主要论据。";
        }
        return "这是一场辩论，请针对对方的观点进行反驳，并进一步强化你的论点。";
    }

    private String buildRolePlayPrompt(RoleAgent speaker) {
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

    private String buildConclusion(SharedContext context, List<RoleAgent> agents,
                                   CollaborationConfig config) {
        CollaborationConfig.DiscussStyle style = config.getDiscussStyle();
        if (style == null) {
            return "讨论完成。";
        }
        return switch (style) {
            case DEBATE -> buildDebateConclusion(context, agents);
            case ROLEPLAY -> buildRolePlayConclusion(context);
            case CONSENSUS -> buildConsensusConclusion(context, agents.size());
            case DYNAMIC -> "讨论完成。";
        };
    }

    private String buildDebateConclusion(SharedContext context, List<RoleAgent> agents) {
        // 如果有裁判（第 3 个及以后的 Agent），让裁判总结
        if (agents.size() > 2) {
            RoleAgent judge = agents.get(agents.size() - 1);
            String judgePrompt = "你是辩论的裁判。请根据以上双方的辩论内容，做出公正的评判和总结，" +
                    "说明哪一方论据更有说服力，并给出最终结论。";
            String judgeConclusion = speakWithStream(judge, context, judgePrompt);
            addMessageWithStream(context, judge.getAgentId(), judge.getRoleName(), judgeConclusion);
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
        if (endedByRole != null) {
            conclusion.append("\n由【").append(endedByRole).append("】主动结束对话。");
        }
        return conclusion.toString();
    }

    private String buildConsensusConclusion(SharedContext context, int totalVoters) {
        StringBuilder conclusion = new StringBuilder("=== 共识决策结果 ===\n\n");
        conclusion.append("议题：").append(context.getTopic()).append("\n");
        conclusion.append("参与人数：").append(totalVoters).append("\n");
        conclusion.append("讨论轮次：").append(context.getCurrentRound()).append("\n\n");

        if (consensusReached) {
            conclusion.append("【结论】达成共识\n共识选项：").append(consensusOption).append("\n");
        } else {
            conclusion.append("【结论】未达成共识\n");
        }

        Map<String, List<String>> lastVotes = latestVotes;
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

    private Map<String, List<String>> collectVotes(List<RoleAgent> agents, SharedContext context) {
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
    // 风格判断辅助（基于 DiscussStyle 而非 Mode）
    // -------------------------------------------------------------------------

    private boolean isDebateStyle(CollaborationConfig config) {
        return config.getDiscussStyle() == CollaborationConfig.DiscussStyle.DEBATE;
    }

    private boolean isRolePlayStyle(CollaborationConfig config) {
        return config.getDiscussStyle() == CollaborationConfig.DiscussStyle.ROLEPLAY;
    }

    private boolean isConsensusStyle(CollaborationConfig config) {
        return config.getDiscussStyle() == CollaborationConfig.DiscussStyle.CONSENSUS;
    }

    private boolean isDynamicStyle(CollaborationConfig config) {
        return config.getDiscussStyle() == CollaborationConfig.DiscussStyle.DYNAMIC;
    }

    // -------------------------------------------------------------------------
    // 动态路由执行逻辑（原 DynamicRoutingStrategy）
    // -------------------------------------------------------------------------

    /**
     * 动态路由执行：Router Agent 根据上下文动态选择下一个发言者
     */
    private String executeDynamic(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        RoleAgent routerAgent = createRouterAgent(config, agents);

        logger.info("开始动态路由讨论", Map.of(
                "topic", context.getTopic(),
                "participants", agents.size(),
                "maxRounds", config.getMaxRounds()
        ));

        while (!shouldTerminate(context, config)) {
            context.nextRound();

            // Router Agent 决定下一个发言者（不需要流式输出给用户）
            String routingDecision = routerAgent.speak(context, buildRoutingPrompt(agents, context));
            context.addMessageSilent(
                    AgentMessage.builder(routerAgent.getAgentId(), routerAgent.getRoleName(), routingDecision)
                            .type(AgentMessage.MessageType.SYSTEM)
                            .build()
            );

            // 检查是否应该结束
            if (CONCLUDE_PATTERN.matcher(routingDecision).find()) {
                logger.info("Router 决定结束协同");
                break;
            }

            // 解析下一个发言者
            String nextRoleName = extractNextRole(routingDecision);
            if (nextRoleName == null) {
                int index = (context.getCurrentRound() - 1) % agents.size();
                nextRoleName = agents.get(index).getRoleName();
            }

            // 找到对应的 Agent 并让其发言
            RoleAgent selectedAgent = findAgentByRole(agents, nextRoleName);
            if (selectedAgent == null) {
                context.addMessage(AgentMessage.system("未找到角色 [" + nextRoleName + "]，跳过本轮"));
                continue;
            }

            String response = speakWithStream(selectedAgent, context, null);
            addMessageWithStream(context, selectedAgent.getAgentId(), selectedAgent.getRoleName(), response);

            logger.info("Agent 发言", Map.of(
                    "round", context.getCurrentRound(),
                    "speaker", selectedAgent.getRoleName(),
                    "responseLength", response.length()
            ));
        }

        // 由 Router Agent 总结
        String summaryPrompt = "协同讨论已结束。请综合所有参与者的观点，给出最终的结论和总结。\n"
                + "要求：1. 概述核心议题 2. 总结各方主要观点 3. 给出综合结论和建议";
        String conclusion = speakWithStream(routerAgent, context, summaryPrompt);
        addMessageWithStream(context, routerAgent.getAgentId(), routerAgent.getRoleName(), conclusion);
        context.setFinalConclusion(conclusion);

        logger.info("动态路由讨论完成", Map.of(
                "totalRounds", context.getCurrentRound(),
                "totalMessages", context.getHistory().size()
        ));

        return conclusion;
    }

    private RoleAgent createRouterAgent(CollaborationConfig config, List<RoleAgent> agents) {
        AgentRole routerRole = config.getRouterRole();
        if (routerRole == null) {
            StringBuilder participantsDesc = new StringBuilder();
            for (RoleAgent agent : agents) {
                AgentRole role = agent.getRole();
                participantsDesc.append("- ").append(role.getRoleName());
                if (role.getDescription() != null && !role.getDescription().isEmpty()) {
                    participantsDesc.append(": ").append(role.getDescription());
                }
                participantsDesc.append("\n");
            }

            String defaultRouterPrompt = "你是一个协同路由器（Router），负责协调多个 Agent 之间的对话。\n\n"
                    + "你的职责：\n"
                    + "1. 分析当前对话上下文和协同目标\n"
                    + "2. 决定下一个最适合发言的角色\n"
                    + "3. 在回复末尾用 [NEXT:角色名] 标记指定下一个发言者\n"
                    + "4. 当你认为讨论已经充分、目标已达成时，用 [CONCLUDE] 标记结束协同\n\n"
                    + "可选择的参与者：\n" + participantsDesc + "\n"
                    + "决策原则：\n"
                    + "- 确保每个参与者都有机会发言\n"
                    + "- 当某个观点需要特定专业角色回应时，优先选择该角色\n"
                    + "- 避免同一个角色连续发言超过 2 次\n"
                    + "- 当讨论陷入重复时，主动引导或结束";

            routerRole = AgentRole.of("Router", defaultRouterPrompt)
                    .withDescription("协同路由器，负责动态选择下一个发言者");
        }

        return executionContext.createAgentExecutor(routerRole);
    }

    private String buildRoutingPrompt(List<RoleAgent> agents, SharedContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析当前对话进展，决定下一步应该由哪个角色发言。\n\n");
        prompt.append("可选角色：");
        for (int i = 0; i < agents.size(); i++) {
            if (i > 0) prompt.append("、");
            prompt.append(agents.get(i).getRoleName());
        }
        prompt.append("\n\n各角色已发言次数：\n");
        for (RoleAgent agent : agents) {
            long count = context.getMessagesByRole(agent.getRoleName()).size();
            prompt.append("- ").append(agent.getRoleName()).append(": ").append(count).append(" 次\n");
        }
        prompt.append("\n请在回复末尾用 [NEXT:角色名] 指定下一个发言者，或用 [CONCLUDE] 结束协同。");
        return prompt.toString();
    }

    private String extractNextRole(String decision) {
        if (decision == null) return null;
        Matcher matcher = NEXT_PATTERN.matcher(decision);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private RoleAgent findAgentByRole(List<RoleAgent> agents, String roleName) {
        for (RoleAgent agent : agents) {
            if (roleName.equals(agent.getRoleName())) {
                return agent;
            }
        }
        for (RoleAgent agent : agents) {
            if (agent.getRoleName().contains(roleName) || roleName.contains(agent.getRoleName())) {
                return agent;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // CollaborationStrategy 接口实现
    // -------------------------------------------------------------------------

    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        if (context.getCurrentRound() >= config.getMaxRounds()) {
            return true;
        }
        if (context.isTokenBudgetExceeded()) {
            return true;
        }
        if (isRolePlayStyle(config) && endedByRole != null) {
            return true;
        }
        if (isConsensusStyle(config) && consensusReached) {
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
        return "通用讨论策略：支持辩论、角色扮演、共识投票、动态路由四种讨论风格";
    }
}
