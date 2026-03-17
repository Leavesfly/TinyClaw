package io.leavesfly.tinyclaw.agent.collaboration;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层决策配置
 * 定义金字塔式层级结构，用于HierarchyStrategy
 */
public class HierarchyConfig {
    
    /** 层级列表（从底层level=0到顶层） */
    private List<HierarchyLevel> levels;
    
    public HierarchyConfig() {
        this.levels = new ArrayList<>();
    }
    
    /**
     * 添加一个层级
     */
    public void addLevel(HierarchyLevel level) {
        levels.add(level);
    }
    
    /**
     * 获取底层（level=0）的所有Agent
     */
    public List<AgentRole> getBottomLevelAgents() {
        if (levels.isEmpty()) {
            return new ArrayList<>();
        }
        // 找到level最小的层
        return levels.stream()
                .filter(l -> l.getLevel() == 0)
                .findFirst()
                .map(HierarchyLevel::getAgents)
                .orElse(new ArrayList<>());
    }
    
    /**
     * 获取顶层Agent
     */
    public List<AgentRole> getTopLevelAgents() {
        if (levels.isEmpty()) {
            return new ArrayList<>();
        }
        int maxLevel = levels.stream()
                .mapToInt(HierarchyLevel::getLevel)
                .max()
                .orElse(0);
        return levels.stream()
                .filter(l -> l.getLevel() == maxLevel)
                .findFirst()
                .map(HierarchyLevel::getAgents)
                .orElse(new ArrayList<>());
    }
    
    /**
     * 获取指定层级
     */
    public HierarchyLevel getLevel(int level) {
        return levels.stream()
                .filter(l -> l.getLevel() == level)
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 获取层级总数
     */
    public int getLevelCount() {
        if (levels.isEmpty()) {
            return 0;
        }
        return levels.stream()
                .mapToInt(HierarchyLevel::getLevel)
                .max()
                .orElse(0) + 1;
    }
    
    // Getters and Setters
    
    public List<HierarchyLevel> getLevels() {
        return levels;
    }
    
    public void setLevels(List<HierarchyLevel> levels) {
        this.levels = levels;
    }
    
    /**
     * 层级定义
     */
    public static class HierarchyLevel {
        
        /** 层级编号（0=底层） */
        private int level;
        
        /** 该层的Agent角色列表 */
        private List<AgentRole> agents;
        
        /** 汇总提示词（非底层需要，用于汇总下层结果） */
        private String aggregationPrompt;
        
        public HierarchyLevel() {
            this.agents = new ArrayList<>();
        }
        
        public HierarchyLevel(int level) {
            this();
            this.level = level;
        }
        
        public void addAgent(AgentRole agent) {
            agents.add(agent);
        }
        
        // Getters and Setters
        
        public int getLevel() {
            return level;
        }
        
        public void setLevel(int level) {
            this.level = level;
        }
        
        public List<AgentRole> getAgents() {
            return agents;
        }
        
        public void setAgents(List<AgentRole> agents) {
            this.agents = agents;
        }
        
        public String getAggregationPrompt() {
            return aggregationPrompt;
        }
        
        public void setAggregationPrompt(String aggregationPrompt) {
            this.aggregationPrompt = aggregationPrompt;
        }
    }
}
