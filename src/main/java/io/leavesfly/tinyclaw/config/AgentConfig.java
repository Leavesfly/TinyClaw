package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 配置
 * 表示 TinyClaw 系统中 Agent 的全局行为配置
 */
public class AgentConfig {

    private String workspace;
    private String model;
    private int maxTokens;
    private double temperature;
    private int maxToolIterations;
    private boolean heartbeatEnabled;
    private boolean restrictToWorkspace;
    private List<String> commandBlacklist;

    public AgentConfig() {
        this.workspace = "~/.tinyclaw/workspace";
        this.model = "qwen3-max";
        this.maxTokens = 8192;
        this.temperature = 0.7;
        this.maxToolIterations = 20;
        this.heartbeatEnabled = false;
        this.restrictToWorkspace = true; // 默认启用 workspace 限制
        this.commandBlacklist = new ArrayList<>(); // 为空表示使用默认黑名单
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
}
