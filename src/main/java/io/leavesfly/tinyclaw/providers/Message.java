package io.leavesfly.tinyclaw.providers;

import java.util.List;

/**
 * LLM 消息表示
 */
public class Message {
    
    private String role;
    private String content;
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
    
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
    
    public static Message tool(String toolCallId, String content) {
        Message msg = new Message("tool", content);
        msg.setToolCallId(toolCallId);
        return msg;
    }
}