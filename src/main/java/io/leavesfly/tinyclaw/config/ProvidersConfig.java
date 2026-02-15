package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * LLM 提供商配置类
 * 支持多个 LLM 提供商：OpenRouter、Anthropic、OpenAI、Gemini、智谱、DashScope、Ollama
 */
public class ProvidersConfig {
    
    private ProviderConfig openrouter;
    private ProviderConfig anthropic;
    private ProviderConfig openai;
    private ProviderConfig zhipu;
    private ProviderConfig gemini;
    private ProviderConfig dashscope;
    private ProviderConfig ollama;
    
    public ProvidersConfig() {
        this.openrouter = new ProviderConfig(getDefaultApiBase("openrouter"));
        this.anthropic = new ProviderConfig(getDefaultApiBase("anthropic"));
        this.openai = new ProviderConfig(getDefaultApiBase("openai"));
        this.zhipu = new ProviderConfig(getDefaultApiBase("zhipu"));
        this.gemini = new ProviderConfig(getDefaultApiBase("gemini"));
        this.dashscope = new ProviderConfig(getDefaultApiBase("dashscope"));
        this.ollama = new ProviderConfig(getDefaultApiBase("ollama"));
    }
    
    // Getters and Setters
    public ProviderConfig getOpenrouter() {
        return openrouter;
    }
    
    public void setOpenrouter(ProviderConfig openrouter) {
        this.openrouter = openrouter;
    }
    
    public ProviderConfig getAnthropic() {
        return anthropic;
    }
    
    public void setAnthropic(ProviderConfig anthropic) {
        this.anthropic = anthropic;
    }
    
    public ProviderConfig getOpenai() {
        return openai;
    }
    
    public void setOpenai(ProviderConfig openai) {
        this.openai = openai;
    }
    
    public ProviderConfig getZhipu() {
        return zhipu;
    }
    
    public void setZhipu(ProviderConfig zhipu) {
        this.zhipu = zhipu;
    }
 
    
    public ProviderConfig getGemini() {
        return gemini;
    }
    
    public void setGemini(ProviderConfig gemini) {
        this.gemini = gemini;
    }
    
    
    public ProviderConfig getDashscope() {
        return dashscope;
    }
    
    public void setDashscope(ProviderConfig dashscope) {
        this.dashscope = dashscope;
    }
    
    public ProviderConfig getOllama() {
        return ollama;
    }
    
    public void setOllama(ProviderConfig ollama) {
        this.ollama = ollama;
    }
    
    /**
     * 获取所有 Provider，按优先级排序
     */
    @JsonIgnore
    public List<ProviderConfig> getAllProviders() {
        return Arrays.asList(
            openrouter, anthropic, openai, gemini, 
            zhipu, dashscope, ollama
        );
    }
    
    /**
     * 获取第一个有效的 Provider
     */
    @JsonIgnore
    public java.util.Optional<ProviderConfig> getFirstValidProvider() {
        return getAllProviders().stream()
            .filter(p -> p != null && p.isValid())
            .findFirst();
    }
    
    /**
     * 获取第一个可用的 Provider（优先 ollama，其次其他有效 provider）
     * ollama 只需要 apiBase，其他 provider 需要 apiKey
     * 返回 provider 的名称和配置
     */
    @JsonIgnore
    public Optional<ProviderWithName> getFirstAvailableProvider() {
        // 优先检查 ollama（本地服务）
        if (ollama != null && ollama.isValidForLocal()) {
            return Optional.of(new ProviderWithName("ollama", ollama));
        }
        
        // 按优先级查找其他有效的 provider
        List<ProviderWithName> providers = Arrays.asList(
            new ProviderWithName("openrouter", openrouter),
            new ProviderWithName("openai", openai),
            new ProviderWithName("anthropic", anthropic),
            new ProviderWithName("zhipu", zhipu),
            new ProviderWithName("dashscope", dashscope),
            new ProviderWithName("gemini", gemini)
        );
        
        return providers.stream()
            .filter(p -> p.config != null && p.config.isValid())
            .findFirst();
    }
    
    /**
     * Provider 配置及其名称
     */
    public static class ProviderWithName {
        public final String name;
        public final ProviderConfig config;
        
        public ProviderWithName(String name, ProviderConfig config) {
            this.name = name;
            this.config = config;
        }
    }
    
    /**
     * 获取 Provider 对应的名称，用于获取默认 API Base
     */
    public String getProviderName(ProviderConfig provider) {
        if (provider == openrouter) return "openrouter";
        if (provider == anthropic) return "anthropic";
        if (provider == openai) return "openai";
        if (provider == gemini) return "gemini";
        if (provider == zhipu) return "zhipu";
        if (provider == dashscope) return "dashscope";
        if (provider == ollama) return "ollama";
        return "unknown";
    }
    
    /**
     * 根据 Provider 名称获取默认的 API Base URL
     */
    public static String getDefaultApiBase(String providerName) {
        switch (providerName) {
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "anthropic": return "https://api.anthropic.com/v1";
            case "openai": return "https://api.openai.com/v1";
            case "gemini": return "https://generativelanguage.googleapis.com/v1beta";
            case "zhipu": return "https://open.bigmodel.cn/api/paas/v4";
            case "dashscope": return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "ollama": return "http://localhost:11434/v1";
            default: return "https://openrouter.ai/api/v1";
        }
    }
    
    /**
     * 通用 Provider 配置
     * 包含 API Key 和 API Base 地址
     */
    public static class ProviderConfig {
        private String apiKey;
        private String apiBase;
        
        public ProviderConfig() {
            this.apiKey = "";
            this.apiBase = "";
        }
        
        public ProviderConfig(String defaultApiBase) {
            this.apiKey = "";
            this.apiBase = defaultApiBase;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public String getApiBase() {
            return apiBase;
        }
        
        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }
        
        /**
         * 检查此 Provider 是否有效
         * 对于本地部署服务（vllm/ollama），只需要有 apiBase 即可
         * 对于云服务，需要有 apiKey
         */
        @JsonIgnore
        public boolean isValid() {
            // apiKey 非空即为有效
            return apiKey != null && !apiKey.isEmpty();
        }
        
        /**
         * 检查此 Provider 是否为本地服务并且有效
         * 本地服务（如 ollama）只需要 apiBase 即可，不需要 apiKey
         */
        @JsonIgnore
        public boolean isValidForLocal() {
            return hasApiBase();
        }
        
        /**
         * 检查是否配置了 API Base（用于 vllm/ollama 等本地服务）
         */
        @JsonIgnore
        public boolean hasApiBase() {
            return apiBase != null && !apiBase.isEmpty();
        }
        
        /**
         * 获取 API Base，如果未配置则返回默认值
         */
        public String getApiBaseOrDefault(String defaultBase) {
            return (apiBase != null && !apiBase.isEmpty()) ? apiBase : defaultBase;
        }
    }
}