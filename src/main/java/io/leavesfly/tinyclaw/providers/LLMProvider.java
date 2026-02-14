package io.leavesfly.tinyclaw.providers;

import java.util.List;
import java.util.Map;

/**
 * LLM提供者接口
 */
public interface LLMProvider {

    /**
     * 发送对话完成请求
     *
     * @param messages 对话消息列表
     * @param tools    可用工具列表（可为null）
     * @param model    使用的模型
     * @param options  额外选项（temperature、max_tokens等）
     * @return LLM响应结果
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) throws Exception;

    /**
     * 获取该提供者的默认模型
     */
    String getDefaultModel();
}
