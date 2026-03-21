package io.leavesfly.tinyclaw.bus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 来自外部通道的入站消息
 */
public class InboundMessage {

    /** 指令常量：开启新会话 */
    public static final String COMMAND_NEW_SESSION = "new_session";

    private String channel;
    private String senderId;
    private String chatId;
    private String content;
    private List<String> media;
    private String command;
    private Map<String, String> metadata;

    /**
     * 消息到达总线的时间戳，用于链路追踪和超时判断
     */
    private final Instant receivedAt = Instant.now();

    public InboundMessage() {
    }

    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
    }

    // Getter 和 Setter 方法

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getMedia() {
        return media;
    }

    public void setMedia(List<String> media) {
        this.media = media;
    }

    /**
     * 获取会话键，由 channel 和 chatId 动态计算得出，保证始终与当前字段一致。
     * 调用方可通过 setSessionKey 覆盖（如 new_session 场景需要指定新的 sessionKey）。
     */
    public String getSessionKey() {
        if (sessionKeyOverride != null) {
            return sessionKeyOverride;
        }
        if (channel != null && chatId != null) {
            return channel + ":" + chatId;
        }
        return null;
    }

    /**
     * 覆盖自动计算的 sessionKey（用于 new_session 等需要指定特定会话键的场景）
     */
    public void setSessionKey(String sessionKey) {
        this.sessionKeyOverride = sessionKey;
    }

    private String sessionKeyOverride;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public boolean isCommand() {
        return command != null && !command.isEmpty();
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    @Override
    public String toString() {
        return "InboundMessage{" +
                "channel='" + channel + '\'' +
                ", senderId='" + senderId + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                ", sessionKey='" + getSessionKey() + '\'' +
                ", receivedAt=" + receivedAt +
                '}';
    }
}
