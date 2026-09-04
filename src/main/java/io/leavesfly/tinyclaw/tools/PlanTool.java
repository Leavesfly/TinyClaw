package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务计划工具（Plan/Todo）：让 Agent 把复杂多步任务拆解为一份可视化清单，并在推进过程中
 * 更新每一项的状态，使用户能实时看到「做到哪一步」。
 *
 * <p>对标主流 Agent 产品的 Todo/Plan 面板。工具每次调用都会沿流式回调下发一个
 * {@link StreamEvent.EventType#PLAN} 事件（完整结构化，不受 TOOL_START 的 500 字符截断影响），
 * 前端据此渲染/刷新同一张计划卡片。</p>
 *
 * <p>与其它单例工具一样，回调在每次执行前由 {@code ReActExecutor.setToolContext} 覆写，
 * execute 内先读入局部变量再用。回调为 null（如纯 CLI 非流式）时只返回文本计划，不报错。</p>
 */
public class PlanTool implements Tool, StreamAwareTool {

    /** 工具名，供前端识别与嵌套执行体决策使用。 */
    public static final String NAME = "update_plan";

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("tools.plan");

    /** 合法状态集合。 */
    private static final List<String> VALID_STATUS = List.of("pending", "in_progress", "completed");

    private volatile LLMProvider.EnhancedStreamCallback streamCallback;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建或更新任务计划清单，用于把复杂的多步骤任务拆解为可跟踪的待办项，并向用户实时展示进度。"
                + "在开始一项多步骤任务时创建计划，在每完成/开始一项时更新对应状态。"
                + "status 取值：pending（待办）、in_progress（进行中）、completed（已完成）。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> todosParam = new HashMap<>();
        todosParam.put("type", "array");
        todosParam.put("description", "完整的任务清单（每次调用都传入全量列表，按执行顺序排列）");

        Map<String, Object> items = new HashMap<>();
        items.put("type", "object");
        Map<String, Object> itemProps = new HashMap<>();

        Map<String, Object> contentProp = new HashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "任务描述，简洁具体");
        itemProps.put("content", contentProp);

        Map<String, Object> statusProp = new HashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", VALID_STATUS);
        statusProp.put("description", "任务状态：pending / in_progress / completed");
        itemProps.put("status", statusProp);

        items.put("properties", itemProps);
        items.put("required", new String[]{"content", "status"});
        todosParam.put("items", items);

        properties.put("todos", todosParam);
        params.put("properties", properties);
        params.put("required", new String[]{"todos"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        List<Map<String, String>> todos = normalize(args.get("todos"));
        if (todos.isEmpty()) {
            throw new IllegalArgumentException("todos 参数是必需的，且至少包含一项任务");
        }

        // 先读入局部变量，避免并发执行时被下一次 setToolContext 覆写
        LLMProvider.EnhancedStreamCallback cb = this.streamCallback;
        if (cb != null) {
            cb.onEvent(StreamEvent.plan(todos));
        }
        logger.debug("Plan updated", Map.of("itemCount", todos.size()));

        return renderTextPlan(todos);
    }

    /**
     * 把模型传入的 todos 规范化为 {@code [{content, status}]} 列表，容忍缺字段与非法状态。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> normalize(Object raw) {
        List<Map<String, String>> todos = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return todos;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object contentObj = map.get("content");
            String content = contentObj != null ? String.valueOf(contentObj).trim() : "";
            if (content.isEmpty()) {
                continue;
            }
            Object statusObj = map.get("status");
            String status = statusObj != null ? String.valueOf(statusObj).trim().toLowerCase() : "pending";
            if (!VALID_STATUS.contains(status)) {
                status = "pending";
            }
            Map<String, String> todo = new LinkedHashMap<>();
            todo.put("content", content);
            todo.put("status", status);
            todos.add(todo);
        }
        return todos;
    }

    /**
     * 渲染纯文本计划，作为工具结果回传给模型（也用于 CLI 非流式场景）。
     */
    private String renderTextPlan(List<Map<String, String>> todos) {
        StringBuilder sb = new StringBuilder("计划已更新：\n");
        for (Map<String, String> todo : todos) {
            String mark = switch (todo.get("status")) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append(mark).append(' ').append(todo.get("content")).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }
}
