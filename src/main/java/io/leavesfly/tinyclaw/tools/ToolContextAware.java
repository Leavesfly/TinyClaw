package io.leavesfly.tinyclaw.tools;

/**
 * 工具上下文感知接口
 * 
 * 用于标识需要通道和聊天ID上下文的工具。
 * 实现此接口的工具可以接收当前的通道和聊天ID信息，
 * 用于在执行时确定消息发送的目标位置。
 */
public interface ToolContextAware {
    
    /**
     * 设置通道上下文信息
     *
     * @param channel 通道标识符（如 telegram、discord、feishu 等）
     * @param chatId 聊天ID
     */
    void setChannelContext(String channel, String chatId);

    /**
     * 设置会话上下文。
     *
     * <p>与 {@link #setChannelContext} 分开是因为二者不等价：{@code /new} 指令产生的
     * sessionKey 形如 {@code channel:chatId:timestamp}，由 channel 与 chatId 拼不回来。
     * 需要按会话上报状态（如进度卡）的工具必须用这里给的原值。</p>
     *
     * <p>默认空实现：只关心投递目标的工具（如 message、cron）无需感知会话。</p>
     *
     * @param sessionKey 当前会话标识，可能为 null
     */
    default void setSessionContext(String sessionKey) {
    }
}
