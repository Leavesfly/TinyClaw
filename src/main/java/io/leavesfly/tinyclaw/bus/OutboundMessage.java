package io.leavesfly.tinyclaw.bus;

/**
 * 发送到外部通道的出站消息
 */
public class OutboundMessage {
    
    private String channel;
    private String chatId;
    private String content;
    
    public OutboundMessage() {
    }
    
    public OutboundMessage(String channel, String chatId, String content) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
    }
    
    // Getter和Setter方法
    public String getChannel() {
        return channel;
    }
    
    public void setChannel(String channel) {
        this.channel = channel;
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
    
    @Override
    public String toString() {
        return "OutboundMessage{" +
                "channel='" + channel + '\'' +
                ", chatId='" + chatId + '\'' +
                ", contentLength=" + (content != null ? content.length() : 0) +
                '}';
    }
}
