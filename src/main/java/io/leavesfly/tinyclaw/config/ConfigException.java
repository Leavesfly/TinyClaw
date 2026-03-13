package io.leavesfly.tinyclaw.config;

import io.leavesfly.tinyclaw.TinyClawException;

/**
 * 配置相关异常
 * 
 * <p>当配置加载或验证失败时抛出此异常。
 * 包括但不限于:
 * <ul>
 *   <li>配置文件不存在</li>
 *   <li>配置格式错误</li>
 *   <li>必需配置项缺失</li>
 *   <li>配置值无效</li>
 *   <li>环境变量未设置</li>
 * </ul>
 * </p>
 */
public class ConfigException extends TinyClawException {
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     */
    public ConfigException(String message) {
        super(message, "CONFIG_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause, "CONFIG_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param cause 原因异常
     */
    public ConfigException(Throwable cause) {
        super(cause != null ? cause.getMessage() : null, cause, "CONFIG_ERROR");
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param errorCode 错误代码
     */
    public ConfigException(String message, String errorCode) {
        super(message, errorCode);
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     * @param errorCode 错误代码
     */
    public ConfigException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode);
    }
}
