package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.channels.Channel;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.hooks.HookContext;
import io.leavesfly.tinyclaw.hooks.HookDecision;
import io.leavesfly.tinyclaw.hooks.HookDispatcher;
import io.leavesfly.tinyclaw.hooks.HookEvent;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 消息路由器，负责将入站消息分发到不同的处理逻辑。
 *
 * <p>从 AgentRuntime 中抽取的消息路由逻辑，包括指令处理、用户消息处理、
 * 系统消息处理、流式输出支持等功能。</p>
 */
class MessageRouter {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("router");
    private static final String PROVIDER_NOT_CONFIGURED_MSG =
            "⚠️ LLM Provider 未配置，请通过 Web Console 的 Settings -> Models 页面配置 API Key 后再试。";
    private static final String DEFAULT_EMPTY_RESPONSE = "已完成处理但没有回复内容。";
    private static final int LOG_PREVIEW_LENGTH = 80;

    private final ProviderManager providerManager;
    private final MessageBus bus;
    private final SessionManager sessions;
    private final ContextBuilder contextBuilder;
    private final Config config;

    private volatile ChannelManager channelManager;

    /** Hook 调度器；未注入时等价于无 hook 零开销。 */
    private volatile HookDispatcher hookDispatcher = HookDispatcher.noop();

    MessageRouter(ProviderManager providerManager, MessageBus bus,
                  SessionManager sessions, ContextBuilder contextBuilder, Config config) {
        this.providerManager = providerManager;
        this.bus = bus;
        this.sessions = sessions;
        this.contextBuilder = contextBuilder;
        this.config = config;
    }

    void setChannelManager(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * 注入 Hook 调度器。未注入时，{@link #routeUser} 中的 SessionStart/UserPromptSubmit/Stop
     * 切点调用等价于直接放行，零副作用。
     */
    void setHookDispatcher(HookDispatcher hookDispatcher) {
        this.hookDispatcher = hookDispatcher == null ? HookDispatcher.noop() : hookDispatcher;
    }

    // ==================== 主路由入口 ====================

    /**
     * 路由消息到对应的处理逻辑。
     *
     * @param msg 入站消息
     * @return 处理结果
     */
    String route(InboundMessage msg) throws Exception {
        logIncoming(msg);

        if (msg.isCommand()) {
            return routeCommand(msg);
        }

        if ("system".equals(msg.getChannel())) {
            return routeSystem(msg);
        }
        return routeUser(msg);
    }

    // ==================== 指令消息处理 ====================

    /**
     * 路由指令消息（如 /new）。
     */
    String routeCommand(InboundMessage msg) {
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
     */
    String handleNewSessionCommand(InboundMessage msg) {
        String newSessionKey = msg.getSessionKey();

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

    /**
     * 路由用户消息到 LLM 处理。
     *
     * <p>本方法织入了 3 个 Hook 切点：</p>
     * <ul>
     *   <li><b>SessionStart</b>：当前 sessionKey 是否在本轮处理前已经存在于 {@link SessionManager} 内，
     *       若不存在则视为新会话开始并触发。返回的 {@code additionalContext} 会被追加到会话 summary。</li>
     *   <li><b>UserPromptSubmit</b>：消息被写入 session 之前。DENY 直接回复 reason；
     *       modifyPrompt 改写 {@code msg.content}，后续流程使用改写后的内容。</li>
     *   <li><b>Stop</b>：LLM 生成完整回复后、持久化与出站之前。modifyOutput 可改写最终回复。</li>
     * </ul>
     */
    String routeUser(InboundMessage msg) throws Exception {
        if (!providerManager.isConfigured()) {
            publishReplyIfNeeded(msg, PROVIDER_NOT_CONFIGURED_MSG);
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        String sessionKey = msg.getSessionKey();

        // ---------- SessionStart（首次出现才触发） ----------
        boolean sessionPreexists = sessions.getSessionKeys().contains(sessionKey);
        if (!sessionPreexists) {
            fireSessionStart(sessionKey);
        }

        // ---------- UserPromptSubmit ----------
        String effectiveContent = msg.getContent();
        HookContext promptCtx = HookContext.builder(HookEvent.USER_PROMPT_SUBMIT)
                .sessionKey(sessionKey)
                .prompt(effectiveContent)
                .build();
        HookDecision promptDecision = hookDispatcher.fire(HookEvent.USER_PROMPT_SUBMIT, promptCtx);
        if (promptDecision.isDeny()) {
            String reason = promptDecision.getReason() == null
                    ? "Request blocked by hook"
                    : promptDecision.getReason();
            logger.info("UserPromptSubmit hook denied request", Map.of(
                    "session_key", sessionKey == null ? "" : sessionKey,
                    "reason", reason));
            publishReplyIfNeeded(msg, reason);
            return reason;
        }
        if (promptDecision.getModifiedPrompt() != null) {
            effectiveContent = promptDecision.getModifiedPrompt();
            msg.setContent(effectiveContent);
            logger.info("UserPromptSubmit hook modified prompt", Map.of(
                    "session_key", sessionKey == null ? "" : sessionKey));
        }
        if (promptDecision.getAdditionalContext() != null) {
            appendSessionContext(sessionKey, promptDecision.getAdditionalContext());
        }

        List<Message> messages = buildContext(sessionKey, msg);
        sessions.addMessage(sessionKey, "user", effectiveContent);
        sessions.save(sessions.getOrCreate(sessionKey));

        ProviderComponents comps = providerManager.getComponents();
        if (comps != null && comps.feedbackManager != null) {
            comps.feedbackManager.recordMessageExchange(sessionKey);
        }

        boolean usedStreaming = isStreamingChannel(msg);
        String response = ensureNonBlank(
                executeWithStreamingIfSupported(msg, messages, sessionKey, usedStreaming),
                DEFAULT_EMPTY_RESPONSE);

        // ---------- Stop ----------
        HookContext stopCtx = HookContext.builder(HookEvent.STOP)
                .sessionKey(sessionKey)
                .prompt(effectiveContent)
                .toolOutput(response)
                .build();
        HookDecision stopDecision = hookDispatcher.fire(HookEvent.STOP, stopCtx);
        if (stopDecision.isDeny()) {
            String reason = stopDecision.getReason() == null
                    ? "Response blocked by hook"
                    : stopDecision.getReason();
            logger.info("Stop hook denied response", Map.of(
                    "session_key", sessionKey == null ? "" : sessionKey, "reason", reason));
            response = reason;
        } else if (stopDecision.getModifiedOutput() != null) {
            response = stopDecision.getModifiedOutput();
            logger.info("Stop hook modified response", Map.of(
                    "session_key", sessionKey == null ? "" : sessionKey));
        }

        persistAndSummarize(sessionKey, response);
        publishReplyIfNeeded(msg, response);
        return response;
    }

    /**
     * 触发 SessionStart 事件：新会话首次出现时调用，将 Hook 返回的 additionalContext
     * 追加到 session summary 作为持久化上下文。
     */
    private void fireSessionStart(String sessionKey) {
        HookContext ctx = HookContext.builder(HookEvent.SESSION_START)
                .sessionKey(sessionKey)
                .build();
        HookDecision decision = hookDispatcher.fire(HookEvent.SESSION_START, ctx);
        if (decision.getAdditionalContext() != null) {
            appendSessionContext(sessionKey, decision.getAdditionalContext());
            logger.info("SessionStart hook injected context", Map.of(
                    "session_key", sessionKey == null ? "" : sessionKey));
        }
    }

    /**
     * 将一段 hook 注入的上下文追加到会话 summary。为避免把 summary 撑爆，单次追加内容超过 4KB 会截断。
     */
    private void appendSessionContext(String sessionKey, String additionalContext) {
        if (sessionKey == null || additionalContext == null || additionalContext.isEmpty()) {
            return;
        }
        sessions.getOrCreate(sessionKey); // 确保 session 存在
        String existing = sessions.getSummary(sessionKey);
        String appended = additionalContext.length() > 4096
                ? additionalContext.substring(0, 4096) + "..."
                : additionalContext;
        String merged = (existing == null || existing.isEmpty())
                ? appended
                : existing + "\n\n" + appended;
        sessions.setSummary(sessionKey, merged);
    }

    /**
     * 判断当前消息的目标通道是否支持流式输出。
     */
    boolean isStreamingChannel(InboundMessage msg) {
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
            ProviderComponents comps = providerManager.getComponents();
            return comps.reActExecutor.execute(messages, sessionKey);
        }

        Channel channel = channelManager.getChannel(msg.getChannel()).orElse(null);
        LLMProvider.StreamCallback streamingCallback = channel.createStreamingCallback(msg.getChatId());

        logger.info("Using streaming output for channel", Map.of("channel", msg.getChannel()));
        ProviderComponents comps = providerManager.getComponents();
        return comps.reActExecutor.executeStream(messages, sessionKey, streamingCallback);
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

    /**
     * 路由系统消息到 LLM 处理。
     */
    String routeSystem(InboundMessage msg) throws Exception {
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
        sessions.save(sessions.getOrCreate(sessionKey));

        ProviderComponents comps = providerManager.getComponents();
        String response = ensureNonBlank(
                comps.reActExecutor.execute(messages, sessionKey), "Background task completed.");

        persistAndSummarize(sessionKey, response);
        bus.publishOutbound(new OutboundMessage(originChannel, originChatId, response));
        return response;
    }

    // ==================== 上下文与会话辅助 ====================

    /**
     * 构建上下文消息列表。
     */
    List<Message> buildContext(String sessionKey, InboundMessage msg) {
        return contextBuilder.buildMessages(
                sessions.getHistory(sessionKey),
                sessions.getSummary(sessionKey),
                msg.getContent(), msg.getChannel(), msg.getChatId());
    }

    /**
     * 构建带图片的上下文（多模态）。
     */
    List<Message> buildContextWithImages(String sessionKey, InboundMessage msg, List<String> images) {
        return contextBuilder.buildMessages(
                sessions.getHistory(sessionKey),
                sessions.getSummary(sessionKey),
                msg.getContent(), images, msg.getChannel(), msg.getChatId());
    }

    /**
     * 保存助手回复并按需触发会话摘要。
     */
    void persistAndSummarize(String sessionKey, String response) {
        sessions.addMessage(sessionKey, "assistant", response);
        sessions.save(sessions.getOrCreate(sessionKey));
        ProviderComponents comps = providerManager.getComponents();
        comps.summarizer.maybeSummarize(sessionKey);
    }

    // ==================== 静态工具方法 ====================

    /**
     * 解析原始来源信息（channel:chatId）。
     */
    static String[] parseOrigin(String chatId) {
        String[] parts = chatId.split(":", 2);
        return parts.length == 2
                ? parts
                : new String[]{"cli", chatId};
    }

    /**
     * 确保字符串非空，否则返回默认值。
     */
    static String ensureNonBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    /**
     * 记录入站消息日志。
     */
    private void logIncoming(InboundMessage msg) {
        logIncoming(msg.getChannel(), msg.getSessionKey(), msg.getContent(),
                msg.getChatId(), msg.getSenderId());
    }

    /**
     * 记录入站消息日志（简化版）。
     */
    private void logIncoming(String channel, String sessionKey, String content,
                             String chatId, String senderId) {
        Map<String, Object> fields = new java.util.HashMap<>();
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
