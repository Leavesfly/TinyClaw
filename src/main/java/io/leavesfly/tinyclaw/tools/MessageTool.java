package io.leavesfly.tinyclaw.tools;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool for sending messages through channels
 */
public class MessageTool implements Tool {
    
    private String currentChannel;
    private String currentChatId;
    private TriConsumer sendCallback;
    
    public MessageTool() {
    }
    
    /**
     * 设置 the current message context
     */
    public void setContext(String channel, String chatId) {
        this.currentChannel = channel;
        this.currentChatId = chatId;
    }
    
    /**
     * 设置 the send callback for delivering messages
     */
    public void setSendCallback(TriConsumer callback) {
        this.sendCallback = callback;
    }
    
    /**
     * TriConsumer interface for callback with three parameters
     */
    @FunctionalInterface
    public interface TriConsumer {
        void accept(String channel, String chatId, String content);
    }
    
    @Override
    public String name() {
        return "message";
    }
    
    @Override
    public String description() {
        return "发送 a message to a specific channel and chat. Use this to deliver responses to users.";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> channelParam = new HashMap<>();
        channelParam.put("type", "string");
        channelParam.put("description", "Target channel (telegram, discord, feishu, etc.)");
        properties.put("channel", channelParam);
        
        Map<String, Object> chatIdParam = new HashMap<>();
        chatIdParam.put("type", "string");
        chatIdParam.put("description", "Target chat ID");
        properties.put("chat_id", chatIdParam);
        
        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description", "Message content to send");
        properties.put("content", contentParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"content"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String content = (String) args.get("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        
        // 获取 channel and chatId from args or use current context
        String channel = (String) args.get("channel");
        String chatId = (String) args.get("chat_id");
        
        if (channel == null || channel.isEmpty()) {
            channel = currentChannel;
        }
        if (chatId == null || chatId.isEmpty()) {
            chatId = currentChatId;
        }
        
        if (channel == null || chatId == null) {
            return "Error: No target channel or chat ID specified";
        }
        
        // 发送 message via callback
        if (sendCallback != null) {
            try {
                sendCallback.accept(channel, chatId, content);
                return "Message sent to " + channel + ":" + chatId;
            } catch (Exception e) {
                return "Error sending message: " + e.getMessage();
            }
        }
        
        return "Message prepared for " + channel + ":" + chatId + " (no callback set)";
    }
}
