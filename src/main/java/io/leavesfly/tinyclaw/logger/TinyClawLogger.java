package io.leavesfly.tinyclaw.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TinyClaw 日志记录器，支持组件化的结构化日志记录。
 * 
 * 核心功能：
 * - 组件化日志：每个组件拥有独立的日志实例
 * - 结构化日志：支持键值对形式的附加字段
 * - 日志级别控制：支持全局日志级别设置
 * - 单例模式：同一组件复用同一日志实例
 * - 多种日志方法：DEBUG、INFO、WARN、ERROR、FATAL
 * 
 * 设计特点：
 * - 基于 SLF4J 实现，支持多种日志框架
 * - 使用 ConcurrentHashMap 缓存日志实例，线程安全
 * - 统一的日志格式：[组件名] 消息 {字段列表}
 * - 静态便捷方法支持快速日志记录
 * 
 * 日志格式示例：
 * - [agent] Starting agent loop
 * - [cron] Job execution failed {job_id="abc123", error="timeout"}
 * 
 * 使用方式：
 * - 实例方式：TinyClawLogger logger = TinyClawLogger.getLogger("agent");
 *              logger.info("Message", Map.of("key", "value"));
 * - 静态方式：TinyClawLogger.infoC("agent", "Message");
 *              TinyClawLogger.infoCF("agent", "Message", Map.of("key", "value"));
 */
public class TinyClawLogger {
    
    private static final String LOGGER_PREFIX = "tinyclaw.";     // 日志记录器名称前缀
    private static final String COMPONENT_PREFIX = "[";          // 组件名前缀
    private static final String COMPONENT_SUFFIX = "] ";         // 组件名后缀
    private static final String FIELD_SEPARATOR = ", ";          // 字段分隔符
    private static final String KEY_VALUE_SEPARATOR = "=";       // 键值分隔符
    private static final String FIELD_START = "{";               // 字段开始符
    private static final String FIELD_END = "}";                 // 字段结束符
    private static final String STRING_QUOTE = "\"";             // 字符串引号
    private static final String NULL_VALUE = "null";             // null 值表示
    private static final String SPACE = " ";                     // 空格
    
    private final Logger logger;          // SLF4J 日志记录器
    private final String component;       // 组件名称
    
    private static final Map<String, TinyClawLogger> loggers = new ConcurrentHashMap<>();  // 日志实例缓存
    private static volatile Level currentLevel = Level.INFO;  // 当前全局日志级别
    
    /**
     * 日志级别枚举。
     * 
     * 级别从低到高：DEBUG < INFO < WARN < ERROR
     */
    public enum Level {
        DEBUG(0),  // 调试级别
        INFO(1),   // 信息级别
        WARN(2),   // 警告级别
        ERROR(3);  // 错误级别
        
        private final int value;  // 级别数值
        
        /**
         * 构造日志级别。
         * 
         * @param value 级别数值
         */
        Level(int value) {
            this.value = value;
        }
        
        /**
         * 获取级别数值。
         * 
         * @return 级别数值
         */
        public int toInt() {
            return value;
        }
    }
    
    /**
     * 私有构造函数，创建指定组件的日志记录器。
     * 
     * @param component 组件名称
     */
    private TinyClawLogger(String component) {
        this.component = component;
        this.logger = LoggerFactory.getLogger(LOGGER_PREFIX + component);
    }
    
    /**
     * 获取或创建指定组件的日志记录器。
     * 
     * 使用单例模式，同一组件名返回相同的日志实例。
     * 
     * @param component 组件名称
     * @return 日志记录器实例
     */
    public static TinyClawLogger getLogger(String component) {
        return loggers.computeIfAbsent(component, TinyClawLogger::new);
    }
    
    /**
     * 设置全局日志级别。
     * 
     * @param level 日志级别
     */
    public static void setLevel(Level level) {
        currentLevel = level;
    }
    
    /**
     * 获取当前全局日志级别。
     * 
     * @return 当前日志级别
     */
    public static Level getLevel() {
        return currentLevel;
    }
    
    /**
     * 记录 DEBUG 级别日志。
     * 
     * @param message 日志消息
     */
    public void debug(String message) {
        if (shouldLog(Level.DEBUG)) {
            logger.debug(formatMessage(message, null));
        }
    }
    
    /**
     * 记录 DEBUG 级别日志（带字段）。
     * 
     * @param message 日志消息
     * @param fields 附加字段
     */
    public void debug(String message, Map<String, Object> fields) {
        if (shouldLog(Level.DEBUG)) {
            logger.debug(formatMessage(message, fields));
        }
    }
    
    /**
     * 记录 INFO 级别日志。
     * 
     * @param message 日志消息
     */
    public void info(String message) {
        if (shouldLog(Level.INFO)) {
            logger.info(formatMessage(message, null));
        }
    }
    
    /**
     * 记录 INFO 级别日志（带字段）。
     * 
     * @param message 日志消息
     * @param fields 附加字段
     */
    public void info(String message, Map<String, Object> fields) {
        if (shouldLog(Level.INFO)) {
            logger.info(formatMessage(message, fields));
        }
    }
    
    /**
     * 记录 WARN 级别日志。
     * 
     * @param message 日志消息
     */
    public void warn(String message) {
        if (shouldLog(Level.WARN)) {
            logger.warn(formatMessage(message, null));
        }
    }
    
    /**
     * 记录 WARN 级别日志（带字段）。
     * 
     * @param message 日志消息
     * @param fields 附加字段
     */
    public void warn(String message, Map<String, Object> fields) {
        if (shouldLog(Level.WARN)) {
            logger.warn(formatMessage(message, fields));
        }
    }
    
    /**
     * 记录 ERROR 级别日志。
     * 
     * @param message 日志消息
     */
    public void error(String message) {
        logger.error(formatMessage(message, null));
    }
    
    /**
     * 记录 ERROR 级别日志（带字段）。
     * 
     * @param message 日志消息
     * @param fields 附加字段
     */
    public void error(String message, Map<String, Object> fields) {
        logger.error(formatMessage(message, fields));
    }
    
    /**
     * 记录 ERROR 级别日志（带异常）。
     * 
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public void error(String message, Throwable throwable) {
        logger.error(formatMessage(message, null), throwable);
    }
    
    /**
     * 记录 ERROR 级别日志（带字段和异常）。
     * 
     * @param message 日志消息
     * @param fields 附加字段
     * @param throwable 异常对象
     */
    public void error(String message, Map<String, Object> fields, Throwable throwable) {
        logger.error(formatMessage(message, fields), throwable);
    }
    
    /**
     * 记录 FATAL 级别日志（致命错误）。
     * 
     * @param message 日志消息
     */
    public void fatal(String message) {
        logger.error(formatMessage(message, null));
    }
    
    /**
     * 记录 FATAL 级别日志（带字段）。
     * 
     * @param message 日志消息
     * @param fields 附加字段
     */
    public void fatal(String message, Map<String, Object> fields) {
        logger.error(formatMessage(message, fields));
    }
    
    /**
     * 检查是否应该记录指定级别的日志。
     * 
     * @param level 日志级别
     * @return 应该记录返回 true，否则返回 false
     */
    private boolean shouldLog(Level level) {
        return currentLevel.toInt() <= level.toInt();
    }
    
    /**
     * 格式化日志消息。
     * 
     * 格式：[组件名] 消息 {字段列表}
     * 
     * @param message 原始消息
     * @param fields 附加字段
     * @return 格式化后的消息
     */
    private String formatMessage(String message, Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append(COMPONENT_PREFIX).append(component).append(COMPONENT_SUFFIX);
        sb.append(message);
        
        if (fields != null && !fields.isEmpty()) {
            sb.append(SPACE);
            sb.append(formatFields(fields));
        }
        
        return sb.toString();
    }
    
    /**
     * 格式化字段列表。
     * 
     * 格式：{key1=value1, key2=value2}
     * 
     * @param fields 字段 Map
     * @return 格式化后的字段字符串
     */
    private String formatFields(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder(FIELD_START);
        boolean first = true;
        
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                sb.append(FIELD_SEPARATOR);
            }
            sb.append(entry.getKey())
              .append(KEY_VALUE_SEPARATOR)
              .append(formatValue(entry.getValue()));
            first = false;
        }
        
        sb.append(FIELD_END);
        return sb.toString();
    }
    
    /**
     * 格式化字段值。
     * 
     * - null 值返回 "null"
     * - 字符串值添加双引号
     * - 其他值调用 toString()
     * 
     * @param value 字段值
     * @return 格式化后的值
     */
    private String formatValue(Object value) {
        if (value == null) {
            return NULL_VALUE;
        }
        if (value instanceof String) {
            return STRING_QUOTE + value + STRING_QUOTE;
        }
        return String.valueOf(value);
    }
    
    /**
     * 静态便捷方法：记录 DEBUG 级别日志。
     * 
     * @param component 组件名称
     * @param message 日志消息
     */
    public static void debugC(String component, String message) {
        getLogger(component).debug(message);
    }
    
    /**
     * 静态便捷方法：记录 DEBUG 级别日志（带字段）。
     * 
     * @param component 组件名称
     * @param message 日志消息
     * @param fields 附加字段
     */
    public static void debugCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).debug(message, fields);
    }
    
    /**
     * 静态便捷方法：记录 INFO 级别日志。
     * 
     * @param component 组件名称
     * @param message 日志消息
     */
    public static void infoC(String component, String message) {
        getLogger(component).info(message);
    }
    
    /**
     * 静态便捷方法：记录 INFO 级别日志（带字段）。
     * 
     * @param component 组件名称
     * @param message 日志消息
     * @param fields 附加字段
     */
    public static void infoCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).info(message, fields);
    }
    
    /**
     * 静态便捷方法：记录 WARN 级别日志。
     * 
     * @param component 组件名称
     * @param message 日志消息
     */
    public static void warnC(String component, String message) {
        getLogger(component).warn(message);
    }
    
    /**
     * 静态便捷方法：记录 WARN 级别日志（带字段）。
     * 
     * @param component 组件名称
     * @param message 日志消息
     * @param fields 附加字段
     */
    public static void warnCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).warn(message, fields);
    }
    
    /**
     * 静态便捷方法：记录 ERROR 级别日志。
     * 
     * @param component 组件名称
     * @param message 日志消息
     */
    public static void errorC(String component, String message) {
        getLogger(component).error(message);
    }
    
    /**
     * 静态便捷方法：记录 ERROR 级别日志（带字段）。
     * 
     * @param component 组件名称
     * @param message 日志消息
     * @param fields 附加字段
     */
    public static void errorCF(String component, String message, Map<String, Object> fields) {
        getLogger(component).error(message, fields);
    }
}
