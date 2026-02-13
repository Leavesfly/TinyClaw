package io.leavesfly.tinyclaw.config;

/**
 * LLM 提供商配置类
 * 支持多个 LLM 提供商：OpenRouter、Anthropic、OpenAI、Gemini、智谱、Groq、vLLM、DashScope
 */
public class ProvidersConfig {
    
    private ProviderConfig openrouter;
    private ProviderConfig anthropic;
    private ProviderConfig openai;
    private ProviderConfig zhipu;
    private ProviderConfig groq;
    private ProviderConfig gemini;
    private ProviderConfig vllm;
    private ProviderConfig dashscope;
    
    public ProvidersConfig() {
        this.openrouter = new ProviderConfig();
        this.anthropic = new ProviderConfig();
        this.openai = new ProviderConfig();
        this.zhipu = new ProviderConfig();
        this.groq = new ProviderConfig();
        this.gemini = new ProviderConfig();
        this.vllm = new ProviderConfig();
        this.dashscope = new ProviderConfig();
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
    
    public ProviderConfig getGroq() {
        return groq;
    }
    
    public void setGroq(ProviderConfig groq) {
        this.groq = groq;
    }
    
    public ProviderConfig getGemini() {
        return gemini;
    }
    
    public void setGemini(ProviderConfig gemini) {
        this.gemini = gemini;
    }
    
    public ProviderConfig getVllm() {
        return vllm;
    }
    
    public void setVllm(ProviderConfig vllm) {
        this.vllm = vllm;
    }
    
    public ProviderConfig getDashscope() {
        return dashscope;
    }
    
    public void setDashscope(ProviderConfig dashscope) {
        this.dashscope = dashscope;
    }
    
    /**
     * 获取所有 Provider，按优先级排序
     */
    public java.util.List<ProviderConfig> getAllProviders() {
        return java.util.Arrays.asList(
            openrouter, anthropic, openai, gemini, 
            zhipu, groq, vllm, dashscope
        );
    }
    
    /**
     * 获取第一个有效的 Provider
     */
    public java.util.Optional<ProviderConfig> getFirstValidProvider() {
        return getAllProviders().stream()
            .filter(p -> p != null && p.isValid())
            .findFirst();
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
         * 检查此 Provider 是否有效（有 API Key）
         */
        public boolean isValid() {
            return apiKey != null && !apiKey.isEmpty();
        }
        
        /**
         * 获取 API Base，如果未配置则返回默认值
         */
        public String getApiBaseOrDefault(String defaultBase) {
            return (apiBase != null && !apiBase.isEmpty()) ? apiBase : defaultBase;
        }
    }
}
