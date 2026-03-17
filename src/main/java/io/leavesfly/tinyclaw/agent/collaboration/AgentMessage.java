package io.leavesfly.tinyclaw.agent.collaboration;

/**
 * 多Agent协同中的单条消息
 * 记录Agent在协同过程中的发言
 */
public class AgentMessage {
    
    /** 发言Agent的唯一标识 */
    private String agentId;
    
    /** Agent的角色名称（如"正方辩手"、"架构师"） */
    private String agentRole;
    
    /** 发言内容 */
    private String content;
    
    /** 发言时间戳 */
    private long timestamp;
    
    public AgentMessage() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public AgentMessage(String agentId, String agentRole, String content) {
        this.agentId = agentId;
        this.agentRole = agentRole;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getters and Setters
    
    public String getAgentId() {
        return agentId;
    }
    
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
    
    public String getAgentRole() {
        return agentRole;
    }
    
    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    @Override
    public String toString() {
        return "[" + agentRole + "] " + content;
    }
}
