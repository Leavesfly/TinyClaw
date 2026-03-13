package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.SSLUtils;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 飞书通道实现 - 基于飞书开放平台
 * 
 * 提供飞书/Lark 平台的消息处理能力，支持：
 * - HTTP API 发送消息
 * - WebSocket 长连接接收消息（无需公网 IP）
 * - Webhook 接收消息（需配合外部 HTTP 服务）
 * 
 * 核心流程：
 * 1. 使用 App ID 和 App Secret 获取访问令牌
 * 2. 通过 API 发送消息
 * 3. WebSocket 模式：建立长连接接收事件推送
 * 4. Webhook 模式：接收外部 HTTP 服务推送的消息事件
 * 
 * 配置要求：
 * - App ID：飞书应用的 App ID
 * - App Secret：飞书应用的 App Secret
 * - connectionMode：连接模式，"websocket"（默认）或 "webhook"
 */
public class FeishuChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("feishu");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60000L;
    
    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    
    private final ChannelsConfig.FeishuConfig config;
    private final OkHttpClient httpClient;
    
    // 访问令牌
    private String tenantAccessToken;
    private long tokenExpireTime;

    
    // WebSocket 连接
    private WebSocket webSocket;
    private Thread heartbeatThread;
    private volatile boolean webSocketRunning;
    private long pingInterval = 30000;
    
    // 重连尝试计数
    private int reconnectAttempts = 0;
    
    /**
     * 创建飞书通道
     * 
     * @param config 飞书配置
     * @param bus 消息总线
     */
    public FeishuChannel(ChannelsConfig.FeishuConfig config, MessageBus bus) {
        super("feishu", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(SSLUtils.getDefaultSSLSocketFactory(), SSLUtils.getDefaultTrustManager())
            .build();
    }
    
    @Override
    public void start() {
        logger.info("正在启动飞书通道...");
        
        if (config.getAppId() == null || config.getAppId().isEmpty() ||
            config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            throw new ChannelException("飞书 App ID 或 App Secret 为空");
        }
        
        if (config.isWebSocketMode()) {
            startWebSocketMode();
        } else {
            startWebhookMode();
        }
        
        setRunning(true);
    }
    
    /**
     * 启动 WebSocket 模式
     */
    private void startWebSocketMode() {
        logger.info("飞书通道以 WebSocket 模式启动");
        
        try {
            refreshTenantAccessToken();
            
            String wsUrl = fetchWebSocketEndpoint();
            connectWebSocket(wsUrl);
            
            startHeartbeat();
            
            logger.info("飞书通道已启动（WebSocket 模式）");
        } catch (Exception e) {
            throw new ChannelException("启动飞书 WebSocket 模式失败", e);
        }
    }
    
    /**
     * 启动 Webhook 模式
     */
    private void startWebhookMode() {
        logger.info("飞书通道以 Webhook 模式启动");
        
        try {
            refreshTenantAccessToken();
        } catch (Exception e) {
            throw new ChannelException("启动飞书 Webhook 模式失败", e);
        }
        
        logger.info("飞书通道已启动（HTTP API 模式）");
        logger.info("请配合 Webhook 服务使用以接收消息");
    }
    
    @Override
    public void stop() {
        logger.info("正在停止飞书通道...");
        setRunning(false);
        
        if (webSocket != null) {
            webSocket.close(1000, "Shutdown");
            webSocket = null;
        }
        
        webSocketRunning = false;
        
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
        
        tenantAccessToken = null;
        
        logger.info("飞书通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            throw new IllegalStateException("飞书通道未运行");
        }
        
        // 确保令牌有效
        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpireTime) {
            refreshTenantAccessToken();
        }
        
        String chatId = message.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("Chat ID 为空");
        }
        
        // 构建消息内容
        String content = String.format("{\"text\":\"%s\"}", escapeJson(message.getContent()));
        
        // 构建请求体
        ObjectNode body = objectMapper.createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", "text");
        body.put("content", content);
        body.put("uuid", "tinyclaw-" + System.nanoTime());
        
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("序列化飞书消息失败", e);
        }
        
        // 发送消息
        String url = API_BASE_URL + "/im/v1/messages?receive_id_type=chat_id";
        
        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + tenantAccessToken)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody;
                try {
                    errorBody = response.body() != null ? response.body().string() : "";
                } catch (java.io.IOException e) {
                    errorBody = "[无法读取错误响应]";
                }
                throw new ChannelException("发送飞书消息失败: HTTP " + response.code() + " " + errorBody);
            }
            
            logger.debug("飞书消息发送成功", Map.of("chat_id", chatId));
        } catch (java.io.IOException e) {
            throw new ChannelException("发送飞书消息失败: 网络错误", e);
        }
    }
    
    /**
     * 刷新租户访问令牌
     */
    private void refreshTenantAccessToken() {
        String url = API_BASE_URL + "/auth/v3/tenant_access_token/internal";
        
        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());
        
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("序列化飞书令牌请求失败", e);
        }
        
        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody;
            try {
                responseBody = response.body() != null ? response.body().string() : "{}";
            } catch (java.io.IOException e) {
                throw new ChannelException("读取飞书令牌响应失败", e);
            }
            
            if (!response.isSuccessful()) {
                logger.error("获取飞书访问令牌失败", Map.of(
                    "status", response.code(),
                    "response", responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody
                ));
                throw new ChannelException("获取飞书访问令牌失败: HTTP " + response.code() + ", 响应: " + responseBody);
            }
            JsonNode json = objectMapper.readTree(responseBody);
            
            int code = json.path("code").asInt(-1);
            if (code != 0) {
                String msg = json.path("msg").asText("未知错误");
                throw new ChannelException("获取飞书访问令牌失败: " + msg);
            }
            
            tenantAccessToken = json.path("tenant_access_token").asText(null);
            int expire = json.path("expire").asInt(7200);
            tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L;
            
            if (tenantAccessToken == null) {
                throw new ChannelException("获取飞书访问令牌失败: 响应中无 tenant_access_token");
            }
            
            logger.debug("飞书访问令牌已刷新", Map.of("expire", expire));
        } catch (java.io.IOException e) {
            throw new ChannelException("获取飞书访问令牌失败: 网络错误", e);
        }
    }
    
    /**
     * 获取 WebSocket 端点
     */
    private String fetchWebSocketEndpoint() {
        String url = API_BASE_URL + "/callback/ws/endpoint";
        
        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("序列化飞书 WebSocket 端点请求失败", e);
        }
        
        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + tenantAccessToken)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ChannelException("获取飞书 WebSocket 端点失败: HTTP " + response.code());
            }
            
            String responseBody;
            try {
                responseBody = response.body() != null ? response.body().string() : "{}";
            } catch (java.io.IOException e) {
                throw new ChannelException("读取飞书 WebSocket 端点响应失败", e);
            }
            JsonNode json = objectMapper.readTree(responseBody);
            
            int code = json.path("code").asInt(-1);
            if (code != 0) {
                String msg = json.path("msg").asText("未知错误");
                throw new ChannelException("获取飞书 WebSocket 端点失败: " + msg);
            }
            
            JsonNode data = json.path("data");
            String wsUrl = data.path("URL").asText(null);
            
            if (wsUrl == null) {
                throw new ChannelException("获取飞书 WebSocket 端点失败: 响应中无 URL");
            }
            
            // 解析客户端配置
            JsonNode clientConfig = data.path("ClientConfig");
            if (clientConfig.has("PingInterval")) {
                pingInterval = clientConfig.get("PingInterval").asLong(30000);
            }
            
            logger.debug("飞书 WebSocket 端点已获取", Map.of("url", wsUrl, "ping_interval", pingInterval));
            return wsUrl;
        } catch (java.io.IOException e) {
            throw new ChannelException("获取飞书 WebSocket 端点失败: 网络错误", e);
        }
    }
    
    /**
     * 连接 WebSocket
     */
    private void connectWebSocket(String wsUrl) {
        webSocketRunning = true;
        
        Request request = new Request.Builder()
            .url(wsUrl)
            .build();
        
        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("飞书 WebSocket 连接已建立");
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleWebSocketMessage(text);
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                logger.info("飞书 WebSocket 连接正在关闭", Map.of("code", String.valueOf(code), "reason", reason));
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("飞书 WebSocket 连接已关闭", Map.of("code", String.valueOf(code), "reason", reason));
                webSocketRunning = false;
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("飞书 WebSocket 连接失败", Map.of("error", t.getMessage()));
                webSocketRunning = false;
                
                if (isRunning()) {
                    scheduleReconnect();
                }
            }
        };
        
        webSocket = httpClient.newWebSocket(request, listener);
    }
    
    /**
     * 启动心跳线程
     */
    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (webSocketRunning && isRunning()) {
                try {
                    Thread.sleep(pingInterval);
                    
                    if (webSocketRunning && webSocket != null) {
                        ObjectNode pingMessage = objectMapper.createObjectNode();
                        pingMessage.put("type", "ping");
                        webSocket.send(pingMessage.toString());
                        logger.debug("已发送 WebSocket ping 心跳");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("发送 WebSocket ping 心跳失败", Map.of("error", e.getMessage()));
                }
            }
        });
        
        heartbeatThread.setDaemon(true);
        heartbeatThread.setName("FeishuWebSocketHeartbeat");
        heartbeatThread.start();
    }
    
    /**
     * 计划重连
     */
    private void scheduleReconnect() {
        // 检查重连次数限制
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.error("飞书 WebSocket 重连次数已达上限，停止重连", Map.of(
                    "attempts", String.valueOf(reconnectAttempts),
                    "max_attempts", String.valueOf(MAX_RECONNECT_ATTEMPTS)
            ));
            return;
        }
        
        // 计算指数退避延迟
        long delay = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << reconnectAttempts), MAX_RECONNECT_DELAY_MS);
        
        new Thread(() -> {
            try {
                Thread.sleep(delay);
                
                if (isRunning()) {
                    reconnectAttempts++;
                    logger.info("尝试重新连接飞书 WebSocket", Map.of(
                            "attempt", String.valueOf(reconnectAttempts),
                            "max_attempts", String.valueOf(MAX_RECONNECT_ATTEMPTS),
                            "delay_ms", String.valueOf(delay)
                    ));
                    
                    try {
                        refreshTenantAccessToken();
                        String wsUrl = fetchWebSocketEndpoint();
                        connectWebSocket(wsUrl);
                        startHeartbeat();
                        
                        // 连接成功后重置重连计数
                        reconnectAttempts = 0;
                        logger.info("飞书 WebSocket 重连成功");
                    } catch (Exception e) {
                        logger.error("重连飞书 WebSocket 失败", Map.of(
                                "attempt", String.valueOf(reconnectAttempts),
                                "error", e.getMessage()
                        ));
                        scheduleReconnect();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private void handleWebSocketMessage(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            String type = json.path("type").asText("");
            
            if ("pong".equals(type)) {
                return;
            }
            
            if ("event".equals(type)) {
                handleIncomingMessage(text);
            }
        } catch (Exception e) {
            logger.error("处理 WebSocket 消息时出错", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 处理接收到的飞书消息事件（由外部 Webhook 调用）
     * 
     * @param messageJson 消息 JSON 字符串
     */
    public void handleIncomingMessage(String messageJson) {
        try {
            JsonNode json = objectMapper.readTree(messageJson);
            
            // 提取消息信息
            JsonNode event = json.path("event");
            JsonNode message = event.path("message");
            JsonNode sender = event.path("sender");
            
            String chatId = message.path("chat_id").asText(null);
            if (chatId == null || chatId.isEmpty()) {
                return;
            }
            
            // 提取发送者 ID
            String senderId = extractSenderId(sender);
            if (senderId.isEmpty()) {
                senderId = "unknown";
            }
            
            // 提取消息内容
            String content = extractMessageContent(message);
            if (content.isEmpty()) {
                content = "[空消息]";
            }
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            if (message.has("message_id")) {
                metadata.put("message_id", message.get("message_id").asText());
            }
            if (message.has("message_type")) {
                metadata.put("message_type", message.get("message_type").asText());
            }
            if (message.has("chat_type")) {
                metadata.put("chat_type", message.get("chat_type").asText());
            }
            if (sender.has("tenant_key")) {
                metadata.put("tenant_key", sender.get("tenant_key").asText());
            }
            
            logger.info("收到飞书消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "preview", StringUtils.truncate(content, 80)
            ));
            
            // 通过父类统一处理权限校验和消息发布
            handleMessage(senderId, chatId, content, null, metadata);
            
        } catch (Exception e) {
            logger.error("处理飞书消息时出错", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 提取发送者 ID
     */
    private String extractSenderId(JsonNode sender) {
        if (sender == null || !sender.has("sender_id")) {
            return "";
        }
        
        JsonNode senderId = sender.get("sender_id");
        
        if (senderId.has("user_id") && !senderId.get("user_id").asText().isEmpty()) {
            return senderId.get("user_id").asText();
        }
        if (senderId.has("open_id") && !senderId.get("open_id").asText().isEmpty()) {
            return senderId.get("open_id").asText();
        }
        if (senderId.has("union_id") && !senderId.get("union_id").asText().isEmpty()) {
            return senderId.get("union_id").asText();
        }
        
        return "";
    }
    
    /**
     * 提取消息内容
     */
    private String extractMessageContent(JsonNode message) {
        if (message == null || !message.has("content")) {
            return "";
        }
        
        String contentStr = message.get("content").asText("");
        
        // 处理文本消息
        if ("text".equals(message.path("message_type").asText(""))) {
            try {
                JsonNode contentNode = objectMapper.readTree(contentStr);
                if (contentNode.has("text")) {
                    return contentNode.get("text").asText();
                }
            } catch (Exception e) {
                // 解析失败，返回原始内容
            }
        }
        
        return contentStr;
    }
    
    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
