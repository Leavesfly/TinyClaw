package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;
import io.leavesfly.tinyclaw.voice.GroqTranscriber;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram 通道实现 - 基于 Telegram Bot API 的完整实现
 * 
 * 提供 Telegram 平台的完整消息处理能力，支持：
 * - 长轮询模式接收消息
 * - 文本消息收发
 * - 图片、语音、音频、文档处理
 * - 语音消息转录（配合 GroqTranscriber）
 * - "正在输入..." 动画指示器
 * - Markdown 到 HTML 格式转换
 * 
 * 核心流程：
 * 1. 启动时通过长轮询连接 Telegram Bot API
 * 2. 收到消息后解析内容（文本、媒体、语音等）
 * 3. 语音消息自动转录为文本
 * 4. 将入站消息发布到消息总线
 * 5. 从消息总线接收出站消息并发送
 * 
 * 设计特点：
 * - 使用占位消息实现"正在思考"动画
 * - 支持 HTML 格式消息发送
 * - 自动下载媒体文件到临时目录
 * - 权限验证（allowFrom 配置）
 */
public class TelegramChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("telegram");
    
    private final ChannelsConfig.TelegramConfig config;
    private TelegramBotsApi botsApi;
    private TelegramBot bot;
    private GroqTranscriber transcriber;
    
    // 占位消息管理 - chatId -> messageId
    private final Map<String, Integer> placeholders = new ConcurrentHashMap<>();
    // 思考动画停止信号 - chatId -> stopChannel
    private final Map<String, Boolean> stopThinking = new ConcurrentHashMap<>();
    
    /**
     * 创建 Telegram 通道
     * 
     * @param config Telegram 配置
     * @param bus 消息总线
     */
    public TelegramChannel(ChannelsConfig.TelegramConfig config, MessageBus bus) {
        super("telegram", bus, config.getAllowFrom());
        this.config = config;
    }
    
    /**
     * 设置语音转录器
     * 
     * 启用后，收到的语音消息会自动转录为文本。
     * 
     * @param transcriber Groq 语音转录器实例
     */
    public void setTranscriber(GroqTranscriber transcriber) {
        this.transcriber = transcriber;
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动 Telegram Bot（长轮询模式）...");
        
        try {
            // 创建 Bot API 实例
            botsApi = new TelegramBotsApi(DefaultBotSession.class);
            
            // 创建并注册 Bot
            bot = new TelegramBot();
            botsApi.registerBot(bot);
            
            // 获取 Bot 信息
            User botUser = bot.execute(new GetMe());
            logger.info("Telegram Bot 连接成功", Map.of(
                "username", "@" + botUser.getUserName(),
                "bot_id", botUser.getId()
            ));
            
            setRunning(true);
        } catch (TelegramApiException e) {
            throw new Exception("启动 Telegram Bot 失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void stop() {
        logger.info("正在停止 Telegram Bot...");
        setRunning(false);
        
        if (bot != null) {
            // 长轮询会在 session 关闭时自动停止
        }
        
        logger.info("Telegram Bot 已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("Telegram Bot 未运行");
        }
        
        long chatId;
        try {
            chatId = Long.parseLong(message.getChatId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的 Chat ID: " + message.getChatId());
        }
        
        // 停止思考动画
        String chatIdStr = message.getChatId();
        stopThinking.put(chatIdStr, true);
        placeholders.remove(chatIdStr);
        
        // 转换 Markdown 到 Telegram HTML 格式
        String htmlContent = markdownToTelegramHTML(message.getContent());
        
        try {
            // 尝试发送消息
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText(htmlContent);
            sendMessage.setParseMode("HTML");
            
            bot.execute(sendMessage);
        } catch (TelegramApiException e) {
            // HTML 解析失败，降级为纯文本
            logger.warn("HTML 解析失败，使用纯文本发送", Map.of("error", e.getMessage()));
            try {
                SendMessage sendMessage = new SendMessage();
                sendMessage.setChatId(chatId);
                sendMessage.setText(message.getContent());
                bot.execute(sendMessage);
            } catch (TelegramApiException ex) {
                throw new Exception("发送 Telegram 消息失败: " + ex.getMessage(), ex);
            }
        }
    }
    
    /**
     * 处理收到的 Telegram 消息
     * 
     * 解析消息内容（文本、媒体、语音等），发布到消息总线。
     * 
     * @param update Telegram 更新对象
     */
    private void handleMessage(Update update) {
        Message message = update.getMessage();
        if (message == null) {
            return;
        }
        
        User user = message.getFrom();
        if (user == null) {
            return;
        }
        
        // 构建 sender ID
        String senderId = String.valueOf(user.getId());
        if (user.getUserName() != null && !user.getUserName().isEmpty()) {
            senderId = user.getId() + "|" + user.getUserName();
        }
        
        // 权限检查
        if (!isAllowed(senderId)) {
            logger.warn("消息被拒绝（不在允许列表）", Map.of(
                "sender_id", senderId,
                "chat_id", message.getChatId()
            ));
            return;
        }
        
        long chatId = message.getChatId();
        StringBuilder content = new StringBuilder();
        List<String> mediaPaths = new ArrayList<>();
        
        // 处理文本消息
        if (message.getText() != null && !message.getText().isEmpty()) {
            content.append(message.getText());
        }
        
        // 处理图片
        if (message.hasPhoto()) {
            List<PhotoSize> photos = message.getPhoto();
            if (!photos.isEmpty()) {
                PhotoSize largestPhoto = photos.get(photos.size() - 1);
                String localPath = downloadFile(largestPhoto.getFileId(), ".jpg");
                if (localPath != null) {
                    mediaPaths.add(localPath);
                    if (content.length() > 0) content.append("\n");
                    content.append("[图片: ").append(localPath).append("]");
                }
            }
        }
        
        // 处理图片说明
        if (message.getCaption() != null && !message.getCaption().isEmpty()) {
            if (content.length() > 0) content.append("\n");
            content.append(message.getCaption());
        }
        
        // 处理语音消息
        if (message.hasVoice()) {
            Voice voice = message.getVoice();
            String localPath = downloadFile(voice.getFileId(), ".ogg");
            if (localPath != null) {
                mediaPaths.add(localPath);
                
                // 尝试语音转录
                String transcribedText = null;
                if (transcriber != null && transcriber.isAvailable()) {
                    try {
                        GroqTranscriber.TranscriptionResponse response = transcriber.transcribe(localPath);
                        transcribedText = response.getText();
                        logger.info("语音转录成功", Map.of(
                            "duration_seconds", voice.getDuration(),
                            "text_length", transcribedText != null ? transcribedText.length() : 0
                        ));
                    } catch (Exception e) {
                        logger.error("语音转录失败", Map.of("error", e.getMessage()));
                    }
                }
                
                if (content.length() > 0) content.append("\n");
                if (transcribedText != null) {
                    content.append("[语音转录: ").append(transcribedText).append("]");
                } else {
                    content.append("[语音: ").append(localPath).append("]");
                }
            }
        }
        
        // 处理音频文件
        if (message.hasAudio()) {
            Audio audio = message.getAudio();
            String localPath = downloadFile(audio.getFileId(), ".mp3");
            if (localPath != null) {
                mediaPaths.add(localPath);
                if (content.length() > 0) content.append("\n");
                content.append("[音频: ").append(localPath).append("]");
            }
        }
        
        // 处理文档
        if (message.hasDocument()) {
            Document doc = message.getDocument();
            String localPath = downloadFile(doc.getFileId(), "");
            if (localPath != null) {
                mediaPaths.add(localPath);
                if (content.length() > 0) content.append("\n");
                content.append("[文件: ").append(localPath).append("]");
            }
        }
        
        // 空消息处理
        if (content.length() == 0) {
            content.append("[空消息]");
        }
        
        String contentStr = content.toString();
        logger.info("收到 Telegram 消息", Map.of(
            "sender_id", senderId,
            "chat_id", chatId,
            "preview", StringUtils.truncate(contentStr, 50)
        ));
        
        // 发送"正在输入"状态
        // Note: 在完整实现中可添加 ChatAction，这里简化处理
        
        // 构建元数据
        Map<String, String> metadata = new HashMap<>();
        metadata.put("message_id", String.valueOf(message.getMessageId()));
        metadata.put("user_id", String.valueOf(user.getId()));
        metadata.put("username", user.getUserName());
        metadata.put("first_name", user.getFirstName());
        metadata.put("is_group", String.valueOf(!message.getChat().isUserChat()));
        
        // 发布到消息总线
        InboundMessage inboundMsg = new InboundMessage(
            "telegram",
            senderId,
            String.valueOf(chatId),
            contentStr
        );
        inboundMsg.setMedia(mediaPaths);
        inboundMsg.setMetadata(metadata);
        bus.publishInbound(inboundMsg);
    }
    
    /**
     * 下载 Telegram 文件到本地
     * 
     * @param fileId Telegram 文件 ID
     * @param extension 文件扩展名
     * @return 本地文件路径，失败返回 null
     */
    private String downloadFile(String fileId, String extension) {
        try {
            // 获取文件 URL
            org.telegram.telegrambots.meta.api.objects.File telegramFile = 
                bot.execute(new org.telegram.telegrambots.meta.api.methods.GetFile(fileId));
            String fileUrl = telegramFile.getFileUrl(bot.getBotToken());
            
            // 创建临时目录
            Path mediaDir = Paths.get(System.getProperty("java.io.tmpdir"), "tinyclaw_media");
            Files.createDirectories(mediaDir);
            
            // 生成文件名
            String fileName = fileId.substring(0, Math.min(16, fileId.length())) + extension;
            Path localPath = mediaDir.resolve(fileName);
            
            // 下载文件
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
    
    /**
     * Telegram Bot 内部类 - 处理 Telegram API 回调
     */
    private class TelegramBot extends TelegramLongPollingBot {
        
        @Override
        public String getBotToken() {
            return config.getToken();
        }
        
        @Override
        public String getBotUsername() {
            // 可选，返回 null 也可以工作
            return null;
        }
        
        @Override
        public void onUpdateReceived(Update update) {
            try {
                handleMessage(update);
            } catch (Exception e) {
                logger.error("处理消息时出错", Map.of("error", e.getMessage()));
            }
        }
    }
}
