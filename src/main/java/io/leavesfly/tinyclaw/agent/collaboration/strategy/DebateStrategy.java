package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationConfig;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.List;
import java.util.Map;

/**
 * 辩论策略
 * 正反双方轮流发言，最后由裁判总结
 */
public class DebateStrategy implements CollaborationStrategy {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");
    
    /** 当前发言者索引 */
    private int currentSpeakerIndex = 0;
    
    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        if (agents.size() < 2) {
            return "辩论至少需要2个参与者";
        }
        
        logger.info("开始辩论", Map.of(
                "topic", context.getTopic(),
                "participants", agents.size(),
                "maxRounds", config.getMaxRounds()
        ));
        
        // 辩论主循环
        while (!shouldTerminate(context, config)) {
            context.nextRound();
            
            // 每轮每个辩手发言一次（除了裁判）
            int debaterCount = agents.size() > 2 ? agents.size() - 1 : agents.size();
            for (int i = 0; i < debaterCount; i++) {
                AgentExecutor speaker = agents.get(i);
                
                logger.debug("轮次 " + context.getCurrentRound() + ", 发言者: " + speaker.getRoleName());
                
                // 构建辩论提示
                String customPrompt = buildDebatePrompt(context, speaker, i, debaterCount);
                String response = speaker.speak(context, customPrompt);
                
                // 记录发言
                context.addMessage(speaker.getAgentId(), speaker.getRoleName(), response);
                
                logger.info("辩手发言", Map.of(
                        "round", context.getCurrentRound(),
                        "speaker", speaker.getRoleName(),
                        "responseLength", response.length()
                ));
            }
        }
        
        // 如果有裁判（第3个Agent），让裁判总结
        String conclusion;
        if (agents.size() > 2) {
            AgentExecutor judge = agents.get(agents.size() - 1);
            String judgePrompt = "你是辩论的裁判。请根据以上双方的辩论内容，做出公正的评判和总结。" +
                    "说明哪一方论据更有说服力，并给出最终结论。";
            conclusion = judge.speak(context, judgePrompt);
            context.addMessage(judge.getAgentId(), judge.getRoleName(), conclusion);
        } else {
            // 没有裁判，生成简单总结
            conclusion = buildSimpleSummary(context);
        }
        
        context.setFinalConclusion(conclusion);
        
        logger.info("辩论结束", Map.of(
                "totalRounds", context.getCurrentRound(),
                "totalMessages", context.getHistory().size()
        ));
        
        return conclusion;
    }
    
    /**
     * 构建辩论提示词
     */
    private String buildDebatePrompt(SharedContext context, AgentExecutor speaker, 
                                      int speakerIndex, int totalDebaters) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("这是一场辩论。你的立场是：").append(speaker.getRole().getSystemPrompt()).append("\n\n");
        
        if (context.getCurrentRound() == 1 && context.getHistory().isEmpty()) {
            prompt.append("请先阐述你的核心观点和主要论据。");
        } else {
            prompt.append("请针对对方的观点进行反驳，并进一步强化你的论点。");
        }
        
        return prompt.toString();
    }
    
    /**
     * 生成简单总结（无裁判时）
     */
    private String buildSimpleSummary(SharedContext context) {
        StringBuilder summary = new StringBuilder();
        summary.append("=== 辩论总结 ===\n\n");
        summary.append("主题：").append(context.getTopic()).append("\n\n");
        summary.append("共进行了 ").append(context.getCurrentRound()).append(" 轮辩论。\n\n");
        summary.append("以上是双方的辩论内容，请用户自行判断。");
        return summary.toString();
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
        if (agents.isEmpty()) {
            return null;
        }
        AgentExecutor next = agents.get(currentSpeakerIndex);
        currentSpeakerIndex = (currentSpeakerIndex + 1) % agents.size();
        return next;
    }
    
    @Override
    public String getName() {
        return "Debate";
    }
    
    @Override
    public String getDescription() {
        return "辩论策略：正反双方轮流发言，最后由裁判总结";
    }
}
