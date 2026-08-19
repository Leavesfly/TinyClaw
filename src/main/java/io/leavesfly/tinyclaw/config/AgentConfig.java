package io.leavesfly.tinyclaw.config;

import io.leavesfly.tinyclaw.evolution.EvolutionConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    
    /**
     * 多 Agent 协同配置类
     * <p>
     * 定义多 Agent 协作时的行为参数，包括：
     * <ul>
     *   <li>协同开关 - 控制是否启用多 Agent 协作</li>
     *   <li>轮次限制 - 控制协作对话的最大轮次</li>
     *   <li>共识阈值 - 控制达成共识的最低置信度</li>
     *   <li>超时设置 - 控制协作任务的最大执行时间</li>
     *   <li>角色模板 - 预定义的 Agent 角色配置</li>
     * </ul>
     * </p>
     */
    public static class CollaborationSettings {

        /**
         * 协同能力开关
         * <p>默认启用</p>
         */
        private boolean enabled = true;

        /**
         * 默认最大协作轮次
         * <p>限制单次协作任务的对话轮数，默认 3 轮</p>
         */
        private int defaultMaxRounds = 3;

        /**
         * 默认共识阈值
         * <p>Agent 达成共识所需的最低置信度，范围 0.0-1.0，默认 0.6</p>
         */
        private double defaultConsensusThreshold = 0.6;

        /**
         * 协同超时时间（毫秒）
         * <p>0 表示不限制超时</p>
         */
        private long timeoutMs = 0;

        /**
         * 预定义角色模板映射
         * <p>按场景分类的角色模板，key 为场景名称，value 为角色模板列表</p>
         */
        private Map<String, List<RoleTemplate>> roleTemplates = new HashMap<>();
        
        /**
         * 检查协同能力是否启用
         *
         * @return 启用时返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置协同能力开关
         *
         * @param enabled 是否启用协同能力
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取默认最大协作轮次
         *
         * @return 最大轮次数
         */
        public int getDefaultMaxRounds() {
            return defaultMaxRounds;
        }

        /**
         * 设置默认最大协作轮次
         *
         * @param defaultMaxRounds 最大轮次数
         */
        public void setDefaultMaxRounds(int defaultMaxRounds) {
            this.defaultMaxRounds = defaultMaxRounds;
        }

        /**
         * 获取默认共识阈值
         *
         * @return 共识阈值
         */
        public double getDefaultConsensusThreshold() {
            return defaultConsensusThreshold;
        }

        /**
         * 设置默认共识阈值
         *
         * @param defaultConsensusThreshold 共识阈值，范围 0.0-1.0
         */
        public void setDefaultConsensusThreshold(double defaultConsensusThreshold) {
            this.defaultConsensusThreshold = defaultConsensusThreshold;
        }

        /**
         * 获取协同超时时间
         *
         * @return 超时时间（毫秒），0 表示不限制
         */
        public long getTimeoutMs() {
            return timeoutMs;
        }

        /**
         * 设置协同超时时间
         *
         * @param timeoutMs 超时时间（毫秒），0 表示不限制
         */
        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        /**
         * 获取预定义角色模板映射
         *
         * @return 角色模板映射
         */
        public Map<String, List<RoleTemplate>> getRoleTemplates() {
            return roleTemplates;
        }

        /**
         * 设置预定义角色模板映射
         *
         * @param roleTemplates 角色模板映射
         */
        public void setRoleTemplates(Map<String, List<RoleTemplate>> roleTemplates) {
            this.roleTemplates = roleTemplates;
        }
    }
    
    /**
     * 心跳配置类（对齐 OpenClaw heartbeat 配置模型）
     * <p>
     * 定义周期性自省轮次的行为参数，包括：
     * <ul>
     *   <li>调度参数 - 间隔、超时</li>
     *   <li>提示词 - 默认 prompt 体可整体覆盖</li>
     *   <li>成本旋钮 - 隔离会话、轻量上下文、心跳专用模型</li>
     *   <li>投递契约 - 告警投递目标与可见性开关</li>
     *   <li>活跃时段 - activeHours 窗口外跳过</li>
     *   <li>per-agent 条目 - 按角色/agent 名覆盖默认配置</li>
     * </ul>
     * </p>
     */
    public static class HeartbeatSettings {

        /**
         * 心跳开关
         * <p>默认关闭；与顶层 heartbeatEnabled 双向同步</p>
         */
        private boolean enabled = false;

        /**
         * 心跳间隔（秒）
         * <p>默认 1800（30 分钟）；0 或负数表示禁用</p>
         */
        private int intervalSeconds = 1800;

        /**
         * 单轮心跳超时（秒）
         * <p>默认 0，表示取 min(intervalSeconds, 600)</p>
         */
        private int timeoutSeconds = 0;

        /**
         * 自定义 prompt 体
         * <p>null 时使用内置默认 prompt（含 HEARTBEAT_OK 契约指令）</p>
         */
        private String prompt;

        /**
         * 心跳专用模型覆盖
         * <p>null 时使用主模型。为规避 model bleed（心跳模型遗留到主会话），
         * 仅在 isolatedSession=true 时生效。</p>
         */
        private String model;

        /**
         * 隔离会话开关
         * <p>默认 true：每轮使用一次性 sessionKey，跑完即删，
         * 避免心跳会话历史无限膨胀</p>
         */
        private boolean isolatedSession = true;

        /**
         * 轻量上下文开关
         * <p>默认 false；为 true 时跳过 workspace bootstrap 文件注入，降低成本</p>
         */
        private boolean lightContext = false;

        /**
         * 告警投递目标
         * <p>none（默认，仅记日志）| last（投递到最近一次入站消息的 channel/chatId）
         * | 显式 channel 名</p>
         */
        private String target = "none";

        /**
         * 是否可见 HEARTBEAT_OK 结果
         * <p>默认 false（OK 静默丢弃）</p>
         */
        private boolean showOk = false;

        /**
         * 是否可见告警内容
         * <p>默认 true；与 showOk 均关闭时整轮跳过</p>
         */
        private boolean showAlerts = true;

        /**
         * 活跃时段
         * <p>窗口外跳过心跳；null 表示不限制时段</p>
         */
        private ActiveHours activeHours;

        /**
         * per-agent 心跳条目
         * <p>key 为角色/agent 名；任一 entry 存在时仅这些 agent 跑心跳，
         * entry 中未设置的字段回落到顶层默认值</p>
         */
        private Map<String, HeartbeatSettings> entries;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
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

        public boolean isIsolatedSession() {
            return isolatedSession;
        }

        public void setIsolatedSession(boolean isolatedSession) {
            this.isolatedSession = isolatedSession;
        }

        public boolean isLightContext() {
            return lightContext;
        }

        public void setLightContext(boolean lightContext) {
            this.lightContext = lightContext;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public boolean isShowOk() {
            return showOk;
        }

        public void setShowOk(boolean showOk) {
            this.showOk = showOk;
        }

        public boolean isShowAlerts() {
            return showAlerts;
        }

        public void setShowAlerts(boolean showAlerts) {
            this.showAlerts = showAlerts;
        }

        public ActiveHours getActiveHours() {
            return activeHours;
        }

        public void setActiveHours(ActiveHours activeHours) {
            this.activeHours = activeHours;
        }

        public Map<String, HeartbeatSettings> getEntries() {
            return entries;
        }

        public void setEntries(Map<String, HeartbeatSettings> entries) {
            this.entries = entries;
        }

        /**
         * 计算有效超时（秒）
         *
         * @return timeoutSeconds > 0 时直接返回，否则取 min(intervalSeconds, 600)
         */
        public int effectiveTimeoutSeconds() {
            if (timeoutSeconds > 0) {
                return timeoutSeconds;
            }
            return Math.min(Math.max(intervalSeconds, 1), 600);
        }

        /**
         * 将当前 entry 叠加到基础配置之上（per-agent 合并）
         *
         * <p>本对象中显式设置（非默认占位）的字段覆盖 base 对应字段；
         * 实现上按"null 字段回填"语义：仅当本对象字段为 null/未启用时取 base 值。
         * 数值型无法区分"未设置"，故 intervalSeconds/timeoutSeconds 恒以 entry 为准，
         * entry 侧应保持合理默认（1800/0）。</p>
         *
         * @param base 顶层默认配置
         * @return 合并后的新配置对象
         */
        public HeartbeatSettings mergedOver(HeartbeatSettings base) {
            HeartbeatSettings merged = new HeartbeatSettings();
            merged.enabled = this.enabled || (base != null && base.enabled);
            merged.intervalSeconds = this.intervalSeconds;
            merged.timeoutSeconds = this.timeoutSeconds;
            merged.prompt = this.prompt != null ? this.prompt : (base != null ? base.prompt : null);
            merged.model = this.model != null ? this.model : (base != null ? base.model : null);
            merged.isolatedSession = this.isolatedSession;
            merged.lightContext = this.lightContext;
            merged.target = this.target != null ? this.target : (base != null ? base.target : "none");
            merged.showOk = this.showOk;
            merged.showAlerts = this.showAlerts;
            merged.activeHours = this.activeHours != null ? this.activeHours : (base != null ? base.activeHours : null);
            return merged;
        }
    }

    /**
     * 心跳活跃时段配置
     * <p>
     * 仅当系统时间落在 [start, end) 窗口内才执行心跳；
     * start == end 视为零宽窗口（全部跳过）。时区缺省用系统时区。
     * 时间格式为 HH:mm（24 小时制）。
     * </p>
     */
    public static class ActiveHours {

        /**
         * 窗口开始时间（HH:mm，含）
         */
        private String start;

        /**
         * 窗口结束时间（HH:mm，不含）
         */
        private String end;

        /**
         * 时区 ID（如 Asia/Shanghai）
         * <p>null 或空时使用系统默认时区</p>
         */
        private String timezone;

        public ActiveHours() {}

        public ActiveHours(String start, String end, String timezone) {
            this.start = start;
            this.end = end;
            this.timezone = timezone;
        }

        public String getStart() {
            return start;
        }

        public void setStart(String start) {
            this.start = start;
        }

        public String getEnd() {
            return end;
        }

        public void setEnd(String end) {
            this.end = end;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }

    /**
     * 角色模板定义类
     * <p>
     * 定义 Agent 在协作中扮演的角色，包括：
     * <ul>
     *   <li>角色名称 - 标识角色的唯一名称</li>
     *   <li>角色提示词 - 定义角色行为的系统提示</li>
     *   <li>模型配置 - 角色使用的特定模型（可选）</li>
     * </ul>
     * </p>
     */
    public static class RoleTemplate {

        /**
         * 角色名称
         */
        private String name;

        /**
         * 角色系统提示词
         * <p>定义角色的行为模式和响应风格</p>
         */
        private String prompt;

        /**
         * 角色使用的模型标识（可选）
         * <p>为空时使用全局默认模型</p>
         */
        private String model;

        /**
         * 默认构造函数
         */
        public RoleTemplate() {}

        /**
         * 构造函数
         *
         * @param name  角色名称
         * @param prompt 角色系统提示词
         */
        public RoleTemplate(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
        }

        /**
         * 获取角色名称
         *
         * @return 角色名称
         */
        public String getName() {
            return name;
        }

        /**
         * 设置角色名称
         *
         * @param name 角色名称
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 获取角色系统提示词
         *
         * @return 系统提示词
         */
        public String getPrompt() {
            return prompt;
        }

        /**
         * 设置角色系统提示词
         *
         * @param prompt 系统提示词
         */
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        /**
         * 获取角色使用的模型标识
         *
         * @return 模型标识，可能为 null
         */
        public String getModel() {
            return model;
        }

        /**
         * 设置角色使用的模型标识
         *
         * @param model 模型标识
         */
        public void setModel(String model) {
            this.model = model;
        }
    }
}
