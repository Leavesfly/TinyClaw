package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带有通用功能的基础通道实现
 *
 * <p>实际的 Telegram/Discord/飞书 等通道都会继承 BaseChannel，
 * 这里封装了日志、白名单校验和将外部消息转换为 InboundMessage 的公共逻辑，
 * 想理解“外部消息是怎样进入 MessageBus 的”，可以从各个具体通道的 handleMessage 调用链看起。</p>
 */
public abstract class BaseChannel implements Channel {
    
    /** 共享的 Jackson ObjectMapper */
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    
    /** JSON 媒体类型 */
    protected static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    protected final TinyClawLogger logger;
    protected final MessageBus bus;
    protected final String name;
    protected final List<String> allowList;
    protected volatile boolean running = false;
    
    /**
     * 维护每个 chatId 到当前活跃 sessionKey 的映射。
     * 当用户发送 /new 指令时，会为该 chatId 生成新的 sessionKey，
     * 旧的 session 保留不动，后续消息使用新的 sessionKey。
     */
    private final Map<String, String> activeSessionKeys = new ConcurrentHashMap<>();
    
    public BaseChannel(String name, MessageBus bus, List<String> allowList) {
        this.name = name;
        this.bus = bus;
        this.allowList = allowList;
        this.logger = TinyClawLogger.getLogger("channel." + name);
    }
    
    @Override
    public String name() {
        return name;
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public boolean isAllowed(String senderId) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        return allowList.contains(senderId);
    }
    
    private static final String COMMAND_NEW = "/new";
    
    /**
     * 处理传入的消息
     *
     * <p>统一完成权限校验、指令识别、InboundMessage 构建和消息发布。
     * 子类在完成各自平台特定的消息解析后，应调用此方法而非直接操作 bus。</p>
     *
     * <p>支持的指令：
     * <ul>
     *   <li>{@code /new} - 开启新会话，保留旧会话记录，后续消息使用全新的会话上下文</li>
     * </ul>
     * </p>
     *
     * @return 已发布的 InboundMessage；如果因权限被拒绝则返回 null
     */
    protected InboundMessage handleMessage(String senderId, String chatId, String content, 
                                           List<String> media, Map<String, String> metadata) {
        if (!isAllowed(senderId)) {
            logger.debug("Message from unauthorized sender ignored", Map.of("sender_id", senderId));
            return null;
        }
        
        // 识别 /new 指令：为该 chatId 生成新的 sessionKey
        String trimmedContent = content != null ? content.trim() : "";
        if (COMMAND_NEW.equalsIgnoreCase(trimmedContent)) {
            String newSessionKey = generateNewSessionKey(chatId);
            activeSessionKeys.put(chatId, newSessionKey);
            
            logger.info("Received /new command, new session created", Map.of(
                    "sender_id", senderId, "chat_id", chatId, "new_session_key", newSessionKey));
            
            InboundMessage msg = new InboundMessage(name, senderId, chatId, trimmedContent);
            msg.setSessionKey(newSessionKey);
            msg.setCommand(InboundMessage.COMMAND_NEW_SESSION);
            if (metadata != null) {
                msg.setMetadata(metadata);
            }
            bus.publishInbound(msg);
            return msg;
        }
        
        // 使用当前活跃的 sessionKey，如果没有则使用默认的 channel:chatId
        String sessionKey = activeSessionKeys.getOrDefault(chatId, name + ":" + chatId);
        
        InboundMessage msg = new InboundMessage(name, senderId, chatId, content);
        msg.setMedia(media);
        msg.setSessionKey(sessionKey);
        if (metadata != null) {
            msg.setMetadata(metadata);
        }
        
        bus.publishInbound(msg);
        return msg;
    }
    
    /**
     * 为指定 chatId 生成新的 sessionKey。
     * 格式为 channel:chatId:timestamp，确保每次 /new 都产生唯一的会话。
     */
    private String generateNewSessionKey(String chatId) {
        return name + ":" + chatId + ":" + System.currentTimeMillis();
    }
    
    protected void setRunning(boolean running) {
        this.running = running;
    }
    
    /**
     * 构建 JSON POST 请求（无认证头）
     * 
     * @param url 请求 URL
     * @param jsonBody JSON 请求体
     * @return OkHttp Request 对象
     */
    protected Request buildJsonPostRequest(String url, String jsonBody) {
        return new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
    }
    
    /**
     * 构建 JSON POST 请求（带认证头）
     * 
     * @param url 请求 URL
     * @param jsonBody JSON 请求体
     * @param authHeader Authorization 头的值
     * @return OkHttp Request 对象
     */
    protected Request buildJsonPostRequest(String url, String jsonBody, String authHeader) {
        return new Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
    }
    
    /**
     * 执行 HTTP 请求并返回响应体字符串
     * 
     * @param client OkHttpClient 实例
     * @param request 请求对象
     * @return 响应体字符串
     * @throws IOException 网络错误或响应失败时抛出
     */
    protected String executeRequest(OkHttpClient client, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }
            return body;
        }
    }
}
