package io.leavesfly.tinyclaw.collaboration.strategy;

import io.leavesfly.tinyclaw.collaboration.AgentMessage;
import io.leavesfly.tinyclaw.collaboration.RoleAgent;
import io.leavesfly.tinyclaw.collaboration.SharedContext;
import io.leavesfly.tinyclaw.providers.LLMProvider;

/**
 * 协同策略抽象基类
 * 提取各策略共用的流式发言辅助方法，消除重复代码。
 */
public abstract class AbstractCollaborationStrategy implements CollaborationStrategy {

    /**
     * 根据 SharedContext 是否持有流式回调，选择流式或非流式发言。
     * <p>有回调时使用 {@code speakStream}，逐 chunk 输出 COLLABORATE_AGENT_CHUNK 事件；
     * 无回调时退化为普通 {@code speak}。
     *
     * @param speaker 发言的 Agent
     * @param context 共享上下文
     * @param prompt  自定义提示（可为 null）
     * @return Agent 的完整回复内容
     */
    protected String speakWithStream(RoleAgent speaker, SharedContext context, String prompt) {
        LLMProvider.EnhancedStreamCallback callback = context.getStreamCallback();
        if (callback != null) {
            return speaker.speakStream(context, prompt, callback);
        }
        if (prompt != null) {
            return speaker.speak(context, prompt);
        }
        return speaker.speak(context);
    }

    /**
     * 根据是否已流式输出过，选择静默或普通方式添加消息到历史。
     * <p>有流式回调时使用 {@code addMessageSilent}（chunk 已输出，不再重复推送完整消息）；
     * 无回调时使用普通 {@code addMessage}（触发 COLLABORATE_AGENT 事件）。
     *
     * @param context  共享上下文
     * @param agentId  Agent 唯一标识
     * @param roleName 角色名称
     * @param content  消息内容
     */
    protected void addMessageWithStream(SharedContext context, String agentId,
                                        String roleName, String content) {
        if (context.getStreamCallback() != null) {
            context.addMessageSilent(new AgentMessage(agentId, roleName, content));
        } else {
            context.addMessage(agentId, roleName, content);
        }
    }
}
