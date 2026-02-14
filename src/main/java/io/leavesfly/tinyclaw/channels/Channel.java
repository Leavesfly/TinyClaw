package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.OutboundMessage;

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
    void start() throws Exception;
    
    /**
     * 停止通道
     */
    void stop();
    
    /**
     * 通过此通道发送消息
     */
    void send(OutboundMessage message) throws Exception;
    
    /**
     * 检查通道是否正在运行
     */
    boolean isRunning();
    
    /**
     * 检查发送者是否被允许
     */
    boolean isAllowed(String senderId);
}
