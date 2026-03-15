package io.leavesfly.tinyclaw.config;

import io.leavesfly.tinyclaw.agent.evolution.EvolutionConfig;

import java.util.ArrayList;
import java.util.List;

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

    public AgentConfig() {
        this.workspace = "~/.tinyclaw/workspace";
        this.model = "qwen3-max";
        this.provider = "dashscope";
        this.maxTokens = 8192;
        this.temperature = 0.7;
        this.maxToolIterations = 20;
        this.heartbeatEnabled = false;
        this.restrictToWorkspace = true; // 默认启用 workspace 限制
        this.commandBlacklist = new ArrayList<>(); // 为空表示使用默认黑名单
        this.evolution = new EvolutionConfig(); // 默认禁用进化功能
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
}
