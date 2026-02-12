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
 * Manager for spawning and tracking subagents
 */
public class SubagentManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("subagent");
    
    private final Map<String, SubagentTask> tasks = new ConcurrentHashMap<>();
    private final LLMProvider provider;
    private final MessageBus bus;
    private final String workspace;
    private final AtomicInteger nextId = new AtomicInteger(1);
    
    /**
     * Represents a subagent task
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
        
        // Getters and setters
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
     * Spawn a new subagent task
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
        
        // 运行 task in background
        Thread thread = new Thread(() -> runTask(subagentTask), "subagent-" + taskId);
        thread.setDaemon(true);
        thread.start();
        
        logger.info("Spawned subagent", Map.of(
                "task_id", taskId,
                "label", label,
                "task_preview", task.length() > 50 ? task.substring(0, 50) + "..." : task
        ));
        
        if (label != null && !label.isEmpty()) {
            return "Spawned subagent '" + label + "' for task: " + task;
        }
        return "Spawned subagent for task: " + task;
    }
    
    private void runTask(SubagentTask task) {
        task.setStatus("running");
        task.setCreated(System.currentTimeMillis());
        
        // 构建 messages for subagent
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "You are a subagent. Complete the given task independently and report the result."));
        messages.add(new Message("user", task.getTask()));
        
        try {
            LLMResponse response = provider.chat(messages, null, provider.getDefaultModel(), Map.of(
                    "max_tokens", 4096
            ));
            
            task.setStatus("completed");
            task.setResult(response.getContent());
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("Error: " + e.getMessage());
            logger.error("Subagent task failed", Map.of(
                    "task_id", task.getId(),
                    "error", e.getMessage()
            ));
        }
        
        // 发送 announce message back to main agent
        if (bus != null) {
            String announceContent;
            if (task.getLabel() != null && !task.getLabel().isEmpty()) {
                announceContent = "Task '" + task.getLabel() + "' completed.\n\nResult:\n" + task.getResult();
            } else {
                announceContent = "Task completed.\n\nResult:\n" + task.getResult();
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
     * 获取 a task by ID
     */
    public SubagentTask getTask(String taskId) {
        return tasks.get(taskId);
    }
    
    /**
     * List all tasks
     */
    public List<SubagentTask> listTasks() {
        return new ArrayList<>(tasks.values());
    }
    
    /**
     * 获取 task count
     */
    public int getTaskCount() {
        return tasks.size();
    }
}
