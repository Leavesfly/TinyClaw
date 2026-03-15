package io.leavesfly.tinyclaw.agent.evolution;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Prompt 优化器，基于评估反馈迭代优化 System Prompt。
 *
 * 核心公式：Prompt(t+1) = O(Prompt(t), E)
 *
 * 优化流程：
 * 1. 收集近期会话的 EvaluationFeedback
 * 2. 分析反馈中的问题模式
 * 3. 使用 LLM 生成文本梯度（优化建议）
 * 4. 应用梯度到当前 Prompt，生成优化版本
 * 5. 保存为候选变体，待评估后决定是否采用
 *
 * 支持的优化策略：
 * - TEXT_GRADIENT：基于文本梯度的微调（默认）
 * - EVO_PROMPT：进化算法驱动的变体生成
 * - MIPRO：多指标迭代优化
 */
public class PromptOptimizer {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.optimizer");

    /** 优化冷却时间（毫秒） */
    private static final long DEFAULT_COOLDOWN_MS = 6 * 60 * 60 * 1000L; // 6 小时

   /** 文本梯度生成的 Prompt 模板 */
    private static final String GRADIENT_PROMPT_TEMPLATE = """
        你是一位专业的 Prompt 工程师。请分析以下关于 AI 助手表现的用户反馈，并生成优化建议。
        
        ## 当前系统提示词（身份部分）
        %s
        
        ## 用户反馈摘要
        - 平均满意度评分: %.2f
        - 反馈样本总数: %d
        - 正面反馈比例: %.1f%%
        
        ## 反馈详情分析
        %s
        
        ## 任务
        基于以上反馈，提供具体可执行的优化建议。
        重点关注：
        1. 识别出的弱点或常见问题
        2. 缺失的能力或指令
        3. 不清晰或模糊的指导
        4. 提升帮助性、准确性或用户满意度的机会
        
        以编号列表形式输出具体的改进建议。
        建议要具体可执行，避免"更有帮助"这类模糊表述。
        """;

    /** 应用梯度的 Prompt 模板 */
    private static final String APPLY_GRADIENT_TEMPLATE = """
        你是一位专业的 Prompt 工程师。请将以下优化建议应用到系统提示词中。
        
        ## 原始系统提示词（身份部分）
        %s
        
        ## 优化建议
        %s
        
        ## 任务
        生成改进后的系统提示词（身份/行为部分）。
        - 自然地融入优化建议
        - 保持核心身份和目的不变
        - 保持格式和结构一致
        - 不要添加冗长的解释，保持简洁
        - 仅输出改进后的提示词文本，不要添加解释
        """;

    private final LLMProvider provider;
    private final String model;
    private final PromptStore promptStore;
    private final FeedbackCollector feedbackCollector;
    private final EvolutionConfig config;

    /** 上次优化时间戳 */
    private final AtomicLong lastOptimizationTimeMs = new AtomicLong(0);

    /**
     * 构造 Prompt 优化器。
     *
     * @param provider          LLM 提供商
     * @param model             使用的模型
     * @param promptStore       Prompt 存储
     * @param feedbackCollector 反馈收集器
     * @param config            进化配置
     */
    public PromptOptimizer(LLMProvider provider, String model, PromptStore promptStore,
                           FeedbackCollector feedbackCollector, EvolutionConfig config) {
        this.provider = provider;
        this.model = model;
        this.promptStore = promptStore;
        this.feedbackCollector = feedbackCollector;
        this.config = config;
    }

    // ==================== 主要入口 ====================

    /**
     * 检查是否需要优化并执行。
     *
     * 条件：
     * 1. 配置启用了 Prompt 优化
     * 2. 超过冷却时间
     * 3. 有足够的反馈数据
     *
     * @param currentPrompt 当前的 System Prompt
     * @return 优化结果，不需要优化时返回 null
     */
    public OptimizationResult maybeOptimize(String currentPrompt) {
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

        // 获取近期反馈
        int lookbackDays = Math.max(7, config.getOptimizationIntervalHours() / 24 + 1);
        List<EvaluationFeedback> recentFeedbacks = feedbackCollector.getRecentAggregatedFeedbacks(lookbackDays);

        if (recentFeedbacks.size() < config.getMinFeedbacksForOptimization()) {
            logger.debug("Not enough feedbacks for optimization", Map.of(
                    "available", recentFeedbacks.size(),
                    "required", config.getMinFeedbacksForOptimization()));
            return null;
        }

        // 执行优化
        OptimizationResult result = optimize(currentPrompt, recentFeedbacks);
        lastOptimizationTimeMs.set(System.currentTimeMillis());

        return result;
    }

    /**
     * 执行 Prompt 优化。
     *
     * @param currentPrompt 当前的 System Prompt
     * @param feedbacks     评估反馈列表
     * @return 优化结果
     */
    public OptimizationResult optimize(String currentPrompt, List<EvaluationFeedback> feedbacks) {
        logger.info("Starting prompt optimization", Map.of(
                "strategy", config.getStrategy().name(),
                "feedback_count", feedbacks.size()));

        try {
            return switch (config.getStrategy()) {
                case TEXT_GRADIENT -> optimizeWithTextGradient(currentPrompt, feedbacks);
                case EVO_PROMPT -> optimizeWithEvolution(currentPrompt, feedbacks);
                case MIPRO -> optimizeWithMIPRO(currentPrompt, feedbacks);
            };
        } catch (Exception e) {
            logger.error("Optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
        }
    }

    // ==================== TEXT_GRADIENT 策略 ====================

    /**
     * 基于文本梯度的优化。
     *
     * 1. 分析反馈生成文本梯度
     * 2. 应用梯度生成新 Prompt
     */
    private OptimizationResult optimizeWithTextGradient(String currentPrompt,
                                                         List<EvaluationFeedback> feedbacks) {
        // 计算反馈统计
        FeedbackSummary summary = summarizeFeedbacks(feedbacks);

        // 检查是否需要优化
        if (summary.avgScore >= config.getAdoptionThreshold()) {
            return OptimizationResult.noImprovementNeeded(currentPrompt,
                    String.format("Current performance (%.2f) meets threshold (%.2f)",
                            summary.avgScore, config.getAdoptionThreshold()));
        }

        // 生成文本梯度
        String textualGradient = generateTextualGradient(currentPrompt, feedbacks, summary);
        if (textualGradient == null || textualGradient.isBlank()) {
            return OptimizationResult.failed(currentPrompt, "Failed to generate textual gradient");
        }

        // 应用梯度
        String optimizedPrompt = applyGradient(currentPrompt, textualGradient);
        if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
            return OptimizationResult.failed(currentPrompt, "Failed to apply gradient");
        }

        // 构建结果
        OptimizationResult result = OptimizationResult.success(
                currentPrompt, optimizedPrompt, textualGradient, OptimizationStrategy.TEXT_GRADIENT);
        result.setFeedbackCount(feedbacks.size());
        result.setOriginalScore(summary.avgScore);

        // 保存为变体
        String variantId = "tg_" + Instant.now().toEpochMilli();
        promptStore.saveVariant(variantId, optimizedPrompt, summary.avgScore, Map.of(
                "strategy", "TEXT_GRADIENT",
                "feedback_count", feedbacks.size(),
                "gradient", textualGradient));

        // 判断是否建议采用
        result.setRecommendAdoption(summary.avgScore < config.getAdoptionThreshold());

        // 如果配置为自动应用，则激活新 Prompt
        if (config.isAutoApplyOptimization() && result.isRecommendAdoption()) {
            promptStore.setActivePrompt(optimizedPrompt);
            logger.info("Auto-applied optimized prompt", Map.of("variant_id", variantId));
        }

        logger.info("Optimization completed", Map.of(
                "original_score", summary.avgScore,
                "recommend_adoption", result.isRecommendAdoption()));

        return result;
    }

    /**
     * 生成文本梯度（优化建议）。
     */
    public String generateTextualGradient(String currentPrompt,
                                           List<EvaluationFeedback> feedbacks,
                                           FeedbackSummary summary) {
        String feedbackAnalysis = buildFeedbackAnalysis(feedbacks);

        String prompt = String.format(GRADIENT_PROMPT_TEMPLATE,
                currentPrompt,
                summary.avgScore,
                summary.totalCount,
                summary.positiveRatio * 100,
                feedbackAnalysis);

        try {
            List<Message> messages = List.of(Message.user(prompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", config.getOptimizationMaxTokens(),
                    "temperature", config.getOptimizationTemperature());

            LLMResponse response = provider.chat(messages, null, model, options);
            return response.getContent();
        } catch (Exception e) {
            logger.error("Failed to generate textual gradient", Map.of("error", e.getMessage()));
            return null;
        }
    }

    /**
     * 应用文本梯度生成优化后的 Prompt。
     */
    public String applyGradient(String currentPrompt, String textualGradient) {
        String prompt = String.format(APPLY_GRADIENT_TEMPLATE, currentPrompt, textualGradient);

        try {
            List<Message> messages = List.of(Message.user(prompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", config.getOptimizationMaxTokens(),
                    "temperature", 0.2); // 应用时使用较低温度保持一致性

            LLMResponse response = provider.chat(messages, null, model, options);
            return response.getContent();
        } catch (Exception e) {
            logger.error("Failed to apply gradient", Map.of("error", e.getMessage()));
            return null;
        }
    }

    // ==================== EVO_PROMPT 策略（简化实现） ====================

    /**
     * 基于进化算法的优化。
     *
     * 简化版本：生成多个变体，选择最佳。
     */
    private OptimizationResult optimizeWithEvolution(String currentPrompt,
                                                      List<EvaluationFeedback> feedbacks) {
        // 当前简化实现：使用 TEXT_GRADIENT 作为基础
        // 未来可扩展为完整的进化算法
        logger.info("EVO_PROMPT using simplified implementation (TEXT_GRADIENT base)");
        return optimizeWithTextGradient(currentPrompt, feedbacks);
    }

    // ==================== MIPRO 策略（简化实现） ====================

    /**
     * 多指标迭代优化。
     *
     * 简化版本：基于多个指标生成综合建议。
     */
    private OptimizationResult optimizeWithMIPRO(String currentPrompt,
                                                  List<EvaluationFeedback> feedbacks) {
        // 当前简化实现：使用 TEXT_GRADIENT 作为基础
        // 未来可扩展为完整的多目标优化
        logger.info("MIPRO using simplified implementation (TEXT_GRADIENT base)");
        return optimizeWithTextGradient(currentPrompt, feedbacks);
    }

    // ==================== 辅助方法 ====================

    /**
     * 汇总反馈统计。
     */
    private FeedbackSummary summarizeFeedbacks(List<EvaluationFeedback> feedbacks) {
        FeedbackSummary summary = new FeedbackSummary();
        summary.totalCount = feedbacks.size();

        if (feedbacks.isEmpty()) {
            return summary;
        }

        summary.avgScore = feedbacks.stream()
                .mapToDouble(EvaluationFeedback::getPrimaryScore)
                .average()
                .orElse(0.5);

        long positiveCount = feedbacks.stream()
                .filter(EvaluationFeedback::isPositive)
                .count();
        summary.positiveRatio = (double) positiveCount / feedbacks.size();

        return summary;
    }

    /**
     * 构建反馈分析文本。
     */
    private String buildFeedbackAnalysis(List<EvaluationFeedback> feedbacks) {
        StringBuilder analysis = new StringBuilder();

        // 按评估模式分组
        Map<EvaluationFeedback.EvalMode, List<EvaluationFeedback>> byMode = feedbacks.stream()
                .filter(fb -> fb.getEvalMode() != null)
                .collect(Collectors.groupingBy(EvaluationFeedback::getEvalMode));

       for (Map.Entry<EvaluationFeedback.EvalMode, List<EvaluationFeedback>> entry : byMode.entrySet()) {
            analysis.append("### ").append(entry.getKey().name()).append(" 反馈\n");
            double avgScore = entry.getValue().stream()
                    .mapToDouble(EvaluationFeedback::getPrimaryScore)
                    .average()
                    .orElse(0.5);
            analysis.append(String.format("- 数量: %d, 平均分: %.2f\n", entry.getValue().size(), avgScore));
        }

        // 提取用户评论
        List<String> comments = feedbacks.stream()
                .filter(fb -> fb.getUserComment() != null && !fb.getUserComment().isBlank())
                .map(EvaluationFeedback::getUserComment)
                .limit(10) // 最多 10 条
                .toList();

       if (!comments.isEmpty()) {
            analysis.append("\n### 用户评论\n");
            for (String comment : comments) {
                analysis.append("- ").append(comment).append("\n");
            }
        }

        // 提取负面反馈的文本梯度
        List<String> negativeGradients = feedbacks.stream()
                .filter(EvaluationFeedback::isNegative)
                .filter(fb -> fb.getTextualGradient() != null && !fb.getTextualGradient().isBlank())
                .map(EvaluationFeedback::getTextualGradient)
                .limit(5)
                .toList();

       if (!negativeGradients.isEmpty()) {
            analysis.append("\n### 识别出的问题\n");
            for (String gradient : negativeGradients) {
                analysis.append("- ").append(gradient).append("\n");
            }
        }

        return analysis.toString();
    }

    // ==================== 公开访问方法 ====================

    /**
     * 检查是否有活跃的优化。
     */
    public boolean hasActiveOptimization() {
        return promptStore.hasActiveOptimization();
    }

    /**
     * 获取活跃的优化 Prompt。
     */
    public String getActiveOptimization() {
        return promptStore.getActivePrompt();
    }

    /**
     * 手动激活指定变体。
     *
     * @param variantId 变体 ID
     * @return 激活成功返回 true
     */
    public boolean activateVariant(String variantId) {
        PromptStore.PromptVariantInfo variant = promptStore.getVariant(variantId);
        if (variant == null) {
            return false;
        }
        promptStore.setActivePrompt(variant.getPrompt());
        logger.info("Manually activated variant", Map.of("id", variantId));
        return true;
    }

    /**
     * 清除活跃优化，恢复默认。
     */
    public void clearOptimization() {
        promptStore.clearActivePrompt();
    }

    /**
     * 获取优化统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("last_optimization_time", lastOptimizationTimeMs.get());
        stats.put("strategy", config.getStrategy().name());
        stats.putAll(promptStore.getStats());
        return stats;
    }

    // ==================== 内部类 ====================

    /**
     * 反馈汇总信息
     */
    private static class FeedbackSummary {
        int totalCount = 0;
        double avgScore = 0.5;
        double positiveRatio = 0.5;
    }
}
