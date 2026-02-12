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
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 飞书通道实现 - 基于飞书开放平台 HTTP API
 * 
 * 提供飞书/Lark 平台的消息处理能力，支持：
 * - HTTP API 发送消息
 * - Webhook 接收消息（需配合外部 HTTP 服务）
 * 
 * 核心流程：
 * 1. 使用 App ID 和 App Secret 获取访问令牌
 * 2. 通过 API 发送消息
 * 3. 接收 Webhook 推送的消息事件
 * 
 * 配置要求：
 * - App ID：飞书应用的 App ID
 * - App Secret：飞书应用的 App Secret
 */
public class FeishuChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("feishu");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    
    private final ChannelsConfig.FeishuConfig config;
    private final OkHttpClient httpClient;
    
    // 访问令牌
    private String tenantAccessToken;
    private long tokenExpireTime;
    
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
            .build();
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动飞书通道...");
        
        if (config.getAppId() == null || config.getAppId().isEmpty() ||
            config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            throw new Exception("飞书 App ID 或 App Secret 为空");
        }
        
        // 获取访问令牌
        refreshTenantAccessToken();
        
        setRunning(true);
        logger.info("飞书通道已启动（HTTP API 模式）");
        logger.info("请配合 Webhook 服务使用以接收消息");
    }
    
    @Override
    public void stop() {
        logger.info("正在停止飞书通道...");
        setRunning(false);
        tenantAccessToken = null;
        logger.info("飞书通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
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
        
        String jsonBody = objectMapper.writeValueAsString(body);
        
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
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new Exception("发送飞书消息失败: HTTP " + response.code() + " " + errorBody);
            }
            
            logger.debug("飞书消息发送成功", Map.of("chat_id", chatId));
        }
    }
    
    /**
     * 刷新租户访问令牌
     */
    private void refreshTenantAccessToken() throws Exception {
        String url = API_BASE_URL + "/auth/v3/tenant_access_token/internal";
        
        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());
        
        String jsonBody = objectMapper.writeValueAsString(body);
        
        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("获取飞书访问令牌失败: HTTP " + response.code());
            }
            
            String responseBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = objectMapper.readTree(responseBody);
            
            int code = json.path("code").asInt(-1);
            if (code != 0) {
                String msg = json.path("msg").asText("未知错误");
                throw new Exception("获取飞书访问令牌失败: " + msg);
            }
            
            tenantAccessToken = json.path("tenant_access_token").asText(null);
            int expire = json.path("expire").asInt(7200);
            tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L;
            
            if (tenantAccessToken == null) {
                throw new Exception("获取飞书访问令牌失败: 响应中无 tenant_access_token");
            }
            
            logger.debug("飞书访问令牌已刷新", Map.of("expire", expire));
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
            
            // 权限检查
            if (!isAllowed(senderId)) {
                logger.warn("消息被拒绝（不在允许列表）", Map.of(
                    "sender_id", senderId,
                    "chat_id", chatId
                ));
                return;
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
            
            // 发布到消息总线
            InboundMessage inboundMsg = new InboundMessage(
                "feishu",
                senderId,
                chatId,
                content
            );
            inboundMsg.setMetadata(metadata);
            bus.publishInbound(inboundMsg);
            
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
