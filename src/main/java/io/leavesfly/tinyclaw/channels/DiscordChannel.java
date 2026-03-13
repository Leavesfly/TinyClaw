package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;
import io.leavesfly.tinyclaw.voice.Transcriber;
import okhttp3.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discord 通道实现 - 基于 Discord Gateway WebSocket 协议（无第三方 SDK）
 *
 * 提供 Discord 平台的完整消息处理能力，支持：
 * - WebSocket 连接到 Discord Gateway
 * - 文本消息收发
 * - 附件文件处理
 * - 音频文件下载和转录
 * - 用户权限验证
 * - 服务器和私聊消息处理
 *
 * 核心流程：
 * 1. 连接 Discord Gateway WSS 端点
 * 2. 处理 HELLO 事件，启动心跳并发送 IDENTIFY
 * 3. 监听 MESSAGE_CREATE 事件处理消息
 * 4. 通过 Discord REST API 发送消息
 *
 * Discord Gateway Intents：
 * - GUILDS (1) | GUILD_MESSAGES (512) | DIRECT_MESSAGES (4096) | MESSAGE_CONTENT (32768)
 */
public class DiscordChannel extends BaseChannel {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("discord");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final String REST_BASE = "https://discord.com/api/v10";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    // Gateway Intents: GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT
    private static final int INTENTS = 1 | 512 | 4096 | 32768;

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac", ".wma"
    );
    private static final Set<String> AUDIO_CONTENT_TYPES = Set.of(
            "audio/", "application/ogg", "application/x-ogg"
    );

    private final ChannelsConfig.DiscordConfig config;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private Transcriber transcriber;

    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatTask;

    private String selfUserId;
    private volatile Integer lastSequence;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * 创建 Discord 通道
     *
     * @param config Discord 配置
     * @param bus    消息总线
     */
    public DiscordChannel(ChannelsConfig.DiscordConfig config, MessageBus bus) {
        super("discord", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)   // WebSocket 不设读超时
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 设置语音转录器
     *
     * @param transcriber 语音转录器实例
     */
    public void setTranscriber(Transcriber transcriber) {
        this.transcriber = transcriber;
    }

    @Override
    public void start() throws ChannelException {
        logger.info("正在启动 Discord Bot...");

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discord-heartbeat");
            t.setDaemon(true);
            return t;
        });

        connectGateway();

        // 等待 READY 事件
        int attempts = 0;
        while (!ready.get() && attempts < 60) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChannelException("Discord Bot 启动被中断", e);
            }
            attempts++;
        }

        if (!ready.get()) {
            throw new ChannelException("Discord Bot 连接超时，未收到 READY 事件");
        }

        setRunning(true);
    }

    /**
     * 建立 Discord Gateway WebSocket 连接
     */
    private void connectGateway() {
        Request request = new Request.Builder().url(GATEWAY_URL).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                logger.debug("Discord Gateway WebSocket 已连接");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleGatewayMessage(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                logger.warn("Discord Gateway 正在关闭", Map.of("code", code, "reason", reason));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                String errMsg = t.getMessage() != null ? t.getMessage() : "unknown";
                logger.error("Discord Gateway 连接失败", Map.of("error", errMsg));
                ready.set(false);
            }
        });
    }

    /**
     * 处理 Discord Gateway 收到的 JSON 消息
     */
    private void handleGatewayMessage(String text) {
        try {
            JsonNode payload = objectMapper.readTree(text);
            int op = payload.path("op").asInt();
            JsonNode d = payload.path("d");

            // 更新序列号
            if (!payload.path("s").isNull() && !payload.path("s").isMissingNode()) {
                lastSequence = payload.path("s").asInt();
            }

            switch (op) {
                case 10: // HELLO
                    long heartbeatInterval = d.path("heartbeat_interval").asLong(41250);
                    startHeartbeat(heartbeatInterval);
                    identify();
                    break;
                case 11: // HEARTBEAT ACK
                    logger.debug("Discord heartbeat ACK");
                    break;
                case 0: // DISPATCH
                    handleDispatch(payload.path("t").asText(""), d);
                    break;
                case 9: // INVALID SESSION
                    logger.error("Discord session 无效");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.error("处理 Discord Gateway 消息出错", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 启动心跳定时器
     */
    private void startHeartbeat(long intervalMs) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                ObjectNode heartbeat = objectMapper.createObjectNode();
                heartbeat.put("op", 1);
                if (lastSequence != null) {
                    heartbeat.put("d", lastSequence);
                } else {
                    heartbeat.putNull("d");
                }
                webSocket.send(objectMapper.writeValueAsString(heartbeat));
            } catch (Exception e) {
                logger.error("发送 Discord heartbeat 失败", Map.of("error", e.getMessage()));
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 发送 IDENTIFY 负载以鉴权
     */
    private void identify() throws Exception {
        ObjectNode identify = objectMapper.createObjectNode();
        identify.put("op", 2);
        ObjectNode d = identify.putObject("d");
        d.put("token", config.getToken());
        d.put("intents", INTENTS);
        ObjectNode properties = d.putObject("properties");
        properties.put("os", "linux");
        properties.put("browser", "tinyclaw");
        properties.put("device", "tinyclaw");
        webSocket.send(objectMapper.writeValueAsString(identify));
    }

    /**
     * 处理 Gateway DISPATCH 事件
     */
    private void handleDispatch(String eventType, JsonNode d) {
        switch (eventType) {
            case "READY":
                selfUserId = d.path("user").path("id").asText();
                String username = d.path("user").path("username").asText();
                logger.info("Discord Bot 连接成功", Map.of(
                        "username", username,
                        "bot_id", selfUserId
                ));
                ready.set(true);
                break;
            case "MESSAGE_CREATE":
                processMessage(d);
                break;
            default:
                break;
        }
    }

    /**
     * 处理 MESSAGE_CREATE 事件数据
     */
    private void processMessage(JsonNode message) {
        JsonNode author = message.path("author");
        // 忽略 Bot 消息
        if (author.path("bot").asBoolean(false)) return;

        String authorId = author.path("id").asText();
        if (authorId.equals(selfUserId)) return;

        String authorName = author.path("username").asText("");
        String discriminator = author.path("discriminator").asText("0");
        String senderName = !discriminator.equals("0") ? authorName + "#" + discriminator : authorName;

        StringBuilder content = new StringBuilder();
        List<String> mediaPaths = new ArrayList<>();

        // 文本内容
        String messageContent = message.path("content").asText("");
        if (!messageContent.isEmpty()) content.append(messageContent);

        // 附件处理
        JsonNode attachments = message.path("attachments");
        if (attachments.isArray()) {
            for (JsonNode attachment : attachments) {
                String url = attachment.path("url").asText();
                String filename = attachment.path("filename").asText();
                String contentType = attachment.path("content_type").asText("");

                if (isAudioFile(filename, contentType)) {
                    String localPath = downloadAttachment(url, filename);
                    if (localPath != null) {
                        mediaPaths.add(localPath);
                        String transcribedText = tryTranscribe(localPath);
                        if (content.length() > 0) content.append("\n");
                        if (transcribedText != null) {
                            content.append("[音频转录: ").append(transcribedText).append("]");
                        } else {
                            content.append("[音频: ").append(localPath).append("]");
                        }
                    } else {
                        mediaPaths.add(url);
                        if (content.length() > 0) content.append("\n");
                        content.append("[附件: ").append(url).append("]");
                    }
                } else {
                    mediaPaths.add(url);
                    if (content.length() > 0) content.append("\n");
                    content.append("[附件: ").append(url).append("]");
                }
            }
        }

        if (content.length() == 0 && mediaPaths.isEmpty()) return;
        if (content.length() == 0) content.append("[仅媒体]");

        String channelId = message.path("channel_id").asText();
        String guildId = message.path("guild_id").asText("");
        boolean isDM = guildId.isEmpty();

        String contentStr = content.toString();
        logger.info("收到 Discord 消息", Map.of(
                "sender_name", senderName,
                "sender_id", authorId,
                "channel_id", channelId,
                "preview", StringUtils.truncate(contentStr, 50)
        ));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("message_id", message.path("id").asText());
        metadata.put("user_id", authorId);
        metadata.put("username", authorName);
        metadata.put("display_name", senderName);
        metadata.put("guild_id", guildId);
        metadata.put("channel_id", channelId);
        metadata.put("is_dm", String.valueOf(isDM));

        handleMessage(authorId, channelId, contentStr, mediaPaths, metadata);
    }

    @Override
    public void stop() {
        logger.info("正在停止 Discord Bot...");
        setRunning(false);
        ready.set(false);
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (heartbeatExecutor != null) heartbeatExecutor.shutdown();
        if (webSocket != null) webSocket.close(1000, "Normal closure");
        logger.info("Discord Bot 已停止");
    }

    @Override
    public void send(OutboundMessage message) throws ChannelException {
        if (!isRunning()) {
            throw new IllegalStateException("Discord Bot 未运行");
        }

        // ... existing code ...

        if (!isRunning()) {
            throw new IllegalStateException("Discord Bot 未运行");
        }

        String channelId = message.getChatId();
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("Channel ID 为空");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("content", message.getContent());

        String url = REST_BASE + "/channels/" + channelId + "/messages";
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bot " + config.getToken())
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA_TYPE))
                    .build();
        } catch (JsonProcessingException e) {
            throw new ChannelException("序列化 Discord 消息失败", e);
        }

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody;
                try {
                    errorBody = response.body() != null ? response.body().string() : "";
                } catch (IOException e) {
                    errorBody = "无法读取错误响应";
                }
                throw new ChannelException("发送 Discord 消息失败: HTTP " + response.code() + " " + errorBody);
            }
        } catch (IOException e) {
            throw new ChannelException("发送 Discord 消息时发生网络错误", e);
        }
    }

    /**
     * 尝试语音转录
     */
    private String tryTranscribe(String localPath) {
        if (transcriber != null && transcriber.isAvailable()) {
            try {
                Transcriber.TranscriptionResult result = transcriber.transcribe(localPath);
                return result.getText();
            } catch (Exception e) {
                logger.error("音频转录失败", Map.of("error", e.getMessage()));
            }
        }
        return null;
    }

    /**
     * 判断是否为音频文件
     */
    private boolean isAudioFile(String filename, String contentType) {
        String lowerName = filename.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (lowerName.endsWith(ext)) return true;
        }
        if (contentType != null) {
            String lowerType = contentType.toLowerCase();
            for (String audioType : AUDIO_CONTENT_TYPES) {
                if (lowerType.startsWith(audioType)) return true;
            }
        }
        return false;
    }

    /**
     * 下载附件到本地临时目录
     */
    private String downloadAttachment(String url, String filename) {
        try {
            Path mediaDir = Paths.get(System.getProperty("java.io.tmpdir"), "tinyclaw_media");
            Files.createDirectories(mediaDir);
            Path localPath = mediaDir.resolve(filename);

            try (InputStream in = new URL(url).openStream();
                 FileOutputStream out = new FileOutputStream(localPath.toFile())) {
                in.transferTo(out);
            }

            logger.debug("附件下载成功", Map.of("path", localPath.toString()));
            return localPath.toString();
        } catch (Exception e) {
            logger.error("附件下载失败", Map.of("error", e.getMessage()));
            return null;
        }
    }
}