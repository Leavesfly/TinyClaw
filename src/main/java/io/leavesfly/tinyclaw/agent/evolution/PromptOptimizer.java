package io.leavesfly.tinyclaw.agent.evolution;

import io.leavesfly.tinyclaw.agent.evolution.strategy.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prompt 优化器协调器，基于评估反馈迭代优化 System Prompt。
 * <p>
 * 核心公式：Prompt(t+1) = O(Prompt(t), E)
 * <p>
 * 优化流程：
 * 1. 收集近期会话的 EvaluationFeedback
 * 2. 根据配置的策略执行优化
 * 3. 保存为候选变体，待评估后决定是否采用
 * <p>
 * 支持的优化策略：
 * <ul>
 *   <li>{@code TEXTUAL_GRADIENT} — 反馈驱动的文本梯度优化</li>
 *   <li>{@code OPRO} — 历史轨迹引导优化</li>
 *   <li>{@code SELF_REFINE} — 自我反思优化</li>
 * </ul>
 */
public class PromptOptimizer {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.optimizer");

    private final LLMProvider provider;
    private final String model;
    private final FeedbackManager feedbackManager;
    private final EvolutionConfig config;
    private final VariantManager variantManager;

    /**
     * 策略注册表
     */
    private final Map<String, OptimizationStrategy> strategies;

    /**
     * 上次优化时间戳
     */
    private final AtomicLong lastOptimizationTimeMs = new AtomicLong(0);

    /**
     * 构造 Prompt 优化器。
     *
     * @param provider        LLM 提供商
     * @param model           使用的模型
     * @param workspace       工作空间路径（用于持久化 Prompt 变体）
     * @param feedbackManager 反馈管理器
     * @param config          进化配置
     */
    public PromptOptimizer(LLMProvider provider, String model, String workspace,
                           FeedbackManager feedbackManager, EvolutionConfig config) {
        this.provider = provider;
        this.model = model;
        this.feedbackManager = feedbackManager;
        this.config = config;
        this.variantManager = new VariantManager(workspace, config);

        // 注册优化策略
        this.strategies = new LinkedHashMap<>();
        registerStrategy(new TextGradientStrategy());
        registerStrategy(new OPROStrategy());
        registerStrategy(new SelfReflectionStrategy());
    }

    /**
     * 注册优化策略。
     *
     * @param strategy 策略实例
     */
    public void registerStrategy(OptimizationStrategy strategy) {
        strategies.put(strategy.name(), strategy);
    }

    // ==================== 主要入口 ====================

    /**
     * 检查是否需要优化并执行（无会话历史版本，兼容旧调用方式）。
     *
     * @param currentPrompt 当前的 System Prompt
     * @return 优化结果，不需要优化时返回 null
     */
    public OptimizationResult maybeOptimize(String currentPrompt) {
        return maybeOptimize(currentPrompt, null);
    }

    /**
     * 检查是否需要优化并执行，支持策略路由。
     *
     * @param currentPrompt    当前的 System Prompt
     * @param recentSessionLog 最近的会话交互记录（Self-Refine 策略需要，其他策略可为 null）
     * @return 优化结果，不需要优化时返回 null
     */
    public OptimizationResult maybeOptimize(String currentPrompt, List<String> recentSessionLog) {
        if (!config.isPromptOptimizationEnabled()) {
            logger.debug("Prompt optimization is disabled");
            return null;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = config.getOptimizationIntervalHours() * 60 * 60 * 1000L;
        if (now - lastOptimizationTimeMs.get() < cooldownMs) {
            logger.debug("Optimization on cooldown", Map.of(
                    "hours_remaining", (cooldownMs - (now - lastOptimizationTimeMs.get())) / 3600000.0));
            return null;
        }

        EvolutionConfig.OptimizationStrategy strategyType = config.getOptimizationStrategy();
        logger.info("Running optimization with strategy", Map.of("strategy", strategyType.name()));

        // 创建优化上下文
        OptimizationContext context = createContext(recentSessionLog);

        // 获取策略并执行
        OptimizationStrategy strategy = strategies.get(strategyType.name());
        if (strategy == null) {
            logger.warn("Unknown strategy, falling back to TEXTUAL_GRADIENT",
                    Map.of("requested", strategyType.name()));
            strategy = strategies.get("TEXTUAL_GRADIENT");
        }

        // 检查前置条件
        if (!strategy.canOptimize(context)) {
            logger.debug("Strategy preconditions not met", Map.of("strategy", strategy.name()));
            return null;
        }

        OptimizationResult result = strategy.optimize(currentPrompt, context);

        if (result != null && result.getStatus() == OptimizationResult.Status.SUCCESS) {
            lastOptimizationTimeMs.set(System.currentTimeMillis());
            maybeAutoApply(result);
        }
        return result;
    }

    /**
     * 创建优化上下文。
     */
    private OptimizationContext createContext(List<String> recentSessionLog) {
        OptimizationContext context = new OptimizationContext(provider, model, config, variantManager);

        // 加载反馈数据（用于 TextGradient 和 OPRO）
        int lookbackDays = Math.max(7, config.getOptimizationIntervalHours() / 24 + 1);
        List<EvaluationFeedback> recentFeedbacks = feedbackManager.getRecentAggregatedFeedbacks(lookbackDays);
        context.setRecentFeedbacks(recentFeedbacks);

        // 设置会话日志（用于 SelfRefine）
        context.setRecentSessionLog(recentSessionLog);

        return context;
    }

    /**
     * 如果配置了自动应用且结果建议采用，则自动激活优化后的 Prompt。
     */
    private void maybeAutoApply(OptimizationResult result) {
        if (config.isAutoApplyOptimization() && result.isRecommendAdoption()) {
            variantManager.setActivePrompt(result.getOptimizedPrompt());
            logger.info("Auto-applied optimized prompt", Map.of(
                    "strategy", result.getStrategy() != null ? result.getStrategy() : "unknown"));
        }
    }

    // ==================== 向后兼容的公共方法 ====================

    /**
     * 执行文本梯度优化（向后兼容）。
     *
     * @param currentPrompt 当前的 System Prompt
     * @param feedbacks     评估反馈列表
     * @return 优化结果
     */
    public OptimizationResult optimizeWithTextualGradient(String currentPrompt,
                                                          List<EvaluationFeedback> feedbacks) {
        OptimizationContext context = new OptimizationContext(provider, model, config, variantManager);
        context.setRecentFeedbacks(feedbacks);

        TextGradientStrategy strategy = (TextGradientStrategy) strategies.get("TEXTUAL_GRADIENT");
        OptimizationResult result = strategy.optimize(currentPrompt, context);

        if (result != null && result.getStatus() == OptimizationResult.Status.SUCCESS) {
            maybeAutoApply(result);
        }
        return result;
    }

    /**
     * 执行 OPRO 优化（向后兼容）。
     *
     * @param currentPrompt 当前的 System Prompt
     * @param feedbacks     评估反馈列表
     * @return 优化结果
     */
    public OptimizationResult optimizeWithOPRO(String currentPrompt,
                                               List<EvaluationFeedback> feedbacks) {
        OptimizationContext context = new OptimizationContext(provider, model, config, variantManager);
        context.setRecentFeedbacks(feedbacks);

        OPROStrategy strategy = (OPROStrategy) strategies.get("OPRO");
        OptimizationResult result = strategy.optimize(currentPrompt, context);

        if (result != null && result.getStatus() == OptimizationResult.Status.SUCCESS) {
            maybeAutoApply(result);
        }
        return result;
    }

    /**
     * 执行 Self-Refine 优化（向后兼容）。
     *
     * @param currentPrompt    当前的 System Prompt
     * @param recentSessionLog 最近的会话交互记录
     * @return 优化结果
     */
    public OptimizationResult optimizeWithSelfRefine(String currentPrompt,
                                                     List<String> recentSessionLog) {
        OptimizationContext context = new OptimizationContext(provider, model, config, variantManager);
        context.setRecentSessionLog(recentSessionLog);

        SelfReflectionStrategy strategy = (SelfReflectionStrategy) strategies.get("SELF_REFINE");
        OptimizationResult result = strategy.optimize(currentPrompt, context);

        if (result != null && result.getStatus() == OptimizationResult.Status.SUCCESS) {
            maybeAutoApply(result);
        }
        return result;
    }

    /**
     * 生成文本梯度（向后兼容）。
     */
    public String generateTextualGradient(String currentPrompt,
                                          List<EvaluationFeedback> feedbacks,
                                          Object summary) {
        OptimizationContext context = new OptimizationContext(provider, model, config, variantManager);
        context.setRecentFeedbacks(feedbacks);

        TextGradientStrategy strategy = (TextGradientStrategy) strategies.get("TEXTUAL_GRADIENT");
        TextGradientStrategy.FeedbackSummary feedbackSummary = strategy.summarizeFeedbacks(feedbacks);
        return strategy.generateTextualGradient(currentPrompt, feedbacks, feedbackSummary, context);
    }

    /**
     * 应用文本梯度（向后兼容）。
     */
    public String applyGradient(String currentPrompt, String textualGradient) {
        OptimizationContext context = new OptimizationContext(provider, model, config, variantManager);
        TextGradientStrategy strategy = (TextGradientStrategy) strategies.get("TEXTUAL_GRADIENT");
        return strategy.applyGradient(currentPrompt, textualGradient, context);
    }

    // ==================== 活跃 Prompt 管理（委托给 VariantManager） ====================

    /**
     * 检查是否有活跃的优化 Prompt。
     */
    public boolean hasActiveOptimization() {
        return variantManager.hasActiveOptimization();
    }

    /**
     * 获取当前活跃的优化 Prompt。
     *
     * @return 活跃 Prompt，无优化时返回 null
     */
    public String getActiveOptimization() {
        return variantManager.getActiveOptimization();
    }

    /**
     * 手动激活指定变体。
     *
     * @param variantId 变体 ID
     * @return 激活成功返回 true
     */
    public boolean activateVariant(String variantId) {
        return variantManager.activateVariant(variantId);
    }

    /**
     * 清除活跃优化，恢复默认。
     */
    public void clearOptimization() {
        variantManager.clearOptimization();
    }

    /**
     * 获取优化统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("last_optimization_time", lastOptimizationTimeMs.get());
        stats.put("current_version", variantManager.getCurrentVersion());
        stats.put("has_active_optimization", hasActiveOptimization());
        stats.put("variant_count", variantManager.getVariantCount());
        return stats;
    }

    // ==================== 内部数据类（向后兼容） ====================

    /**
     * Prompt 变体信息（向后兼容，委托给 VariantManager.PromptVariantInfo）
     */
    public static class PromptVariantInfo extends VariantManager.PromptVariantInfo {
        public PromptVariantInfo() {
            super();
        }

        public PromptVariantInfo(String id, String prompt, double score,
                                 java.time.Instant createdAt, Map<String, Object> metadata) {
            super(id, prompt, score, createdAt, metadata);
        }
    }
}
