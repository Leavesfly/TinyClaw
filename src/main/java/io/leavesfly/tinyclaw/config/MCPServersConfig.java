package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器配置
 * 
 * 管理多个 MCP 服务器的连接配置
 */
public class MCPServersConfig {
    
    private boolean enabled;
    private List<MCPServerConfig> servers;
    
    public MCPServersConfig() {
        this.enabled = false;
        this.servers = new ArrayList<>();
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public List<MCPServerConfig> getServers() {
        return servers;
    }
    
    public void setServers(List<MCPServerConfig> servers) {
        this.servers = servers;
    }
    
    /**
     * 单个 MCP 服务器配置
     */
    public static class MCPServerConfig {
        private String name;
        private String description;
        private String endpoint;
        private String apiKey;
        private boolean enabled;
        private int timeout;
        
        public MCPServerConfig() {
            this.enabled = true;
            this.timeout = 30000; // 默认 30 秒
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public String getEndpoint() {
            return endpoint;
        }
        
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public int getTimeout() {
            return timeout;
        }
        
        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }
    }
}
