package io.leavesfly.tinyclaw.providers;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider interface
 */
public interface LLMProvider {

    /**
     * 发送 a chat completion request
     *
     * @param messages The conversation messages
     * @param tools    Available tools (can be null)
     * @param model    The model to use
     * @param options  Additional options (temperature, max_tokens, etc.)
     * @return The LLM response
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) throws Exception;

    /**
     * 获取 the default model for this provider
     */
    String getDefaultModel();
}
