package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationConfig;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.List;
import java.util.Map;

/**
 * 角色扮演策略
 * 多Agent按角色设定对话，支持场景剧本
 */
public class RolePlayStrategy implements CollaborationStrategy {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");
    
    /** 当前发言者索引 */
    private int currentSpeakerIndex = 0;
    
    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        if (agents.isEmpty()) {
            return "角色扮演至少需要1个参与者";
        }
        
        logger.info("开始角色扮演", Map.of(
                "topic", context.getTopic(),
                "participants", agents.size(),
                "maxRounds", config.getMaxRounds()
        ));
        
        // 角色扮演主循环
        while (!shouldTerminate(context, config)) {
            context.nextRound();
            
            // 每轮每个角色发言一次
            for (AgentExecutor speaker : agents) {
                logger.debug("轮次 " + context.getCurrentRound() + ", 角色: " + speaker.getRoleName());
                
                // 构建角色扮演提示
                String customPrompt = buildRolePlayPrompt(context, speaker);
                String response = speaker.speak(context, customPrompt);
                
                // 记录发言
                context.addMessage(speaker.getAgentId(), speaker.getRoleName(), response);
                
                logger.info("角色发言", Map.of(
                        "round", context.getCurrentRound(),
                        "role", speaker.getRoleName(),
                        "responseLength", response.length()
                ));
                
                // 检查是否有结束标记
                if (isConversationEnded(response)) {
                    context.setMeta("ended_by", speaker.getRoleName());
                    break;
                }
            }
            
            // 检查是否被主动结束
            if (context.getMeta("ended_by") != null) {
                break;
            }
        }
        
        // 生成对话总结
        String conclusion = buildConclusion(context);
        context.setFinalConclusion(conclusion);
        
        logger.info("角色扮演结束", Map.of(
                "totalRounds", context.getCurrentRound(),
                "totalMessages", context.getHistory().size()
        ));
        
        return conclusion;
    }
    
    /**
     * 构建角色扮演提示词
     */
    private String buildRolePlayPrompt(SharedContext context, AgentExecutor speaker) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("这是一个角色扮演场景。\n");
        prompt.append("你扮演的角色是：").append(speaker.getRoleName()).append("\n\n");
        prompt.append("请完全代入这个角色进行对话。回复时不要出戏，保持角色的语气和立场。\n");
        prompt.append("如果对话已经达到了自然结束点，你可以在回复末尾加上 [对话结束] 标记。");
        
        return prompt.toString();
    }
    
    /**
     * 检查对话是否被主动结束
     */
    private boolean isConversationEnded(String response) {
        return response != null && (
                response.contains("[对话结束]") || 
                response.contains("[END]") ||
                response.contains("[结束]")
        );
    }
    
    /**
     * 生成对话总结
     */
    private String buildConclusion(SharedContext context) {
        StringBuilder conclusion = new StringBuilder();
        conclusion.append("=== 角色扮演对话记录 ===\n\n");
        conclusion.append("场景：").append(context.getTopic()).append("\n\n");
        
        // 添加完整对话历史
        for (var msg : context.getHistory()) {
            conclusion.append("【").append(msg.getAgentRole()).append("】\n");
            conclusion.append(msg.getContent()).append("\n\n");
        }
        
        conclusion.append("---\n");
        conclusion.append("共 ").append(context.getCurrentRound()).append(" 轮对话，");
        conclusion.append(context.getHistory().size()).append(" 条消息。");
        
        String endedBy = context.getMeta("ended_by");
        if (endedBy != null) {
            conclusion.append("\n由【").append(endedBy).append("】主动结束对话。");
        }
        
        return conclusion.toString();
    }
    
    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // 被主动结束
        if (context.getMeta("ended_by") != null) {
            return true;
        }
        
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
        return "RolePlay";
    }
    
    @Override
    public String getDescription() {
        return "角色扮演策略：多Agent按角色设定对话，支持场景剧本";
    }
}
