package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 多 Agent 协同配置
 * 定义协同模式、参与角色、轮次限制等参数。
 *
 * <p>推荐使用静态工厂方法创建，再通过链式方法补充角色/任务：
 * <pre>{@code
 * // 辩论
 * CollaborationConfig config = CollaborationConfig.debate("AI 是否会取代程序员", 3)
 *         .addRole("正方", "你认为 AI 会取代程序员...")
 *         .addRole("反方", "你认为 AI 不会取代程序员...");
 *
 * // 团队协作
 * CollaborationConfig config = CollaborationConfig.teamWork("开发一个登录模块")
 *         .addTask(task1).addTask(task2);
 * }</pre>
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
        /** 通用工作流模式：LLM 动态生成 Workflow */
        WORKFLOW
    }

    /** 协同模式 */
    private Mode mode;

    /** 协同目标/主题 */
    private String goal;

    /** 最大轮次（讨论类模式使用） */
    private int maxRounds;

    /** 参与角色定义 */
    private List<AgentRole> roles;

    /** 分层决策专用配置 */
    private HierarchyConfig hierarchy;

    /** 团队协作专用：任务列表 */
    private List<TeamTask> tasks;

    /** 共识决策专用：共识阈值（0.0-1.0） */
    private double consensusThreshold;

    /** 超时时间（毫秒），0 表示不限制 */
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

    // -------------------------------------------------------------------------
    // 静态工厂方法
    // -------------------------------------------------------------------------

    /**
     * 创建辩论配置
     *
     * @param goal      辩论主题
     * @param maxRounds 最大辩论轮次
     */
    public static CollaborationConfig debate(String goal, int maxRounds) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.DEBATE;
        config.goal = goal;
        config.maxRounds = maxRounds;
        return config;
    }

    /**
     * 创建角色扮演配置
     *
     * @param scenario  角色扮演场景描述
     * @param maxRounds 最大对话轮次
     */
    public static CollaborationConfig rolePlay(String scenario, int maxRounds) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.ROLEPLAY;
        config.goal = scenario;
        config.maxRounds = maxRounds;
        return config;
    }

    /**
     * 创建团队协作配置
     *
     * @param goal 团队协作目标
     */
    public static CollaborationConfig teamWork(String goal) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.TEAM;
        config.goal = goal;
        return config;
    }

    /**
     * 创建分层决策配置
     *
     * @param goal            决策议题
     * @param hierarchyConfig 层级结构配置
     */
    public static CollaborationConfig hierarchy(String goal, HierarchyConfig hierarchyConfig) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.HIERARCHY;
        config.goal = goal;
        config.hierarchy = hierarchyConfig;
        return config;
    }

    /**
     * 创建共识决策配置
     *
     * @param goal      决策议题
     * @param threshold 共识阈值（0.0-1.0，如 0.6 表示 60% 以上同意即达成共识）
     */
    public static CollaborationConfig consensus(String goal, double threshold) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.CONSENSUS;
        config.goal = goal;
        config.consensusThreshold = threshold;
        return config;
    }

    /**
     * 创建通用工作流配置
     *
     * @param goal     工作流目标
     * @param workflow 工作流定义
     */
    public static CollaborationConfig workflow(String goal, WorkflowDefinition workflow) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.WORKFLOW;
        config.goal = goal;
        config.workflow = workflow;
        return config;
    }

    // -------------------------------------------------------------------------
    // 链式配置方法
    // -------------------------------------------------------------------------

    /**
     * 添加参与角色
     */
    public CollaborationConfig addRole(AgentRole role) {
        roles.add(role);
        return this;
    }

    /**
     * 添加参与角色（便捷方法，自动创建 AgentRole）
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

    /**
     * 设置超时时间
     *
     * @param timeoutMillis 超时毫秒数，0 表示不限制
     */
    public CollaborationConfig withTimeout(long timeoutMillis) {
        this.timeoutMs = timeoutMillis;
        return this;
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

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
