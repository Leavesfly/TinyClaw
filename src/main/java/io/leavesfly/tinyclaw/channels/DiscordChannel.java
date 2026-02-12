package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;
import io.leavesfly.tinyclaw.voice.GroqTranscriber;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

/**
 * Discord 通道实现 - 基于 JDA (Java Discord API) 的完整实现
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
 * 1. 启动时通过 WebSocket 连接到 Discord Gateway
 * 2. 收到消息后解析内容（文本、附件等）
 * 3. 音频附件自动下载并可选转录
 * 4. 将入站消息发布到消息总线
 * 5. 从消息总线接收出站消息并发送
 * 
 * 设计特点：
 * - 使用 JDA 的 Intent 系统控制权限
 * - 自动忽略自己发送的消息
 * - 支持服务器频道和私信
 * - 自动下载音频附件进行转录
 */
public class DiscordChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("discord");
    
    private final ChannelsConfig.DiscordConfig config;
    private JDA jda;
    private GroqTranscriber transcriber;
    
    // 音频文件扩展名
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
        ".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac", ".wma"
    );
    
    // 音频 MIME 类型前缀
    private static final Set<String> AUDIO_CONTENT_TYPES = Set.of(
        "audio/", "application/ogg", "application/x-ogg"
    );
    
    /**
     * 创建 Discord 通道
     * 
     * @param config Discord 配置
     * @param bus 消息总线
     */
    public DiscordChannel(ChannelsConfig.DiscordConfig config, MessageBus bus) {
        super("discord", bus, config.getAllowFrom());
        this.config = config;
    }
    
    /**
     * 设置语音转录器
     * 
     * 启用后，收到的音频附件会自动转录为文本。
     * 
     * @param transcriber Groq 语音转录器实例
     */
    public void setTranscriber(GroqTranscriber transcriber) {
        this.transcriber = transcriber;
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动 Discord Bot...");
        
        try {
            // 构建 JDA 实例，启用必要的 Intent
            jda = JDABuilder.createDefault(config.getToken())
                .enableIntents(
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.DIRECT_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT
                )
                .addEventListeners(new DiscordMessageListener())
                .build();
            
            // 等待连接就绪
            jda.awaitReady();
            
            // 获取 Bot 信息
            User botUser = jda.getSelfUser();
            logger.info("Discord Bot 连接成功", Map.of(
                "username", botUser.getName(),
                "bot_id", botUser.getId()
            ));
            
            setRunning(true);
        } catch (Exception e) {
            throw new Exception("启动 Discord Bot 失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void stop() {
        logger.info("正在停止 Discord Bot...");
        setRunning(false);
        
        if (jda != null) {
            jda.shutdown();
        }
        
        logger.info("Discord Bot 已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("Discord Bot 未运行");
        }
        
        String channelId = message.getChatId();
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("Channel ID 为空");
        }
        
        // 获取目标频道
        TextChannel textChannel = jda.getTextChannelById(channelId);
        PrivateChannel privateChannel = jda.getPrivateChannelById(channelId);
        
        // 发送消息
        try {
            if (textChannel != null) {
                textChannel.sendMessage(message.getContent()).queue();
            } else if (privateChannel != null) {
                privateChannel.sendMessage(message.getContent()).queue();
            } else {
                throw new IllegalArgumentException("找不到频道: " + channelId);
            }
        } catch (Exception e) {
            throw new Exception("发送 Discord 消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理收到的 Discord 消息
     * 
     * @param event 消息事件
     */
    private void handleMessage(MessageReceivedEvent event) {
        Message message = event.getMessage();
        User author = message.getAuthor();
        
        // 忽略自己发送的消息
        if (author.isBot()) {
            return;
        }
        
        String senderId = author.getId();
        String senderName = author.getName();
        if (author.getDiscriminator() != null && !author.getDiscriminator().equals("0")) {
            senderName += "#" + author.getDiscriminator();
        }
        
        // 权限检查
        if (!isAllowed(senderId)) {
            logger.warn("消息被拒绝（不在允许列表）", Map.of(
                "sender_id", senderId,
                "channel_id", event.getChannel().getId()
            ));
            return;
        }
        
        StringBuilder content = new StringBuilder();
        List<String> mediaPaths = new ArrayList<>();
        
        // 处理文本内容
        if (message.getContentRaw() != null && !message.getContentRaw().isEmpty()) {
            content.append(message.getContentRaw());
        }
        
        // 处理附件
        for (Message.Attachment attachment : message.getAttachments()) {
            boolean isAudio = isAudioFile(attachment.getFileName(), attachment.getContentType());
            
            if (isAudio) {
                // 下载音频文件
                String localPath = downloadAttachment(attachment.getUrl(), attachment.getFileName());
                if (localPath != null) {
                    mediaPaths.add(localPath);
                    
                    // 尝试语音转录
                    String transcribedText = null;
                    if (transcriber != null && transcriber.isAvailable()) {
                        try {
                            GroqTranscriber.TranscriptionResponse response = transcriber.transcribe(localPath);
                            transcribedText = response.getText();
                            logger.info("音频转录成功", Map.of(
                                "filename", attachment.getFileName(),
                                "text_length", transcribedText != null ? transcribedText.length() : 0
                            ));
                        } catch (Exception e) {
                            logger.error("音频转录失败", Map.of("error", e.getMessage()));
                        }
                    }
                    
                    if (content.length() > 0) content.append("\n");
                    if (transcribedText != null) {
                        content.append("[音频转录: ").append(transcribedText).append("]");
                    } else {
                        content.append("[音频: ").append(localPath).append("]");
                    }
                } else {
                    // 下载失败，使用 URL
                    mediaPaths.add(attachment.getUrl());
                    if (content.length() > 0) content.append("\n");
                    content.append("[附件: ").append(attachment.getUrl()).append("]");
                }
            } else {
                // 非音频附件，记录 URL
                mediaPaths.add(attachment.getUrl());
                if (content.length() > 0) content.append("\n");
                content.append("[附件: ").append(attachment.getUrl()).append("]");
            }
        }
        
        // 空消息检查
        if (content.length() == 0 && mediaPaths.isEmpty()) {
            return;
        }
        
        if (content.length() == 0) {
            content.append("[仅媒体]");
        }
        
        String contentStr = content.toString();
        logger.info("收到 Discord 消息", Map.of(
            "sender_name", senderName,
            "sender_id", senderId,
            "channel_id", event.getChannel().getId(),
            "preview", StringUtils.truncate(contentStr, 50)
        ));
        
        // 构建元数据
        Map<String, String> metadata = new HashMap<>();
        metadata.put("message_id", message.getId());
        metadata.put("user_id", senderId);
        metadata.put("username", author.getName());
        metadata.put("display_name", senderName);
        metadata.put("guild_id", event.getGuild() != null ? event.getGuild().getId() : "");
        metadata.put("channel_id", event.getChannel().getId());
        metadata.put("is_dm", String.valueOf(event.getChannel() instanceof PrivateChannel));
        
        // 发布到消息总线
        InboundMessage inboundMsg = new InboundMessage(
            "discord",
            senderId,
            event.getChannel().getId(),
            contentStr
        );
        inboundMsg.setMedia(mediaPaths);
        inboundMsg.setMetadata(metadata);
        bus.publishInbound(inboundMsg);
    }
    
    /**
     * 判断是否为音频文件
     */
    private boolean isAudioFile(String filename, String contentType) {
        // 检查文件扩展名
        String lowerName = filename.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        
        // 检查 MIME 类型
        if (contentType != null) {
            String lowerType = contentType.toLowerCase();
            for (String audioType : AUDIO_CONTENT_TYPES) {
                if (lowerType.startsWith(audioType)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 下载附件到本地
     */
    private String downloadAttachment(String url, String filename) {
        try {
            // 创建临时目录
            Path mediaDir = Paths.get(System.getProperty("java.io.tmpdir"), "tinyclaw_media");
            Files.createDirectories(mediaDir);
            
            Path localPath = mediaDir.resolve(filename);
            
            // 下载文件
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
    
    /**
     * Discord 消息监听器 - 处理 Discord API 回调
     */
    private class DiscordMessageListener extends ListenerAdapter {
        
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            try {
                handleMessage(event);
            } catch (Exception e) {
                logger.error("处理消息时出错", Map.of("error", e.getMessage()));
            }
        }
    }
}
