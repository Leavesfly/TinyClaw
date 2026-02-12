package io.leavesfly.tinyclaw.config;

/**
 * LLM Providers configuration
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
     * Generic provider configuration
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
    }
}
