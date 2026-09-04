package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
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
 * - SUBAGENT_THINKING: 子代理思考/推理过程（可选展示）
 * - SUBAGENT_END: 子代理执行结束
 * - COLLABORATE_START: 多 Agent 协同开始
 * - COLLABORATE_AGENT: 协同中的 Agent 发言
 * - COLLABORATE_AGENT_THINKING: 协同中的 Agent 思考/推理过程（可选展示）
 * - COLLABORATE_TOPOLOGY: 协同关系拓扑结构（初始版/终版全量下发，驱动实时拓扑图）
 * - COLLABORATE_NODE: 协同拓扑节点状态变化（实时增量，点亮图上的节点）
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
        /** 子代理思考/推理过程 */
        SUBAGENT_THINKING,
        /** 子代理执行结束 */
        SUBAGENT_END,
        /** 多 Agent 协同开始 */
        COLLABORATE_START,
        /** 协同中的 Agent 发言（完整消息） */
        COLLABORATE_AGENT,
        /** 协同中的 Agent 发言增量（流式 chunk） */
        COLLABORATE_AGENT_CHUNK,
        /** 协同中的 Agent 思考/推理过程 */
        COLLABORATE_AGENT_THINKING,
        /** 协同关系拓扑结构（协同开始时下发初始版，结束时下发含真实状态的终版） */
        COLLABORATE_TOPOLOGY,
        /** 协同拓扑节点状态变化（执行期增量，驱动前端实时点亮） */
        COLLABORATE_NODE,
        /** 多 Agent 协同结束 */
        COLLABORATE_END,
        /** 思考/推理过程 */
        THINKING,
        /** 危险命令审批请求（HITL）：暂停执行，等待用户在 Web 控制台批准/拒绝 */
        APPROVAL_REQUEST,
        /** 结构化提问（HITL）：向用户征询信息/决策并等待回答 */
        ASK_USER,
        /** 任务计划清单（Plan/Todo）：展示多步任务的分解与进度 */
        PLAN
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
                Map.of("tool", safe(toolName), "args", args != null ? args : Map.of()));
    }
    
    /** 创建工具调用结束事件 */
    public static StreamEvent toolEnd(String toolName, String result, boolean success) {
        return new StreamEvent(EventType.TOOL_END, result, 
                Map.of("tool", safe(toolName), "success", success));
    }
    
    /** 创建子代理开始事件 */
    public static StreamEvent subagentStart(String taskId, String task, String label) {
        return new StreamEvent(EventType.SUBAGENT_START, task,
                Map.of("taskId", safe(taskId), "label", safe(label)));
    }
    
    /** 创建子代理内容事件 */
    public static StreamEvent subagentContent(String taskId, String content) {
        return new StreamEvent(EventType.SUBAGENT_CONTENT, content,
                Map.of("taskId", safe(taskId)));
    }
    
    /** 创建子代理思考过程事件 */
    public static StreamEvent subagentThinking(String taskId, String content) {
        return new StreamEvent(EventType.SUBAGENT_THINKING, content,
                Map.of("taskId", safe(taskId)));
    }
    
    /** 创建子代理结束事件 */
    public static StreamEvent subagentEnd(String taskId, String result, boolean success) {
        return new StreamEvent(EventType.SUBAGENT_END, result,
                Map.of("taskId", safe(taskId), "success", success));
    }
    
    /** 创建协同开始事件 */
    public static StreamEvent collaborateStart(String mode, String topic) {
        return new StreamEvent(EventType.COLLABORATE_START, topic,
                Map.of("mode", safe(mode)));
    }
    
    /** 创建协同 Agent 发言事件（完整消息） */
    public static StreamEvent collaborateAgent(String agentName, String content) {
        return new StreamEvent(EventType.COLLABORATE_AGENT, content,
                Map.of("agent", safe(agentName)));
    }
    
    /**
     * 创建协同 Agent 发言增量事件（流式 chunk）
     *
     * @param agentName 角色名
     * @param chunk     文本增量
     * @param turn      发言轮次标识（同一次发言的所有事件共用，见 {@link #withScope}）
     */
    public static StreamEvent collaborateAgentChunk(String agentName, String chunk, String turn) {
        return new StreamEvent(EventType.COLLABORATE_AGENT_CHUNK, chunk,
                Map.of("agent", safe(agentName), "turn", safe(turn)));
    }
    
    /**
     * 创建协同 Agent 思考过程事件
     *
     * @param agentName 角色名
     * @param content   推理内容增量
     * @param turn      发言轮次标识
     */
    public static StreamEvent collaborateAgentThinking(String agentName, String content, String turn) {
        return new StreamEvent(EventType.COLLABORATE_AGENT_THINKING, content,
                Map.of("agent", safe(agentName), "turn", safe(turn)));
    }
    
    /**
     * 创建协同拓扑结构事件。
     *
     * <p>协同开始时下发初始版（全 PENDING），结束时下发终版（含真实状态与边）。
     * 前端收到终版后全量替换，用户看到的最后一张图与落盘记录一致。</p>
     *
     * <p>参数类型为 {@code Object} 而非具体拓扑类：本类位于 providers 包，不应反向
     * 依赖 collaboration 包。拓扑对象只需是 Jackson 可序列化的普通数据类。</p>
     *
     * @param topology 协同拓扑快照（collaboration.CollaborationTopology）
     */
    public static StreamEvent collaborateTopology(Object topology) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("topology", topology);
        return new StreamEvent(EventType.COLLABORATE_TOPOLOGY, null, meta);
    }

    /**
     * 创建协同拓扑节点状态变化事件。
     *
     * @param nodeId 节点 id（与拓扑结构里的节点 id 对应）
     * @param label  节点展示名（CLI 降级显示用）
     * @param status 新状态（COMPLETED / FAILED / SKIPPED / RUNNING / PENDING）
     */
    public static StreamEvent collaborateNode(String nodeId, String label, String status) {
        return new StreamEvent(EventType.COLLABORATE_NODE, safe(label),
                Map.of("nodeId", safe(nodeId), "label", safe(label), "status", safe(status)));
    }

    /** 创建协同结束事件 */
    public static StreamEvent collaborateEnd(String mode, String result) {
        return new StreamEvent(EventType.COLLABORATE_END, result,
                Map.of("mode", safe(mode)));
    }
    
    /** 创建思考过程事件 */
    public static StreamEvent thinking(String content) {
        return new StreamEvent(EventType.THINKING, content, null);
    }

    /**
     * 创建危险命令审批请求事件（HITL）。
     *
     * <p>前端据此渲染审批卡片；用户点击批准/拒绝后经 REST 回传，
     * 由 {@code InteractionBroker} 唤醒阻塞中的工具执行。</p>
     *
     * @param requestId 交互请求 id（回传时用于定位等待中的 future）
     * @param command   待审批的危险命令
     * @param reason    触发审批的原因（命中的黑名单规则/拦截说明）
     */
    public static StreamEvent approvalRequest(String requestId, String command, String reason) {
        return new StreamEvent(EventType.APPROVAL_REQUEST, command,
                Map.of("requestId", safe(requestId), "command", safe(command), "reason", safe(reason)));
    }

    /**
     * 创建结构化提问事件（HITL）。
     *
     * @param requestId 交互请求 id
     * @param question  问题文本
     * @param options   可选项（可为空，表示自由作答）
     */
    public static StreamEvent askUser(String requestId, String question, java.util.List<String> options) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("requestId", safe(requestId));
        meta.put("options", options != null ? options : java.util.List.of());
        return new StreamEvent(EventType.ASK_USER, question, meta);
    }

    /**
     * 创建任务计划事件（Plan/Todo）。
     *
     * <p>专用结构化事件而非复用 TOOL_START：后者的 args 值会被截断到 500 字符，
     * 较长的清单 JSON 会被截断导致前端无法解析。本事件完整序列化 todos 数组。</p>
     *
     * @param todos 任务项列表，每项形如 {@code {content, status}}
     */
    public static StreamEvent plan(java.util.List<?> todos) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("todos", todos != null ? todos : java.util.List.of());
        return new StreamEvent(EventType.PLAN, null, meta);
    }
    
    /**
     * 为嵌套执行（子代理 / 协同角色）产生的过程事件补上归属标识。
     *
     * <p>事件类型与内容原样保留，仅在 metadata 追加归属字段（子代理用 {@code taskId}，
     * 协同角色用 {@code agent} 与 {@code turn}）。工具调用这类结构化事件因此能原样上传，
     * 前端据此把卡片渲染进对应的子代理卡片或本次发言块，而不是当作主 Agent 自己的
     * 工具调用；也避开了降级成 {@code format()} 文本后碎片化混入正文的旧路径。</p>
     *
     * @param key   归属字段名（taskId / agent / turn）
     * @param value 归属字段值
     * @return 带归属标识的新事件（原事件不变）
     */
    public StreamEvent withScope(String key, String value) {
        Map<String, Object> scoped = new HashMap<>();
        if (metadata != null) {
            scoped.putAll(metadata);
        }
        scoped.put(key, safe(value));
        return new StreamEvent(type, content, scoped);
    }
    
    /**
     * metadata 值的空值兜底。
     * 
     * <p>{@code Map.of} 不接受 null 值，会直接抛 NPE。这些标识字段（taskId / 工具名 /
     * 角色名）由上游传入，一旦某条链路漏传就会在构造事件时炸掉整个执行循环——而事件
     * 只是过程展示，不该有能力中断任务。</p>
     */
    private static String safe(String value) {
        return value != null ? value : "";
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
            case SUBAGENT_THINKING -> "💭 " + ensureTrailingNewline(content);
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
            case COLLABORATE_AGENT_THINKING -> "💭 " + ensureTrailingNewline(content);
            case COLLABORATE_TOPOLOGY -> {
                // 图形信息无法在纯文本通道呈现，只报规模，避免污染 CLI 正文
                yield "\n🕸 协同拓扑已下发（详见 Web 控制台）\n";
            }
            case COLLABORATE_NODE -> {
                String label = getMeta("label");
                String status = getMeta("status");
                String display = (label != null && !label.isEmpty()) ? label : getMeta("nodeId");
                yield "▸ " + display + " · " + status + "\n";
            }
            case COLLABORATE_END -> "\n🎯 协同完成\n";
            case THINKING -> "💭 " + ensureTrailingNewline(content);
            case APPROVAL_REQUEST -> "\n⚠️ 需要审批的危险命令: " + content + "\n";
            case ASK_USER -> "\n❓ " + content + "\n";
            case PLAN -> {
                Object todos = getMeta("todos");
                int n = (todos instanceof java.util.List<?> l) ? l.size() : 0;
                yield "\n📋 任务计划已更新（" + n + " 项）\n";
            }
        };
    }
    
    /**
     * 保证思考文本以换行结尾：行粒度的 THINKING 内容自带行尾换行，
     * 软冲刷或外部构造的内容可能缺失，补齐避免 CLI 降级输出与后续内容粘连。
     */
    private static String ensureTrailingNewline(String content) {
        String text = content != null ? content : "";
        return text.endsWith("\n") ? text : text + "\n";
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
                    putScopeFields(node);
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
                    putScopeFields(node);
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
                case SUBAGENT_THINKING -> {
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
                    putScopeFields(node);
                }
                case COLLABORATE_AGENT_THINKING -> {
                    String agent = getMeta("agent");
                    node.put("agent", agent != null ? agent : "");
                    node.put("content", content != null ? content : "");
                    putScopeFields(node);
                }
                case COLLABORATE_TOPOLOGY -> {
                    Object topology = getMeta("topology");
                    // 序列化失败时降级为空拓扑而非断流，前端收不到结构只是少了实时图。
                    // 连 StackOverflowError 也接：自引用结构会让 Jackson 递归爆栈，
                    // 它是 Error 不是 RuntimeException，不接会击穿整个流
                    if (topology != null) {
                        try {
                            node.set("topology", MAPPER.valueToTree(topology));
                        } catch (RuntimeException | StackOverflowError e) {
                            node.putObject("topology");
                        }
                    }
                }
                case COLLABORATE_NODE -> {
                    String nodeId = getMeta("nodeId");
                    String label = getMeta("label");
                    String status = getMeta("status");
                    node.put("nodeId", nodeId != null ? nodeId : "");
                    node.put("label", label != null ? label : "");
                    node.put("status", status != null ? status : "");
                }
                case COLLABORATE_END -> {
                    String mode = getMeta("mode");
                    node.put("mode", mode != null ? mode : "");
                    node.put("result", content != null ? content : "");
                }
                case APPROVAL_REQUEST -> {
                    String requestId = getMeta("requestId");
                    String reason = getMeta("reason");
                    node.put("requestId", requestId != null ? requestId : "");
                    node.put("command", content != null ? content : "");
                    node.put("reason", reason != null ? reason : "");
                }
                case ASK_USER -> {
                    String requestId = getMeta("requestId");
                    node.put("requestId", requestId != null ? requestId : "");
                    node.put("question", content != null ? content : "");
                    Object options = getMeta("options");
                    if (options != null) {
                        node.set("options", MAPPER.valueToTree(options));
                    }
                }
                case PLAN -> {
                    Object todos = getMeta("todos");
                    if (todos != null) {
                        node.set("todos", MAPPER.valueToTree(todos));
                    }
                }
            }

            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // 序列化失败时降级为纯文本内容，保证流不中断
            return "{\"type\":\"CONTENT\",\"content\":" + escapeJsonString(content) + "}";
        }
    }

    /**
     * 输出嵌套归属字段（若有），供前端把卡片渲染进对应容器。
     * {@code turn} 标识一次发言，使并行协同下交错到达的事件能各归各块。
     * 主 Agent 自己的事件无这些字段，前端落回顶层消息容器。
     */
    private void putScopeFields(ObjectNode node) {
        String taskId = getMeta("taskId");
        if (taskId != null && !taskId.isEmpty()) {
            node.put("taskId", taskId);
        }
        String agent = getMeta("agent");
        if (agent != null && !agent.isEmpty()) {
            node.put("agent", agent);
        }
        String turn = getMeta("turn");
        if (turn != null && !turn.isEmpty()) {
            node.put("turn", turn);
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
