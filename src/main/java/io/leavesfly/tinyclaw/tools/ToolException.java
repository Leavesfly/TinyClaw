package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.TinyClawException;

/**
 * 工具执行相关异常
 * 
 * <p>当工具执行失败时抛出此异常。
 * 包括但不限于:
 * <ul>
 *   <li>参数验证失败</li>
 *   <li>文件操作失败</li>
 *   <li>命令执行失败</li>
 *   <li>网络请求失败</li>
 *   <li>权限不足</li>
 * </ul>
 * </p>
 */
public class ToolException extends TinyClawException {
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     */
    public ToolException(String message) {
        super(message, "TOOL_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     */
    public ToolException(String message, Throwable cause) {
        super(message, cause, "TOOL_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param cause 原因异常
     */
    public ToolException(Throwable cause) {
        super(cause.getMessage(), cause, "TOOL_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param errorCode 错误代码
     */
    public ToolException(String message, String errorCode) {
        super(message, errorCode);
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     * @param errorCode 错误代码
     */
    public ToolException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode);
    }
}
