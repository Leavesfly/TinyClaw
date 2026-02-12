package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.providers.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话 - 表示一次对话
 */
public class Session {
    
    private String key;
    private List<Message> messages;
    private String summary;
    private Instant created;
    private Instant updated;
    
    public Session() {
        this.messages = new ArrayList<>();
        this.created = Instant.now();
        this.updated = Instant.now();
    }
    
    public Session(String key) {
        this();
        this.key = key;
    }
    
    // Getters and Setters
    public String getKey() {
        return key;
    }
    
    public void setKey(String key) {
        this.key = key;
    }
    
    public List<Message> getMessages() {
        return messages;
    }
    
    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
    }
    
    public Instant getCreated() {
        return created;
    }
    
    public void setCreated(Instant created) {
        this.created = created;
    }
    
    public Instant getUpdated() {
        return updated;
    }
    
    public void setUpdated(Instant updated) {
        this.updated = updated;
    }
    
    /**
     * 向会话添加一条简单消息
     */
    public void addMessage(String role, String content) {
        Message msg = new Message(role, content);
        this.messages.add(msg);
        this.updated = Instant.now();
    }
    
    /**
     * 向会话添加完整消息（包含工具调用）
     */
    public void addFullMessage(Message message) {
        this.messages.add(message);
        this.updated = Instant.now();
    }
    
    /**
     * 获取消息历史记录的副本
     */
    public List<Message> getHistory() {
        return new ArrayList<>(messages);
    }
    
    /**
     * 截断历史记录，仅保留最后 N 条消息
     */
    public void truncateHistory(int keepLast) {
        if (messages.size() > keepLast) {
            messages = new ArrayList<>(messages.subList(messages.size() - keepLast, messages.size()));
            this.updated = Instant.now();
        }
    }
}