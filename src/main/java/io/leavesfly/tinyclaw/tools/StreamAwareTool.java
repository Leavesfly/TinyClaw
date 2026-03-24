package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.providers.LLMProvider;

/**
 * 流式输出感知工具接口
 * 
 * 用于标识支持流式输出的工具。
 * 实现此接口的工具可以接收流式回调，
 * 用于在执行过程中实时输出中间结果。
 */
public interface StreamAwareTool {
    
    /**
     * 设置流式回调
     * 
     * @param callback 流式回调，可为 null
     */
    void setStreamCallback(LLMProvider.EnhancedStreamCallback callback);
}
