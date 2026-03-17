package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 多Agent协同配置
 * 定义协同模式、参与角色、轮次限制等参数
 */
public class CollaborationConfig {
    
    /**
     * 协同模式枚举
     */
    public enum Mode {
        /** 辩论模式：正反双方轮流发言 */
        DEBATE,
        /** 团队协作模式：任务分解并行/串行执行 */
        TEAM,
        /** 角色扮演模式：多角色对话模拟 */
        ROLEPLAY,
        /** 共识决策模式：讨论后投票 */
        CONSENSUS,
        /** 分层决策模式：层级汇报式决策 */
        HIERARCHY,
        /** 通用工作流模式：LLM动态生成Workflow */
        WORKFLOW
    }
    
    /** 协同模式 */
    private Mode mode;
    
    /** 协同目标/主题 */
    private String goal;
    
    /** 最大轮次 */
    private int maxRounds;
    
    /** 参与角色定义 */
    private List<AgentRole> roles;
    
    /** 分层决策专用配置 */
    private HierarchyConfig hierarchy;
    
    /** 团队协作专用：任务列表 */
    private List<TeamTask> tasks;
    
    /** 共识决策专用：共识阈值（0.0-1.0） */
    private double consensusThreshold;
    
    /** 超时时间（毫秒），0表示不限制 */
    private long timeoutMs;
    
    /** 通用工作流定义 */
    private WorkflowDefinition workflow;
    
    public CollaborationConfig() {
        this.mode = Mode.DEBATE;
        this.maxRounds = 3;
        this.roles = new ArrayList<>();
        this.tasks = new ArrayList<>();
        this.consensusThreshold = 0.6;
        this.timeoutMs = 0;
    }
    
    /**
     * 创建辩论配置
     */
    public static CollaborationConfig debate(String goal, int maxRounds) {
        CollaborationConfig config = new CollaborationConfig();
        config.setMode(Mode.DEBATE);
        config.setGoal(goal);
        config.setMaxRounds(maxRounds);
        return config;
    }
    
    /**
     * 创建团队协作配置
     */
    public static CollaborationConfig teamWork(String goal) {
        CollaborationConfig config = new CollaborationConfig();
        config.setMode(Mode.TEAM);
        config.setGoal(goal);
        return config;
    }
    
    /**
     * 创建分层决策配置
     */
    public static CollaborationConfig hierarchy(String goal, HierarchyConfig hierarchyConfig) {
        CollaborationConfig config = new CollaborationConfig();
        config.setMode(Mode.HIERARCHY);
        config.setGoal(goal);
        config.setHierarchy(hierarchyConfig);
        return config;
    }
    
    /**
     * 创建共识决策配置
     */
    public static CollaborationConfig consensus(String goal, double threshold) {
        CollaborationConfig config = new CollaborationConfig();
        config.setMode(Mode.CONSENSUS);
        config.setGoal(goal);
        config.setConsensusThreshold(threshold);
        return config;
    }
    
    /**
     * 创建通用工作流配置
     */
    public static CollaborationConfig workflow(String goal, WorkflowDefinition workflow) {
        CollaborationConfig config = new CollaborationConfig();
        config.setMode(Mode.WORKFLOW);
        config.setGoal(goal);
        config.setWorkflow(workflow);
        return config;
    }
    
    /**
     * 添加角色
     */
    public CollaborationConfig addRole(AgentRole role) {
        roles.add(role);
        return this;
    }
    
    /**
     * 添加角色（便捷方法）
     */
    public CollaborationConfig addRole(String roleName, String systemPrompt) {
        roles.add(AgentRole.of(roleName, systemPrompt));
        return this;
    }
    
    /**
     * 添加团队任务
     */
    public CollaborationConfig addTask(TeamTask task) {
        tasks.add(task);
        return this;
    }
    
    // Getters and Setters
    
    public Mode getMode() {
        return mode;
    }
    
    public void setMode(Mode mode) {
        this.mode = mode;
    }
    
    public String getGoal() {
        return goal;
    }
    
    public void setGoal(String goal) {
        this.goal = goal;
    }
    
    public int getMaxRounds() {
        return maxRounds;
    }
    
    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }
    
    public List<AgentRole> getRoles() {
        return roles;
    }
    
    public void setRoles(List<AgentRole> roles) {
        this.roles = roles;
    }
    
    public HierarchyConfig getHierarchy() {
        return hierarchy;
    }
    
    public void setHierarchy(HierarchyConfig hierarchy) {
        this.hierarchy = hierarchy;
    }
    
    public List<TeamTask> getTasks() {
        return tasks;
    }
    
    public void setTasks(List<TeamTask> tasks) {
        this.tasks = tasks;
    }
    
    public double getConsensusThreshold() {
        return consensusThreshold;
    }
    
    public void setConsensusThreshold(double consensusThreshold) {
        this.consensusThreshold = consensusThreshold;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
    
    public WorkflowDefinition getWorkflow() {
        return workflow;
    }
    
    public void setWorkflow(WorkflowDefinition workflow) {
        this.workflow = workflow;
    }
}
