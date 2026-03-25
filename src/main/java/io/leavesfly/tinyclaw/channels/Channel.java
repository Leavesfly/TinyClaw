package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.providers.LLMProvider;

/**
 * 消息通道接口（Telegram、Discord等）
 *
 */
public interface Channel {
    
    /**
     * 获取通道名称
     */
    String name();
    
    /**
     * 启动通道
     */
    void start();
    
    /**
     * 停止通道
     */
    void stop();
    
    /**
     * 通过此通道发送消息
     */
    void send(OutboundMessage message);
    
    /**
     * 检查通道是否正在运行
     */
    boolean isRunning();
    
    /**
     * 检查发送者是否被允许
     */
    boolean isAllowed(String senderId);

    /**
     * 是否支持流式输出。
     *
     * 支持流式的通道可以在 LLM 生成过程中逐步将内容推送给用户，
     * 而不是等待完整回复后一次性发送。默认不支持。
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * 创建流式输出回调。
     *
     * 仅在 {@link #supportsStreaming()} 返回 true 时有效。
     * 返回的 {@link LLMProvider.StreamCallback} 由 AgentRuntime 传给 LLMExecutor，
     * 通道实现负责在回调中将 LLM 生成的 chunk 实时推送给用户。
     *
     * @param chatId 目标会话 ID
     * @return 流式回调实例；返回 null 时退化为普通阻塞式调用
     */
    default LLMProvider.StreamCallback createStreamingCallback(String chatId) {
        return null;
    }
}