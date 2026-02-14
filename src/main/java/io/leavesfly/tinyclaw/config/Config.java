package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 主配置类
 * 
 * 这是TinyClaw系统的根配置类，聚合了所有子系统的配置信息：
 * 
 * 配置组成部分：
 * - AgentsConfig：Agent相关配置（模型、上下文窗口、迭代次数等）
 * - ChannelsConfig：消息通道配置（Telegram、Discord、微信等）
 * - ProvidersConfig：LLM提供商配置（API密钥、端点等）
 * - GatewayConfig：网关服务配置
 * - ToolsConfig：工具系统配置（搜索引擎API密钥等）
 * 
 * 设计特点：
 * - 使用Jackson注解支持JSON序列化/反序列化
 * - 提供合理的默认值配置
 * - 支持配置的动态更新和热重载
 * - 结构化配置便于管理和维护
 * - 提供Builder模式便于流畅构建配置
 * - 包含配置验证机制
 * 
 * 加载方式：
 * 1. 从config.json文件加载
 * 2. 从环境变量读取敏感信息
 * 3. 提供程序化配置构建方法
 * 
 * 使用示例：
 * <pre>
 * // 方式1：加载配置文件
 * Config config = ConfigLoader.load("~/.tinyclaw/config.json");
 * 
 * // 方式2：使用默认配置
 * Config config = Config.defaultConfig();
 * 
 * // 方式3：使用Builder模式
 * Config config = Config.builder()
 *     .workspace("~/my-workspace")
 *     .model("gpt-4")
 *     .openAiApiKey("sk-...")
 *     .maxTokens(4096)
 *     .build();
 * 
 * // 验证配置
 * config.validate().ifPresent(error -> {
 *     System.err.println("配置错误: " + error);
 * });
 * </pre>
 */
public class Config {
    
    private ModelsConfig models;
    private AgentsConfig agents;
    private ChannelsConfig channels;
    private ProvidersConfig providers;
    private GatewayConfig gateway;
    private ToolsConfig tools;
    private SocialNetworkConfig socialNetwork;
    
    public Config() {
        // 设置 defaults
        this.models = new ModelsConfig();
        this.agents = new AgentsConfig();
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
    
    public AgentsConfig getAgents() {
        return agents;
    }
    
    public void setAgents(AgentsConfig agents) {
        this.agents = agents;
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
    
    @JsonIgnore
    public String getWorkspacePath() {
        return ConfigLoader.expandHome(agents.getDefaults().getWorkspace());
    }
    
    /**
     * 获取第一个可用的 API Key
     * 按优先级顺序查找：OpenRouter > Anthropic > OpenAI > Gemini > Zhipu > Groq > vLLM > DashScope
     */
    @JsonIgnore
    public String getApiKey() {
        return providers.getFirstValidProvider()
            .map(ProvidersConfig.ProviderConfig::getApiKey)
            .orElse("");
    }
    
    /**
     * 获取第一个可用 Provider 的 API Base
     * 每个 Provider 都有默认的 API Base
     */
    @JsonIgnore
    public String getApiBase() {
        // OpenRouter (优先级最高)
        if (hasValidApiKey(providers.getOpenrouter())) {
            String base = providers.getOpenrouter().getApiBase();
            return isNotEmpty(base) ? base : "https://openrouter.ai/api/v1";
        }
        
        // Zhipu
        if (hasValidApiKey(providers.getZhipu())) {
            return providers.getZhipu().getApiBase();
        }
        
        // vLLM
        if (hasValidApiKey(providers.getVllm()) && isNotEmpty(providers.getVllm().getApiBase())) {
            return providers.getVllm().getApiBase();
        }
        
        // DashScope
        if (hasValidApiKey(providers.getDashscope())) {
            String base = providers.getDashscope().getApiBase();
            return isNotEmpty(base) ? base : "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        
        return "";
    }
    
    /**
     * 检查字符串是否非空
     */
    private static boolean isNotEmpty(String str) {
        return str != null && !str.isEmpty();
    }
    
    /**
     * 检查 Provider 是否有有效的 API Key
     */
    private boolean hasValidApiKey(ProvidersConfig.ProviderConfig provider) {
        return provider != null && isNotEmpty(provider.getApiKey());
    }
    
    /**
     * 验证配置的完整性
     * @return 验证结果，如果有问题则返回错误信息
     */
    @JsonIgnore
    public java.util.Optional<String> validate() {
        // 检查是否至少配置了一个 Provider
        if (getApiKey().isEmpty()) {
            return java.util.Optional.of("未配置任何 LLM Provider 的 API Key");
        }
        
        // 检查工作空间路径
        if (agents == null || agents.getDefaults() == null || 
            agents.getDefaults().getWorkspace() == null || 
            agents.getDefaults().getWorkspace().isEmpty()) {
            return java.util.Optional.of("工作空间路径未配置");
        }
        
        return java.util.Optional.empty();
    }
    
    /**
     * 创建默认配置
     */
    public static Config defaultConfig() {
        Config config = new Config();
        
        // Agents defaults
        config.getAgents().getDefaults().setWorkspace("~/.tinyclaw/workspace");
        config.getAgents().getDefaults().setModel("qwen3-max");
        config.getAgents().getDefaults().setMaxTokens(8192);
        config.getAgents().getDefaults().setTemperature(0.7);
        config.getAgents().getDefaults().setMaxToolIterations(20);
        
        // Gateway defaults
        config.getGateway().setHost("0.0.0.0");
        config.getGateway().setPort(18790);
        
        // Tools defaults
        config.getTools().getWeb().getSearch().setMaxResults(5);
        
        return config;
    }
    
    /**
     * 创建配置构建器
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Config Builder 用于流畅地构建配置对象
     */
    public static class Builder {
        private final Config config;
        
        private Builder() {
            this.config = new Config();
        }
        
        public Builder workspace(String workspace) {
            config.getAgents().getDefaults().setWorkspace(workspace);
            return this;
        }
        
        public Builder model(String model) {
            config.getAgents().getDefaults().setModel(model);
            return this;
        }
        
        public Builder maxTokens(int maxTokens) {
            config.getAgents().getDefaults().setMaxTokens(maxTokens);
            return this;
        }
        
        public Builder temperature(double temperature) {
            config.getAgents().getDefaults().setTemperature(temperature);
            return this;
        }
        
        public Builder maxToolIterations(int maxIterations) {
            config.getAgents().getDefaults().setMaxToolIterations(maxIterations);
            return this;
        }
        
        public Builder openRouterApiKey(String apiKey) {
            config.getProviders().getOpenrouter().setApiKey(apiKey);
            return this;
        }
        
        public Builder openAiApiKey(String apiKey) {
            config.getProviders().getOpenai().setApiKey(apiKey);
            return this;
        }
        
        public Builder gatewayHost(String host) {
            config.getGateway().setHost(host);
            return this;
        }
        
        public Builder gatewayPort(int port) {
            config.getGateway().setPort(port);
            return this;
        }
        
        public Config build() {
            return config;
        }
    }
}
