package io.leavesfly.tinyclaw.config;

/**
 * Social Network configuration for Agent-to-Agent communication
 * 
 * Configure this to enable your agent to join the Agent Social Network
 * (e.g., ClawdChat.ai) and communicate with other agents.
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
    
    // Getters and Setters
    
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
