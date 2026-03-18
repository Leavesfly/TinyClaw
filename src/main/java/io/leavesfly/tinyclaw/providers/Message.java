package io.leavesfly.tinyclaw.providers;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 消息表示，支持多模态内容（文本+图片）
 */
public class Message {
    
    private String role;
    private String content;
    private List<String> images;  // 图片路径列表，支持多模态
    private List<ToolCall> toolCalls;
    private String toolCallId;
    
    public Message() {
    }
    
    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
    
    // Getter 和 Setter 方法
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public List<String> getImages() {
        return images;
    }
    
    public void setImages(List<String> images) {
        this.images = images;
    }
    
    /**
     * 检查消息是否包含图片
     */
    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
    
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
    
    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
    
    public String getToolCallId() {
        return toolCallId;
    }
    
    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }
    
    // Builder 方法
    public static Message system(String content) {
        return new Message("system", content);
    }
    
    public static Message user(String content) {
        return new Message("user", content);
    }
    
    /**
     * 创建带图片的用户消息
     */
    public static Message user(String content, List<String> images) {
        Message msg = new Message("user", content);
        msg.setImages(images);
        return msg;
    }
    
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
    
    public static Message tool(String toolCallId, String content) {
        Message msg = new Message("tool", content);
        msg.setToolCallId(toolCallId);
        return msg;
    }
}