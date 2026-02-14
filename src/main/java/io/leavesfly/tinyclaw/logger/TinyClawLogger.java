package io.leavesfly.tinyclaw.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TinyClaw 日志记录器 - 支持组件化的结构化日志记录
 */
public class TinyClawLogger {
    
    /**
     * 日志级别枚举
     */
    public enum Level {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3);
        
        private final int value;
        
        Level(int value) {
            this.value = value;
        }
        
        public int toInt() {
            return value;
        }
    }
    
    private final Logger logger;
    private final String component;
    
    private static final Map<String, TinyClawLogger> loggers = new ConcurrentHashMap<>();
    private static volatile Level currentLevel = Level.INFO;
    
    private TinyClawLogger(String component) {
        this.component = component;
        this.logger = LoggerFactory.getLogger("tinyclaw." + component);
    }
    
    /**
     * 获取或创建指定组件的日志记录器
     */
    public static TinyClawLogger getLogger(String component) {
        return loggers.computeIfAbsent(component, TinyClawLogger::new);
    }
    
    /**
     * 设置全局日志级别
     */
    public static void setLevel(Level level) {
        currentLevel = level;
    }
    
    /**
     * 获取当前日志级别
     */
    public static Level getLevel() {
        return currentLevel;
    }
    
    // Debug 调试日志方法
    public void debug(String message) {
        if (currentLevel.toInt() <= Level.DEBUG.toInt()) {
            logger.debug(formatMessage(message, null));
        }
    }
    
    public void debug(String message, Map<String, Object> fields) {
        if (currentLevel.toInt() <= Level.DEBUG.toInt()) {
            logger.debug(formatMessage(message, fields));
        }
    }
    
    // Info 信息日志方法
    public void info(String message) {
        if (currentLevel.toInt() <= Level.INFO.toInt()) {
            logger.info(formatMessage(message, null));
        }
    }
    
    public void info(String message, Map<String, Object> fields) {
        if (currentLevel.toInt() <= Level.INFO.toInt()) {
            logger.info(formatMessage(message, fields));
        }
    }
    
    // Warn 警告日志方法
    public void warn(String message) {
        if (currentLevel.toInt() <= Level.WARN.toInt()) {
            logger.warn(formatMessage(message, null));
        }
    }
    
    public void warn(String message, Map<String, Object> fields) {
        if (currentLevel.toInt() <= Level.WARN.toInt()) {
            logger.warn(formatMessage(message, fields));
        }
    }
    
    // Error 错误日志方法
    public void error(String message) {
        logger.error(formatMessage(message, null));
    }
    
    public void error(String message, Map<String, Object> fields) {
        logger.error(formatMessage(message, fields));
    }
    
    public void error(String message, Throwable throwable) {
        logger.error(formatMessage(message, null), throwable);
    }
    
    public void error(String message, Map<String, Object> fields, Throwable throwable) {
        logger.error(formatMessage(message, fields), throwable);
    }
    
    // Fatal 致命错误日志方法
    public void fatal(String message) {
        logger.error(formatMessage(message, null));
    }
    
    public void fatal(String message, Map<String, Object> fields) {
        logger.error(formatMessage(message, fields));
    }
    
    // 格式化辅助方法
    private String formatMessage(String message, Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(component).append("] ");
        sb.append(message);
        
        if (fields != null && !fields.isEmpty()) {
            sb.append(" ");
            sb.append(formatFields(fields));
        }
        
        return sb.toString();
    }
    
    private String formatFields(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(formatValue(entry.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
    
    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return String.valueOf(value);
    }
    
    // 静态便捷方法
    
    public static void debugC(String component, String message) {
        getLogger(component).debug(message);
    }
    
    public static void debugCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).debug(message, fields);
    }
    
    public static void infoC(String component, String message) {
        getLogger(component).info(message);
    }
    
    public static void infoCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).info(message, fields);
    }
    
    public static void warnC(String component, String message) {
        getLogger(component).warn(message);
    }
    
    public static void warnCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).warn(message, fields);
    }
    
    public static void errorC(String component, String message) {
        getLogger(component).error(message);
    }
    
    public static void errorCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).error(message, fields);
    }
}
