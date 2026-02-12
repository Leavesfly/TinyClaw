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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 钉钉通道实现 - 基于钉钉机器人 Webhook API
 * 
 * 提供钉钉平台的消息发送能力，支持：
 * - Webhook 消息发送
 * - 签名验证
 * - Markdown 格式消息
 * - session_webhook 回复机制
 * 
 * 核心流程：
 * 1. 使用 Client ID 和 Client Secret 配置
 * 2. 通过 Webhook 接收消息（需配合钉钉机器人配置）
 * 3. 解析消息内容并发布到消息总线
 * 4. 使用 session_webhook 发送回复
 * 
 * 配置要求：
 * - Client ID：钉钉应用的 Client ID
 * - Client Secret：钉钉应用的 Client Secret
 * - Webhook URL：机器人 Webhook 地址（可选）
 * 
 * 注意：
 * - 接收消息需要配置钉钉机器人的消息接收地址
 * - 发送消息使用 session_webhook 或配置的 Webhook
 */
public class DingTalkChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("dingtalk");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    private final ChannelsConfig.DingTalkConfig config;
    private final OkHttpClient httpClient;
    
    // 存储 session_webhook 用于回复
    private final Map<String, String> sessionWebhooks = new ConcurrentHashMap<>();
    
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动钉钉通道...");
        
        if (config.getClientId() == null || config.getClientId().isEmpty()) {
            throw new Exception("钉钉 Client ID 为空");
        }
        
        setRunning(true);
        logger.info("钉钉通道已启动（Webhook 模式）");
        logger.info("请确保已配置钉钉机器人的消息接收地址");
    }
    
    @Override
    public void stop() {
        logger.info("正在停止钉钉通道...");
        setRunning(false);
        sessionWebhooks.clear();
        logger.info("钉钉通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
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
            throw new Exception("未找到 chat " + chatId + " 的 session_webhook，无法发送消息");
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
            
            // 权限检查
            if (!isAllowed(senderId)) {
                logger.warn("消息被拒绝（不在允许列表）", Map.of(
                    "sender_id", senderId,
                    "chat_id", chatId
                ));
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"权限不足\"}}";
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
            
            // 发布到消息总线
            InboundMessage inboundMsg = new InboundMessage(
                "dingtalk",
                senderId,
                chatId,
                content
            );
            inboundMsg.setMetadata(metadata);
            bus.publishInbound(inboundMsg);
            
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
    private void sendMarkdownMessage(String webhook, String title, String content) throws Exception {
        ObjectNode markdown = objectMapper.createObjectNode();
        markdown.put("msgtype", "markdown");
        
        ObjectNode markdownContent = markdown.putObject("markdown");
        markdownContent.put("title", title);
        markdownContent.put("text", content);
        
        String jsonBody = objectMapper.writeValueAsString(markdown);
        
        Request request = new Request.Builder()
            .url(webhook)
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("发送钉钉消息失败: HTTP " + response.code());
            }
            
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonNode responseJson = objectMapper.readTree(responseBody);
            
            int errcode = responseJson.path("errcode").asInt(0);
            if (errcode != 0) {
                String errmsg = responseJson.path("errmsg").asText("未知错误");
                throw new Exception("发送钉钉消息失败: " + errmsg);
            }
            
            logger.debug("钉钉消息发送成功");
        }
    }
    
    /**
     * 计算钉钉签名
     * 
     * @param secret 签名密钥
     * @param timestamp 时间戳
     * @return 签名字符串
     */
    private String calculateSignature(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signData);
    }
    
}
