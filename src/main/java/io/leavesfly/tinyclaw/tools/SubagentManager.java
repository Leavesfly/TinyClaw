package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理管理器
 * 用于生成和跟踪子代理任务
 */
public class SubagentManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("subagent");
    
    private final Map<String, SubagentTask> tasks = new ConcurrentHashMap<>();
    private final LLMProvider provider;
    private final MessageBus bus;
    private final String workspace;
    private final AtomicInteger nextId = new AtomicInteger(1);
    
    /**
     * 表示一个子代理任务
     */
    public static class SubagentTask {
        private String id;
        private String task;
        private String label;
        private String originChannel;
        private String originChatId;
        private String status;
        private String result;
        private long created;
        
        public SubagentTask() {}
        
        // Getter 和 Setter 方法
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        
        public String getOriginChannel() { return originChannel; }
        public void setOriginChannel(String originChannel) { this.originChannel = originChannel; }
        
        public String getOriginChatId() { return originChatId; }
        public void setOriginChatId(String originChatId) { this.originChatId = originChatId; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        
        public long getCreated() { return created; }
        public void setCreated(long created) { this.created = created; }
    }
    
    public SubagentManager(LLMProvider provider, String workspace, MessageBus bus) {
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
    }
    
    /**
     * 生成一个新的子代理任务
     */
    public String spawn(String task, String label, String originChannel, String originChatId) {
        String taskId = "subagent-" + nextId.getAndIncrement();
        
        SubagentTask subagentTask = new SubagentTask();
        subagentTask.setId(taskId);
        subagentTask.setTask(task);
        subagentTask.setLabel(label != null ? label : "");
        subagentTask.setOriginChannel(originChannel != null ? originChannel : "cli");
        subagentTask.setOriginChatId(originChatId != null ? originChatId : "direct");
        subagentTask.setStatus("running");
        subagentTask.setCreated(System.currentTimeMillis());
        
        tasks.put(taskId, subagentTask);
        
        // 在后台运行任务
        Thread thread = new Thread(() -> runTask(subagentTask), "subagent-" + taskId);
        thread.setDaemon(true);
        thread.start();
        
        logger.info("Spawned subagent", Map.of(
                "task_id", taskId,
                "label", label,
                "task_preview", task.length() > 50 ? task.substring(0, 50) + "..." : task
        ));
        
        if (label != null && !label.isEmpty()) {
            return "已生成子代理 '" + label + "' 处理任务: " + task;
        }
        return "已生成子代理处理任务: " + task;
    }
    
    private void runTask(SubagentTask task) {
        task.setStatus("running");
        task.setCreated(System.currentTimeMillis());
        
        // 为子代理构建消息
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个子代理。独立完成给定的任务并报告结果。"));
        messages.add(new Message("user", task.getTask()));
        
        try {
            LLMResponse response = provider.chat(messages, null, provider.getDefaultModel(), Map.of(
                    "max_tokens", 4096
            ));
            
            task.setStatus("completed");
            task.setResult(response.getContent());
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("错误: " + e.getMessage());
            logger.error("Subagent task failed", Map.of(
                    "task_id", task.getId(),
                    "error", e.getMessage()
            ));
        }
        
        // 发送通知消息回主 Agent
        if (bus != null) {
            String announceContent;
            if (task.getLabel() != null && !task.getLabel().isEmpty()) {
                announceContent = "任务 '" + task.getLabel() + "' 已完成。\n\n结果:\n" + task.getResult();
            } else {
                announceContent = "任务已完成。\n\n结果:\n" + task.getResult();
            }
            
            bus.publishInbound(new InboundMessage(
                    "system",
                    "subagent:" + task.getId(),
                    task.getOriginChannel() + ":" + task.getOriginChatId(),
                    announceContent
            ));
        }
    }
    
    /**
     * 根据 ID 获取任务
     */
    public SubagentTask getTask(String taskId) {
        return tasks.get(taskId);
    }
    
    /**
     * 列出所有任务
     */
    public List<SubagentTask> listTasks() {
        return new ArrayList<>(tasks.values());
    }
    
    /**
     * 获取任务数量
     */
    public int getTaskCount() {
        return tasks.size();
    }
}
