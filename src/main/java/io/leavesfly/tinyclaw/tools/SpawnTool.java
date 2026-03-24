package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理生成工具 - 支持同步（subagent as tool）和异步（fire-and-forget）两种模式。
 * 
 * 默认同步模式：子代理执行完毕后将实际结果作为 tool_result 返回给主 Agent，
 * 主 Agent 可基于结果继续推理。
 * 
 * 异步模式（async=true）：子代理在后台运行，立即返回确认信息，
 * 完成后通过 MessageBus 通知主 Agent。
 */
public class SpawnTool implements Tool, ToolContextAware, StreamAwareTool {
    
    private final SubagentManager manager;
    private String originChannel = "cli";
    private String originChatId = "direct";
    /** 流式回调（用于输出子代理执行过程） */
    private volatile LLMProvider.EnhancedStreamCallback streamCallback;
    
    public SpawnTool(SubagentManager manager) {
        this.manager = manager;
    }
    
    @Override
    public String name() {
        return "spawn";
    }
    
    @Override
    public String description() {
        return "生成一个子代理处理任务。" +
               "默认同步执行：子代理完成后直接返回结果，适合需要基于结果继续推理的场景。" +
               "设置 async=true 可切换为异步模式：子代理在后台运行，完成后通知，适合耗时的后台任务。";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> task = new HashMap<>();
        task.put("type", "string");
        task.put("description", "子代理要完成的任务");
        properties.put("task", task);
        
        Map<String, Object> label = new HashMap<>();
        label.put("type", "string");
        label.put("description", "任务的可选简短标签（用于显示）");
        properties.put("label", label);
        
        Map<String, Object> async = new HashMap<>();
        async.put("type", "boolean");
        async.put("description", "是否异步执行。默认 false（同步，等待子代理完成并返回结果）。设为 true 则在后台运行，立即返回确认信息。");
        properties.put("async", async);
        
        params.put("properties", properties);
        params.put("required", List.of("task"));
        
        return params;
    }
    
    /**
     * 设置生成上下文
     */
    public void setContext(String channel, String chatId) {
        this.originChannel = channel != null ? channel : "cli";
        this.originChatId = chatId != null ? chatId : "direct";
    }
    
    @Override
    public void setChannelContext(String channel, String chatId) {
        setContext(channel, chatId);
    }
    
    /**
     * 设置流式回调，用于输出子代理的执行过程。
     * 
     * @param callback 流式回调，可为 null
     */
    @Override
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return "错误: 任务参数是必需的";
        }
        
        String label = (String) args.get("label");
        
        if (manager == null) {
            return "错误: 子代理管理器未配置";
        }
        
        boolean async = Boolean.TRUE.equals(args.get("async"));
        
        if (async) {
            // 异步模式：后台运行，立即返回确认信息
            return manager.spawn(task, label, originChannel, originChatId);
        }
        
        // 同步模式（默认）：阻塞等待子代理完成，返回实际结果
        // 如果有流式回调，使用流式版本输出子代理的执行过程
        return manager.spawnAndWaitStream(task, label, streamCallback);
    }
}