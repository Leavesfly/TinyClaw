package io.leavesfly.tinyclaw.providers;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider interface
 *
 * <p>学习提示：如果你要接入一种完全不兼容 OpenAI 协议的新提供商，可以实现这个接口，
 * 再在 HTTPProvider.createProvider 或调用方根据模型前缀/配置选择具体实现。</p>
 */
public interface LLMProvider {
    
    /**
     * 发送 a chat completion request
     * 
     * @param messages The conversation messages
     * @param tools Available tools (can be null)
     * @param model The model to use
     * @param options Additional options (temperature, max_tokens, etc.)
     * @return The LLM response
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) throws Exception;
    
    /**
     * 获取 the default model for this provider
     */
    String getDefaultModel();
}
