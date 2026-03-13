package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;
import okhttp3.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WhatsApp 通道实现 - 基于 WhatsApp Bridge WebSocket（使用 OkHttp，无第三方 SDK）
 *
 * 提供通过 WhatsApp Bridge 服务发送和接收消息的能力：
 * - WebSocket 连接到 Bridge 服务
 * - 文本消息收发
 * - 媒体消息支持
 *
 * 核心流程：
 * 1. 通过 OkHttp WebSocket 连接到 WhatsApp Bridge 服务
 * 2. 接收 Bridge 转发的 WhatsApp 消息
 * 3. 解析消息内容并发布到消息总线
 * 4. 通过 WebSocket 发送出站消息
 *
 * 配置要求：
 * - Bridge URL：WhatsApp Bridge 服务的 WebSocket 地址（如 ws://localhost:3001）
 */
public class WhatsAppChannel extends BaseChannel {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("whatsapp");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChannelsConfig.WhatsAppConfig config;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /**
     * 创建 WhatsApp 通道
     *
     * @param config WhatsApp 配置
     * @param bus    消息总线
     */
    public WhatsAppChannel(ChannelsConfig.WhatsAppConfig config, MessageBus bus) {
        super("whatsapp", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)   // WebSocket 不设读超时
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void start() throws ChannelException {
        logger.info("正在启动 WhatsApp 通道...", Map.of("bridge_url", config.getBridgeUrl()));

        if (config.getBridgeUrl() == null || config.getBridgeUrl().isEmpty()) {
            throw new ChannelException("WhatsApp Bridge URL 为空");
        }

        CountDownLatch connectLatch = new CountDownLatch(1);

        Request request = new Request.Builder().url(config.getBridgeUrl()).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected.set(true);
                logger.info("WhatsApp Bridge 连接成功");
                connectLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                connected.set(false);
                logger.warn("WhatsApp Bridge 连接关闭", Map.of("code", code, "reason", reason));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected.set(false);
                String errMsg = t.getMessage() != null ? t.getMessage() : "unknown";
                logger.error("WhatsApp Bridge 连接错误", Map.of("error", errMsg));
                connectLatch.countDown();
            }
        });

        try {
            boolean ok = connectLatch.await(15, TimeUnit.SECONDS);
            if (!ok || !connected.get()) {
                throw new ChannelException("连接 WhatsApp Bridge 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("连接 WhatsApp Bridge 被中断", e);
        }

        setRunning(true);
        logger.info("WhatsApp 通道已启动");
    }

    @Override
    public void stop() {
        logger.info("正在停止 WhatsApp 通道...");
        setRunning(false);
        connected.set(false);
        if (webSocket != null) {
            webSocket.close(1000, "Normal closure");
        }
        logger.info("WhatsApp 通道已停止");
    }

    @Override
    public void send(OutboundMessage message) throws ChannelException {
        if (!isRunning() || !connected.get()) {
            throw new IllegalStateException("WhatsApp 通道未运行");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "message");
        payload.put("to", message.getChatId());
        payload.put("content", message.getContent());

        try {
            webSocket.send(objectMapper.writeValueAsString(payload));
            logger.debug("WhatsApp 消息已发送", Map.of("chat_id", message.getChatId()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("序列化 WhatsApp 消息失败", e);
        }
    }

    /**
     * 处理接收到的消息
     */
    private void handleIncomingMessage(String message) {
        try {
            JsonNode msg = objectMapper.readTree(message);

            String msgType = msg.path("type").asText("");
            if (!"message".equals(msgType)) {
                return;
            }

            String senderId = msg.path("from").asText(null);
            if (senderId == null || senderId.isEmpty()) {
                return;
            }

            String chatId = msg.path("chat").asText(senderId);
            String content = msg.path("content").asText("");

            List<String> mediaPaths = new ArrayList<>();
            JsonNode mediaNode = msg.path("media");
            if (mediaNode.isArray()) {
                for (JsonNode m : mediaNode) {
                    mediaPaths.add(m.asText());
                }
            }

            Map<String, String> metadata = new HashMap<>();
            if (msg.has("id")) metadata.put("message_id", msg.get("id").asText());
            if (msg.has("from_name")) metadata.put("user_name", msg.get("from_name").asText());

            logger.info("收到 WhatsApp 消息", Map.of(
                    "sender_id", senderId,
                    "chat_id", chatId,
                    "preview", StringUtils.truncate(content, 50)
            ));

            handleMessage(senderId, chatId, content, mediaPaths, metadata);

        } catch (Exception e) {
            logger.error("处理 WhatsApp 消息时出错", Map.of("error", e.getMessage()));
        }
    }
}
