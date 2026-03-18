package io.leavesfly.tinyclaw.config;

import io.leavesfly.tinyclaw.agent.evolution.EvolutionConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 配置
 * 表示 TinyClaw 系统中 Agent 的全局行为配置
 */
public class AgentConfig {

    private String workspace;
    private String model;
    private String provider;
    private int maxTokens;
    private double temperature;
    private int maxToolIterations;
    private boolean heartbeatEnabled;
    private boolean restrictToWorkspace;
    private List<String> commandBlacklist;
    
    /** 进化能力配置（反馈收集、Prompt 优化）*/
    private EvolutionConfig evolution;
    
    /** 多Agent协同配置 */
    private CollaborationSettings collaboration;

    public AgentConfig() {
        this.workspace = "~/.tinyclaw/workspace";
        this.model = "qwen3.5-plus";
        this.provider = "dashscope";
        this.maxTokens = 32768;
        this.temperature = 0.7;
        this.maxToolIterations = 20;
        this.heartbeatEnabled = false;
        this.restrictToWorkspace = true; // 默认启用 workspace 限制
        this.commandBlacklist = new ArrayList<>(); // 为空表示使用默认黑名单
        this.evolution = new EvolutionConfig(); // 默认禁用进化功能
        this.collaboration = new CollaborationSettings(); // 默认协同配置
    }

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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
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

    public List<String> getCommandBlacklist() {
        return commandBlacklist;
    }

    public void setCommandBlacklist(List<String> commandBlacklist) {
        this.commandBlacklist = commandBlacklist;
    }

    public EvolutionConfig getEvolution() {
        return evolution;
    }

    public void setEvolution(EvolutionConfig evolution) {
        this.evolution = evolution;
    }

    /**
     * 检查是否启用反馈收集。
     *
     * @return 启用时返回 true
     */
    public boolean isFeedbackEnabled() {
        return evolution != null && evolution.isFeedbackEnabled();
    }

    /**
     * 检查是否启用 Prompt 优化。
     *
     * @return 启用时返回 true
     */
    public boolean isPromptOptimizationEnabled() {
        return evolution != null && evolution.isPromptOptimizationEnabled();
    }
    
    public CollaborationSettings getCollaboration() {
        return collaboration;
    }
    
    public void setCollaboration(CollaborationSettings collaboration) {
        this.collaboration = collaboration;
    }
    
    /**
     * 检查是否启用多Agent协同。
     *
     * @return 启用时返回 true
     */
    public boolean isCollaborationEnabled() {
        return collaboration != null && collaboration.isEnabled();
    }
    
    /**
     * 多Agent协同配置
     */
    public static class CollaborationSettings {
        
        /** 是否启用协同能力 */
        private boolean enabled = true;
        
        /** 默认最大轮次 */
        private int defaultMaxRounds = 3;
        
        /** 默认共识阈值 */
        private double defaultConsensusThreshold = 0.6;
        
        /** 协同超时时间（毫秒），0表示不限制 */
        private long timeoutMs = 0;
        
        /** 预定义角色模板 */
        private Map<String, List<RoleTemplate>> roleTemplates = new HashMap<>();
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public int getDefaultMaxRounds() {
            return defaultMaxRounds;
        }
        
        public void setDefaultMaxRounds(int defaultMaxRounds) {
            this.defaultMaxRounds = defaultMaxRounds;
        }
        
        public double getDefaultConsensusThreshold() {
            return defaultConsensusThreshold;
        }
        
        public void setDefaultConsensusThreshold(double defaultConsensusThreshold) {
            this.defaultConsensusThreshold = defaultConsensusThreshold;
        }
        
        public long getTimeoutMs() {
            return timeoutMs;
        }
        
        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
        
        public Map<String, List<RoleTemplate>> getRoleTemplates() {
            return roleTemplates;
        }
        
        public void setRoleTemplates(Map<String, List<RoleTemplate>> roleTemplates) {
            this.roleTemplates = roleTemplates;
        }
    }
    
    /**
     * 角色模板定义
     */
    public static class RoleTemplate {
        private String name;
        private String prompt;
        private String model;
        
        public RoleTemplate() {}
        
        public RoleTemplate(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getPrompt() {
            return prompt;
        }
        
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
    }
}
