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
 * 共识决策策略
 * 多Agent讨论后投票，支持多轮投票直到达成共识
 */
public class ConsensusStrategy implements CollaborationStrategy {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");
    
    /** 投票选项提取正则 */
    private static final Pattern VOTE_PATTERN = Pattern.compile("\\[投票[:：]?\\s*([^\\]]+)\\]");
    
    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        if (agents.size() < 2) {
            return "共识决策至少需要2个参与者";
        }
        
        double threshold = config.getConsensusThreshold();
        
        logger.info("开始共识决策", Map.of(
                "topic", context.getTopic(),
                "participants", agents.size(),
                "threshold", threshold
        ));
        
        boolean consensusReached = false;
        
        // 共识决策主循环
        while (!shouldTerminate(context, config) && !consensusReached) {
            context.nextRound();
            
            logger.info("共识决策轮次", Map.of("round", context.getCurrentRound()));
            
            // 第一阶段：讨论
            for (AgentExecutor speaker : agents) {
                String customPrompt = buildDiscussionPrompt(context, speaker);
                String response = speaker.speak(context, customPrompt);
                context.addMessage(speaker.getAgentId(), speaker.getRoleName(), response);
                
                logger.debug("讨论发言", Map.of(
                        "speaker", speaker.getRoleName(),
                        "responseLength", response.length()
                ));
            }
            
            // 第二阶段：投票
            Map<String, List<String>> votes = collectVotes(agents, context);
            context.setMeta("votes_round_" + context.getCurrentRound(), votes);
            
            logger.info("投票结果", Map.of(
                    "round", context.getCurrentRound(),
                    "options", votes.keySet()
            ));
            
            // 检查是否达成共识
            consensusReached = checkConsensus(votes, agents.size(), threshold);
            
            if (consensusReached) {
                context.setMeta("consensus_option", getMajorityOption(votes));
                logger.info("达成共识", Map.of(
                        "option", getMajorityOption(votes),
                        "round", context.getCurrentRound()
                ));
            }
        }
        
        // 生成结论
        String conclusion = buildConclusion(context, consensusReached, agents.size());
        context.setFinalConclusion(conclusion);
        
        return conclusion;
    }
    
    /**
     * 构建讨论提示词
     */
    private String buildDiscussionPrompt(SharedContext context, AgentExecutor speaker) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("这是一个需要达成共识的决策讨论。\n\n");
        
        if (context.getCurrentRound() == 1) {
            prompt.append("请先分析问题，阐述你的观点和建议方案。\n");
        } else {
            prompt.append("基于之前的讨论，请补充你的观点或调整你的立场。\n");
        }
        
        prompt.append("\n讨论结束后，请在回复末尾用 [投票:你的选择] 格式表明你的最终选择。\n");
        prompt.append("例如：[投票:方案A] 或 [投票:同意] 或 [投票:反对]");
        
        return prompt.toString();
    }
    
    /**
     * 收集投票
     */
    private Map<String, List<String>> collectVotes(List<AgentExecutor> agents, SharedContext context) {
        Map<String, List<String>> votes = new HashMap<>();
        
        // 获取本轮的发言
        List<AgentMessage> recentMessages = context.getRecentMessages(agents.size());
        
        for (AgentMessage msg : recentMessages) {
            String vote = extractVote(msg.getContent());
            if (vote != null) {
                votes.computeIfAbsent(vote, k -> new ArrayList<>()).add(msg.getAgentRole());
            }
        }
        
        return votes;
    }
    
    /**
     * 从回复中提取投票选项
     */
    private String extractVote(String content) {
        if (content == null) {
            return null;
        }
        
        Matcher matcher = VOTE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        return null;
    }
    
    /**
     * 检查是否达成共识
     */
    private boolean checkConsensus(Map<String, List<String>> votes, int totalVoters, double threshold) {
        if (votes.isEmpty()) {
            return false;
        }
        
        for (List<String> voters : votes.values()) {
            double ratio = (double) voters.size() / totalVoters;
            if (ratio >= threshold) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取票数最多的选项
     */
    private String getMajorityOption(Map<String, List<String>> votes) {
        return votes.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse("无");
    }
    
    /**
     * 生成结论
     */
    private String buildConclusion(SharedContext context, boolean consensusReached, int totalVoters) {
        StringBuilder conclusion = new StringBuilder();
        conclusion.append("=== 共识决策结果 ===\n\n");
        conclusion.append("议题：").append(context.getTopic()).append("\n");
        conclusion.append("参与人数：").append(totalVoters).append("\n");
        conclusion.append("讨论轮次：").append(context.getCurrentRound()).append("\n\n");
        
        if (consensusReached) {
            String consensusOption = context.getMeta("consensus_option");
            conclusion.append("【结论】达成共识\n");
            conclusion.append("共识选项：").append(consensusOption).append("\n");
        } else {
            conclusion.append("【结论】未达成共识\n");
        }
        
        // 添加最后一轮投票详情
        @SuppressWarnings("unchecked")
        Map<String, List<String>> lastVotes = context.getMeta("votes_round_" + context.getCurrentRound());
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
    
    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // 达到最大轮次
        if (context.getCurrentRound() >= config.getMaxRounds()) {
            return true;
        }
        
        // 超时检查
        if (config.getTimeoutMs() > 0 && context.getElapsedTime() > config.getTimeoutMs()) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public AgentExecutor getNextSpeaker(SharedContext context, List<AgentExecutor> agents) {
        return null; // 共识决策所有人同时发言
    }
    
    @Override
    public String getName() {
        return "Consensus";
    }
    
    @Override
    public String getDescription() {
        return "共识决策策略：多Agent讨论后投票，支持多轮投票直到达成共识";
    }
}
