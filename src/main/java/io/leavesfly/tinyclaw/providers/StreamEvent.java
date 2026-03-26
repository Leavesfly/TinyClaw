package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
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
        /** 协同中的 Agent 发言（完整消息） */
        COLLABORATE_AGENT,
        /** 协同中的 Agent 发言增量（流式 chunk） */
        COLLABORATE_AGENT_CHUNK,
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
    
    /** 创建协同 Agent 发言事件（完整消息） */
    public static StreamEvent collaborateAgent(String agentName, String content) {
        return new StreamEvent(EventType.COLLABORATE_AGENT, content,
                Map.of("agent", agentName));
    }
    
    /** 创建协同 Agent 发言增量事件（流式 chunk） */
    public static StreamEvent collaborateAgentChunk(String agentName, String chunk) {
        return new StreamEvent(EventType.COLLABORATE_AGENT_CHUNK, chunk,
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
                || type == EventType.COLLABORATE_AGENT
                || type == EventType.COLLABORATE_AGENT_CHUNK;
    }
    
    /**
     * 格式化为用户可读的字符串（用于 CLI 显示）
     */
    public String format() {
        return switch (type) {
            case CONTENT -> content;
            case TOOL_START -> {
                Map<String, Object> args = getMeta("args");
                String argsPreview = "";
                if (args != null && !args.isEmpty()) {
                    String argsStr = args.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    if (argsStr.length() > 200) {
                        argsStr = argsStr.substring(0, 200) + "...";
                    }
                    argsPreview = " " + argsStr;
                }
                yield "\n🔧 调用工具: " + content + argsPreview + "\n";
            }
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
            case COLLABORATE_AGENT_CHUNK -> content;
            case COLLABORATE_END -> "\n🎯 协同完成\n";
            case THINKING -> "💭 " + content + "\n";
        };
    }
    
    /**
     * 序列化为 JSON 字符串，用于 SSE 结构化传输。
     * 前端通过 type 字段区分事件类型，渲染不同 UI 组件。
     *
     * 输出格式示例：
     * {"type":"CONTENT","content":"hello"}
     * {"type":"TOOL_START","content":"write_file","tool":"write_file","args":{"path":"...","content":"..."}}
     * {"type":"TOOL_END","tool":"write_file","success":true,"result":"..."}
     */
    public String toJson() {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", type.name());

            switch (type) {
                case CONTENT -> node.put("content", content != null ? content : "");
                case THINKING -> node.put("content", content != null ? content : "");
                case TOOL_START -> {
                    node.put("tool", content != null ? content : "");
                    Map<String, Object> args = getMeta("args");
                    if (args != null && !args.isEmpty()) {
                        ObjectNode argsNode = MAPPER.createObjectNode();
                        args.forEach((key, value) -> {
                            if (value == null) {
                                argsNode.put(key, "");
                                return;
                            }
                            String strValue = value.toString();
                            // 对超长参数值（如文件内容）做截断，避免单个 SSE 事件过大导致传输异常
                            if (strValue.length() > 500) {
                                strValue = strValue.substring(0, 500) + "…（内容过长已截断）";
                            }
                            argsNode.put(key, strValue);
                        });
                        node.set("args", argsNode);
                    }
                }
                case TOOL_END -> {
                    String toolName = getMeta("tool");
                    Boolean success = getMeta("success");
                    node.put("tool", toolName != null ? toolName : "");
                    node.put("success", Boolean.TRUE.equals(success));
                    node.put("result", content != null ? content : "");
                }
                case SUBAGENT_START -> {
                    String taskId = getMeta("taskId");
                    String label = getMeta("label");
                    node.put("taskId", taskId != null ? taskId : "");
                    node.put("label", label != null ? label : "");
                    node.put("task", content != null ? content : "");
                }
                case SUBAGENT_CONTENT -> {
                    String taskId = getMeta("taskId");
                    node.put("taskId", taskId != null ? taskId : "");
                    node.put("content", content != null ? content : "");
                }
                case SUBAGENT_END -> {
                    String taskId = getMeta("taskId");
                    Boolean success = getMeta("success");
                    node.put("taskId", taskId != null ? taskId : "");
                    node.put("success", Boolean.TRUE.equals(success));
                    node.put("result", content != null ? content : "");
                }
                case COLLABORATE_START -> {
                    String mode = getMeta("mode");
                    node.put("mode", mode != null ? mode : "");
                    node.put("topic", content != null ? content : "");
                }
                case COLLABORATE_AGENT -> {
                    String agent = getMeta("agent");
                    node.put("agent", agent != null ? agent : "");
                    node.put("content", content != null ? content : "");
                }
                case COLLABORATE_AGENT_CHUNK -> {
                    String agent = getMeta("agent");
                    node.put("agent", agent != null ? agent : "");
                    node.put("content", content != null ? content : "");
                }
                case COLLABORATE_END -> {
                    String mode = getMeta("mode");
                    node.put("mode", mode != null ? mode : "");
                    node.put("result", content != null ? content : "");
                }
            }

            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // 序列化失败时降级为纯文本内容，保证流不中断
            return "{\"type\":\"CONTENT\",\"content\":" + escapeJsonString(content) + "}";
        }
    }

    private static String escapeJsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    @Override
    public String toString() {
        return "StreamEvent{type=" + type + ", content='" + 
                (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + "'}";
    }
}
