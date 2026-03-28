package io.leavesfly.tinyclaw.providers;

import java.util.List;
import java.util.Map;

/**
 * LLM提供者接口
 */
public interface LLMProvider {

    /**
     * 流式输出回调接口（基础版本，仅支持文本内容）
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
     * 增强的流式输出回调接口，支持多种类型的事件。
     * 
     * 可用于输出工具调用、子代理执行、多 Agent 协同等过程信息。
     */
    interface EnhancedStreamCallback extends StreamCallback {
        /**
         * 当接收到流式事件时调用
         *
         * @param event 流式事件
         */
        void onEvent(StreamEvent event);
        
        /**
         * 默认实现：将内容事件转换为普通 chunk 调用
         */
        @Override
        default void onChunk(String content) {
            onEvent(StreamEvent.content(content));
        }
        
        /**
         * 创建一个包装器，将基础 StreamCallback 包装为 EnhancedStreamCallback。
         * 对于非内容事件，会调用 format() 方法格式化后输出。
         */
        static EnhancedStreamCallback wrap(StreamCallback callback) {
            if (callback == null) return null;
            if (callback instanceof EnhancedStreamCallback enhanced) {
                return enhanced;
            }
            return event -> {
                // 对于内容事件，直接输出内容
                if (event.getType() == StreamEvent.EventType.CONTENT) {
                    callback.onChunk(event.getContent());
                } else {
                    // 对于其他事件，格式化后输出
                    callback.onChunk(event.format());
                }
            };
        }
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
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options);

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
    LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options, StreamCallback callback);

    /**
     * 获取该提供者的默认模型
     */
    String getDefaultModel();

    /**
     * 获取该提供者的名称（如 dashscope、openai、ollama 等）
     */
    String getName();
}