package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置
 * 通过 definitions 定义具体模型与 provider 的映射关系
 */
public class ModelsConfig {
    
    /**
     * 模型定义映射表
     * key: 模型名称（如 "qwen3-max", "gpt-4o"）
     * value: 模型定义
     */
    @JsonProperty("definitions")
    private Map<String, ModelDefinition> definitions = new HashMap<>();
    
    public ModelsConfig() {
        // 添加常用模型的默认定义
        
        // 通义千问系列
        definitions.put("qwen3-max", new ModelDefinition("dashscope", "qwen3-max", 200000));
        definitions.put("qwen3-turbo", new ModelDefinition("dashscope", "qwen3-turbo", 128000));
        definitions.put("qwen3-next-80b-a3b-thinking", new ModelDefinition("dashscope", "qwen3-next-80b-a3b-thinking", 200000));
        
        // GPT 系列
        definitions.put("gpt-4o", new ModelDefinition("openai", "gpt-4o", 128000));
        definitions.put("gpt-4o-mini", new ModelDefinition("openai", "gpt-4o-mini", 128000));
        definitions.put("gpt-4-turbo", new ModelDefinition("openai", "gpt-4-turbo", 128000));
        
        // Claude 系列
        definitions.put("claude-3-5-sonnet-20241022", new ModelDefinition("anthropic", "claude-3-5-sonnet-20241022", 200000));
        definitions.put("claude-3-5-haiku-20241022", new ModelDefinition("anthropic", "claude-3-5-haiku-20241022", 200000));
        
        // 智谱系列
        definitions.put("glm-4-plus", new ModelDefinition("zhipu", "glm-4-plus", 128000));
        definitions.put("glm-4-flash", new ModelDefinition("zhipu", "glm-4-flash", 128000));
        
        // Gemini 系列
        definitions.put("gemini-2.0-flash-exp", new ModelDefinition("gemini", "gemini-2.0-flash-exp", 1000000));
        
        // 本地模型示例
        definitions.put("qwen2.5", new ModelDefinition("ollama", "qwen2.5", 32768));
        definitions.put("llama3.1", new ModelDefinition("ollama", "llama3.1", 128000));
    }
    
    public Map<String, ModelDefinition> getDefinitions() {
        return definitions;
    }
    
    public void setDefinitions(Map<String, ModelDefinition> definitions) {
        this.definitions = definitions;
    }
    
    /**
     * 模型定义
     */
    public static class ModelDefinition {
        /**
         * 使用的提供商（必须在 providers 中定义）
         */
        @JsonProperty("provider")
        private String provider;
        
        /**
         * 实际的模型名称
         */
        @JsonProperty("model")
        private String model;
        
        /**
         * 最大上下文长度（Token）
         */
        @JsonProperty("max_context_size")
        private Integer maxContextSize;
        
        /**
         * 模型描述（可选）
         */
        @JsonProperty("description")
        private String description;
        
        public ModelDefinition() {
        }
        
        public ModelDefinition(String provider, String model, Integer maxContextSize) {
            this.provider = provider;
            this.model = model;
            this.maxContextSize = maxContextSize;
        }
        
        public String getProvider() {
            return provider;
        }
        
        public void setProvider(String provider) {
            this.provider = provider;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public Integer getMaxContextSize() {
            return maxContextSize;
        }
        
        public void setMaxContextSize(Integer maxContextSize) {
            this.maxContextSize = maxContextSize;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
    }
}
