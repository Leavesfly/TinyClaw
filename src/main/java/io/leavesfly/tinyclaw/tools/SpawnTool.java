package io.leavesfly.tinyclaw.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理生成工具 - 生成子代理处理后台任务
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
        return "生成一个子代理在后台处理任务。" +
               "用于复杂或耗时的任务，可以独立运行。" +
               "子代理将完成任务并在完成时报告。";
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
    public String execute(Map<String, Object> args) throws Exception {
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return "错误: 任务参数是必需的";
        }
        
        String label = (String) args.get("label");
        
        if (manager == null) {
            return "错误: 子代理管理器未配置";
        }
        
        return manager.spawn(task, label, originChannel, originChatId);
    }
}
