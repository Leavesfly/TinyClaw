package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.channels.Channel;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.config.Config;
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
     */
    String routeUser(InboundMessage msg) throws Exception {
        if (!providerManager.isConfigured()) {
            publishReplyIfNeeded(msg, PROVIDER_NOT_CONFIGURED_MSG);
            return PROVIDER_NOT_CONFIGURED_MSG;
        }

        String sessionKey = msg.getSessionKey();
        List<Message> messages = buildContext(sessionKey, msg);
        sessions.addMessage(sessionKey, "user", msg.getContent());
        sessions.save(sessions.getOrCreate(sessionKey));

        ProviderComponents comps = providerManager.getComponents();
        if (comps != null && comps.feedbackManager != null) {
            comps.feedbackManager.recordMessageExchange(sessionKey);
        }

        boolean usedStreaming = isStreamingChannel(msg);
        String response = ensureNonBlank(
                executeWithStreamingIfSupported(msg, messages, sessionKey, usedStreaming),
                DEFAULT_EMPTY_RESPONSE);

        persistAndSummarize(sessionKey, response);
        publishReplyIfNeeded(msg, response);
        return response;
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
