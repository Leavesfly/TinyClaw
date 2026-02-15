package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 主配置类，TinyClaw 系统的根配置。
 * 
 * 这是 TinyClaw 系统的根配置类，聚合了所有子系统的配置信息。
 * 
 * 配置组成部分：
 * - ModelsConfig：模型配置
 * - AgentConfig：Agent 相关配置（模型、上下文窗口、迭代次数等）
 * - ChannelsConfig：消息通道配置（Telegram、Discord、微信等）
 * - ProvidersConfig：LLM 提供商配置（API 密钥、端点等）
 * - GatewayConfig：网关服务配置
 * - ToolsConfig：工具系统配置（搜索引擎 API 密钥等）
 * - SocialNetworkConfig：社交网络配置
 * 
 * 设计特点：
 * - 使用 Jackson 注解支持 JSON 序列化和反序列化
 * - 提供合理的默认值配置
 * - 支持配置的动态更新和热重载
 * - 结构化配置便于管理和维护
 * - 提供 Builder 模式便于流畅构建配置
 * - 包含配置验证机制
 * 
 * 加载方式：
 * 1. 从 config.json 文件加载
 * 2. 从环境变量读取敏感信息
 * 3. 提供程序化配置构建方法
 * 
 * 使用示例：
 * - 方式1：加载配置文件
 *   Config config = ConfigLoader.load("~/.tinyclaw/config.json");
 * 
 * - 方式2：使用默认配置
 *   Config config = Config.defaultConfig();
 * 
 * - 方式3：使用 Builder 模式
 *   Config config = Config.builder()
 *       .workspace("~/my-workspace")
 *       .model("gpt-4")
 *       .openAiApiKey("sk-...")
 *       .maxTokens(4096)
 *       .build();
 * 
 * - 验证配置
 *   config.validate().ifPresent(error -> {
 *       System.err.println("配置错误: " + error);
 *   });
 */
public class Config {
    
    private static final String DEFAULT_OPENROUTER_BASE = "https://openrouter.ai/api/v1";        // OpenRouter 默认 API 地址
    private static final String DEFAULT_DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";  // DashScope 默认 API 地址
    
    private ModelsConfig models;                // 模型配置
    private AgentConfig agent;                  // Agent 配置
    private ChannelsConfig channels;            // 通道配置
    private ProvidersConfig providers;          // Provider 配置
    private GatewayConfig gateway;              // 网关配置
    private ToolsConfig tools;                  // 工具配置
    private SocialNetworkConfig socialNetwork;  // 社交网络配置
    
    /**
     * 构造默认配置。
     * 
     * 初始化所有配置对象为默认值。
     */
    public Config() {
        this.models = new ModelsConfig();
        this.agent = new AgentConfig();
        this.channels = new ChannelsConfig();
        this.providers = new ProvidersConfig();
        this.gateway = new GatewayConfig();
        this.tools = new ToolsConfig();
        this.socialNetwork = new SocialNetworkConfig();
    }
    
    // Getters and Setters
    public ModelsConfig getModels() {
        return models;
    }
    
    public void setModels(ModelsConfig models) {
        this.models = models;
    }
    
    public AgentConfig getAgent() {
        return agent;
    }
    
    public void setAgent(AgentConfig agent) {
        this.agent = agent;
    }
    
    public ChannelsConfig getChannels() {
        return channels;
    }
    
    public void setChannels(ChannelsConfig channels) {
        this.channels = channels;
    }
    
    public ProvidersConfig getProviders() {
        return providers;
    }
    
    public void setProviders(ProvidersConfig providers) {
        this.providers = providers;
    }
    
    public GatewayConfig getGateway() {
        return gateway;
    }
    
    public void setGateway(GatewayConfig gateway) {
        this.gateway = gateway;
    }
    
    public ToolsConfig getTools() {
        return tools;
    }
    
    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }
    
    public SocialNetworkConfig getSocialNetwork() {
        return socialNetwork;
    }
    
    public void setSocialNetwork(SocialNetworkConfig socialNetwork) {
        this.socialNetwork = socialNetwork;
    }
    
    /**
     * 获取工作空间路径。
     * 
     * 自动展开 ~ 为用户主目录。
     * 
     * @return 展开后的工作空间绝对路径
     */
    @JsonIgnore
    public String getWorkspacePath() {
        return ConfigLoader.expandHome(agent.getWorkspace());
    }
    
    /**
     * 获取第一个可用的 API Key。
     * 
     * 按优先级顺序查找：OpenRouter > Anthropic > OpenAI > Gemini > Zhipu > Groq > vLLM > DashScope
     * 
     * @return API Key，如果没有可用的返回空字符串
     */
    @JsonIgnore
    public String getApiKey() {
        return providers.getFirstValidProvider()
            .map(ProvidersConfig.ProviderConfig::getApiKey)
            .orElse("");
    }
    
    /**
     * 获取第一个可用 Provider 的 API Base。
     * 
     * 每个 Provider 都有默认的 API Base。
     * 
     * @return API Base URL，如果没有可用的返回空字符串
     */
    @JsonIgnore
    public String getApiBase() {
        // OpenRouter（优先级最高）
        if (hasValidApiKey(providers.getOpenrouter())) {
            return getProviderApiBase(providers.getOpenrouter(), DEFAULT_OPENROUTER_BASE);
        }
        
        // Zhipu
        if (hasValidApiKey(providers.getZhipu())) {
            return providers.getZhipu().getApiBase();
        }
        
        // DashScope
        if (hasValidApiKey(providers.getDashscope())) {
            return getProviderApiBase(providers.getDashscope(), DEFAULT_DASHSCOPE_BASE);
        }
        
        return "";
    }
    
    /**
     * 获取 Provider 的 API Base，如果未设置则使用默认值。
     * 
     * @param provider Provider 配置
     * @param defaultBase 默认 API Base
     * @return API Base URL
     */
    private String getProviderApiBase(ProvidersConfig.ProviderConfig provider, String defaultBase) {
        String base = provider.getApiBase();
        return isNotEmpty(base) ? base : defaultBase;
    }
    
    /**
     * 检查字符串是否非空。
     * 
     * @param str 待检查的字符串
     * @return 字符串非空返回 true，否则返回 false
     */
    private static boolean isNotEmpty(String str) {
        return str != null && !str.isEmpty();
    }
    
    /**
     * 检查 Provider 是否有有效的 API Key。
     * 
     * @param provider Provider 配置
     * @return 有有效 API Key 返回 true，否则返回 false
     */
    private boolean hasValidApiKey(ProvidersConfig.ProviderConfig provider) {
        return provider != null && isNotEmpty(provider.getApiKey());
    }
    
    /**
     * 验证配置的完整性。
     * 
     * @return 验证结果，如果有问题则返回错误信息，否则返回空
     */
    @JsonIgnore
    public java.util.Optional<String> validate() {
        // 检查是否至少配置了一个 Provider
        if (getApiKey().isEmpty()) {
            return java.util.Optional.of("未配置任何 LLM Provider 的 API Key");
        }
        
        // 检查工作空间路径
        if (!isValidWorkspace()) {
            return java.util.Optional.of("工作空间路径未配置");
        }
        
        return java.util.Optional.empty();
    }
    
    /**
     * 检查工作空间配置是否有效。
     * 
     * @return 工作空间配置有效返回 true，否则返回 false
     */
    private boolean isValidWorkspace() {
        return agent != null && 
               agent.getWorkspace() != null && 
               !agent.getWorkspace().isEmpty();
    }
    
    /**
     * 创建默认配置。
     * 
     * @return 默认配置对象
     */
    public static Config defaultConfig() {
        Config config = new Config();
        
        // 设置 Agent 默认值
        setAgentDefaults(config);
        
        // 设置 Gateway 默认值
        setGatewayDefaults(config);
        
        // 设置 Tools 默认值
        setToolsDefaults(config);
        
        return config;
    }
    
    /**
     * 设置 Agent 默认配置。
     * 
     * @param config 配置对象
     */
    private static void setAgentDefaults(Config config) {
        config.getAgent().setWorkspace("~/.tinyclaw/workspace");
        config.getAgent().setModel("qwen3-max");
        config.getAgent().setMaxTokens(8192);
        config.getAgent().setTemperature(0.7);
        config.getAgent().setMaxToolIterations(20);
    }
    
    /**
     * 设置 Gateway 默认配置。
     * 
     * @param config 配置对象
     */
    private static void setGatewayDefaults(Config config) {
        config.getGateway().setHost("0.0.0.0");
        config.getGateway().setPort(18790);
    }
    
    /**
     * 设置 Tools 默认配置。
     * 
     * @param config 配置对象
     */
    private static void setToolsDefaults(Config config) {
        config.getTools().getWeb().getSearch().setMaxResults(5);
    }
    
    /**
     * 创建配置构建器。
     * 
     * @return 配置构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * 配置构建器，用于流畅地构建配置对象。
     * 
     * 提供链式调用方法设置各项配置，最后调用 build() 方法生成配置对象。
     */
    public static class Builder {
        private final Config config;  // 构建中的配置对象
        
        /**
         * 构造构建器。
         */
        private Builder() {
            this.config = new Config();
        }
        
        /**
         * 设置工作空间路径。
         * 
         * @param workspace 工作空间路径
         * @return 构建器实例
         */
        public Builder workspace(String workspace) {
            config.getAgent().setWorkspace(workspace);
            return this;
        }
        
        /**
         * 设置模型名称。
         * 
         * @param model 模型名称
         * @return 构建器实例
         */
        public Builder model(String model) {
            config.getAgent().setModel(model);
            return this;
        }
        
        /**
         * 设置最大 Token 数。
         * 
         * @param maxTokens 最大 Token 数
         * @return 构建器实例
         */
        public Builder maxTokens(int maxTokens) {
            config.getAgent().setMaxTokens(maxTokens);
            return this;
        }
        
        /**
         * 设置温度参数。
         * 
         * @param temperature 温度参数
         * @return 构建器实例
         */
        public Builder temperature(double temperature) {
            config.getAgent().setTemperature(temperature);
            return this;
        }
        
        /**
         * 设置最大工具迭代次数。
         * 
         * @param maxIterations 最大工具迭代次数
         * @return 构建器实例
         */
        public Builder maxToolIterations(int maxIterations) {
            config.getAgent().setMaxToolIterations(maxIterations);
            return this;
        }
        
        /**
         * 设置 OpenRouter API Key。
         * 
         * @param apiKey API Key
         * @return 构建器实例
         */
        public Builder openRouterApiKey(String apiKey) {
            config.getProviders().getOpenrouter().setApiKey(apiKey);
            return this;
        }
        
        /**
         * 设置 OpenAI API Key。
         * 
         * @param apiKey API Key
         * @return 构建器实例
         */
        public Builder openAiApiKey(String apiKey) {
            config.getProviders().getOpenai().setApiKey(apiKey);
            return this;
        }
        
        /**
         * 设置网关主机地址。
         * 
         * @param host 主机地址
         * @return 构建器实例
         */
        public Builder gatewayHost(String host) {
            config.getGateway().setHost(host);
            return this;
        }
        
        /**
         * 设置网关端口。
         * 
         * @param port 端口号
         * @return 构建器实例
         */
        public Builder gatewayPort(int port) {
            config.getGateway().setPort(port);
            return this;
        }
        
        /**
         * 构建配置对象。
         * 
         * @return 配置对象
         */
        public Config build() {
            return config;
        }
    }
}
