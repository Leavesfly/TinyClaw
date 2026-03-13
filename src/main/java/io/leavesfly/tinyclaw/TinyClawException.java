package io.leavesfly.tinyclaw;

/**
 * TinyClaw 基础异常类
 * 
 * <p>所有 TinyClaw 框架内的异常都应继承此类。
 * 继承自 RuntimeException,因此不需要在方法签名中声明 throws。</p>
 * 
 * <p>使用场景:
 * <ul>
 *   <li>LLM 调用失败</li>
 *   <li>消息通道通信错误</li>
 *   <li>工具执行异常</li>
 *   <li>配置加载错误</li>
 * </ul>
 * </p>
 */
public class TinyClawException extends RuntimeException {
    
    private final String errorCode;
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     */
    public TinyClawException(String message) {
        super(message);
        this.errorCode = "UNKNOWN";
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     */
    public TinyClawException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "UNKNOWN";
    }
    
    /**
     * 构造异常
     * 
     * @param cause 原因异常
     */
    public TinyClawException(Throwable cause) {
        super(cause);
        this.errorCode = "UNKNOWN";
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param errorCode 错误代码
     */
    public TinyClawException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
    
    /**
     * 构造异常
     * 
     * @param message 错误消息
     * @param cause 原因异常
     * @param errorCode 错误代码
     */
    public TinyClawException(String message, Throwable cause, String errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    /**
     * 获取错误代码
     * 
     * @return 错误代码
     */
    public String getErrorCode() {
        return errorCode;
    }
}
