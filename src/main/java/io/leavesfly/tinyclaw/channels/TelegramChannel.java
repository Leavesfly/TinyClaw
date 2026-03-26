package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;
import io.leavesfly.tinyclaw.voice.Transcriber;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram 通道实现 - 基于 Telegram Bot HTTP API（无第三方 SDK）
 *
 * 提供 Telegram 平台的完整消息处理能力，支持：
 * - 长轮询模式接收消息（getUpdates）
 * - 文本消息收发
 * - 图片、语音、音频、文档处理
 * - 语音消息转录（配合 Transcriber）
 * - Markdown 到 HTML 格式转换
 *
 * 核心流程：
 * 1. 启动时开启后台轮询线程，循环调用 getUpdates
 * 2. 收到消息后解析内容（文本、媒体、语音等）
 * 3. 语音消息自动转录为文本
 * 4. 将入站消息发布到消息总线
 * 5. 从消息总线接收出站消息并调用 sendMessage API 发送
 */
public class TelegramChannel extends BaseChannel {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("telegram");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String API_BASE = "https://api.telegram.org/bot";

    private final ChannelsConfig.TelegramConfig config;
    private final OkHttpClient httpClient;
    private final OkHttpClient longPollClient;
    private Transcriber transcriber;
    private Thread pollingThread;
    private final AtomicLong lastUpdateId = new AtomicLong(-1);

    /**
     * 创建 Telegram 通道
     *
     * @param config Telegram 配置
     * @param bus    消息总线
     */
    public TelegramChannel(ChannelsConfig.TelegramConfig config, MessageBus bus) {
        super("telegram", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        // 长轮询需要更长的读超时
        this.longPollClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
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
        logger.info("正在启动 Telegram Bot（长轮询模式）...");

        // 验证 Token
        String meUrl = API_BASE + config.getToken() + "/getMe";
        Request request = new Request.Builder().url(meUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new ChannelException("验证 Telegram Token 失败: HTTP " + response.code());
            }
            JsonNode json;
            try {
                json = objectMapper.readTree(response.body().string());
            } catch (IOException e) {
                throw new ChannelException("解析 Telegram 响应失败", e);
            }
            if (!json.path("ok").asBoolean(false)) {
                throw new ChannelException("Telegram Token 无效: " + json.path("description").asText(""));
            }
            JsonNode result = json.path("result");
            logger.info("Telegram Bot 连接成功", Map.of(
                    "username", "@" + result.path("username").asText(""),
                    "bot_id", result.path("id").asLong()
            ));
        } catch (IOException e) {
            throw new ChannelException("验证 Telegram Token 时发生网络错误", e);
        }

        setRunning(true);

        // 启动轮询线程
        pollingThread = new Thread(this::pollUpdates, "telegram-polling");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    @Override
    public void stop() {
        logger.info("正在停止 Telegram Bot...");
        setRunning(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        logger.info("Telegram Bot 已停止");
    }

    @Override
    public void send(OutboundMessage message) throws ChannelException {
        if (!isRunning()) {
            throw new IllegalStateException("Telegram Bot 未运行");
        }

        String htmlContent = markdownToTelegramHTML(message.getContent());
        String url = API_BASE + config.getToken() + "/sendMessage";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", message.getChatId());
        body.put("text", htmlContent);
        body.put("parse_mode", "HTML");

        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA_TYPE))
                    .build();
        } catch (JsonProcessingException e) {
            throw new ChannelException("序列化 Telegram 消息失败", e);
        }

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // HTML 解析失败，降级为纯文本
                logger.warn("HTML 发送失败，降级为纯文本", Map.of("status", response.code()));

                body.put("text", message.getContent());
                Request fallback;
                try {
                    fallback = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA_TYPE))
                            .build();
                } catch (JsonProcessingException e) {
                    throw new ChannelException("序列化 Telegram 消息失败", e);
                }
                try (Response fbResp = httpClient.newCall(fallback).execute()) {
                    if (!fbResp.isSuccessful()) {
                        throw new ChannelException("发送 Telegram 消息失败: HTTP " + fbResp.code());
                    }
                } catch (IOException e) {
                    throw new ChannelException("发送 Telegram 消息时发生网络错误", e);
                }
            }
        } catch (IOException e) {
            throw new ChannelException("发送 Telegram 消息时发生网络错误", e);
        }
    }

    /**
     * 长轮询循环，在后台线程中运行
     */
    private void pollUpdates() {
        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            try {
                long offset = lastUpdateId.get() + 1;
                String url = API_BASE + config.getToken()
                        + "/getUpdates?offset=" + offset
                        + "&timeout=30&allowed_updates=[\"message\"]";

                Request request = new Request.Builder().url(url).build();
                try (Response response = longPollClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) continue;

                    JsonNode json = objectMapper.readTree(response.body().string());
                    if (!json.path("ok").asBoolean(false)) continue;

                    JsonNode results = json.path("result");
                    for (JsonNode update : results) {
                        long updateId = update.path("update_id").asLong();
                        if (updateId > lastUpdateId.get()) {
                            lastUpdateId.set(updateId);
                        }
                        processUpdate(update);
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (isRunning()) {
                    logger.error("Telegram 轮询出错", Map.of("error", e.getMessage()));
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * 处理单条 Telegram Update
     */
    private void processUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;

        JsonNode from = message.path("from");
        if (from.isMissingNode()) return;

        long userId = from.path("id").asLong();
        String username = from.path("username").asText("");
        String senderId = username.isEmpty() ? String.valueOf(userId) : userId + "|" + username;

        long chatId = message.path("chat").path("id").asLong();
        StringBuilder content = new StringBuilder();
        List<String> mediaPaths = new ArrayList<>();

        // 文本消息
        String text = message.path("text").asText("");
        if (!text.isEmpty()) content.append(text);

        // 图片
        JsonNode photos = message.path("photo");
        if (photos.isArray() && photos.size() > 0) {
            JsonNode largest = photos.get(photos.size() - 1);
            String localPath = downloadFile(largest.path("file_id").asText(), ".jpg");
            if (localPath != null) {
                mediaPaths.add(localPath);
                if (content.length() > 0) content.append("\n");
                content.append("[图片: ").append(localPath).append("]");
            }
        }

        // 图片说明
        String caption = message.path("caption").asText("");
        if (!caption.isEmpty()) {
            if (content.length() > 0) content.append("\n");
            content.append(caption);
        }

        // 语音消息
        JsonNode voice = message.path("voice");
        if (!voice.isMissingNode()) {
            String localPath = downloadFile(voice.path("file_id").asText(), ".ogg");
            if (localPath != null) {
                mediaPaths.add(localPath);
                String transcribedText = tryTranscribe(localPath);
                if (content.length() > 0) content.append("\n");
                if (transcribedText != null) {
                    content.append("[语音转录: ").append(transcribedText).append("]");
                } else {
                    content.append("[语音: ").append(localPath).append("]");
                }
            }
        }

        // 音频文件
        JsonNode audio = message.path("audio");
        if (!audio.isMissingNode()) {
            String localPath = downloadFile(audio.path("file_id").asText(), ".mp3");
            if (localPath != null) {
                mediaPaths.add(localPath);
                if (content.length() > 0) content.append("\n");
                content.append("[音频: ").append(localPath).append("]");
            }
        }

        // 文档
        JsonNode document = message.path("document");
        if (!document.isMissingNode()) {
            String localPath = downloadFile(document.path("file_id").asText(), "");
            if (localPath != null) {
                mediaPaths.add(localPath);
                if (content.length() > 0) content.append("\n");
                content.append("[文件: ").append(localPath).append("]");
            }
        }

        if (content.length() == 0) content.append("[空消息]");

        String contentStr = content.toString();
        logger.info("收到 Telegram 消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "preview", StringUtils.truncate(contentStr, 50)
        ));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("message_id", String.valueOf(message.path("message_id").asLong()));
        metadata.put("user_id", String.valueOf(userId));
        metadata.put("username", username);
        metadata.put("first_name", from.path("first_name").asText(""));
        boolean isGroup = !message.path("chat").path("type").asText("").equals("private");
        metadata.put("is_group", String.valueOf(isGroup));

        handleMessage(senderId, String.valueOf(chatId), contentStr, mediaPaths, metadata);
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
                logger.error("语音转录失败", Map.of("error", e.getMessage()));
            }
        }
        return null;
    }

    /**
     * 下载 Telegram 文件到本地临时目录
     *
     * @param fileId    Telegram 文件 ID
     * @param extension 文件扩展名
     * @return 本地文件路径，失败返回 null
     */
    private String downloadFile(String fileId, String extension) {
        try {
            // 获取文件路径
            String getFileUrl = API_BASE + config.getToken() + "/getFile?file_id=" + fileId;
            Request request = new Request.Builder().url(getFileUrl).build();
            String filePath;
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JsonNode json = objectMapper.readTree(response.body().string());
                filePath = json.path("result").path("file_path").asText(null);
                if (filePath == null) return null;
            }

            String fileUrl = "https://api.telegram.org/file/bot" + config.getToken() + "/" + filePath;

            Path mediaDir = Paths.get(System.getProperty("java.io.tmpdir"), "tinyclaw_media");
            Files.createDirectories(mediaDir);

            String fileName = fileId.substring(0, Math.min(16, fileId.length())) + extension;
            Path localPath = mediaDir.resolve(fileName);

            try (InputStream in = new URL(fileUrl).openStream();
                 FileOutputStream out = new FileOutputStream(localPath.toFile())) {
                in.transferTo(out);
            }

            logger.debug("文件下载成功", Map.of("path", localPath.toString()));
            return localPath.toString();
        } catch (Exception e) {
            logger.error("文件下载失败", Map.of("error", e.getMessage()));
            return null;
        }
    }

    /**
     * 将 Markdown 转换为 Telegram HTML 格式
     *
     * @param markdown Markdown 文本
     * @return Telegram HTML 格式文本
     */
    private String markdownToTelegramHTML(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }

        String html = markdown;

        // 处理代码块（先处理，避免被其他规则影响）
        Pattern codeBlockPattern = Pattern.compile("```[\\w]*\\n?([\\s\\S]*?)```");
        Map<String, String> codeBlocks = new LinkedHashMap<>();
        int codeIndex = 0;
        Matcher codeMatcher = codeBlockPattern.matcher(html);
        while (codeMatcher.find()) {
            String placeholder = "\u0000CB" + codeIndex + "\u0000";
            codeBlocks.put(placeholder, escapeHtml(codeMatcher.group(1)));
            html = html.replaceFirst(Pattern.quote(codeMatcher.group(0)), placeholder);
            codeIndex++;
        }

        // 处理行内代码
        Pattern inlineCodePattern = Pattern.compile("`([^`]+)`");
        Map<String, String> inlineCodes = new LinkedHashMap<>();
        codeIndex = 0;
        Matcher inlineMatcher = inlineCodePattern.matcher(html);
        while (inlineMatcher.find()) {
            String placeholder = "\u0000IC" + codeIndex + "\u0000";
            inlineCodes.put(placeholder, escapeHtml(inlineMatcher.group(1)));
            html = html.replaceFirst(Pattern.quote(inlineMatcher.group(0)), placeholder);
            codeIndex++;
        }

        // 移除标题标记
        html = html.replaceAll("^#{1,6}\\s+", "");
        // 移除引用标记
        html = html.replaceAll("^>\\s*", "");

        // 转义 HTML
        html = escapeHtml(html);

        // 处理链接
        html = html.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        // 处理粗体
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("__(.+?)__", "<b>$1</b>");
        // 处理斜体
        html = html.replaceAll("_([^_]+)_", "<i>$1</i>");
        // 处理删除线
        html = html.replaceAll("~~(.+?)~~", "<s>$1</s>");
        // 处理列表
        html = html.replaceAll("^[-*]\\s+", "• ");

        // 恢复代码块
        for (Map.Entry<String, String> entry : codeBlocks.entrySet()) {
            html = html.replace(entry.getKey(), "<pre><code>" + entry.getValue() + "</code></pre>");
        }
        // 恢复行内代码
        for (Map.Entry<String, String> entry : inlineCodes.entrySet()) {
            html = html.replace(entry.getKey(), "<code>" + entry.getValue() + "</code>");
        }

        return html;
    }

    /**
     * HTML 转义
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}