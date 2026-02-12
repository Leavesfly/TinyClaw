package io.leavesfly.tinyclaw.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SpawnTool - Spawn subagents for background tasks
 */
public class SpawnTool implements Tool {
    
    private final SubagentManager manager;
    private String originChannel = "cli";
    private String originChatId = "direct";
    
    public SpawnTool(SubagentManager manager) {
        this.manager = manager;
    }
    
    @Override
    public String name() {
        return "spawn";
    }
    
    @Override
    public String description() {
        return "Spawn a subagent to handle a task in the background. " +
               "Use this for complex or time-consuming tasks that can run independently. " +
               "The subagent will complete the task and report back when done.";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> task = new HashMap<>();
        task.put("type", "string");
        task.put("description", "The task for subagent to complete");
        properties.put("task", task);
        
        Map<String, Object> label = new HashMap<>();
        label.put("type", "string");
        label.put("description", "Optional short label for the task (for display)");
        properties.put("label", label);
        
        params.put("properties", properties);
        params.put("required", List.of("task"));
        
        return params;
    }
    
    /**
     * 设置 context for spawn
     */
    public void setContext(String channel, String chatId) {
        this.originChannel = channel != null ? channel : "cli";
        this.originChatId = chatId != null ? chatId : "direct";
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return "Error: task is required";
        }
        
        String label = (String) args.get("label");
        
        if (manager == null) {
            return "Error: Subagent manager not configured";
        }
        
        return manager.spawn(task, label, originChannel, originChatId);
    }
}
