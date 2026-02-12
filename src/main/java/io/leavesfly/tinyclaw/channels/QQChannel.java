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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * QQ 通道实现 - 基于腾讯 QQ 开放平台 API
 * 
 * 提供腾讯 QQ 机器人的消息处理能力：
 * - 私聊消息收发
 * - 群聊 @ 消息处理
 * - WebSocket 消息接收（需配合网关服务）
 * 
 * 核心流程：
 * 1. 使用 App ID 和 App Secret 获取访问令牌
 * 2. 通过 WebSocket 或 HTTP 接收消息事件
 * 3. 解析消息内容并发布到消息总线
 * 4. 使用 API 发送回复消息
 * 
 * 配置要求：
 * - App ID：QQ 机器人的 App ID
 * - App Secret：QQ 机器人的 App Secret
 * 
 * 注意：
 * - 需要在 QQ 开放平台注册机器人应用
 * - 消息接收需要配合网关服务使用
 */
public class QQChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("qq");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    private static final String API_BASE_URL = "https://api.sgroup.qq.com";
    
    private final ChannelsConfig.QQConfig config;
    private final OkHttpClient httpClient;
    
    // 访问令牌
    private String accessToken;
    private long tokenExpireTime;
    
    // 已处理消息 ID（去重）
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();
    
    /**
     * 创建 QQ 通道
     * 
     * @param config QQ 配置
     * @param bus 消息总线
     */
    public QQChannel(ChannelsConfig.QQConfig config, MessageBus bus) {
        super("qq", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动 QQ 通道...");
        
        if (config.getAppId() == null || config.getAppId().isEmpty() ||
            config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            throw new Exception("QQ App ID 或 App Secret 为空");
        }
        
        // 获取访问令牌
        refreshAccessToken();
        
        setRunning(true);
        logger.info("QQ 通道已启动（API 模式）");
        logger.info("请配合网关服务使用以接收消息");
    }
    
    @Override
    public void stop() {
        logger.info("正在停止 QQ 通道...");
        setRunning(false);
        accessToken = null;
        processedIds.clear();
        logger.info("QQ 通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("QQ 通道未运行");
        }
        
        // 确保令牌有效
        if (accessToken == null || System.currentTimeMillis() >= tokenExpireTime) {
            refreshAccessToken();
        }
        
        // 构建消息体
        ObjectNode body = objectMapper.createObjectNode();
        body.put("content", message.getContent());
        
        String jsonBody = objectMapper.writeValueAsString(body);
        
        // 发送私聊消息
        String url = API_BASE_URL + "/v2/users/" + message.getChatId() + "/messages";
        
        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "QQBot " + accessToken)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new Exception("发送 QQ 消息失败: HTTP " + response.code() + " " + errorBody);
            }
            
            logger.debug("QQ 消息发送成功", Map.of("chat_id", message.getChatId()));
        }
    }
    
    /**
     * 刷新访问令牌
     */
    private void refreshAccessToken() throws Exception {
        String url = "https://bots.qq.com/app/getAppAccessToken";
        
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appId", config.getAppId());
        body.put("clientSecret", config.getAppSecret());
        
        String jsonBody = objectMapper.writeValueAsString(body);
        
        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("获取 QQ 访问令牌失败: HTTP " + response.code());
            }
            
            String responseBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = objectMapper.readTree(responseBody);
            
            accessToken = json.path("access_token").asText(null);
            int expiresIn = json.path("expires_in").asInt(7200);
            tokenExpireTime = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
            
            if (accessToken == null) {
                throw new Exception("获取 QQ 访问令牌失败: 响应中无 access_token");
            }
            
            logger.debug("QQ 访问令牌已刷新", Map.of("expires_in", expiresIn));
        }
    }
    
    /**
     * 处理接收到的消息（由外部网关调用）
     * 
     * @param messageJson 消息 JSON 字符串
     */
    public void handleIncomingMessage(String messageJson) {
        try {
            JsonNode json = objectMapper.readTree(messageJson);
            
            String messageId = json.path("id").asText(null);
            if (messageId == null) {
                return;
            }
            
            // 去重检查
            if (processedIds.contains(messageId)) {
                return;
            }
            processedIds.add(messageId);
            
            // 清理过期的消息 ID
            if (processedIds.size() > 10000) {
                processedIds.clear();
            }
            
            // 提取发送者信息
            JsonNode author = json.path("author");
            String senderId = author.path("id").asText("unknown");
            if (senderId.equals("unknown")) {
                return;
            }
            
            // 权限检查
            if (!isAllowed(senderId)) {
                logger.warn("消息被拒绝（不在允许列表）", Map.of("sender_id", senderId));
                return;
            }
            
            // 提取消息内容
            String content = json.path("content").asText("");
            if (content.isEmpty()) {
                return;
            }
            
            // 确定 chat ID
            String chatId = senderId;
            String groupId = json.path("group_id").asText(null);
            if (groupId != null && !groupId.isEmpty()) {
                chatId = groupId;
            }
            
            logger.info("收到 QQ 消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "length", content.length()
            ));
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            metadata.put("message_id", messageId);
            if (groupId != null) {
                metadata.put("group_id", groupId);
            }
            
            // 发布到消息总线
            InboundMessage inboundMsg = new InboundMessage(
                "qq",
                senderId,
                chatId,
                content
            );
            inboundMsg.setMetadata(metadata);
            bus.publishInbound(inboundMsg);
            
        } catch (Exception e) {
            logger.error("处理 QQ 消息时出错", Map.of("error", e.getMessage()));
        }
    }
}
