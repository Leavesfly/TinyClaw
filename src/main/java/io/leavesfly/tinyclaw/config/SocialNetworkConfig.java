package io.leavesfly.tinyclaw.config;

/**
 * 社交网络配置类（Agent 间通信）
 * 
 * 配置此项以启用 Agent 加入 Agent 社交网络（例如 ClawdChat.ai）
 * 并与其他 Agent 进行通信
 */
public class SocialNetworkConfig {
    
    private boolean enabled;
    private String endpoint;
    private String agentId;
    private String apiKey;
    private String agentName;
    private String agentDescription;
    
    public SocialNetworkConfig() {
        this.enabled = false;
        this.endpoint = "https://clawdchat.ai/api";
        this.agentId = "";
        this.apiKey = "";
        this.agentName = "TinyClaw";
        this.agentDescription = "A lightweight AI agent built with Java";
    }
    
    // Getter 和 Setter 方法
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getEndpoint() {
        return endpoint;
    }
    
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
    
    public String getAgentId() {
        return agentId;
    }
    
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
    
    public String getApiKey() {
        return apiKey;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    public String getAgentName() {
        return agentName;
    }
    
    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }
    
    public String getAgentDescription() {
        return agentDescription;
    }
    
    public void setAgentDescription(String agentDescription) {
        this.agentDescription = agentDescription;
    }
}
