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
    
    /**
     * 提取异常链最底层的根因信息（类名 + 消息）。
     * 
     * <p>LLM 调用异常常被多层包装（如 {@code LLMException("执行请求失败", cause)}），
     * 仅打印外层消息会丢失底层网络根因（如 {@code SocketTimeoutException: timeout}），
     * 错误日志与用户提示应始终携带该方法提取的根因。</p>
     * 
     * @param e 待解析的异常（可为 null）
     * @return 根因描述，入参为 null 时返回 "unknown"
     */
    public static String rootCauseMessage(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
}
