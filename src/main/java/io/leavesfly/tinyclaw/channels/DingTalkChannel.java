package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.SSLUtils;
import io.leavesfly.tinyclaw.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 钉钉通道实现 - 基于钉钉机器人 Webhook API 和 Stream 模式
 * 
 * 提供钉钉平台的消息发送能力，支持：
 * - Webhook 消息发送（被动接收模式）
 * - Stream 模式（主动连接，无需公网 IP）
 * - 签名验证
 * - Markdown 格式消息
 * - session_webhook 回复机制
 * 
 * 核心流程：
 * 1. 使用 Client ID 和 Client Secret 配置
 * 2. Webhook 模式：通过 Webhook 接收消息（需配合钉钉机器人配置）
 * 3. Stream 模式：主动连接钉钉 Stream 服务，实时接收消息
 * 4. 解析消息内容并发布到消息总线
 * 5. 使用 session_webhook 发送回复
 * 
 * 配置要求：
 * - Client ID：钉钉应用的 Client ID
 * - Client Secret：钉钉应用的 Client Secret
 * - Webhook URL：机器人 Webhook 地址（Webhook 模式可选）
 * - Connection Mode：连接模式，"webhook" 或 "stream"（默认 "stream"）
 * 
 * 注意：
 * - Webhook 模式需要配置钉钉机器人的消息接收地址
 * - Stream 模式无需公网 IP，主动连接钉钉服务器
 * - 发送消息使用 session_webhook 或配置的 Webhook
 */
public class DingTalkChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("dingtalk");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String STREAM_CONNECTION_URL = "https://api.dingtalk.com/v1.0/gateway/connections/open";
    private static final long RECONNECT_DELAY_SECONDS = 5;
    
    private final ChannelsConfig.DingTalkConfig config;
    private final OkHttpClient httpClient;
    
    // 存储 session_webhook 用于回复
    private final Map<String, String> sessionWebhooks = new ConcurrentHashMap<>();
    
    // Stream 模式相关
    private WebSocket webSocket;
    private volatile boolean streamModeRunning = false;
    
    /**
     * 创建钉钉通道
     * 
     * @param config 钉钉配置
     * @param bus 消息总线
     */
    public DingTalkChannel(ChannelsConfig.DingTalkConfig config, MessageBus bus) {
        super("dingtalk", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .sslSocketFactory(SSLUtils.getDefaultSSLSocketFactory(), SSLUtils.getDefaultTrustManager())
            .build();
    }
    
    @Override
    public void start() {
        logger.info("正在启动钉钉通道...");
        
        if (config.getClientId() == null || config.getClientId().isEmpty()) {
            throw new ChannelException("钉钉 Client ID 为空");
        }
        
        if (config.getClientSecret() == null || config.getClientSecret().isEmpty()) {
            throw new ChannelException("钉钉 Client Secret 为空");
        }
        
        setRunning(true);
        
        if (config.isStreamMode()) {
            startStreamMode();
            logger.info("钉钉通道已启动（Stream 模式）");
        } else {
            logger.info("钉钉通道已启动（Webhook 模式）");
            logger.info("请确保已配置钉钉机器人的消息接收地址");
        }
    }
    
    /**
     * 启动 Stream 模式
     * 在守护线程中运行，避免阻塞主线程
     */
    private void startStreamMode() {
        streamModeRunning = true;
        Thread streamThread = new Thread(() -> {
            while (isRunning() && streamModeRunning) {
                try {
                    connectStreamConnection();
                    break;
                } catch (Exception e) {
                    logger.error("Stream 连接失败，将在 " + RECONNECT_DELAY_SECONDS + " 秒后重试", 
                        Map.of("error", e.getMessage()));
                    try {
                        Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "DingTalkStreamThread");
        streamThread.setDaemon(true);
        streamThread.start();
    }
    
    /**
     * 注册 Stream 连接并建立 WebSocket 连接
     */
    private void connectStreamConnection() {
        StreamConnectionInfo connectionInfo = registerStreamConnection();
        
        String websocketUrl = connectionInfo.endpoint + "?ticket=" + connectionInfo.ticket;
        logger.info("正在连接钉钉 Stream 服务", Map.of("url", websocketUrl));
        
        Request request = new Request.Builder()
            .url(websocketUrl)
            .build();
        
        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("钉钉 Stream 连接已建立");
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleStreamMessage(text);
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                logger.info("钉钉 Stream 连接正在关闭", Map.of("code", String.valueOf(code), "reason", reason));
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("钉钉 Stream 连接已关闭", Map.of("code", String.valueOf(code), "reason", reason));
                if (streamModeRunning && isRunning()) {
                    scheduleReconnect();
                }
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("钉钉 Stream 连接失败", Map.of("error", t.getMessage()));
                if (streamModeRunning && isRunning()) {
                    scheduleReconnect();
                }
            }
        };
        
        webSocket = httpClient.newWebSocket(request, listener);
    }
    
    /**
     * 注册 Stream 连接，获取 WebSocket endpoint 和 ticket
     */
    private StreamConnectionInfo registerStreamConnection() {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("clientId", config.getClientId());
        requestBody.put("clientSecret", config.getClientSecret());
        
        // 订阅机器人消息回调和事件推送
        ArrayNode subscriptions = requestBody.putArray("subscriptions");
        ObjectNode botCallback = subscriptions.addObject();
        botCallback.put("type", "CALLBACK");
        botCallback.put("topic", "/v1.0/im/bot/messages/get");
        ObjectNode eventSubscription = subscriptions.addObject();
        eventSubscription.put("type", "EVENT");
        eventSubscription.put("topic", "*");
        
        requestBody.put("ua", "tinyclaw-sdk-java/1.0.0");
        
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            logger.info("Stream 注册请求体", Map.of("body", jsonBody));
            
            Request request = new Request.Builder()
                .url(STREAM_CONNECTION_URL)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    logger.error("注册 Stream 连接失败", Map.of("code", String.valueOf(response.code()), "body", errorBody));
                    throw new ChannelException("注册 Stream 连接失败: HTTP " + response.code() + " " + errorBody);
                }
                
                String responseBody = response.body() != null ? response.body().string() : "";
                logger.info("Stream 注册响应", Map.of("body", responseBody));
                JsonNode responseJson = objectMapper.readTree(responseBody);
                
                String endpoint = responseJson.path("endpoint").asText(null);
                String ticket = responseJson.path("ticket").asText(null);
                
                if (endpoint == null || endpoint.isEmpty()) {
                    throw new ChannelException("未获取到 endpoint");
                }
                if (ticket == null || ticket.isEmpty()) {
                    throw new ChannelException("未获取到 ticket");
                }
                
                logger.info("Stream 连接注册成功", Map.of("endpoint", endpoint));
                return new StreamConnectionInfo(endpoint, ticket);
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("注册 Stream 连接时出错: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理 Stream 模式收到的消息
     */
    private void handleStreamMessage(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            
            JsonNode headers = json.path("headers");
            String topic = headers.path("topic").asText("");
            String messageId = headers.path("messageId").asText("");
            
            logger.info("收到 Stream 消息", Map.of(
                "topic", topic,
                "messageId", messageId
            ));
            
            // 处理心跳消息
            if ("ping".equals(topic)) {
                String data = json.path("data").asText("{}");
                sendStreamAck(messageId, data);
                return;
            }
            
            // 处理机器人消息
            if ("/v1.0/im/bot/messages/get".equals(topic)) {
                String data = json.path("data").asText("");
                if (data.isEmpty()) {
                    logger.warn("收到空消息数据");
                    return;
                }
                
                logger.info("收到机器人消息数据", Map.of(
                    "data_length", data.length(),
                    "data_preview", StringUtils.truncate(data, 200)
                ));
                
                // 调用原有的 handleIncomingMessage 处理消息
                handleIncomingMessage(data);
                
                // 发送 ACK
                sendStreamAck(messageId, "");
            } else {
                logger.info("收到未处理的 Stream topic", Map.of("topic", topic));
            }
            
        } catch (Exception e) {
            logger.error("处理 Stream 消息时出错", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 发送 Stream ACK 响应
     */
    private void sendStreamAck(String messageId, String data) {
        if (webSocket == null || !isRunning()) {
            return;
        }
        
        try {
            ObjectNode ack = objectMapper.createObjectNode();
            ack.put("code", 200);
            
            ObjectNode ackHeaders = ack.putObject("headers");
            ackHeaders.put("contentType", "application/json");
            ackHeaders.put("messageId", messageId);
            
            ack.put("message", "OK");
            ack.put("data", data);
            
            String ackJson = objectMapper.writeValueAsString(ack);
            webSocket.send(ackJson);
            
        } catch (Exception e) {
            logger.error("发送 Stream ACK 时出错", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 调度重连
     */
    private void scheduleReconnect() {
        if (!streamModeRunning || !isRunning()) {
            return;
        }
        
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
                if (streamModeRunning && isRunning()) {
                    connectStreamConnection();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("重连失败", Map.of("error", e.getMessage()));
            }
        }, "DingTalkReconnectThread");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }
    
    @Override
    public void stop() {
        logger.info("正在停止钉钉通道...");
        
        streamModeRunning = false;
        
        if (webSocket != null) {
            webSocket.close(1000, "Channel stopped");
            webSocket = null;
        }
        
        setRunning(false);
        sessionWebhooks.clear();
        logger.info("钉钉通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            throw new IllegalStateException("钉钉通道未运行");
        }
        
        String chatId = message.getChatId();
        
        // 优先使用 session_webhook
        String webhook = sessionWebhooks.get(chatId);
        if (webhook == null || webhook.isEmpty()) {
            // 尝试使用配置的 Webhook
            webhook = config.getWebhook();
        }
        
        if (webhook == null || webhook.isEmpty()) {
            throw new ChannelException("未找到 chat " + chatId + " 的 session_webhook，无法发送消息");
        }
        
        logger.info("发送钉钉消息", Map.of(
            "chat_id", chatId,
            "preview", StringUtils.truncate(message.getContent(), 100)
        ));
        
        // 发送 Markdown 格式消息
        sendMarkdownMessage(webhook, "TinyClaw", message.getContent());
    }
    
    /**
     * 处理接收到的钉钉消息
     * 
     * 此方法由外部 HTTP 接口调用（如 Servlet 或 HTTP Server）
     * 
     * @param requestBody 钉钉推送的请求体
     * @return 响应内容
     */
    public String handleIncomingMessage(String requestBody) {
        try {
            JsonNode json = objectMapper.readTree(requestBody);
            
            // 提取消息内容
            String content = "";
            JsonNode textNode = json.path("text");
            if (textNode.has("content")) {
                content = textNode.get("content").asText();
            }
            
            if (content.isEmpty()) {
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"收到\"}}";
            }
            
            // 提取发送者信息
            String senderId = json.path("senderStaffId").asText("unknown");
            String senderNick = json.path("senderNick").asText("未知用户");
            String chatId = senderId;
            
            // 群聊处理
            String conversationType = json.path("conversationType").asText("1");
            if (!"1".equals(conversationType)) {
                chatId = json.path("conversationId").asText(senderId);
            }
            
            // 存储 session_webhook
            String sessionWebhook = json.path("sessionWebhook").asText(null);
            if (sessionWebhook != null && !sessionWebhook.isEmpty()) {
                sessionWebhooks.put(chatId, sessionWebhook);
            }
            
            logger.info("收到钉钉消息", Map.of(
                "sender_nick", senderNick,
                "sender_id", senderId,
                "chat_id", chatId,
                "preview", StringUtils.truncate(content, 50)
            ));
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            metadata.put("sender_name", senderNick);
            metadata.put("conversation_id", json.path("conversationId").asText(""));
            metadata.put("conversation_type", conversationType);
            metadata.put("platform", "dingtalk");
            if (sessionWebhook != null) {
                metadata.put("session_webhook", sessionWebhook);
            }
            
            // 通过父类统一处理权限校验和消息发布
            InboundMessage inboundMsg = handleMessage(senderId, chatId, content, null, metadata);
            if (inboundMsg == null) {
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"权限不足\"}}";
            }
            
            // 返回空响应，消息将通过消息总线异步回复
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"\"}}";
            
        } catch (Exception e) {
            logger.error("处理钉钉消息时出错", Map.of("error", e.getMessage()));
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"处理消息时出错\"}}";
        }
    }
    
    /**
     * 发送 Markdown 格式消息
     * 
     * @param webhook Webhook 地址
     * @param title 消息标题
     * @param content Markdown 内容
     */
    private void sendMarkdownMessage(String webhook, String title, String content) {
        ObjectNode markdown = objectMapper.createObjectNode();
        markdown.put("msgtype", "markdown");
        
        ObjectNode markdownContent = markdown.putObject("markdown");
        markdownContent.put("title", title);
        markdownContent.put("text", content);
        
        try {
            String jsonBody = objectMapper.writeValueAsString(markdown);
            
            Request request = new Request.Builder()
                .url(webhook)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ChannelException("发送钉钉消息失败: HTTP " + response.code());
                }
                
                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode responseJson = objectMapper.readTree(responseBody);
                
                int errcode = responseJson.path("errcode").asInt(0);
                if (errcode != 0) {
                    String errmsg = responseJson.path("errmsg").asText("未知错误");
                    throw new ChannelException("发送钉钉消息失败: " + errmsg);
                }
                
                logger.debug("钉钉消息发送成功");
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("发送钉钉消息时出错: " + e.getMessage(), e);
        }
    }
    
    /**
     * 计算钉钉签名
     * 
     * @param secret 签名密钥
     * @param timestamp 时间戳
     * @return 签名字符串
     */
    private String calculateSignature(String secret, long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            throw new ChannelException("计算钉钉签名失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Stream 连接信息
     */
    private static class StreamConnectionInfo {
        final String endpoint;
        final String ticket;
        
        StreamConnectionInfo(String endpoint, String ticket) {
            this.endpoint = endpoint;
            this.ticket = ticket;
        }
    }
}