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
    /** 工具调用记录列表，用于历史会话回放时重建工具调用卡片 */
    private List<ToolCallRecord> toolCallRecords;
    
    public Session() {
        this.messages = new ArrayList<>();
        this.toolCallRecords = new ArrayList<>();
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
    
    public List<ToolCallRecord> getToolCallRecords() {
        return toolCallRecords;
    }

    public void setToolCallRecords(List<ToolCallRecord> toolCallRecords) {
        this.toolCallRecords = toolCallRecords != null ? toolCallRecords : new ArrayList<>();
    }

    /**
     * 添加一条工具调用记录。
     * afterAssistantIndex 由调用方传入，表示该工具调用发生在第几条 assistant 消息之后。
     */
    public void addToolCallRecord(ToolCallRecord record) {
        if (this.toolCallRecords == null) {
            this.toolCallRecords = new ArrayList<>();
        }
        this.toolCallRecords.add(record);
        this.updated = Instant.now();
    }

    /**
     * 统计当前 messages 中 assistant 消息的数量（用于计算 afterAssistantIndex）
     */
    public int countAssistantMessages() {
        if (messages == null) return 0;
        return (int) messages.stream().filter(m -> "assistant".equals(m.getRole())).count();
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
     * 截断历史记录，仅保留最后 N 条消息。
     * 
     * 截断时会确保不破坏 tool_calls / tool 消息的配对关系：
     * - 如果截断点恰好落在 assistant(tool_calls) 之后、tool(result) 之前，
     *   会向前调整截断点以包含完整的 assistant 消息。
     * - 如果截断后开头是 tool 消息，会跳过这些孤立的 tool 消息。
     */
    public void truncateHistory(int keepLast) {
        if (messages.size() <= keepLast) {
            return;
        }
        
        int startIndex = messages.size() - keepLast;
        startIndex = adjustStartIndexForToolMessageIntegrity(startIndex);
        
        messages = new ArrayList<>(messages.subList(startIndex, messages.size()));
        this.updated = Instant.now();
    }
    
    /**
     * 调整截断起始索引，确保不破坏 tool_calls / tool 消息的配对关系。
     * 
     * 策略：
     * 1. 如果起始位置是 tool 消息，向前查找其对应的 assistant(tool_calls) 消息并包含它
     * 2. 如果向前找不到对应的 assistant 消息，则向后跳过所有孤立的 tool 消息
     */
    private int adjustStartIndexForToolMessageIntegrity(int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) {
            return startIndex;
        }
        
        Message startMessage = messages.get(startIndex);
        if (!"tool".equals(startMessage.getRole())) {
            return startIndex;
        }
        
        // 向前查找最近的 assistant(tool_calls) 消息
        for (int i = startIndex - 1; i >= 0; i--) {
            Message candidate = messages.get(i);
            if ("assistant".equals(candidate.getRole()) 
                    && candidate.getToolCalls() != null 
                    && !candidate.getToolCalls().isEmpty()) {
                return i;
            }
            // 如果遇到非 tool 且非目标 assistant 的消息，停止向前查找
            if (!"tool".equals(candidate.getRole())) {
                break;
            }
        }
        
        // 向前找不到配对的 assistant，向后跳过所有孤立的 tool 消息
        int adjusted = startIndex;
        while (adjusted < messages.size() && "tool".equals(messages.get(adjusted).getRole())) {
            adjusted++;
        }
        return adjusted;
    }
}