package io.leavesfly.tinyclaw.agent.evolution.strategy;

import io.leavesfly.tinyclaw.agent.evolution.EvaluationFeedback;
import io.leavesfly.tinyclaw.agent.evolution.EvolutionConfig;
import io.leavesfly.tinyclaw.agent.evolution.VariantManager;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;

import java.util.List;
import java.util.Map;

/**
 * 优化上下文，封装策略执行所需的依赖。
 * <p>
 * 提供 LLM 调用能力、反馈数据、历史变体数据和配置参数。
 */
public class OptimizationContext {

    private final LLMProvider provider;
    private final String model;
    private final EvolutionConfig config;
    private final VariantManager variantManager;

    /**
     * 近期评估反馈列表（TextGradient、OPRO 策略使用）
     */
    private List<EvaluationFeedback> recentFeedbacks;

    /**
     * 近期会话日志（SelfRefine 策略使用）
     */
    private List<String> recentSessionLog;

    public OptimizationContext(LLMProvider provider, String model,
                               EvolutionConfig config, VariantManager variantManager) {
        this.provider = provider;
        this.model = model;
        this.config = config;
        this.variantManager = variantManager;
    }

    /**
     * 调用 LLM 进行聊天。
     *
     * @param messages   消息列表
     * @param options    LLM 调用选项
     * @return LLM 响应
     */
    public LLMResponse chat(List<Message> messages, Map<String, Object> options) {
        return provider.chat(messages, null, model, options);
    }

    /**
     * 使用默认优化参数调用 LLM。
     *
     * @param userPrompt 用户提示
     * @return LLM 响应内容
     */
    public String chatWithOptimizationParams(String userPrompt) {
        List<Message> messages = List.of(Message.user(userPrompt));
        Map<String, Object> options = Map.of(
                "max_tokens", config.getOptimizationMaxTokens(),
                "temperature", config.getOptimizationTemperature());
        LLMResponse response = provider.chat(messages, null, model, options);
        return response.getContent();
    }

    /**
     * 使用低温度调用 LLM（用于应用阶段，保持改动可控）。
     *
     * @param userPrompt 用户提示
     * @return LLM 响应内容
     */
    public String chatWithLowTemperature(String userPrompt) {
        List<Message> messages = List.of(Message.user(userPrompt));
        Map<String, Object> options = Map.of(
                "max_tokens", config.getOptimizationMaxTokens(),
                "temperature", 0.2);
        LLMResponse response = provider.chat(messages, null, model, options);
        return response.getContent();
    }

    // ==================== Getters / Setters ====================

    public LLMProvider getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public EvolutionConfig getConfig() {
        return config;
    }

    public VariantManager getVariantManager() {
        return variantManager;
    }

    public List<EvaluationFeedback> getRecentFeedbacks() {
        return recentFeedbacks;
    }

    public void setRecentFeedbacks(List<EvaluationFeedback> recentFeedbacks) {
        this.recentFeedbacks = recentFeedbacks;
    }

    public List<String> getRecentSessionLog() {
        return recentSessionLog;
    }

    public void setRecentSessionLog(List<String> recentSessionLog) {
        this.recentSessionLog = recentSessionLog;
    }

    /**
     * 获取优化所需的最小反馈数量。
     */
    public int getMinFeedbacksRequired() {
        return config.getMinFeedbacksForOptimization();
    }

    /**
     * 获取采纳阈值。
     */
    public double getAdoptionThreshold() {
        return config.getAdoptionThreshold();
    }
}
