package io.leavesfly.tinyclaw.providers;

import io.leavesfly.tinyclaw.TinyClawException;

/**
 * LLM 调用相关异常
 * 
 * <p>当 LLM 提供者调用失败时抛出此异常。
 * 包括但不限于:
 * <ul>
 *   <li>API 请求失败</li>
 *   <li>认证错误</li>
 *   <li>速率限制</li>
 *   <li>响应解析错误</li>
 *   <li>网络超时</li>
 * </ul>
 * </p>
 */
public class LLMException extends TinyClawException {
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     */
    public LLMException(String message) {
        super(message, "LLM_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     */
    public LLMException(String message, Throwable cause) {
        super(message, cause, "LLM_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param cause 原因异常
     */
    public LLMException(Throwable cause) {
        super(cause.getMessage(), cause, "LLM_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param errorCode 错误代码
     */
    public LLMException(String message, String errorCode) {
        super(message, errorCode);
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     * @param errorCode 错误代码
     */
    public LLMException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode);
    }
}
