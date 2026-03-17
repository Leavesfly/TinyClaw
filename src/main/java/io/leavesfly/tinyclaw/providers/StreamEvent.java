package io.leavesfly.tinyclaw.providers;

import java.util.Map;

/**
 * 流式输出事件，用于传递多种类型的过程信息。
 * 
 * 支持的事件类型：
 * - CONTENT: 普通文本内容（主 Agent 的回复）
 * - TOOL_START: 工具调用开始
 * - TOOL_END: 工具调用结束
 * - SUBAGENT_START: 子代理开始执行
 * - SUBAGENT_CONTENT: 子代理输出内容
 * - SUBAGENT_END: 子代理执行结束
 * - COLLABORATE_START: 多 Agent 协同开始
 * - COLLABORATE_AGENT: 协同中的 Agent 发言
 * - COLLABORATE_END: 多 Agent 协同结束
 * - THINKING: 思考/推理过程（可选展示）
 */
public class StreamEvent {
    
    public enum EventType {
        /** 普通文本内容（主 Agent 的回复） */
        CONTENT,
        /** 工具调用开始 */
        TOOL_START,
        /** 工具调用结束 */
        TOOL_END,
        /** 子代理开始执行 */
        SUBAGENT_START,
        /** 子代理输出内容 */
        SUBAGENT_CONTENT,
        /** 子代理执行结束 */
        SUBAGENT_END,
        /** 多 Agent 协同开始 */
        COLLABORATE_START,
        /** 协同中的 Agent 发言 */
        COLLABORATE_AGENT,
        /** 多 Agent 协同结束 */
        COLLABORATE_END,
        /** 思考/推理过程 */
        THINKING
    }
    
    private final EventType type;
    private final String content;
    private final Map<String, Object> metadata;
    
    private StreamEvent(EventType type, String content, Map<String, Object> metadata) {
        this.type = type;
        this.content = content;
        this.metadata = metadata;
    }
    
    // ==================== 工厂方法 ====================
    
    /** 创建普通内容事件 */
    public static StreamEvent content(String content) {
        return new StreamEvent(EventType.CONTENT, content, null);
    }
    
    /** 创建工具调用开始事件 */
    public static StreamEvent toolStart(String toolName, Map<String, Object> args) {
        return new StreamEvent(EventType.TOOL_START, toolName, 
                Map.of("tool", toolName, "args", args != null ? args : Map.of()));
    }
    
    /** 创建工具调用结束事件 */
    public static StreamEvent toolEnd(String toolName, String result, boolean success) {
        return new StreamEvent(EventType.TOOL_END, result, 
                Map.of("tool", toolName, "success", success));
    }
    
    /** 创建子代理开始事件 */
    public static StreamEvent subagentStart(String taskId, String task, String label) {
        return new StreamEvent(EventType.SUBAGENT_START, task,
                Map.of("taskId", taskId, "label", label != null ? label : ""));
    }
    
    /** 创建子代理内容事件 */
    public static StreamEvent subagentContent(String taskId, String content) {
        return new StreamEvent(EventType.SUBAGENT_CONTENT, content,
                Map.of("taskId", taskId));
    }
    
    /** 创建子代理结束事件 */
    public static StreamEvent subagentEnd(String taskId, String result, boolean success) {
        return new StreamEvent(EventType.SUBAGENT_END, result,
                Map.of("taskId", taskId, "success", success));
    }
    
    /** 创建协同开始事件 */
    public static StreamEvent collaborateStart(String mode, String topic) {
        return new StreamEvent(EventType.COLLABORATE_START, topic,
                Map.of("mode", mode));
    }
    
    /** 创建协同 Agent 发言事件 */
    public static StreamEvent collaborateAgent(String agentName, String content) {
        return new StreamEvent(EventType.COLLABORATE_AGENT, content,
                Map.of("agent", agentName));
    }
    
    /** 创建协同结束事件 */
    public static StreamEvent collaborateEnd(String mode, String result) {
        return new StreamEvent(EventType.COLLABORATE_END, result,
                Map.of("mode", mode));
    }
    
    /** 创建思考过程事件 */
    public static StreamEvent thinking(String content) {
        return new StreamEvent(EventType.THINKING, content, null);
    }
    
    // ==================== Getters ====================
    
    public EventType getType() {
        return type;
    }
    
    public String getContent() {
        return content;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    /**
     * 获取指定 metadata 字段的值
     */
    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key) {
        if (metadata == null) return null;
        return (T) metadata.get(key);
    }
    
    /**
     * 判断是否为内容类事件（需要显示给用户的文本）
     */
    public boolean isContentEvent() {
        return type == EventType.CONTENT 
                || type == EventType.SUBAGENT_CONTENT 
                || type == EventType.COLLABORATE_AGENT;
    }
    
    /**
     * 格式化为用户可读的字符串（用于 CLI 显示）
     */
    public String format() {
        return switch (type) {
            case CONTENT -> content;
            case TOOL_START -> "\n🔧 调用工具: " + content + "\n";
            case TOOL_END -> {
                Boolean success = getMeta("success");
                String icon = Boolean.TRUE.equals(success) ? "✅" : "❌";
                yield icon + " 工具执行完成\n";
            }
            case SUBAGENT_START -> {
                String label = getMeta("label");
                String displayLabel = (label != null && !label.isEmpty()) ? " '" + label + "'" : "";
                yield "\n👤 子代理" + displayLabel + " 开始执行...\n";
            }
            case SUBAGENT_CONTENT -> content;
            case SUBAGENT_END -> {
                Boolean success = getMeta("success");
                String icon = Boolean.TRUE.equals(success) ? "✅" : "❌";
                yield "\n" + icon + " 子代理执行完成\n";
            }
            case COLLABORATE_START -> {
                String mode = getMeta("mode");
                yield "\n🤝 启动多 Agent 协同 [" + mode + "]: " + content + "\n";
            }
            case COLLABORATE_AGENT -> {
                String agent = getMeta("agent");
                yield "\n💬 [" + agent + "]: " + content + "\n";
            }
            case COLLABORATE_END -> "\n🎯 协同完成\n";
            case THINKING -> "💭 " + content + "\n";
        };
    }
    
    @Override
    public String toString() {
        return "StreamEvent{type=" + type + ", content='" + 
                (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + "'}";
    }
}
