package io.leavesfly.tinyclaw.bus;

import java.util.List;
import java.util.Map;

/**
 * 来自外部通道的入站消息
 */
public class InboundMessage {
    
    private String channel;
    private String senderId;
    private String chatId;
    private String content;
    private List<String> media;
    private String sessionKey;
    private Map<String, String> metadata;
    
    public InboundMessage() {
    }
    
    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
        this.sessionKey = channel + ":" + chatId;
    }
    
    // Getter和Setter方法
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
    
    public String getSessionKey() {
        return sessionKey;
    }
    
    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
    
    @Override
    public String toString() {
        return "InboundMessage{" +
                "channel='" + channel + '\'' +
                ", senderId='" + senderId + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                ", sessionKey='" + sessionKey + '\'' +
                '}';
    }
}
