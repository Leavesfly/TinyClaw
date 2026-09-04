package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 全局配置类
 * <p>
 * 定义 TinyClaw 系统中 Agent 的核心行为参数，包括：
 * <ul>
 *   <li>工作空间配置 - Agent 的工作目录路径</li>
 *   <li>模型配置 - LLM 模型选择和参数调优</li>
 *   <li>安全配置 - 命令黑名单和工作空间限制</li>
 *   <li>进化能力 - 反馈收集和 Prompt 优化</li>
 *   <li>多 Agent 协同 - 分布式任务协作</li>
 * </ul>
 * </p>
 *
 * @see EvolutionConfig 进化能力配置
 * @see CollaborationSettings 多 Agent 协同配置
 */
public class AgentConfig {

    /**
     * 工作空间路径
     * <p>Agent 执行文件操作的基础目录，默认为 ~/.tinyclaw/workspace</p>
     */
    private String workspace;

    /**
     * LLM 模型标识
     * <p>指定 Agent 使用的语言模型，如 qwen3.5-plus、gpt-4 等</p>
     */
    private String model;

    /**
     * LLM 提供商标识
     * <p>指定模型服务提供商，如 dashscope、openai 等</p>
     */
    private String provider;

    /**
     * 最大 Token 数
     * <p>单次请求的最大 token 数量，默认 16384</p>
     */
    private int maxTokens;

    /**
     * 温度参数
     * <p>控制模型输出的随机性，范围 0.0-1.0，默认 0.7</p>
     */
    private double temperature;

    /**
     * 最大工具迭代次数
     * <p>限制 Agent 调用工具的最大轮次，防止无限循环，默认 20</p>
     */
    private int maxToolIterations;

    /**
     * 心跳检测开关（顶层兼容字段）
     * <p>启用后 Agent 会周期性执行自省轮次，默认关闭。
     * 详细参数见 {@link HeartbeatSettings}（配置中的 agent.heartbeat 节点）。</p>
     */
    private boolean heartbeatEnabled;

    /**
     * 心跳详细配置
     * <p>间隔、超时、投递目标、成本旋钮等，对齐 OpenClaw heartbeat 配置模型</p>
     */
    private HeartbeatSettings heartbeat;

    /**
     * 工作空间限制开关
     * <p>启用后 Agent 只能在工作空间内执行文件操作，默认启用</p>
     */
    private boolean restrictToWorkspace;

    /**
     * 思考模式开关
     * <p>默认开启：不注入任何关闭参数，由模型自行决定是否思考。
     * 关闭后请求中会按 provider 差异注入关闭参数
     * （ollama 用 reasoning_effort=none，其他用 enable_thinking=false）。</p>
     */
    private boolean thinkingEnabled = true;

    /**
     * 命令黑名单
     * <p>禁止执行的命令列表，为空时使用默认黑名单</p>
     */
    private List<String> commandBlacklist;

    /**
     * 危险命令人工审批（HITL）开关
     * <p>默认开启：命中安全黑名单的命令在交互式 Web 会话中不再直接拒绝，
     * 而是在 Web 控制台弹出审批卡片，用户批准后执行。非 Web 通道无审批 UI，维持硬拦截。</p>
     */
    private boolean hitlApprovalEnabled = true;

    /**
     * 进化能力配置
     * <p>包含反馈收集和 Prompt 优化功能配置</p>
     */
    private EvolutionConfig evolution;

    /**
     * 多 Agent 协同配置
     * <p>配置分布式任务协作相关参数</p>
     */
    private CollaborationSettings collaboration;

    /**
     * 构造函数，初始化默认配置
     * <p>
     * 默认配置包括：
     * <ul>
     *   <li>工作空间: ~/.tinyclaw/workspace</li>
     *   <li>模型: qwen3.5-plus</li>
     *   <li>提供商: dashscope</li>
     *   <li>最大 Token: 16384</li>
     *   <li>温度: 0.7</li>
     *   <li>最大工具迭代: 20 次</li>
     *   <li>工作空间限制: 启用</li>
     * </ul>
     * </p>
     */
    public AgentConfig() {
        this.workspace = "~/.tinyclaw/workspace";
        this.model = "qwen3.5-plus";
        this.provider = "dashscope";
        this.maxTokens = 16384;
        this.temperature = 0.7;
        this.maxToolIterations = 20;
        this.heartbeatEnabled = false;
        this.heartbeat = new HeartbeatSettings();
        this.restrictToWorkspace = true;
        this.commandBlacklist = new ArrayList<>();
        this.evolution = new EvolutionConfig();
        this.collaboration = new CollaborationSettings();
    }

    /**
     * 获取工作空间路径
     *
     * @return 工作空间路径
     */
    public String getWorkspace() {
        return workspace;
    }

    /**
     * 设置工作空间路径
     *
     * @param workspace 工作空间路径
     */
    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    /**
     * 获取 LLM 模型标识
     *
     * @return 模型标识
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置 LLM 模型标识
     *
     * @param model 模型标识
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 获取 LLM 提供商标识
     *
     * @return 提供商标识
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 设置 LLM 提供商标识
     *
     * @param provider 提供商标识
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * 获取最大 Token 数
     *
     * @return 最大 Token 数
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * 设置最大 Token 数
     *
     * @param maxTokens 最大 Token 数
     */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * 获取温度参数
     *
     * @return 温度参数值
     */
    public double getTemperature() {
        return temperature;
    }

    /**
     * 设置温度参数
     *
     * @param temperature 温度参数值，范围 0.0-1.0
     */
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /**
     * 获取最大工具迭代次数
     *
     * @return 最大迭代次数
     */
    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    /**
     * 设置最大工具迭代次数
     *
     * @param maxToolIterations 最大迭代次数
     */
    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = maxToolIterations;
    }

    /**
     * 检查心跳检测是否启用
     *
     * <p>顶层 heartbeatEnabled 与 heartbeat.enabled 任一为 true 即视为启用，
     * 兼容旧配置文件只写其一的情况。</p>
     *
     * @return 启用时返回 true
     */
    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled || (heartbeat != null && heartbeat.isEnabled());
    }

    /**
     * 设置心跳检测开关
     *
     * <p>同时同步到 heartbeat.enabled，保证两个入口真值一致。</p>
     *
     * @param heartbeatEnabled 是否启用心跳检测
     */
    public void setHeartbeatEnabled(boolean heartbeatEnabled) {
        this.heartbeatEnabled = heartbeatEnabled;
        if (this.heartbeat == null) {
            this.heartbeat = new HeartbeatSettings();
        }
        this.heartbeat.setEnabled(heartbeatEnabled);
    }

    /**
     * 获取心跳详细配置
     *
     * @return 心跳配置对象，永不为 null
     */
    public HeartbeatSettings getHeartbeat() {
        if (heartbeat == null) {
            heartbeat = new HeartbeatSettings();
        }
        return heartbeat;
    }

    /**
     * 设置心跳详细配置
     *
     * @param heartbeat 心跳配置对象
     */
    public void setHeartbeat(HeartbeatSettings heartbeat) {
        this.heartbeat = heartbeat;
    }

    /**
     * 检查工作空间限制是否启用
     *
     * @return 启用时返回 true
     */
    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }

    /**
     * 设置工作空间限制开关
     *
     * @param restrictToWorkspace 是否启用工作空间限制
     */
    public void setRestrictToWorkspace(boolean restrictToWorkspace) {
        this.restrictToWorkspace = restrictToWorkspace;
    }

    /**
     * 是否启用危险命令人工审批（HITL）
     *
     * @return 启用时返回 true
     */
    public boolean isHitlApprovalEnabled() {
        return hitlApprovalEnabled;
    }

    /**
     * 设置危险命令人工审批开关
     *
     * @param hitlApprovalEnabled 是否启用
     */
    public void setHitlApprovalEnabled(boolean hitlApprovalEnabled) {
        this.hitlApprovalEnabled = hitlApprovalEnabled;
    }

    /**
     * 检查思考模式是否启用
     *
     * @return 启用时返回 true（默认）
     */
    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    /**
     * 设置思考模式开关
     *
     * @param thinkingEnabled 是否启用思考模式
     */
    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    /**
     * 获取命令黑名单
     *
     * @return 命令黑名单列表
     */
    public List<String> getCommandBlacklist() {
        return commandBlacklist;
    }

    /**
     * 设置命令黑名单
     *
     * @param commandBlacklist 命令黑名单列表
     */
    public void setCommandBlacklist(List<String> commandBlacklist) {
        this.commandBlacklist = commandBlacklist;
    }

    /**
     * 获取进化能力配置
     *
     * @return 进化能力配置对象
     */
    public EvolutionConfig getEvolution() {
        return evolution;
    }

    /**
     * 设置进化能力配置
     *
     * @param evolution 进化能力配置对象
     */
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
    
    /**
     * 获取多 Agent 协同配置
     *
     * @return 协同配置对象
     */
    public CollaborationSettings getCollaboration() {
        return collaboration;
    }

    /**
     * 设置多 Agent 协同配置
     *
     * @param collaboration 协同配置对象
     */
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
}
