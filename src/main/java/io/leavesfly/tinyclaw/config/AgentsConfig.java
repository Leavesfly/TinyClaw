package io.leavesfly.tinyclaw.config;

/**
 * Agent 配置类
 * 包含 Agent 的默认配置信息
 */
public class AgentsConfig {
    
    private AgentDefaults defaults;
    
    public AgentsConfig() {
        this.defaults = new AgentDefaults();
    }
    
    public AgentDefaults getDefaults() {
        return defaults;
    }
    
    public void setDefaults(AgentDefaults defaults) {
        this.defaults = defaults;
    }
    
    /**
     * Agent 默认设置
     * 包含模型、工作空间、最大迭代次数等配置
     */
    public static class AgentDefaults {
        private String workspace;
        private String model;
        private int maxTokens;
        private double temperature;
        private int maxToolIterations;
        private boolean heartbeatEnabled;
        private boolean restrictToWorkspace;
        private java.util.List<String> commandBlacklist;
        
        public AgentDefaults() {
            this.workspace = "~/.tinyclaw/workspace";
            this.model = "glm-4.7";
            this.maxTokens = 8192;
            this.temperature = 0.7;
            this.maxToolIterations = 20;
            this.heartbeatEnabled = false;
            this.restrictToWorkspace = true; // Default: enable sandbox
            this.commandBlacklist = new java.util.ArrayList<>(); // Empty means use default
        }
        
        // Getters and Setters
        public String getWorkspace() {
            return workspace;
        }
        
        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public int getMaxTokens() {
            return maxTokens;
        }
        
        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
        
        public double getTemperature() {
            return temperature;
        }
        
        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
        
        public int getMaxToolIterations() {
            return maxToolIterations;
        }
        
        public void setMaxToolIterations(int maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }
        
        public boolean isHeartbeatEnabled() {
            return heartbeatEnabled;
        }
        
        public void setHeartbeatEnabled(boolean heartbeatEnabled) {
            this.heartbeatEnabled = heartbeatEnabled;
        }
        
        public boolean isRestrictToWorkspace() {
            return restrictToWorkspace;
        }
        
        public void setRestrictToWorkspace(boolean restrictToWorkspace) {
            this.restrictToWorkspace = restrictToWorkspace;
        }
        
        public java.util.List<String> getCommandBlacklist() {
            return commandBlacklist;
        }
        
        public void setCommandBlacklist(java.util.List<String> commandBlacklist) {
            this.commandBlacklist = commandBlacklist;
        }
    }
}
