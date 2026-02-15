package io.leavesfly.tinyclaw.providers;

import java.util.List;
import java.util.Map;

/**
 * LLM提供者接口
 */
public interface LLMProvider {

    /**
     * 流式输出回调接口
     */
    @FunctionalInterface
    interface StreamCallback {
        /**
         * 当接收到流式内容块时调用
         *
         * @param content 内容块
         */
        void onChunk(String content);
    }

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
     * 发送流式对话完成请求
     *
     * @param messages 对话消息列表
     * @param tools    可用工具列表（可为null）
     * @param model    使用的模型
     * @param options  额外选项（temperature、max_tokens等）
     * @param callback 流式内容回调
     * @return 完整的LLM响应结果（用于获取工具调用等信息）
     */
    LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options, StreamCallback callback) throws Exception;

    /**
     * 获取该提供者的默认模型
     */
    String getDefaultModel();
}
