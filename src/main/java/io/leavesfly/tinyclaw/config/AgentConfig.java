package io.leavesfly.tinyclaw.config;

import io.leavesfly.tinyclaw.agent.evolution.EvolutionConfig;

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
     * 心跳检测开关
     * <p>启用后 Agent 会定期发送心跳信号，默认关闭</p>
     */
    private boolean heartbeatEnabled;

    /**
     * 工作空间限制开关
     * <p>启用后 Agent 只能在工作空间内执行文件操作，默认启用</p>
     */
    private boolean restrictToWorkspace;

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
     * @return 启用时返回 true
     */
    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    /**
     * 设置心跳检测开关
     *
     * @param heartbeatEnabled 是否启用心跳检测
     */
    public void setHeartbeatEnabled(boolean heartbeatEnabled) {
        this.heartbeatEnabled = heartbeatEnabled;
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
