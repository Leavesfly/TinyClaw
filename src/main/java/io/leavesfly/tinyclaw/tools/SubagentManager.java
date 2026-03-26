package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.agent.ReActExecutor;
import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.session.SessionManager;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 子代理管理器
 * 用于生成和跟踪子代理任务
 */
public class SubagentManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("subagent");
    
    // 任务保留时间（默认1小时）
    private static final long TASK_RETENTION_MS = 60 * 60 * 1000;
    // 清理间隔（10分钟）
    private static final long CLEANUP_INTERVAL_MS = 10 * 60 * 1000;
    
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    
    private final Map<String, SubagentTask> tasks = new ConcurrentHashMap<>();
    private final LLMProvider provider;
    private final MessageBus bus;
    private final String workspace;
    private final ToolRegistry tools;
    private final String model;
    private final int maxIterations;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ExecutorService executor;
    private volatile long lastCleanup = System.currentTimeMillis();
    
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
    
    public SubagentManager(LLMProvider provider, String workspace, MessageBus bus,
                           ToolRegistry tools, String model, int maxIterations) {
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.tools = tools;
        this.model = model;
        this.maxIterations = maxIterations > 0 ? maxIterations : DEFAULT_MAX_ITERATIONS;
        // 使用线程池管理子代理任务
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("subagent-pool-" + t.getId());
            return t;
        });
    }
    
    /**
     * 便捷构造器，使用默认配置
     */
    public SubagentManager(LLMProvider provider, String workspace, MessageBus bus, ToolRegistry tools) {
        this(provider, workspace, bus, tools, provider.getDefaultModel(), DEFAULT_MAX_ITERATIONS);
    }
    
    /**
     * 同步生成子代理并等待执行完成，返回子代理的实际执行结果。
     * 这是 "subagent as tool" 的核心方法：主 Agent 阻塞等待子 Agent 完成，
     * 结果作为 tool_result 直接返回给主 Agent 的推理循环。
     */
    public String spawnAndWait(String task, String label) {
        return spawnAndWaitStream(task, label, null);
    }
    
    /**
     * 同步生成子代理并等待执行完成（流式版本）。
     * 支持通过回调输出子代理的执行过程信息。
     * 
     * @param task 子代理任务描述
     * @param label 任务标签（可选）
     * @param callback 流式回调，用于输出子代理的执行过程（可为 null）
     * @return 子代理的执行结果
     */
    public String spawnAndWaitStream(String task, String label, 
                                     LLMProvider.EnhancedStreamCallback callback) {
        maybeCleanupOldTasks();

        String taskId = "subagent-" + nextId.getAndIncrement();

        SubagentTask subagentTask = new SubagentTask();
        subagentTask.setId(taskId);
        subagentTask.setTask(task);
        subagentTask.setLabel(label != null ? label : "");
        subagentTask.setOriginChannel("internal");
        subagentTask.setOriginChatId("sync");
        subagentTask.setStatus("running");
        subagentTask.setCreated(System.currentTimeMillis());

        tasks.put(taskId, subagentTask);

        logger.info("Spawned sync subagent", Map.of(
                "task_id", taskId,
                "label", label != null ? label : "",
                "task_preview", task.length() > 50 ? task.substring(0, 50) + "..." : task
        ));
        
        // 通过回调输出子代理开始事件
        if (callback != null) {
            callback.onEvent(StreamEvent.subagentStart(taskId, task, label));
        }

        // 同步执行，阻塞当前线程直到子 Agent 完成
        runTaskSyncWithStream(subagentTask, callback);
        
        // 通过回调输出子代理结束事件
        if (callback != null) {
            boolean success = "completed".equals(subagentTask.getStatus());
            callback.onEvent(StreamEvent.subagentEnd(taskId, subagentTask.getResult(), success));
        }

        return subagentTask.getResult();
    }

    /**
     * 同步执行子代理任务（不通过 MessageBus 回传，直接将结果写入 task 对象）。
     */
    private void runTaskSync(SubagentTask task) {
        runTaskSyncWithStream(task, null);
    }
    
    /**
     * 同步执行子代理任务（流式版本）。
     * 支持通过回调输出子代理的流式响应。
     * 
     * @param task 子代理任务
     * @param callback 流式回调（可为 null）
     */
    private void runTaskSyncWithStream(SubagentTask task, LLMProvider.EnhancedStreamCallback callback) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
                "你是一个子代理。独立完成给定的任务并报告结果。" +
                "你可以使用提供的工具来完成任务。" +
                "完成后，用简洁明了的方式汇报结果。"));
        messages.add(new Message("user", task.getTask()));

        String subagentSessionPath = Paths.get(workspace, "sessions", "subagent").toString();
        SessionManager subagentSessions = new SessionManager(subagentSessionPath);
        String sessionKey = "subagent:" + task.getId();

        try {
            ReActExecutor reActExecutor = new ReActExecutor(provider, tools, subagentSessions, model, null, maxIterations);
            String result;
            
            if (callback != null) {
                // 使用流式执行，将子代理的输出通过回调传递
                result = reActExecutor.executeStream(messages, sessionKey, chunk -> {
                    // 将子代理的内容包装为 subagentContent 事件
                    callback.onEvent(StreamEvent.subagentContent(task.getId(), chunk));
                });
            } else {
                result = reActExecutor.execute(messages, sessionKey);
            }

            task.setStatus("completed");
            task.setResult(result != null ? result : "任务已完成但无返回内容");

            logger.info("Sync subagent task completed", Map.of(
                    "task_id", task.getId(),
                    "result_length", task.getResult().length()
            ));
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("子代理执行失败: " + e.getMessage());
            logger.error("Sync subagent task failed", Map.of(
                    "task_id", task.getId(),
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 异步生成一个新的子代理任务（fire-and-forget 模式）。
     * 子代理在后台线程中运行，完成后通过 MessageBus 通知主 Agent。
     */
    public String spawn(String task, String label, String originChannel, String originChatId) {
        // 定期清理过期任务
        maybeCleanupOldTasks();
        
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
        
        // 在线程池中运行任务
        executor.submit(() -> runTask(subagentTask));
        
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
        messages.add(new Message("system", 
                "你是一个子代理。独立完成给定的任务并报告结果。" +
                "你可以使用提供的工具来完成任务。" +
                "完成后，用简洁明了的方式汇报结果。"));
        messages.add(new Message("user", task.getTask()));
        
        // 为子代理创建独立的会话管理器
        String subagentSessionPath = Paths.get(workspace, "sessions", "subagent").toString();
        SessionManager subagentSessions = new SessionManager(subagentSessionPath);
        String sessionKey = "subagent:" + task.getId();
        
        try {
            // 使用 ReActExecutor 实现完整的工具调用和循环能力
            ReActExecutor executor = new ReActExecutor(provider, tools, subagentSessions, model, null, maxIterations);
            String result = executor.execute(messages, sessionKey);
            
            task.setStatus("completed");
            task.setResult(result != null ? result : "任务已完成但无返回内容");
            
            logger.info("Subagent task completed", Map.of(
                    "task_id", task.getId(),
                    "result_length", task.getResult().length()
            ));
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("错误: " + e.getMessage());
            logger.error("Subagent task failed", Map.of(
                    "task_id", task.getId(),
                    "error", e.getMessage()
            ));
        } finally {
            // 发送通知消息回主 Agent
            sendTaskCompletion(task);
        }
    }
    
    /**
     * 发送任务完成通知
     */
    private void sendTaskCompletion(SubagentTask task) {
        if (bus == null) {
            return;
        }
        
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
    
    /**
     * 定期清理过期任务
     */
    private void maybeCleanupOldTasks() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        
        lastCleanup = now;
        int removed = 0;
        
        for (Map.Entry<String, SubagentTask> entry : tasks.entrySet()) {
            SubagentTask task = entry.getValue();
            // 清理已完成或失败且超过保留时间的任务
            boolean isFinished = "completed".equals(task.getStatus()) || "failed".equals(task.getStatus());
            boolean isExpired = now - task.getCreated() > TASK_RETENTION_MS;
            
            if (isFinished && isExpired) {
                tasks.remove(entry.getKey());
                removed++;
            }
        }
        
        if (removed > 0) {
            logger.info("清理过期子代理任务", Map.of("removed", removed, "remaining", tasks.size()));
        }
    }
    
    /**
     * 关闭线程池
     */
    public void shutdown() {
        logger.info("关闭 SubagentManager");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
