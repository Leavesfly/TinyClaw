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
 * 
 * 加载方式：
 * 1. 从config.json文件加载
 * 2. 从环境变量读取敏感信息
 * 3. 提供程序化配置构建方法
 */
public class Config {
    
    private AgentsConfig agents;
    private ChannelsConfig channels;
    private ProvidersConfig providers;
    private GatewayConfig gateway;
    private ToolsConfig tools;
    private SocialNetworkConfig socialNetwork;
    
    public Config() {
        // 设置 defaults
        this.agents = new AgentsConfig();
        this.channels = new ChannelsConfig();
        this.providers = new ProvidersConfig();
        this.gateway = new GatewayConfig();
        this.tools = new ToolsConfig();
        this.socialNetwork = new SocialNetworkConfig();
    }
    
    // Getters and Setters
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
    
    @JsonIgnore
    public String getApiKey() {
        if (providers.getOpenrouter() != null && providers.getOpenrouter().getApiKey() != null && !providers.getOpenrouter().getApiKey().isEmpty()) {
            return providers.getOpenrouter().getApiKey();
        }
        if (providers.getAnthropic() != null && providers.getAnthropic().getApiKey() != null && !providers.getAnthropic().getApiKey().isEmpty()) {
            return providers.getAnthropic().getApiKey();
        }
        if (providers.getOpenai() != null && providers.getOpenai().getApiKey() != null && !providers.getOpenai().getApiKey().isEmpty()) {
            return providers.getOpenai().getApiKey();
        }
        if (providers.getGemini() != null && providers.getGemini().getApiKey() != null && !providers.getGemini().getApiKey().isEmpty()) {
            return providers.getGemini().getApiKey();
        }
        if (providers.getZhipu() != null && providers.getZhipu().getApiKey() != null && !providers.getZhipu().getApiKey().isEmpty()) {
            return providers.getZhipu().getApiKey();
        }
        if (providers.getGroq() != null && providers.getGroq().getApiKey() != null && !providers.getGroq().getApiKey().isEmpty()) {
            return providers.getGroq().getApiKey();
        }
        if (providers.getVllm() != null && providers.getVllm().getApiKey() != null && !providers.getVllm().getApiKey().isEmpty()) {
            return providers.getVllm().getApiKey();
        }
        if (providers.getDashscope() != null && providers.getDashscope().getApiKey() != null && !providers.getDashscope().getApiKey().isEmpty()) {
            return providers.getDashscope().getApiKey();
        }
        return "";
    }
    
    @JsonIgnore
    public String getApiBase() {
        if (providers.getOpenrouter() != null && providers.getOpenrouter().getApiKey() != null && !providers.getOpenrouter().getApiKey().isEmpty()) {
            if (providers.getOpenrouter().getApiBase() != null && !providers.getOpenrouter().getApiBase().isEmpty()) {
                return providers.getOpenrouter().getApiBase();
            }
            return "https://openrouter.ai/api/v1";
        }
        if (providers.getZhipu() != null && providers.getZhipu().getApiKey() != null && !providers.getZhipu().getApiKey().isEmpty()) {
            return providers.getZhipu().getApiBase();
        }
        if (providers.getVllm() != null && providers.getVllm().getApiKey() != null && !providers.getVllm().getApiKey().isEmpty() 
            && providers.getVllm().getApiBase() != null && !providers.getVllm().getApiBase().isEmpty()) {
            return providers.getVllm().getApiBase();
        }
        if (providers.getDashscope() != null && providers.getDashscope().getApiKey() != null && !providers.getDashscope().getApiKey().isEmpty()) {
            String dashscopeBase = providers.getDashscope().getApiBase();
            return (dashscopeBase != null && !dashscopeBase.isEmpty()) ? dashscopeBase : "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return "";
    }
    
    public static Config defaultConfig() {
        Config config = new Config();
        
        // Agents defaults
        config.getAgents().getDefaults().setWorkspace("~/.tinyclaw/workspace");
        config.getAgents().getDefaults().setModel("glm-4.7");
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
}
