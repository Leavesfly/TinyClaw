package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.TinyClawException;

/**
 * 通道相关异常
 * 
 * <p>当消息通道操作失败时抛出此异常。
 * 包括但不限于:
 * <ul>
 *   <li>通道启动失败</li>
 *   <li>消息发送失败</li>
 *   <li>连接断开</li>
 *   <li>认证失败</li>
 *   <li>消息解析错误</li>
 * </ul>
 * </p>
 */
public class ChannelException extends TinyClawException {
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     */
    public ChannelException(String message) {
        super(message, "CHANNEL_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     */
    public ChannelException(String message, Throwable cause) {
        super(message, cause, "CHANNEL_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param cause 原因异常
     */
    public ChannelException(Throwable cause) {
        super(cause != null ? cause.getMessage() : null, cause, "CHANNEL_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param errorCode 错误代码
     */
    public ChannelException(String message, String errorCode) {
        super(message, errorCode);
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     * @param errorCode 错误代码
     */
    public ChannelException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode);
    }
}
