package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.collaboration.AgentOrchestrator;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;

import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.config.Config;

import io.leavesfly.tinyclaw.evolution.*;
import io.leavesfly.tinyclaw.evolution.reflection.*;
import io.leavesfly.tinyclaw.hooks.HookConfigLoader;
import io.leavesfly.tinyclaw.hooks.HookContext;
import io.leavesfly.tinyclaw.hooks.HookDispatcher;
import io.leavesfly.tinyclaw.hooks.HookEvent;
import io.leavesfly.tinyclaw.hooks.HookRegistry;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPManager;

import io.leavesfly.tinyclaw.memory.MemoryEvolver;
import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.Tool;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TinyClaw 核心执行引擎，协调消息路由、上下文构建、会话管理与 LLM 交互。
 *
 * <p>将 LLM 调用委托给 {@link ReActExecutor}，会话摘要委托给 {@link SessionSummarizer}，
 * 消息路由委托给 {@link MessageRouter}，自身聚焦于生命周期管理与外部接口。</p>
 */
public class AgentRuntime {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent");
    private static final String PROVIDER_NOT_CONFIGURED_MSG =
            "⚠️ LLM Provider 未配置，请通过 Web Console 的 Settings -> Models 页面配置 API Key 后再试。";
    private static final String DEFAULT_EMPTY_RESPONSE = "已完成处理但没有回复内容。";
    private static final int LOG_PREVIEW_LENGTH = 80;

    /* ---------- 不可变依赖 ---------- */
    private final MessageBus bus;
    private final String workspace;
    private final SessionManager sessions;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry tools;
    private final Config config;
    private final ProviderManager providerManager;
    private final MessageRouter messageRouter;

    /* ---------- 可热更新组件（volatile 保证线程可见性） ---------- */
    private volatile MCPManager mcpManager;

    /**
     * Provider 切换时一次性替换的组件集合，通过 {@link ProviderComponents} 聚合，
     * 避免多个 volatile 字段在并发场景下出现部分更新的中间状态。
     */
    private volatile ProviderComponents components;

    /* ---------- 通道管理器（可选，由 GatewayBootstrap 注入） ---------- */
    private volatile ChannelManager channelManager;

    /**
     * Hook 调度器。构造时从 {@code ~/.tinyclaw/hooks.json} 加载；文件不存在或加载失败时
     * 退回为 {@link HookDispatcher#noop()}，保证主流程零开销。
     */
    private final HookDispatcher hookDispatcher;

    private volatile boolean running = false;

    // ==================== 构造与初始化 ====================

    public AgentRuntime(Config config, MessageBus bus, LLMProvider provider) {
        this.bus = bus;
        this.config = config;
        this.workspace = config.getWorkspacePath();

        ensureDirectoryExists(workspace);

        this.tools = new ToolRegistry();
        this.sessions = new SessionManager(Paths.get(workspace, "sessions").toString());
        this.contextBuilder = new ContextBuilder(workspace);
        this.contextBuilder.setTools(this.tools);

        // 初始化 Hook 调度器：从默认路径加载，必须在 providerManager.setProvider 之前，
        // 以便 ReActExecutor 首次构造时就能拿到 dispatcher。
        HookRegistry hookRegistry = HookConfigLoader.loadDefault();
        this.hookDispatcher = new HookDispatcher(hookRegistry);

        this.providerManager = new ProviderManager(config, contextBuilder, tools, sessions, workspace);
        this.providerManager.setHookDispatcher(this.hookDispatcher);
        this.messageRouter = new MessageRouter(providerManager, bus, sessions, contextBuilder, config);
        this.messageRouter.setHookDispatcher(this.hookDispatcher);

        if (provider != null) {
            providerManager.setProvider(provider);
            logger.info("Agent initialized with provider", Map.of(
                    "model", config.getAgent().getModel(),
                    "workspace", workspace,
                    "max_iterations", config.getAgent().getMaxToolIterations(),
                    "hooks_enabled", !hookRegistry.isEmpty()));
        } else {
            logger.info("Agent initialized without provider (configuration mode)", Map.of(
                    "workspace", workspace,
                    "hooks_enabled", !hookRegistry.isEmpty()));
        }

        initializeMCPServers();
    }

    // ==================== Provider 管理 ====================

    /** 动态设置或替换 LLM Provider，线程安全。 */
    public void setProvider(LLMProvider provider) {
        providerManager.setProvider(provider);
    }

    /**
     * 根据当前 config 中的 provider/model 配置热重载 LLM Provider，无需重启即可生效。
     *
     * <p>优先从 ModelsConfig 中通过 model 名称反查对应的 provider，保证 api_base 与 model
     * 始终来自同一个绑定关系，避免 AgentConfig.provider 与 model 手动错配的问题。
     * 若 model 未在 ModelsConfig 中定义，则 fallback 到 AgentConfig.provider。</p>
     *
     * @return true 表示重载成功，false 表示 provider 未配置或无效
     */
    public boolean reloadModel() {
        return providerManager.reloadModel();
    }

    public boolean isProviderConfigured() {
        return providerManager.isConfigured();
    }

    public LLMProvider getProvider() {
        return providerManager.getProvider();
    }

    /**
     * 注入通道管理器，供 AgentRuntime 在处理消息时查询通道能力（如流式输出支持）。
     * 由 GatewayBootstrap 在初始化完成后调用。
     */
    public void setChannelManager(ChannelManager channelManager) {
        this.channelManager = channelManager;
        this.messageRouter.setChannelManager(channelManager);
    }

    // ==================== 生命周期 ====================

    /** 阻塞式运行 Agent 主循环，持续消费消息总线直到 {@link #stop()} 被调用。 */
    public void run() {
        running = true;
        logger.info("Agent loop started");

        while (running) {
            try {
                InboundMessage message = bus.consumeInbound();
                if (message == null) {
                    continue; // MessageBus 已关闭，poll 返回 null
                }
                messageRouter.route(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.error("Error processing message", Map.of(
                        "error", errorMsg,
                        "exception_type", e.getClass().getSimpleName()));
            }
        }

        logger.info("Agent loop stopped");
    }

    public void stop() {
        running = false;
        shutdownMCPServers();

        // 关闭 Reflection 2.0 Recorder（释放后台线程和队列）
        ProviderComponents comps = providerManager.getComponents();
        if (comps != null && comps.recorder != null) {
            try {
                comps.recorder.close();
            } catch (Exception e) {
                logger.error("Failed to close ToolCallRecorder", Map.of("error", e.getMessage()));
            }
        }

        // SessionEnd：通知外部 hook 一次性收尾。hook 返回的决策在此切点不改变行为，仅作通知。
        try {
            hookDispatcher.fire(HookEvent.SESSION_END,
                    HookContext.builder(HookEvent.SESSION_END).build());
        } catch (RuntimeException e) {
            logger.error("SessionEnd hook threw exception, ignored", Map.of("error", e.getMessage()));
        }
    }

    /** 获取当前 Hook 调度器，供测试或外部组件按需触发自定义事件。 */
    public HookDispatcher getHookDispatcher() {
        return hookDispatcher;
    }

    // ==================== 工具注册 ====================

    public void registerTool(Tool tool) {
        tools.register(tool);
        contextBuilder.setTools(tools);
    }
    
    /** 获取工具注册表，供外部组件（如 SubagentManager）使用 */
    public ToolRegistry getToolRegistry() {
        return tools;
    }
    
    /** 获取技能加载器实例，供外部组件（如 SkillsTool）共享以保持技能列表一致性 */
    public SkillsLoader getSkillsLoader() {
        return contextBuilder.getSkillsLoader();
    }

    /** 获取记忆存储实例，供外部组件（如工具层）访问记忆读写能力 */
    public MemoryStore getMemoryStore() {
        return contextBuilder.getMemoryStore();
    }

    /** 获取会话管理器，供外部组件（如 WebConsoleServer）共享同一实例，避免内存状态不一致 */
    public SessionManager getSessionManager() {
        return sessions;
    }

    /** 获取记忆进化引擎，供外部组件（如心跳服务）触发记忆进化 */
    public MemoryEvolver getMemoryEvolver() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.memoryEvolver : null;
    }

    /** 获取 Token 消耗存储，供外部组件（如 TokenUsageTool）使用 */
    public TokenUsageStore getTokenUsageStore() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.tokenUsageStore : null;
    }

    /** 获取协同编排器，供外部组件（如 CollaborateTool）使用 */
    public AgentOrchestrator getOrchestrator() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.orchestrator : null;
    }

    public FeedbackManager getFeedbackManager() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.feedbackManager : null;
    }

    /** 获取 Prompt 优化器，供外部组件触发优化 */
    public PromptOptimizer getPromptOptimizer() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.promptOptimizer : null;
    }

    /** 获取 Reflection 2.0 引擎，供外部组件（如 Web Handler）使用 */
    public ReflectionEngine getReflectionEngine() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.reflectionEngine : null;
    }

    /** 获取 Reflection 2.0 健康聚合器，供外部组件使用 */
    public ToolHealthAggregator getToolHealthAggregator() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.healthAggregator : null;
    }

    /** 获取 Reflection 2.0 修复应用器，供外部组件使用 */
    public RepairApplier getRepairApplier() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null ? comps.repairApplier : null;
    }
    
    /**
     * 执行进化周期（供心跳服务调用）。
     *
     * <p>包含：基于反馈的记忆进化、常规记忆进化、Prompt 优化、会话清理。
     * 各步骤独立容错，单步失败不影响后续步骤执行。</p>
     */
    public void runEvolutionCycle() {
        ProviderComponents comps = providerManager.getComponents();
        if (comps == null) {
            return;
        }

        FeedbackManager feedbackManager = comps.feedbackManager;
        MemoryEvolver memoryEvolver = comps.memoryEvolver;
        PromptOptimizer promptOptimizer = comps.promptOptimizer;

        // 1. 基于反馈的智能记忆进化
        if (feedbackManager != null && memoryEvolver != null) {
            safeRun("feedback-based memory evolution", () -> {
                List<EvaluationFeedback> recentFeedbacks = feedbackManager.getRecentAggregatedFeedbacks(1);
                for (EvaluationFeedback feedback : recentFeedbacks) {
                    memoryEvolver.evolveWithFeedback(feedback);
                }
                if (!recentFeedbacks.isEmpty()) {
                    logger.debug("Processed feedback-based memory evolution",
                            Map.of("feedback_count", recentFeedbacks.size()));
                }
            });
        }

        // 2. 常规记忆进化
        if (memoryEvolver != null) {
            safeRun("memory evolution", memoryEvolver::evolve);
        }

        // 3. Prompt 优化（如果启用）
        if (promptOptimizer != null && config.getAgent().isPromptOptimizationEnabled()) {
            safeRun("prompt optimization", () -> {
                PromptOptimizer activeOptimizer = contextBuilder.getPromptOptimizer();
                String currentPrompt = activeOptimizer != null
                        ? activeOptimizer.getActiveOptimization()
                        : "";
                String safePrompt = currentPrompt != null ? currentPrompt : "";

                // 如果是 Self-Refine 策略，收集最近的会话历史作为反思素材
                List<String> recentSessionLog = null;
                EvolutionConfig evolutionConfig = config.getAgent().getEvolution();
                if (evolutionConfig != null
                        && evolutionConfig.getOptimizationStrategy()
                            == EvolutionConfig.OptimizationStrategy.SELF_REFINE) {
                    recentSessionLog = collectRecentSessionLogs(
                            evolutionConfig.getSelfRefineSessionCount());
                }

                promptOptimizer.maybeOptimize(safePrompt, recentSessionLog);
            });
        }

        // 4. 清理已结束会话的跟踪数据
        if (feedbackManager != null) {
            feedbackManager.cleanupEndedSessions();
        }

        // 5. Reflection 2.0：工具级自我调试周期
        if (comps.reflectionEngine != null && comps.failureDetector != null) {
            safeRun("reflection 2.0 cycle", () -> {
                // 消费失败检测结果
                List<FailureDetector.DetectionResult> detections = comps.failureDetector.drainDetections();
                if (!detections.isEmpty()) {
                    logger.info("Reflection 2.0: processing detections",
                            Map.of("count", detections.size()));
                    // 触发反思引擎
                    List<RepairProposal> newProposals = comps.reflectionEngine.reflect(detections);
                    // 自动应用已审批的提案
                    if (comps.repairApplier != null) {
                        for (RepairProposal proposal : newProposals) {
                            if (proposal.getStatus() == RepairProposal.Status.APPROVED) {
                                comps.repairApplier.apply(proposal);
                            }
                        }
                    }
                }
            });

            // 清理过期事件日志
            if (comps.logStore != null) {
                safeRun("reflection event cleanup", () -> {
                    EvolutionConfig evolutionConfig = config.getAgent().getEvolution();
                    if (evolutionConfig != null) {
                        ReflectionConfig reflectionConfig = evolutionConfig.getReflection();
                        if (reflectionConfig != null) {
                            comps.logStore.cleanup(reflectionConfig.getEventRetentionDays());
                        }
                    }
                });
            }
        }
    }

    /**
     * 安全执行一个可能抛出异常的任务，失败时记录错误日志但不中断调用方。
     *
     * @param taskName 任务名称，用于错误日志定位
     * @param task     要执行的任务
     */
    private void safeRun(String taskName, ThrowingRunnable task) {
        try {
            task.run();
        } catch (Exception e) {
            logger.error(taskName + " failed", Map.of("error", e.getMessage()));
        }
    }

    /** 可抛出受检异常的 Runnable，供 {@link #safeRun} 使用。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * 收集最近的会话交互记录，供 Self-Refine 策略进行自我反思。
     *
     * <p>从 SessionManager 中获取最近的 N 个会话，将每个会话的消息历史
     * 格式化为 "role: content" 文本，作为 Self-Refine 的反思素材。</p>
     *
     * @param maxSessionCount 最多收集的会话数量
     * @return 格式化的会话记录列表，每个元素对应一个会话
     */
    private List<String> collectRecentSessionLogs(int maxSessionCount) {
        List<String> sessionLogs = new ArrayList<>();
        Set<String> sessionKeys = sessions.getSessionKeys();

        if (sessionKeys.isEmpty()) {
            return sessionLogs;
        }

        // 取最近的 N 个会话（SessionManager 的 key 集合按插入顺序排列）
        List<String> keyList = new ArrayList<>(sessionKeys);
        int startIndex = Math.max(0, keyList.size() - maxSessionCount);
        List<String> recentKeys = keyList.subList(startIndex, keyList.size());

        for (String sessionKey : recentKeys) {
            List<Message> history = sessions.getHistory(sessionKey);
            if (history == null || history.isEmpty()) {
                continue;
            }

            StringBuilder sessionText = new StringBuilder();
            for (Message message : history) {
                String role = message.getRole() != null ? message.getRole() : "unknown";
                String content = message.getContent() != null ? message.getContent() : "";
                // 截断过长的单条消息，避免上下文爆炸
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                sessionText.append(role).append(": ").append(content).append("\n");
            }

            if (sessionText.length() > 0) {
                sessionLogs.add(sessionText.toString());
            }
        }

        return sessionLogs;
    }

    // ==================== 公开入口（CLI / 外部调用） ====================

    /** 同步处理单条消息，适用于 CLI 交互模式。 */
    public String processDirect(String content, String sessionKey) throws Exception {
        InboundMessage message = new InboundMessage("cli", "user", "direct", content);
        message.setSessionKey(sessionKey);
        return messageRouter.route(message);
    }

    /** 流式处理单条消息，通过回调逐块输出，适用于 CLI 流式模式。 */
    public String processDirectStream(String content, String sessionKey,
                                      LLMProvider.StreamCallback callback) throws Exception {
        return processDirectStream(content, null, sessionKey, callback);
    }
    
    /** 流式处理单条消息，支持多模态内容（文本+图片）。 */
    public String processDirectStream(String content, List<String> images, String sessionKey,
                                      LLMProvider.StreamCallback callback) throws Exception {
        if (!providerManager.isConfigured()) {
            notifyCallback(callback, PROVIDER_NOT_CONFIGURED_MSG);
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        logIncoming("cli", sessionKey, content);

        // 将相对路径转换为绝对路径，确保 HTTPProvider 能读取到图片文件
        List<String> absoluteImagePaths = resolveImagePaths(images);

        InboundMessage message = new InboundMessage("cli", "user", "direct", content);
        message.setMedia(absoluteImagePaths);  // 设置图片列表
        List<Message> messages = messageRouter.buildContextWithImages(sessionKey, message, absoluteImagePaths);
        
        // 保存用户消息（含图片，存储相对路径供前端显示）
        sessions.addFullMessage(sessionKey, Message.user(content, images));
        sessions.save(sessions.getOrCreate(sessionKey)); // 在 LLM 调用前先持久化用户消息，防止异常时丢失

        ProviderComponents comps = providerManager.getComponents();
        String response = ensureNonBlank(
                comps.reActExecutor.executeStream(messages, sessionKey, callback), DEFAULT_EMPTY_RESPONSE);

        messageRouter.persistAndSummarize(sessionKey, response);
        return response;
    }

    /** 处理带通道信息的消息，适用于定时任务等场景。 */
    public String processDirectWithChannel(String content, String sessionKey,
                                           String channel, String chatId) throws Exception {
        InboundMessage message = new InboundMessage(channel, "cron", chatId, content);
        message.setSessionKey(sessionKey);
        return messageRouter.route(message);
    }

    /**
     * 中断当前正在执行的 LLM 任务。
     * 设置 ReActExecutor 的中断标志位，使其在下一次迭代时提前退出。
     *
     * @return 是否成功发送中断信号（true 表示有活跃的执行器可以中断）
     */
    public boolean abortCurrentTask() {
        ProviderComponents comps = providerManager.getComponents();
        if (comps != null && comps.reActExecutor != null) {
            comps.reActExecutor.abort();
            logger.info("Abort signal sent to ReActExecutor");
            return true;
        }
        return false;
    }

    /**
     * 查询当前是否有 LLM 任务正在运行。
     *
     * @return 如果有任务正在运行返回 true
     */
    public boolean isTaskRunning() {
        ProviderComponents comps = providerManager.getComponents();
        return comps != null && comps.reActExecutor != null && comps.reActExecutor.isRunning();
    }

    // ==================== 消息分发 ====================
    // 已迁移到 MessageRouter

    // ==================== 启动信息 ====================

    public Map<String, Object> getStartupInfo() {
        return Map.of(
                "tools", Map.of("count", tools.count(), "names", tools.list()),
                "skills", contextBuilder.getSkillsInfo());
    }

    // ==================== MCP 服务器管理 ====================

    private void initializeMCPServers() {
        if (config.getMcpServers() == null || !config.getMcpServers().isEnabled()) {
            return;
        }
        try {
            mcpManager = new MCPManager(config.getMcpServers(), tools);
            mcpManager.initialize();
            int connectedCount = mcpManager.getConnectedCount();
            if (connectedCount > 0) {
                logger.info("MCP servers initialized", Map.of("connected", connectedCount));
            }
        } catch (Exception e) {
            logger.error("Failed to initialize MCP servers", Map.of("error", e.getMessage()));
        }
    }

    private void shutdownMCPServers() {
        if (mcpManager == null) {
            return;
        }
        try {
            mcpManager.shutdown();
        } catch (Exception e) {
            logger.error("Failed to shutdown MCP servers", Map.of("error", e.getMessage()));
        }
    }

    // ==================== 通用工具方法 ====================

    /**
     * 将图片路径列表中的相对路径转换为绝对路径。
     *
     * 上传的图片存储为相对路径（如 "uploads/xxx.jpg"），
     * HTTPProvider 需要绝对路径才能读取文件并转换为 Base64。
     *
     * @param images 原始图片路径列表（可能包含相对路径或已是绝对路径）
     * @return 转换后的绝对路径列表，null 输入返回 null
     */
    private List<String> resolveImagePaths(List<String> images) {
        if (images == null || images.isEmpty()) {
            return images;
        }
        return images.stream()
                .map(path -> {
                    if (path == null || path.startsWith("/") || path.startsWith("data:")) {
                        return path;
                    }
                    return Paths.get(workspace, path).toAbsolutePath().toString();
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private static String ensureNonBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private static void ensureDirectoryExists(String path) {
        try {
            Files.createDirectories(Paths.get(path));
        } catch (IOException e) {
            logger.warn("Failed to create directory: " + path + " - " + e.getMessage());
        }
    }

    private static void notifyCallback(LLMProvider.StreamCallback callback, String message) {
        if (callback != null) {
            callback.onChunk(message);
        }
    }

    private void logIncoming(InboundMessage msg) {
        logIncoming(msg.getChannel(), msg.getSessionKey(), msg.getContent(),
                msg.getChatId(), msg.getSenderId());
    }

    private void logIncoming(String channel, String sessionKey, String content) {
        logIncoming(channel, sessionKey, content, null, null);
    }

    private void logIncoming(String channel, String sessionKey, String content,
                             String chatId, String senderId) {
        // 用 Map.of 无法处理可选字段（不允许 null 值），改用 HashMap 构建可选字段
        Map<String, Object> fields = new HashMap<>();
        fields.put("channel", channel);
        fields.put("session_key", sessionKey);
        fields.put("preview", StringUtils.truncate(content, LOG_PREVIEW_LENGTH));
        if (chatId != null) {
            fields.put("chat_id", chatId);
        }
        if (senderId != null) {
            fields.put("sender_id", senderId);
        }
        logger.info("Processing message", fields);
    }
}
