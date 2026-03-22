package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.collaboration.AgentOrchestrator;
import io.leavesfly.tinyclaw.agent.evolution.*;
import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.channels.Channel;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ModelsConfig;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPManager;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TinyClaw 核心执行引擎，协调消息路由、上下文构建、会话管理与 LLM 交互。
 *
 * <p>将 LLM 调用委托给 {@link LLMExecutor}，会话摘要委托给 {@link SessionSummarizer}，
 * 自身聚焦于消息分发与生命周期管理。</p>
 */
public class AgentLoop {

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

    /* ---------- 可热更新组件（volatile 保证线程可见性） ---------- */
    private volatile LLMProvider provider;
    private volatile MCPManager mcpManager;

    /**
     * Provider 切换时一次性替换的组件集合，通过 {@link ProviderComponents} 聚合，
     * 避免多个 volatile 字段在并发场景下出现部分更新的中间状态。
     */
    private volatile ProviderComponents components;

    /* ---------- 通道管理器（可选，由 GatewayBootstrap 注入） ---------- */
    private volatile ChannelManager channelManager;

    private volatile boolean running = false;
    private volatile boolean providerConfigured = false;

    private final Object providerLock = new Object();

    // ==================== 构造与初始化 ====================

    public AgentLoop(Config config, MessageBus bus, LLMProvider provider) {
        this.bus = bus;
        this.config = config;
        this.workspace = config.getWorkspacePath();

        ensureDirectoryExists(workspace);

        this.tools = new ToolRegistry();
        this.sessions = new SessionManager(Paths.get(workspace, "sessions").toString());
        this.contextBuilder = new ContextBuilder(workspace);
        this.contextBuilder.setTools(this.tools);

        if (provider != null) {
            applyProvider(provider);
            logger.info("Agent initialized with provider", Map.of(
                    "model", config.getAgent().getModel(),
                    "workspace", workspace,
                    "max_iterations", config.getAgent().getMaxToolIterations()));
        } else {
            logger.info("Agent initialized without provider (configuration mode)", Map.of(
                    "workspace", workspace));
        }

        initializeMCPServers();
    }

    // ==================== Provider 管理 ====================

    /** 动态设置或替换 LLM Provider，线程安全。 */
    public void setProvider(LLMProvider provider) {
        if (provider == null) {
            return;
        }
        synchronized (providerLock) {
            applyProvider(provider);
        }
        logger.info("Provider configured dynamically", Map.of(
                "model", config.getAgent().getModel()));
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
        String modelName = config.getAgent().getModel();
        String providerName = resolveProviderName(modelName);

        if (providerName == null || providerName.isEmpty()) {
            logger.warn("reloadModel skipped: provider name could not be resolved",
                    Map.of("model", modelName));
            return false;
        }

        ProvidersConfig.ProviderConfig providerConfig = config.getProviders().getByName(providerName);
        if (providerConfig == null || !providerConfig.isValid()) {
            logger.warn("reloadModel skipped: provider not configured or invalid",
                    Map.of("provider", providerName, "model", modelName));
            return false;
        }

        String apiBase = providerConfig.getApiBaseOrDefault(ProvidersConfig.getDefaultApiBase(providerName));
        setProvider(new HTTPProvider(providerConfig.getApiKey(), apiBase));

        logger.info("Model reloaded successfully", Map.of("provider", providerName, "model", modelName));
        return true;
    }

    /**
     * 从 ModelsConfig 中反查 model 对应的 provider 名称。
     * 若 model 未在 ModelsConfig 中定义，则 fallback 到 AgentConfig.provider。
     */
    private String resolveProviderName(String modelName) {
        ModelsConfig.ModelDefinition modelDef = config.getModels().getDefinitions().get(modelName);
        if (modelDef != null) {
            return modelDef.getProvider();
        }
        String fallback = config.getAgent().getProvider();
        logger.warn("reloadModel: model not found in ModelsConfig, falling back to agent config provider",
                Map.of("model", modelName, "fallback_provider", fallback != null ? fallback : ""));
        return fallback;
    }

    public boolean isProviderConfigured() {
        return providerConfigured;
    }

    public LLMProvider getProvider() {
        return provider;
    }

    /**
     * 注入通道管理器，供 AgentLoop 在处理消息时查询通道能力（如流式输出支持）。
     * 由 GatewayBootstrap 在初始化完成后调用。
     */
    public void setChannelManager(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * 从 ModelsConfig 中解析当前模型的上下文窗口大小。
     *
     * 优先使用 ModelsConfig 中配置的 maxContextSize，
     * 若模型未在配置中定义则 fallback 到 DEFAULT_CONTEXT_WINDOW。
     *
     * @param model 模型名称
     * @return 上下文窗口 token 数
     */
    private int resolveContextWindow(String model) {
        ModelsConfig.ModelDefinition definition = config.getModels().getDefinitions().get(model);
        if (definition != null && definition.getMaxContextSize() != null) {
            return definition.getMaxContextSize();
        }
        logger.warn("Model not found in ModelsConfig, using default context window",
                Map.of("model", model, "default", AgentConstants.DEFAULT_CONTEXT_WINDOW));
        return AgentConstants.DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * 将 provider 及其派生组件一次性赋值，消除构造器与 setProvider 之间的重复逻辑。
     * 调用方需自行保证线程安全（构造器天然安全，setProvider 通过 providerLock 保护）。
     */
    private void applyProvider(LLMProvider newProvider) {
        this.provider = newProvider;

        String model = config.getAgent().getModel();
        int maxIterations = config.getAgent().getMaxToolIterations();
        int contextWindow = resolveContextWindow(model);
        String providerName = resolveProviderName(model);

        // 同步上下文窗口到 ContextBuilder，用于计算记忆 token 预算
        contextBuilder.setContextWindow(contextWindow);

        MemoryStore memoryStore = contextBuilder.getMemoryStore();
        MemoryEvolver memoryEvolver = new MemoryEvolver(memoryStore, newProvider, model);

        TokenUsageStore tokenUsageStore = new TokenUsageStore(workspace);
        LLMExecutor llmExecutor = new LLMExecutor(newProvider, tools, sessions, model, providerName, maxIterations);
        llmExecutor.setTokenUsageStore(tokenUsageStore);

        SessionSummarizer summarizer = new SessionSummarizer(
                sessions, newProvider, model, contextWindow, memoryStore, memoryEvolver);

        this.components = buildOptionalComponents(
                newProvider, model, maxIterations, llmExecutor, summarizer, memoryEvolver, tokenUsageStore);

        this.providerConfigured = true;
    }

    /**
     * 构建完整的 {@link ProviderComponents}，包含核心组件与可选的进化/协同组件。
     *
     * <p>将各可选功能的初始化逻辑收敛在此处，使 {@link #applyProvider} 保持高层编排视角，
     * 不感知各组件的构造细节。</p>
     */
    private ProviderComponents buildOptionalComponents(
            LLMProvider newProvider, String model, int maxIterations,
            LLMExecutor llmExecutor, SessionSummarizer summarizer,
            MemoryEvolver memoryEvolver, TokenUsageStore tokenUsageStore) {

        FeedbackManager feedbackManager = null;
        PromptOptimizer promptOptimizer = null;
        AgentOrchestrator orchestrator = null;

        // 进化组件（反馈收集 + Prompt 优化）
        EvolutionConfig evolutionConfig = config.getAgent().getEvolution();
        if (evolutionConfig != null && evolutionConfig.isAnyEvolutionEnabled()) {
            if (evolutionConfig.isFeedbackEnabled()) {
                feedbackManager = new FeedbackManager(workspace, evolutionConfig);
                llmExecutor.setFeedbackManager(feedbackManager);
                logger.info("Feedback collection enabled");
            }
            if (evolutionConfig.isPromptOptimizationEnabled() && feedbackManager != null) {
                promptOptimizer = new PromptOptimizer(
                        newProvider, model, workspace, feedbackManager, evolutionConfig);
                contextBuilder.setPromptOptimizer(promptOptimizer);
                logger.info("Prompt optimization enabled");
            }
        } else {
            logger.debug("Evolution features disabled");
        }

        // 协同组件（多 Agent 编排）
        if (config.getAgent().isCollaborationEnabled()) {
            orchestrator = new AgentOrchestrator(newProvider, tools, workspace, model, maxIterations);
            logger.info("Collaboration features enabled",
                    Map.of("supportedModes", "debate,team,roleplay,consensus,hierarchy"));
        } else {
            logger.debug("Collaboration features disabled");
        }

        return new ProviderComponents(llmExecutor, summarizer, memoryEvolver, tokenUsageStore,
                feedbackManager, promptOptimizer, orchestrator);
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
                processMessage(message);
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

    /** 获取记忆存储实例，供外部组件（如工具层）调用 writeLongTerm / readToday 等能力 */
    public MemoryStore getMemoryStore() {
        return contextBuilder.getMemoryStore();
    }

    /** 获取会话管理器，供外部组件（如 WebConsoleServer）共享同一实例，避免内存状态不一致 */
    public SessionManager getSessionManager() {
        return sessions;
    }

    /** 获取记忆进化引擎，供外部组件（如心跳服务）触发记忆进化 */
    public MemoryEvolver getMemoryEvolver() {
        return components != null ? components.memoryEvolver : null;
    }

    /** 获取 Token 消耗存储，供外部组件（如 TokenUsageTool）使用 */
    public TokenUsageStore getTokenUsageStore() {
        return components != null ? components.tokenUsageStore : null;
    }

    /** 获取协同编排器，供外部组件（如 CollaborateTool）使用 */
    public AgentOrchestrator getOrchestrator() {
        return components != null ? components.orchestrator : null;
    }

    public FeedbackManager getFeedbackManager() {
        return components != null ? components.feedbackManager : null;
    }

    /** 获取 Prompt 优化器，供外部组件触发优化 */
    public PromptOptimizer getPromptOptimizer() {
        return components != null ? components.promptOptimizer : null;
    }
    
    /**
     * 执行进化周期（供心跳服务调用）。
     *
     * <p>包含：基于反馈的记忆进化、常规记忆进化、Prompt 优化、会话清理。
     * 各步骤独立容错，单步失败不影响后续步骤执行。</p>
     */
    public void runEvolutionCycle() {
        if (components == null) {
            return;
        }

        FeedbackManager feedbackManager = components.feedbackManager;
        MemoryEvolver memoryEvolver = components.memoryEvolver;
        PromptOptimizer promptOptimizer = components.promptOptimizer;

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
                promptOptimizer.maybeOptimize(currentPrompt != null ? currentPrompt : "");
            });
        }

        // 4. 清理已结束会话的跟踪数据
        if (feedbackManager != null) {
            feedbackManager.cleanupEndedSessions();
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

    // ==================== 公开入口（CLI / 外部调用） ====================

    /** 同步处理单条消息，适用于 CLI 交互模式。 */
    public String processDirect(String content, String sessionKey) throws Exception {
        InboundMessage message = new InboundMessage("cli", "user", "direct", content);
        message.setSessionKey(sessionKey);
        return processMessage(message);
    }

    /** 流式处理单条消息，通过回调逐块输出，适用于 CLI 流式模式。 */
    public String processDirectStream(String content, String sessionKey,
                                      LLMProvider.StreamCallback callback) throws Exception {
        return processDirectStream(content, null, sessionKey, callback);
    }
    
    /** 流式处理单条消息，支持多模态内容（文本+图片）。 */
    public String processDirectStream(String content, List<String> images, String sessionKey,
                                      LLMProvider.StreamCallback callback) throws Exception {
        if (!providerConfigured) {
            notifyCallback(callback, PROVIDER_NOT_CONFIGURED_MSG);
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        logIncoming("cli", sessionKey, content);

        // 将相对路径转换为绝对路径，确保 HTTPProvider 能读取到图片文件
        List<String> absoluteImagePaths = resolveImagePaths(images);

        InboundMessage message = new InboundMessage("cli", "user", "direct", content);
        message.setMedia(absoluteImagePaths);  // 设置图片列表
        List<Message> messages = buildContextWithImages(sessionKey, message, absoluteImagePaths);
        
        // 保存用户消息（含图片，存储相对路径供前端显示）
        sessions.addFullMessage(sessionKey, Message.user(content, images));
        sessions.save(sessions.getOrCreate(sessionKey)); // 在 LLM 调用前先持久化用户消息，防止异常时丢失

        String response = ensureNonBlank(
                components.llmExecutor.executeStream(messages, sessionKey, callback), DEFAULT_EMPTY_RESPONSE);

        persistAndSummarize(sessionKey, response);
        return response;
    }

    /** 处理带通道信息的消息，适用于定时任务等场景。 */
    public String processDirectWithChannel(String content, String sessionKey,
                                           String channel, String chatId) throws Exception {
        InboundMessage message = new InboundMessage(channel, "cron", chatId, content);
        message.setSessionKey(sessionKey);
        return processMessage(message);
    }

    // ==================== 消息分发 ====================

    private String processMessage(InboundMessage msg) throws Exception {
        logIncoming(msg);

        // 处理指令消息
        if (msg.isCommand()) {
            return processCommandMessage(msg);
        }

        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }
        return processUserMessage(msg);
    }

    // ==================== 指令消息处理 ====================

    /**
     * 处理指令消息（如 /new）。
     * 指令消息不会发送给 LLM，而是由 Agent 直接执行对应操作。
     */
    private String processCommandMessage(InboundMessage msg) {
        String command = msg.getCommand();

        if (InboundMessage.COMMAND_NEW_SESSION.equals(command)) {
            return handleNewSessionCommand(msg);
        }

        logger.warn("Unknown command received", Map.of("command", command));
        String unknownResponse = "未知指令: /" + command;
        publishReplyIfNeeded(msg, unknownResponse);
        return unknownResponse;
    }

    /**
     * 处理 /new 指令：开启全新会话。
     * 旧会话保留不动，后续消息将使用新的 sessionKey（由 BaseChannel 生成）。
     */
    private String handleNewSessionCommand(InboundMessage msg) {
        String newSessionKey = msg.getSessionKey();

        // 预创建新会话，确保 SessionManager 中存在该 session
        sessions.getOrCreate(newSessionKey);

        logger.info("New session created by /new command", Map.of(
                "new_session_key", newSessionKey,
                "channel", msg.getChannel(),
                "sender_id", msg.getSenderId()));

        String response = "✨ 新会话已开启，让我们开始新的对话吧！";
        publishReplyIfNeeded(msg, response);
        return response;
    }

    // ==================== 用户消息处理 ====================

    private String processUserMessage(InboundMessage msg) throws Exception {
        if (!providerConfigured) {
            publishReplyIfNeeded(msg, PROVIDER_NOT_CONFIGURED_MSG);
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        String sessionKey = msg.getSessionKey();
        List<Message> messages = buildContext(sessionKey, msg);
        sessions.addMessage(sessionKey, "user", msg.getContent());
        sessions.save(sessions.getOrCreate(sessionKey)); // 在 LLM 调用前先持久化用户消息，防止异常时丢失

        // 记录消息交互（进化组件启用时）
        if (components != null && components.feedbackManager != null) {
            components.feedbackManager.recordMessageExchange(sessionKey);
        }

        boolean usedStreaming = isStreamingChannel(msg);
        String response = ensureNonBlank(
                executeWithStreamingIfSupported(msg, messages, sessionKey, usedStreaming),
                DEFAULT_EMPTY_RESPONSE);

        persistAndSummarize(sessionKey, response);
        // 无论是否走流式路径，最终完整回复统一由此处发送
        // 流式路径中 callback 只负责发送"思考中"占位消息，不发送最终内容
        publishReplyIfNeeded(msg, response);
        return response;
    }

    /**
     * 判断当前消息的目标通道是否支持流式输出。
     */
    private boolean isStreamingChannel(InboundMessage msg) {
        if (channelManager == null || "cli".equals(msg.getChannel())) {
            return false;
        }
        Channel channel = channelManager.getChannel(msg.getChannel()).orElse(null);
        return channel != null && channel.supportsStreaming();
    }

    /**
     * 根据目标通道是否支持流式输出，选择对应的 LLM 执行路径。
     *
     * <p>若通道支持流式（如钉钉），则先发送占位消息告知用户正在处理，
     * LLM 完成后通过通道直接发送完整回复，避免重复发送。</p>
     *
     * @param msg           入站消息，用于获取通道名称和 chatId
     * @param messages      已构建好的上下文消息列表
     * @param sessionKey    当前会话 key
     * @param usedStreaming 是否走流式路径
     * @return LLM 生成的完整回复内容
     */
    private String executeWithStreamingIfSupported(InboundMessage msg,
                                                   List<Message> messages,
                                                   String sessionKey,
                                                   boolean usedStreaming) throws Exception {
        if (!usedStreaming) {
            return components.llmExecutor.execute(messages, sessionKey);
        }

        Channel channel = channelManager.getChannel(msg.getChannel()).orElse(null);
        LLMProvider.StreamCallback streamingCallback = channel.createStreamingCallback(msg.getChatId());

        logger.info("Using streaming output for channel", Map.of("channel", msg.getChannel()));
        // callback 仅负责发送"思考中"占位消息，最终完整回复由外层 processUserMessage 统一发送
        return components.llmExecutor.executeStream(messages, sessionKey, streamingCallback);
    }

    /**
     * 将回复发布到出站队列，使 ChannelManager 能将消息路由到对应通道。
     * 仅对来自外部通道的消息发布（跳过 CLI 直接调用）。
     */
    private void publishReplyIfNeeded(InboundMessage msg, String response) {
        String channel = msg.getChannel();
        if ("cli".equals(channel)) {
            return;
        }
        bus.publishOutbound(new OutboundMessage(channel, msg.getChatId(), response));
    }

    // ==================== 系统消息处理 ====================

    private String processSystemMessage(InboundMessage msg) throws Exception {
        logger.info("Processing system message", Map.of(
                "sender_id", msg.getSenderId(),
                "chat_id", msg.getChatId()));

        String[] origin = parseOrigin(msg.getChatId());
        String originChannel = origin[0];
        String originChatId = origin[1];
        String sessionKey = originChannel + ":" + originChatId;
        String userMessage = "[System: " + msg.getSenderId() + "] " + msg.getContent();

        InboundMessage syntheticMessage =
                new InboundMessage(originChannel, msg.getSenderId(), originChatId, userMessage);
        List<Message> messages = buildContext(sessionKey, syntheticMessage);
        sessions.addMessage(sessionKey, "user", userMessage);
        sessions.save(sessions.getOrCreate(sessionKey)); // 在 LLM 调用前先持久化用户消息，防止异常时丢失

        String response = ensureNonBlank(
                components.llmExecutor.execute(messages, sessionKey), "Background task completed.");

        persistAndSummarize(sessionKey, response); // 保存 assistant 回复并触发摘要
        bus.publishOutbound(new OutboundMessage(originChannel, originChatId, response));
        return response;
    }

    // ==================== 上下文与会话辅助 ====================

    private List<Message> buildContext(String sessionKey, InboundMessage msg) {
        return contextBuilder.buildMessages(
                sessions.getHistory(sessionKey),
                sessions.getSummary(sessionKey),
                msg.getContent(), msg.getChannel(), msg.getChatId());
    }
    
    /** 构建带图片的上下文（多模态）。 */
    private List<Message> buildContextWithImages(String sessionKey, InboundMessage msg, List<String> images) {
        return contextBuilder.buildMessages(
                sessions.getHistory(sessionKey),
                sessions.getSummary(sessionKey),
                msg.getContent(), images, msg.getChannel(), msg.getChatId());
    }

    /** 保存助手回复并按需触发会话摘要。 */
    private void persistAndSummarize(String sessionKey, String response) {
        sessions.addMessage(sessionKey, "assistant", response);
        sessions.save(sessions.getOrCreate(sessionKey));
        components.summarizer.maybeSummarize(sessionKey);
    }

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

    private static String[] parseOrigin(String chatId) {
        String[] parts = chatId.split(":", 2);
        return parts.length == 2
                ? parts
                : new String[]{"cli", chatId};
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
