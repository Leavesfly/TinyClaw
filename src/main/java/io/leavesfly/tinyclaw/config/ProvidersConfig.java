package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.List;

/**
 * LLM 提供商配置类
 * 支持多个 LLM 提供商：OpenRouter、Anthropic、OpenAI、Gemini、智谱、Groq、vLLM、DashScope、Ollama
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
    private ProviderConfig ollama;
    
    public ProvidersConfig() {
        this.openrouter = new ProviderConfig();
        this.anthropic = new ProviderConfig();
        this.openai = new ProviderConfig();
        this.zhipu = new ProviderConfig();
        this.groq = new ProviderConfig();
        this.gemini = new ProviderConfig();
        this.vllm = new ProviderConfig();
        this.dashscope = new ProviderConfig();
        this.ollama = new ProviderConfig();
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
            zhipu, groq, vllm, dashscope, ollama
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
     * 获取 Provider 对应的名称，用于获取默认 API Base
     */
    public String getProviderName(ProviderConfig provider) {
        if (provider == openrouter) return "openrouter";
        if (provider == anthropic) return "anthropic";
        if (provider == openai) return "openai";
        if (provider == gemini) return "gemini";
        if (provider == zhipu) return "zhipu";
        if (provider == groq) return "groq";
        if (provider == vllm) return "vllm";
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
            case "groq": return "https://api.groq.com/openai/v1";
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
            return apiKey != null && !apiKey.isEmpty();
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