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
}
