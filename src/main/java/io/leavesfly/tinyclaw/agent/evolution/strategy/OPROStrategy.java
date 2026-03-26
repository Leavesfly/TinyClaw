package io.leavesfly.tinyclaw.agent.evolution.strategy;

import io.leavesfly.tinyclaw.agent.evolution.EvaluationFeedback;
import io.leavesfly.tinyclaw.agent.evolution.OptimizationResult;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OPRO（Optimization by PROmpting）优化策略。
 * <p>
 * 核心思想：维护一个 (prompt, score) 的历史轨迹，将轨迹作为上下文让 LLM "看到趋势"，
 * 从而生成下一个可能更好的 Prompt。LLM 能从历史中发现模式，比随机变异更有方向性。
 * <p>
 * 流程：
 * <ol>
 *   <li>构建历史轨迹：从已有变体中提取 (prompt_summary, score) 对</li>
 *   <li>将当前 Prompt 和评分加入轨迹</li>
 *   <li>将完整轨迹 + 当前反馈摘要作为上下文，让 LLM 生成新 Prompt</li>
 *   <li>保存为新变体</li>
 * </ol>
 */
public class OPROStrategy implements OptimizationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.strategy.opro");

    /**
     * OPRO：基于历史轨迹生成新 Prompt 的模板
     */
    private static final String OPRO_OPTIMIZATION_TEMPLATE = """
            你是一位 Prompt 优化专家。你的任务是根据历史优化轨迹，生成一个更好的系统提示词。
                    
            ## 优化目标
            生成一个能获得更高用户满意度评分的系统提示词。
                    
            ## 历史优化轨迹
            以下是过去尝试过的系统提示词及其对应的用户满意度评分（0.0~1.0，越高越好）：
                    
            %s
                    
            ## 当前反馈摘要
            - 最近平均满意度评分: %.2f
            - 反馈样本总数: %d
            - 正面反馈比例: %.1f%%
                    
            ## 任务
            仔细分析历史轨迹中的趋势：
            1. 哪些改动带来了评分提升？保留这些改进
            2. 哪些改动导致了评分下降？避免重复这些错误
            3. 还有哪些方向尚未尝试？
                    
            基于以上分析，生成一个新的、更优的系统提示词。
            仅输出改进后的提示词文本，不要添加解释或分析过程。
            """;

    @Override
    public String name() {
        return "OPRO";
    }

    @Override
    public boolean canOptimize(OptimizationContext context) {
        List<EvaluationFeedback> feedbacks = context.getRecentFeedbacks();
        if (feedbacks == null || feedbacks.size() < context.getMinFeedbacksRequired()) {
            logger.debug("Not enough feedbacks for OPRO optimization", Map.of(
                    "available", feedbacks != null ? feedbacks.size() : 0,
                    "required", context.getMinFeedbacksRequired()));
            return false;
        }
        return true;
    }

    @Override
    public OptimizationResult optimize(String currentPrompt, OptimizationContext context) {
        List<EvaluationFeedback> feedbacks = context.getRecentFeedbacks();
        logger.info("Starting OPRO optimization", Map.of(
                "feedback_count", feedbacks.size(),
                "trajectory_size", context.getVariantManager().getVariantCount()));

        try {
            FeedbackSummary summary = summarizeFeedbacks(feedbacks);

            if (summary.avgScore >= context.getAdoptionThreshold()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        String.format("OPRO: Current performance (%.2f) meets threshold (%.2f)",
                                summary.avgScore, context.getAdoptionThreshold()));
            }

            // 构建历史轨迹文本
            int maxTrajectorySize = context.getConfig().getOproMaxTrajectorySize();
            String trajectoryText = context.getVariantManager()
                    .buildOptimizationTrajectory(currentPrompt, summary.avgScore, maxTrajectorySize);

            // 用 OPRO 模板生成新 Prompt
            String oproPrompt = String.format(OPRO_OPTIMIZATION_TEMPLATE,
                    trajectoryText, summary.avgScore, summary.totalCount,
                    summary.positiveRatio * 100);

            String optimizedPrompt = context.chatWithOptimizationParams(oproPrompt);

            if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
                return OptimizationResult.failed(currentPrompt, "OPRO: LLM returned empty response");
            }

            OptimizationResult result = OptimizationResult.success(
                    currentPrompt, optimizedPrompt, trajectoryText, name());
            result.setFeedbackCount(feedbacks.size());
            result.setOriginalScore(summary.avgScore);
            result.setRecommendAdoption(summary.avgScore < context.getAdoptionThreshold());

            // 保存变体
            String variantId = "opro_" + Instant.now().toEpochMilli();
            context.getVariantManager().saveVariant(variantId, optimizedPrompt, summary.avgScore, Map.of(
                    "strategy", name(),
                    "feedback_count", feedbacks.size(),
                    "trajectory_size", context.getVariantManager().getVariantCount()));

            logger.info("OPRO optimization completed", Map.of(
                    "original_score", summary.avgScore,
                    "trajectory_entries", context.getVariantManager().getVariantCount(),
                    "recommend_adoption", result.isRecommendAdoption()));

            return result;
        } catch (Exception e) {
            logger.error("OPRO optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 汇总反馈统计信息
     */
    private FeedbackSummary summarizeFeedbacks(List<EvaluationFeedback> feedbacks) {
        FeedbackSummary summary = new FeedbackSummary();
        summary.totalCount = feedbacks.size();
        if (feedbacks.isEmpty()) {
            return summary;
        }
        summary.avgScore = feedbacks.stream()
                .mapToDouble(EvaluationFeedback::getPrimaryScore)
                .average().orElse(0.5);
        long positiveCount = feedbacks.stream().filter(EvaluationFeedback::isPositive).count();
        summary.positiveRatio = (double) positiveCount / feedbacks.size();
        return summary;
    }

    /**
     * 反馈汇总信息
     */
    private static class FeedbackSummary {
        int totalCount = 0;
        double avgScore = 0.5;
        double positiveRatio = 0.5;
    }
}
