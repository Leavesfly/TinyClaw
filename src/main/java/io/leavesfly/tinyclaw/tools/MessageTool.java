package io.leavesfly.tinyclaw.tools;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息发送工具
 * 用于通过通道发送消息
 */
public class MessageTool implements Tool {
    
    private String currentChannel;
    private String currentChatId;
    private TriConsumer sendCallback;
    
    public MessageTool() {
    }
    
    /**
     * 设置当前消息上下文
     */
    public void setContext(String channel, String chatId) {
        this.currentChannel = channel;
        this.currentChatId = chatId;
    }
    
    /**
     * 设置发送回调函数，用于传递消息
     */
    public void setSendCallback(TriConsumer callback) {
        this.sendCallback = callback;
    }
    
    /**
     * 三参数消费者接口，用于回调
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
        return "向特定通道和聊天发送消息。使用此工具将响应传递给用户。";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> channelParam = new HashMap<>();
        channelParam.put("type", "string");
        channelParam.put("description", "目标通道（telegram、discord、feishu 等）");
        properties.put("channel", channelParam);
        
        Map<String, Object> chatIdParam = new HashMap<>();
        chatIdParam.put("type", "string");
        chatIdParam.put("description", "目标聊天 ID");
        properties.put("chat_id", chatIdParam);
        
        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description", "要发送的消息内容");
        properties.put("content", contentParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"content"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String content = (String) args.get("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("内容参数是必需的");
        }
        
        // 从参数或当前上下文获取 channel 和 chatId
        String channel = (String) args.get("channel");
        String chatId = (String) args.get("chat_id");
        
        if (channel == null || channel.isEmpty()) {
            channel = currentChannel;
        }
        if (chatId == null || chatId.isEmpty()) {
            chatId = currentChatId;
        }
        
        if (channel == null || chatId == null) {
            return "错误: 未指定目标通道或聊天 ID";
        }
        
        // 通过回调发送消息
        if (sendCallback != null) {
            try {
                sendCallback.accept(channel, chatId, content);
                return "消息已发送到 " + channel + ":" + chatId;
            } catch (Exception e) {
                return "发送消息错误: " + e.getMessage();
            }
        }
        
        return "消息已准备发送至 " + channel + ":" + chatId + " （未设置回调）";
    }
}
