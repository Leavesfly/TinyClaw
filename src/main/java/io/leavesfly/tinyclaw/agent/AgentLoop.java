package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.collaboration.AgentOrchestrator;
import io.leavesfly.tinyclaw.agent.evolution.*;
import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPManager;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.CollaborateTool;
import io.leavesfly.tinyclaw.tools.Tool;
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
    private volatile LLMExecutor llmExecutor;
    private volatile SessionSummarizer summarizer;
    private volatile MemoryEvolver memoryEvolver;
    private volatile LLMProvider provider;
    private volatile MCPManager mcpManager;
    
    /* ---------- 进化组件（可选，配置启用后初始化） ---------- */
    private volatile FeedbackStore feedbackStore;
    private volatile FeedbackCollector feedbackCollector;
    private volatile PromptStore promptStore;
    private volatile PromptOptimizer promptOptimizer;
    
    /* ---------- 协同组件（可选，配置启用后初始化） ---------- */
    private volatile AgentOrchestrator orchestrator;

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

    public boolean isProviderConfigured() {
        return providerConfigured;
    }

    public LLMProvider getProvider() {
        return provider;
    }

    /**
     * 将 provider 及其派生组件一次性赋值，消除构造器与 setProvider 之间的重复逻辑。
     * 调用方需自行保证线程安全（构造器天然安全，setProvider 通过 providerLock 保护）。
     */
    private void applyProvider(LLMProvider newProvider) {
        this.provider = newProvider;
        String model = config.getAgent().getModel();
        int maxIterations = config.getAgent().getMaxToolIterations();
        int contextWindow = config.getAgent().getMaxTokens();
        this.llmExecutor = new LLMExecutor(newProvider, tools, sessions, model, maxIterations);

        // 创建记忆进化引擎
        MemoryStore memoryStore = contextBuilder.getMemoryStore();
        this.memoryEvolver = new MemoryEvolver(memoryStore, newProvider, model);

        // 将上下文窗口传递给 ContextBuilder，用于计算记忆 token 预算
        contextBuilder.setContextWindow(contextWindow);

        this.summarizer = new SessionSummarizer(sessions, newProvider, model, contextWindow,
                memoryStore, memoryEvolver);
        this.providerConfigured = true;
        
        // 初始化进化组件（如果启用）
        initializeEvolutionComponents(newProvider, model);
        
        // 初始化多Agent协同组件（如果启用）
        initializeCollaborationComponents(newProvider, model, maxIterations);
    }
    
    /**
     * 初始化进化组件（反馈收集、Prompt 优化）。
     * 
     * 仅在配置启用时初始化，不影响现有功能。
     */
    private void initializeEvolutionComponents(LLMProvider newProvider, String model) {
        EvolutionConfig evolutionConfig = config.getAgent().getEvolution();
        if (evolutionConfig == null || !evolutionConfig.isAnyEvolutionEnabled()) {
            logger.debug("Evolution features disabled");
            return;
        }
        
        try {
           // 初始化反馈存储和收集器
            if (evolutionConfig.isFeedbackEnabled()) {
                this.feedbackStore = new FeedbackStore(workspace, evolutionConfig.getFeedbackRetentionDays());
                this.feedbackCollector = new FeedbackCollector(feedbackStore);
                
                // 将反馈收集器注入到 LLMExecutor，用于记录工具执行结果
                if (llmExecutor != null) {
                    llmExecutor.setFeedbackCollector(feedbackCollector);
                }
                logger.info("Feedback collection enabled");
            }
            
            // 初始化 Prompt 优化器
            if (evolutionConfig.isPromptOptimizationEnabled() && feedbackCollector != null) {
                this.promptStore = new PromptStore(workspace, evolutionConfig.getMaxHistoryVersions());
                this.promptOptimizer = new PromptOptimizer(
                        newProvider, model, promptStore, feedbackCollector, evolutionConfig);
                
                // 将优化器设置到 ContextBuilder
                contextBuilder.setPromptOptimizer(promptOptimizer);
                logger.info("Prompt optimization enabled", Map.of(
                        "strategy", evolutionConfig.getStrategy().name()));
            }
        } catch (Exception e) {
            logger.error("Failed to initialize evolution components", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 初始化多Agent协同组件。
     * 
     * 仅在配置启用时初始化，不影响现有功能。
     */
    private void initializeCollaborationComponents(LLMProvider newProvider, String model, int maxIterations) {
        if (!config.getAgent().isCollaborationEnabled()) {
            logger.debug("Collaboration features disabled");
            return;
        }
        
        try {
            // 创建协同编排器
            this.orchestrator = new AgentOrchestrator(
                    newProvider, tools, workspace, model, maxIterations);
            
            // 创建并注册协同工具（生命周期由 ToolRegistry 托管，无需成员变量持有）
            CollaborateTool collaborateTool = new CollaborateTool(orchestrator);
            collaborateTool.setLLMContext(newProvider, model);
            tools.register(collaborateTool);
            contextBuilder.setTools(tools);
            
            logger.info("Collaboration features enabled", Map.of(
                    "supportedModes", "debate,team,roleplay,consensus,hierarchy"));
        } catch (Exception e) {
            logger.error("Failed to initialize collaboration components", Map.of("error", e.getMessage()));
        }
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

    /** 获取记忆进化引擎，供外部组件（如心跳服务）触发记忆进化 */
    public MemoryEvolver getMemoryEvolver() {
        return memoryEvolver;
    }
    
    /** 获取反馈收集器，供外部组件记录反馈 */
    public FeedbackCollector getFeedbackCollector() {
        return feedbackCollector;
    }
    
    /** 获取 Prompt 优化器，供外部组件触发优化 */
    public PromptOptimizer getPromptOptimizer() {
        return promptOptimizer;
    }
    
    /**
     * 执行进化周期（供心跳服务调用）。
     * 
     * 包含记忆进化和可选的 Prompt 优化。
     */
    public void runEvolutionCycle() {
        // 1. 基于反馈的智能记忆进化（如果启用）
        if (feedbackCollector != null && memoryEvolver != null) {
            try {
                // 获取最近一天的聚合反馈
                List<EvaluationFeedback> recentFeedbacks = feedbackCollector.getRecentAggregatedFeedbacks(1);
                for (EvaluationFeedback feedback : recentFeedbacks) {
                    memoryEvolver.evolveWithFeedback(feedback);
                }
                if (!recentFeedbacks.isEmpty()) {
                    logger.debug("Processed feedback-based memory evolution", Map.of(
                            "feedback_count", recentFeedbacks.size()));
                }
            } catch (Exception e) {
                logger.error("Feedback-based memory evolution failed", Map.of("error", e.getMessage()));
            }
        }
        
        // 2. 常规记忆进化
        if (memoryEvolver != null) {
            try {
                memoryEvolver.evolve();
            } catch (Exception e) {
                logger.error("Memory evolution failed", Map.of("error", e.getMessage()));
            }
        }
        
        // 3. Prompt 优化（如果启用）
        if (promptOptimizer != null && config.getAgent().isPromptOptimizationEnabled()) {
            try {
                String currentPrompt = contextBuilder.getPromptOptimizer() != null 
                        ? contextBuilder.getPromptOptimizer().getActiveOptimization()
                        : null;
                // 传入基础 prompt 进行优化检查
                promptOptimizer.maybeOptimize(currentPrompt != null ? currentPrompt : "");
            } catch (Exception e) {
                logger.error("Prompt optimization failed", Map.of("error", e.getMessage()));
            }
        }
        
        // 4. 清理已结束会话的跟踪数据
        if (feedbackCollector != null) {
            feedbackCollector.cleanupEndedSessions();
        }
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
        if (!providerConfigured) {
            notifyCallback(callback, PROVIDER_NOT_CONFIGURED_MSG);
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        logIncoming("cli", sessionKey, content);

        InboundMessage message = new InboundMessage("cli", "user", "direct", content);
        List<Message> messages = buildContext(sessionKey, message);
        sessions.addMessage(sessionKey, "user", content);
        sessions.save(sessions.getOrCreate(sessionKey)); // 在 LLM 调用前先持久化用户消息，防止异常时丢失

        String response = ensureNonBlank(
                llmExecutor.executeStream(messages, sessionKey, callback), DEFAULT_EMPTY_RESPONSE);

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
        if (feedbackCollector != null) {
            feedbackCollector.recordMessageExchange(sessionKey);
        }

        String response = ensureNonBlank(
                llmExecutor.execute(messages, sessionKey), DEFAULT_EMPTY_RESPONSE);

        persistAndSummarize(sessionKey, response);
        publishReplyIfNeeded(msg, response);
        return response;
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
                llmExecutor.execute(messages, sessionKey), "Background task completed.");

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

    /** 保存助手回复并按需触发会话摘要。 */
    private void persistAndSummarize(String sessionKey, String response) {
        sessions.addMessage(sessionKey, "assistant", response);
        sessions.save(sessions.getOrCreate(sessionKey));
        summarizer.maybeSummarize(sessionKey);
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
