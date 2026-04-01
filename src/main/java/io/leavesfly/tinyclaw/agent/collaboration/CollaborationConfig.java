package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协同配置
 * 定义协同模式、参与角色、轮次限制等参数。
 *
 * <p>推荐使用静态工厂方法创建，再通过链式方法补充角色/任务：
 * <pre>{@code
 * // 讨论（辩论风格）
 * CollaborationConfig config = CollaborationConfig.discuss("AI 是否会取代程序员", 3)
 *         .addRole("正方", "你认为 AI 会取代程序员...")
 *         .addRole("反方", "你认为 AI 不会取代程序员...");
 *
 * // 任务并行执行
 * CollaborationConfig config = CollaborationConfig.tasks("开发一个登录模块")
 *         .addTask(task1).addTask(task2);
 * }</pre>
 */
public class CollaborationConfig {

    /**
     * 协同模式枚举（3 种核心模式）
     */
    public enum Mode {
        /** 讨论模式：多 Agent 轮流发言，通过 {@link DiscussStyle} 区分辩论/角色扮演/共识/动态路由 */
        DISCUSS,
        /** 任务模式：任务分解执行，通过 {@link TasksStyle} 区分扁平并行/层级汇报 */
        TASKS,
        /** 工作流模式：基于 DAG 的复杂流程编排，支持 LLM 动态生成 */
        WORKFLOW
    }

    /**
     * 讨论风格（DISCUSS 模式的子类型，影响提示词和终止条件）
     */
    public enum DiscussStyle {
        /** 辩论：正反双方轮流发言，可设裁判 */
        DEBATE,
        /** 角色扮演：多角色对话模拟，支持主动结束 */
        ROLEPLAY,
        /** 共识决策：讨论后投票，达到阈值即结束 */
        CONSENSUS,
        /** 动态路由：Router Agent 动态选择下一个发言者 */
        DYNAMIC
    }

    /**
     * 任务风格（TASKS 模式的子类型，影响任务组织方式）
     */
    public enum TasksStyle {
        /** 扁平并行：任务按依赖图并行/串行执行 */
        PARALLEL,
        /** 层级汇报：金字塔式逐层汇报决策 */
        HIERARCHY
    }

    /** 协同模式 */
    private Mode mode;

    /** 讨论风格（仅 DISCUSS 模式有效，null 表示由策略自动选择） */
    private DiscussStyle discussStyle;

    /** 任务风格（仅 TASKS 模式有效，null 表示默认 PARALLEL） */
    private TasksStyle tasksStyle;

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

    /** 通用工作流定义 */
    private WorkflowDefinition workflow;

    /** Token 预算上限（0 表示不限制） */
    private long maxTokenBudget;

    /** Human-in-the-Loop 审批回调（null 表示不需要审批） */
    private transient ApprovalCallback approvalCallback;

    /** 是否启用优雅降级（协同失败时自动降级为单 Agent 模式） */
    private boolean fallbackEnabled;

    /** 动态路由模式专用：Router Agent 的角色定义 */
    private AgentRole routerRole;

    /** 是否启用自反馈循环（Workflow 执行后由 Critic Agent 评估） */
    private boolean selfReflectionEnabled;

    /** 自反馈最大重试次数 */
    private int maxReflectionRetries;

    /** 扩展元数据（用于传递不适合作为一等字段的配置，如主 Agent 上下文摘要） */
    private Map<String, String> metadata;

    public CollaborationConfig() {
        this.mode = Mode.DISCUSS;
        this.maxRounds = 3;
        this.roles = new ArrayList<>();
        this.tasks = new ArrayList<>();
        this.consensusThreshold = 0.6;
        this.maxTokenBudget = 0;
        this.fallbackEnabled = false;
        this.selfReflectionEnabled = false;
        this.maxReflectionRetries = 2;
        this.metadata = new HashMap<>();
    }

    // -------------------------------------------------------------------------
    // 静态工厂方法（推荐使用新的 discuss/tasks/workflow 三合一入口）
    // -------------------------------------------------------------------------

    /**
     * 创建讨论配置（统一入口，风格由策略自动选择）
     *
     * @param goal      讨论主题
     * @param maxRounds 最大轮次
     */
    public static CollaborationConfig discuss(String goal, int maxRounds) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.DISCUSS;
        config.goal = goal;
        config.maxRounds = maxRounds;
        return config;
    }

    /**
     * 创建任务配置（统一入口，默认扁平并行）
     *
     * @param goal 任务目标
     */
    public static CollaborationConfig tasks(String goal) {
        CollaborationConfig config = new CollaborationConfig();
        config.mode = Mode.TASKS;
        config.goal = goal;
        config.tasksStyle = TasksStyle.PARALLEL;
        return config;
    }

    /**
     * 创建工作流配置
     *
     * @param goal     工作流目标
     * @param workflow 工作流定义（可为 null，由 LLM 自动生成）
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
     * 设置 Token 预算上限
     *
     * @param maxTokens 最大 token 数，0 表示不限制
     */
    public CollaborationConfig withTokenBudget(long maxTokens) {
        this.maxTokenBudget = maxTokens;
        return this;
    }

    /**
     * 设置 Human-in-the-Loop 审批回调
     *
     * @param callback 审批回调，null 表示不需要审批
     */
    public CollaborationConfig withApproval(ApprovalCallback callback) {
        this.approvalCallback = callback;
        return this;
    }

    /**
     * 启用优雅降级（协同失败时自动降级为单 Agent 模式）
     */
    public CollaborationConfig withFallback() {
        this.fallbackEnabled = true;
        return this;
    }

    /**
     * 设置动态路由模式的 Router Agent
     *
     * @param router Router Agent 角色定义
     */
    public CollaborationConfig withRouter(AgentRole router) {
        this.routerRole = router;
        return this;
    }

    /**
     * 启用自反馈循环
     *
     * @param maxRetries 最大重试次数
     */
    public CollaborationConfig withSelfReflection(int maxRetries) {
        this.selfReflectionEnabled = true;
        this.maxReflectionRetries = maxRetries;
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

    public DiscussStyle getDiscussStyle() {
        return discussStyle;
    }

    public void setDiscussStyle(DiscussStyle discussStyle) {
        this.discussStyle = discussStyle;
    }

    public TasksStyle getTasksStyle() {
        return tasksStyle;
    }

    public void setTasksStyle(TasksStyle tasksStyle) {
        this.tasksStyle = tasksStyle;
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

    public WorkflowDefinition getWorkflow() {
        return workflow;
    }

    public void setWorkflow(WorkflowDefinition workflow) {
        this.workflow = workflow;
    }

    public long getMaxTokenBudget() {
        return maxTokenBudget;
    }

    public void setMaxTokenBudget(long maxTokenBudget) {
        this.maxTokenBudget = maxTokenBudget;
    }

    public ApprovalCallback getApprovalCallback() {
        return approvalCallback;
    }

    public void setApprovalCallback(ApprovalCallback approvalCallback) {
        this.approvalCallback = approvalCallback;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public void setFallbackEnabled(boolean fallbackEnabled) {
        this.fallbackEnabled = fallbackEnabled;
    }

    public AgentRole getRouterRole() {
        return routerRole;
    }

    public void setRouterRole(AgentRole routerRole) {
        this.routerRole = routerRole;
    }

    public boolean isSelfReflectionEnabled() {
        return selfReflectionEnabled;
    }

    public void setSelfReflectionEnabled(boolean selfReflectionEnabled) {
        this.selfReflectionEnabled = selfReflectionEnabled;
    }

    public int getMaxReflectionRetries() {
        return maxReflectionRetries;
    }

    public void setMaxReflectionRetries(int maxReflectionRetries) {
        this.maxReflectionRetries = maxReflectionRetries;
    }

    /**
     * 设置扩展元数据
     */
    public CollaborationConfig withMeta(String key, String value) {
        this.metadata.put(key, value);
        return this;
    }

    /**
     * 获取扩展元数据
     */
    public String getMeta(String key) {
        return metadata.get(key);
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
