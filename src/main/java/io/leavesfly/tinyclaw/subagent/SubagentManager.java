package io.leavesfly.tinyclaw.subagent;

import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.collaboration.CollaborateTool;
import io.leavesfly.tinyclaw.react.ReActExecutor;
import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.session.SessionManager;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 子代理管理器
 * 用于生成和跟踪子代理任务
 */
public class SubagentManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("subagent");

    /** 通用子代理的默认 system prompt（未指定专职定义时使用） */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个子代理。独立完成给定的任务并报告结果。" +
                    "你可以使用提供的工具来完成任务。" +
                    "完成后，用简洁明了的方式汇报结果。";

    // 任务保留时间（默认1小时）
    private static final long TASK_RETENTION_MS = 60 * 60 * 1000;
    // 清理间隔（10分钟）
    private static final long CLEANUP_INTERVAL_MS = 10 * 60 * 1000;

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    /** 子代理任务最大并发数（子代理任务为分钟级 LLM 循环，需限制并发避免线程/资源耗尽） */
    private static final int MAX_CONCURRENT_SUBAGENTS = 8;

    /** 等待队列容量，超出后拒绝新任务 */
    private static final int SUBAGENT_QUEUE_CAPACITY = 16;

    /**
     * 子代理一律拿不到的工具：协同只能由主 Agent 发起，子代理不得再派生。
     *
     * <p>{@code collaborate}：不设这道闸，协同角色仍能借 {@code spawn} 绕回来——角色派生
     * 子代理、子代理再调 {@code collaborate}，等于让单例的编排器、策略与协同线程池自我嵌套。</p>
     *
     * <p>{@code spawn}：同步派生在调用方线程内联执行（见 {@link #spawnAndWaitStream}），子代理
     * 再派生就是深度无界的栈递归，每层各带一轮 {@code maxIterations} 的 LLM 循环；SpawnTool
     * 同样是单实例，内层会覆写外层的流式回调与 sessionKey，其 finally 里的 clearProgress
     * 还会抹掉外层的进度卡。</p>
     *
     * <p>两者都不能只在子代理声明了工具白名单时才剔除：未声明时子代理拿到的是完整工具集，
     * 那才是默认情形，等于没设防。</p>
     */
    private static final List<String> DENIED_TOOLS = List.of(CollaborateTool.NAME, SpawnTool.NAME);

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

    /** 动态子代理定义加载器（可选，注入后支持按 AGENT.md 定义派生专职子代理） */
    private volatile SubagentsLoader agentsLoader;

    /**
     * 子代理共享的会话管理器，懒加载后复用。
     * 不能每次任务都 new：SessionManager 构造会建立整个目录的元信息索引，
     * 每次新建等于让每次子代理调用都付一次全目录扫描的成本。
     */
    private volatile SessionManager subagentSessions;

    /**
     * 获取子代理共享会话管理器（首次调用时初始化）
     */
    private SessionManager subagentSessions() {
        SessionManager local = subagentSessions;
        if (local == null) {
            synchronized (this) {
                local = subagentSessions;
                if (local == null) {
                    local = new SessionManager(
                            Paths.get(workspace, "sessions", "subagent").toString());
                    subagentSessions = local;
                }
            }
        }
        return local;
    }

    /**
     * 表示一个子代理任务
     */
    public static class SubagentTask {
        private String id;
        private String task;
        private String label;
        private String agentName;
        private String originChannel;
        private String originChatId;
        private String status;
        private String result;
        private long created;

        public SubagentTask() {
        }

        // Getter 和 Setter 方法
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTask() {
            return task;
        }

        public void setTask(String task) {
            this.task = task;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getOriginChannel() {
            return originChannel;
        }

        public void setOriginChannel(String originChannel) {
            this.originChannel = originChannel;
        }

        public String getOriginChatId() {
            return originChatId;
        }

        public void setOriginChatId(String originChatId) {
            this.originChatId = originChatId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public long getCreated() {
            return created;
        }

        public void setCreated(long created) {
            this.created = created;
        }
    }

    public SubagentManager(LLMProvider provider, String workspace, MessageBus bus,
                           ToolRegistry tools, String model, int maxIterations) {
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.tools = tools;
        this.model = model;
        this.maxIterations = maxIterations > 0 ? maxIterations : DEFAULT_MAX_ITERATIONS;
        // 使用有界线程池管理子代理任务，空闲线程 60 秒后回收
        this.executor = new ThreadPoolExecutor(
                2, MAX_CONCURRENT_SUBAGENTS,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(SUBAGENT_QUEUE_CAPACITY),
                r -> {
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
     * 注入动态子代理定义加载器，启用 "按 AGENT.md 定义派生专职子代理" 能力。
     */
    public void setAgentsLoader(SubagentsLoader agentsLoader) {
        this.agentsLoader = agentsLoader;
    }

    /**
     * 获取动态子代理定义加载器（可能为 null）。
     */
    public SubagentsLoader getAgentsLoader() {
        return agentsLoader;
    }

    /**
     * 解析专职子代理定义。agentName 为空或未注入 loader 时返回 null（使用通用子代理）。
     */
    private SubagentDefinition resolveDefinition(String agentName) {
        SubagentsLoader loader = this.agentsLoader;
        if (loader == null || agentName == null || agentName.isEmpty()) {
            return null;
        }
        SubagentDefinition def = loader.load(agentName);
        if (def == null) {
            logger.warn("指定的子代理定义不存在，回退到通用子代理", Map.of("agent", agentName));
        }
        return def;
    }

    /**
     * 为子代理构建 ReActExecutor：按定义覆盖模型、工具白名单和最大迭代次数，
     * 未定义的部分继承主 Agent 配置。
     *
     * <p>两条子代理执行路径（流式与同步）都经由此处取工具集，是收口 {@link #DENIED_TOOLS}
     * 的唯一位置。</p>
     */
    private ReActExecutor buildExecutor(SubagentDefinition def, SessionManager subagentSessions) {
        String effectiveModel = def != null && def.getModel() != null ? def.getModel() : model;
        int effectiveMaxIterations = def != null && def.getMaxIterations() > 0
                ? def.getMaxIterations() : maxIterations;
        ToolRegistry effectiveTools = tools;
        if (def != null && def.getTools() != null && !def.getTools().isEmpty()) {
            // 按定义的白名单收窄工具权限
            effectiveTools = tools.filter(def.getTools());
        }
        // 协同只能由主 Agent 发起、子代理不得再派生：白名单里显式写了也不放行
        effectiveTools = effectiveTools.exclude(DENIED_TOOLS);
        return new ReActExecutor(provider, effectiveTools, subagentSessions,
                effectiveModel, provider.getName(), effectiveMaxIterations);
    }

    /**
     * 构建子代理的初始消息列表：专职定义的正文作为 system prompt，否则使用默认提示词。
     */
    private List<Message> buildMessages(SubagentDefinition def, String taskContent) {
        String systemPrompt = def != null && def.getSystemPrompt() != null && !def.getSystemPrompt().isEmpty()
                ? def.getSystemPrompt() : DEFAULT_SYSTEM_PROMPT;
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", taskContent));
        return messages;
    }

    /**
     * 同步生成子代理并等待执行完成，返回子代理的实际执行结果。
     * 这是 "subagent as tool" 的核心方法：主 Agent 阻塞等待子 Agent 完成，
     * 结果作为 tool_result 直接返回给主 Agent 的推理循环。
     */
    public String spawnAndWait(String task, String label) {
        return spawnAndWaitStream(task, label, null, null);
    }

    /**
     * 同步生成子代理并等待执行完成（流式版本）。
     * 支持通过回调输出子代理的执行过程信息。
     *
     * @param task     子代理任务描述
     * @param label    任务标签（可选）
     * @param callback 流式回调，用于输出子代理的执行过程（可为 null）
     * @return 子代理的执行结果
     */
    public String spawnAndWaitStream(String task, String label, LLMProvider.EnhancedStreamCallback callback) {
        return spawnAndWaitStream(task, label, null, callback);
    }

    /**
     * 同步生成子代理并等待执行完成（流式 + 专职子代理版本）。
     *
     * @param task      子代理任务描述
     * @param label     任务标签（可选）
     * @param agentName 专职子代理名称（可选，对应 workspace/agents/<name>/AGENT.md）
     * @param callback  流式回调，用于输出子代理的执行过程（可为 null）
     * @return 子代理的执行结果
     */
    public String spawnAndWaitStream(String task, String label, String agentName,
                                     LLMProvider.EnhancedStreamCallback callback) {
        maybeCleanupOldTasks();

        String taskId = "subagent-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextId.getAndIncrement();

        SubagentTask subagentTask = new SubagentTask();
        subagentTask.setId(taskId);
        subagentTask.setTask(task);
        subagentTask.setLabel(label != null ? label : "");
        subagentTask.setAgentName(agentName);
        subagentTask.setOriginChannel("internal");
        subagentTask.setOriginChatId("sync");
        subagentTask.setStatus("running");
        subagentTask.setCreated(System.currentTimeMillis());

        tasks.put(taskId, subagentTask);

        logger.info("Spawned sync subagent", Map.of(
                "task_id", taskId,
                "label", label != null ? label : "",
                "agent", agentName != null ? agentName : "generic",
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
     * @param task     子代理任务
     * @param callback 流式回调（可为 null）
     */
    private void runTaskSyncWithStream(SubagentTask task, LLMProvider.EnhancedStreamCallback callback) {
        SubagentDefinition def = resolveDefinition(task.getAgentName());
        List<Message> messages = buildMessages(def, task.getTask());

        SessionManager subagentSessions = subagentSessions();
        String sessionKey = "subagent:" + task.getId();
        // 任务提示词入库：ReActExecutor 只存它自己产生的消息，
        // 不补这一句子代理转录会以 assistant 消息开头，没有任务描述，无法回放
        subagentSessions.recordPromptMessages(sessionKey, messages);

        try {
            ReActExecutor reActExecutor = buildExecutor(def, subagentSessions);

            String result;

            if (callback != null) {
                // 使用流式执行：子代理的事件保持结构化转发。
                // 思考内容以 SUBAGENT_THINKING 透出、工具调用带 taskId 归属原样透出；
                // 若降级为普通 chunk 回调，ReActExecutor 内部的 wrap() 会把这些事件 format()
                // 成文本，导致思维链与工具调用逐段碎片化混入子代理正文。
                // 思考与正文按「语义族」归并：子代理内部再嵌一层执行体（协同角色/更深的子代理）
                // 时，它的思考会以 COLLABORATE_AGENT_THINKING / SUBAGENT_THINKING 到达，
                // 只认 THINKING 就会让这些内容落进兜底分支被 format() 成 💭 文本行混进正文
                String taskId = task.getId();
                LLMProvider.EnhancedStreamCallback subagentCallback = event -> {
                    switch (event.getType()) {
                        case CONTENT, SUBAGENT_CONTENT, COLLABORATE_AGENT, COLLABORATE_AGENT_CHUNK ->
                                callback.onEvent(StreamEvent.subagentContent(taskId, event.getContent()));
                        case THINKING, SUBAGENT_THINKING, COLLABORATE_AGENT_THINKING ->
                                callback.onEvent(StreamEvent.subagentThinking(taskId, event.getContent()));
                        // 工具调用保持结构化，只标注归属任务，前端在子代理卡片内渲染工具卡片
                        case TOOL_START, TOOL_END -> callback.onEvent(event.withScope("taskId", taskId));
                        // 剩下的都是起止标记，以可读文本行混入子代理输出
                        default -> callback.onEvent(StreamEvent.subagentContent(taskId, event.format()));
                    }
                };
                result = reActExecutor.executeStream(messages, sessionKey, subagentCallback);
            } else {
                result = reActExecutor.execute(messages, sessionKey);
            }

            task.setStatus("completed");
            task.setResult(result != null ? result : "任务已完成但无返回内容");
            subagentSessions.recordReply(sessionKey, task.getResult());

            logger.info("Sync subagent task completed", Map.of(
                    "task_id", task.getId(),
                    "result_length", task.getResult().length()
            ));
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("子代理执行失败: " + e.getMessage());
            logTaskFailure("Sync subagent task failed", task, e);
        }
    }

    /**
     * 异步生成一个新的子代理任务（fire-and-forget 模式）。
     * 子代理在后台线程中运行，完成后通过 MessageBus 通知主 Agent。
     */
    public String spawn(String task, String label, String originChannel, String originChatId) {
        return spawn(task, label, null, originChannel, originChatId);
    }

    /**
     * 异步生成一个新的子代理任务（fire-and-forget + 专职子代理版本）。
     *
     * @param agentName 专职子代理名称（可选）
     */
    public String spawn(String task, String label, String agentName, String originChannel, String originChatId) {
        // 定期清理过期任务
        maybeCleanupOldTasks();

        String taskId = "subagent-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextId.getAndIncrement();

        SubagentTask subagentTask = new SubagentTask();
        subagentTask.setId(taskId);
        subagentTask.setTask(task);
        subagentTask.setLabel(label != null ? label : "");
        subagentTask.setAgentName(agentName);
        subagentTask.setOriginChannel(originChannel != null ? originChannel : "cli");
        subagentTask.setOriginChatId(originChatId != null ? originChatId : "direct");
        subagentTask.setStatus("running");
        subagentTask.setCreated(System.currentTimeMillis());

        tasks.put(taskId, subagentTask);

        // 在线程池中运行任务；并发与队列均满时拒绝并反馈调用方
        try {
            executor.submit(() -> runTask(subagentTask));
        } catch (RejectedExecutionException e) {
            subagentTask.setStatus("failed");
            subagentTask.setResult("子代理并发已达上限，请稍后重试");
            logger.warn("Subagent spawn rejected", Map.of("task_id", taskId));
            return "子代理并发已达上限（" + MAX_CONCURRENT_SUBAGENTS + "），请稍后重试。";
        }

        logger.info("Spawned subagent", Map.of(
                "task_id", taskId,
                "label", label,
                "agent", agentName != null ? agentName : "generic",
                "task_preview", task.length() > 50 ? task.substring(0, 50) + "..." : task
        ));

        String agentSuffix = agentName != null && !agentName.isEmpty() ? "（专职子代理: " + agentName + "）" : "";
        if (label != null && !label.isEmpty()) {
            return "已生成子代理 '" + label + "'" + agentSuffix + " 处理任务: " + task;
        }
        return "已生成子代理" + agentSuffix + "处理任务: " + task;
    }

    private void runTask(SubagentTask task) {
        task.setStatus("running");
        task.setCreated(System.currentTimeMillis());

        // 为子代理构建消息（专职定义的正文作为 system prompt）
        SubagentDefinition def = resolveDefinition(task.getAgentName());
        List<Message> messages = buildMessages(def, task.getTask());

        // 子代理共享同一个会话管理器，各任务以 sessionKey 隔离
        SessionManager subagentSessions = subagentSessions();
        String sessionKey = "subagent:" + task.getId();
        subagentSessions.recordPromptMessages(sessionKey, messages);

        try {
            // 使用 ReActExecutor 实现完整的工具调用和循环能力
            ReActExecutor executor = buildExecutor(def, subagentSessions);
            String result = executor.execute(messages, sessionKey);

            task.setStatus("completed");
            task.setResult(result != null ? result : "任务已完成但无返回内容");
            subagentSessions.recordReply(sessionKey, task.getResult());

            logger.info("Subagent task completed", Map.of(
                    "task_id", task.getId(),
                    "result_length", task.getResult().length()
            ));
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("错误: " + e.getMessage());
            logTaskFailure("Subagent task failed", task, e);
        } finally {
            // 发送通知消息回主 Agent
            sendTaskCompletion(task);
        }
    }

    /**
     * 记录子代理任务失败的详细日志，包含标签、来源、异常类型、根因及完整堆栈。
     */
    private void logTaskFailure(String message, SubagentTask task, Exception e) {
        String taskText = task.getTask();
        String taskPreview = taskText != null && taskText.length() > 100
                ? taskText.substring(0, 100) + "..."
                : taskText;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("task_id", task.getId());
        fields.put("label", task.getLabel());
        fields.put("origin", task.getOriginChannel() + ":" + task.getOriginChatId());
        fields.put("error", e.getMessage());
        fields.put("error_type", e.getClass().getName());
        fields.put("root_cause", rootCauseMessage(e));
        fields.put("task_preview", taskPreview);
        // 传入异常对象以输出完整调用堆栈
        logger.error(message, fields, e);
    }

    /**
     * 提取异常链最底层的根因信息（类名 + 消息）。
     */
    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName() + ": " + cause.getMessage();
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
