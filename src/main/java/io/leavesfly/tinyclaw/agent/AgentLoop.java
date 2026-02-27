package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPManager;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.SessionManager;
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
    private volatile LLMProvider provider;
    private volatile MCPManager mcpManager;

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
        this.summarizer = new SessionSummarizer(sessions, newProvider, model, contextWindow);
        this.providerConfigured = true;
    }

    // ==================== 生命周期 ====================

    /** 阻塞式运行 Agent 主循环，持续消费消息总线直到 {@link #stop()} 被调用。 */
    public void run() {
        running = true;
        logger.info("Agent loop started");

        while (running) {
            try {
                processMessage(bus.consumeInbound());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing message", Map.of("error", e.getMessage()));
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

        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }
        return processUserMessage(msg);
    }

    // ==================== 用户消息处理 ====================

    private String processUserMessage(InboundMessage msg) throws Exception {
        if (!providerConfigured) {
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        String sessionKey = msg.getSessionKey();
        List<Message> messages = buildContext(sessionKey, msg);
        sessions.addMessage(sessionKey, "user", msg.getContent());

        String response = ensureNonBlank(
                llmExecutor.execute(messages, sessionKey), DEFAULT_EMPTY_RESPONSE);

        persistAndSummarize(sessionKey, response);
        return response;
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

        String response = ensureNonBlank(
                llmExecutor.execute(messages, sessionKey), "Background task completed.");

        sessions.addMessage(sessionKey, "assistant", response);
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
