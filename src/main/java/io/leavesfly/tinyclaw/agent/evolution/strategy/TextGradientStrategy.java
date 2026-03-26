package io.leavesfly.tinyclaw.agent.evolution.strategy;

import io.leavesfly.tinyclaw.agent.evolution.EvaluationFeedback;
import io.leavesfly.tinyclaw.agent.evolution.OptimizationResult;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文本梯度（Textual Gradient）优化策略。
 * <p>
 * 基于用户反馈分析问题模式，生成文本梯度（优化建议），
 * 然后将梯度应用到当前 Prompt 生成优化版本。
 * <p>
 * 流程：
 * <ol>
 *   <li>汇总反馈统计信息</li>
 *   <li>生成文本梯度（优化建议）</li>
 *   <li>应用梯度生成新 Prompt</li>
 *   <li>保存为候选变体</li>
 * </ol>
 */
public class TextGradientStrategy implements OptimizationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.strategy.textgradient");

    /**
     * 文本梯度生成的 Prompt 模板
     */
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

    /**
     * 应用梯度的 Prompt 模板
     */
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

    @Override
    public String name() {
        return "TEXTUAL_GRADIENT";
    }

    @Override
    public boolean canOptimize(OptimizationContext context) {
        List<EvaluationFeedback> feedbacks = context.getRecentFeedbacks();
        if (feedbacks == null || feedbacks.size() < context.getMinFeedbacksRequired()) {
            logger.debug("Not enough feedbacks for TextualGradient optimization", Map.of(
                    "available", feedbacks != null ? feedbacks.size() : 0,
                    "required", context.getMinFeedbacksRequired()));
            return false;
        }
        return true;
    }

    @Override
    public OptimizationResult optimize(String currentPrompt, OptimizationContext context) {
        List<EvaluationFeedback> feedbacks = context.getRecentFeedbacks();
        logger.info("Starting TextualGradient optimization", Map.of("feedback_count", feedbacks.size()));

        try {
            FeedbackSummary summary = summarizeFeedbacks(feedbacks);

            if (summary.avgScore >= context.getAdoptionThreshold()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        String.format("Current performance (%.2f) meets threshold (%.2f)",
                                summary.avgScore, context.getAdoptionThreshold()));
            }

            String textualGradient = generateTextualGradient(currentPrompt, feedbacks, summary, context);
            if (textualGradient == null || textualGradient.isBlank()) {
                return OptimizationResult.failed(currentPrompt, "Failed to generate textual gradient");
            }

            String optimizedPrompt = applyGradient(currentPrompt, textualGradient, context);
            if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
                return OptimizationResult.failed(currentPrompt, "Failed to apply gradient");
            }

            OptimizationResult result = OptimizationResult.success(
                    currentPrompt, optimizedPrompt, textualGradient, name());
            result.setFeedbackCount(feedbacks.size());
            result.setOriginalScore(summary.avgScore);
            result.setRecommendAdoption(summary.avgScore < context.getAdoptionThreshold());

            // 保存变体
            String variantId = "tg_" + Instant.now().toEpochMilli();
            context.getVariantManager().saveVariant(variantId, optimizedPrompt, summary.avgScore, Map.of(
                    "strategy", name(),
                    "feedback_count", feedbacks.size(),
                    "gradient", textualGradient));

            return result;
        } catch (Exception e) {
            logger.error("TextualGradient optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
        }
    }

    /**
     * 生成文本梯度（优化建议）。
     */
    public String generateTextualGradient(String currentPrompt,
                                          List<EvaluationFeedback> feedbacks,
                                          FeedbackSummary summary,
                                          OptimizationContext context) {
        String feedbackAnalysis = buildFeedbackAnalysis(feedbacks);
        String prompt = String.format(GRADIENT_PROMPT_TEMPLATE,
                currentPrompt, summary.avgScore, summary.totalCount,
                summary.positiveRatio * 100, feedbackAnalysis);

        try {
            return context.chatWithOptimizationParams(prompt);
        } catch (Exception e) {
            logger.error("Failed to generate textual gradient", Map.of("error", e.getMessage()));
            return null;
        }
    }

    /**
     * 应用文本梯度生成优化后的 Prompt。
     */
    public String applyGradient(String currentPrompt, String textualGradient, OptimizationContext context) {
        String prompt = String.format(APPLY_GRADIENT_TEMPLATE, currentPrompt, textualGradient);

        try {
            return context.chatWithLowTemperature(prompt);
        } catch (Exception e) {
            logger.error("Failed to apply gradient", Map.of("error", e.getMessage()));
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 汇总反馈统计信息
     */
    public FeedbackSummary summarizeFeedbacks(List<EvaluationFeedback> feedbacks) {
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

    private String buildFeedbackAnalysis(List<EvaluationFeedback> feedbacks) {
        StringBuilder analysis = new StringBuilder();

        Map<EvaluationFeedback.EvalMode, List<EvaluationFeedback>> byMode = feedbacks.stream()
                .filter(fb -> fb.getEvalMode() != null)
                .collect(Collectors.groupingBy(EvaluationFeedback::getEvalMode));

        for (Map.Entry<EvaluationFeedback.EvalMode, List<EvaluationFeedback>> entry : byMode.entrySet()) {
            analysis.append("### ").append(entry.getKey().name()).append(" 反馈\n");
            double avgScore = entry.getValue().stream()
                    .mapToDouble(EvaluationFeedback::getPrimaryScore)
                    .average().orElse(0.5);
            analysis.append(String.format("- 数量: %d, 平均分: %.2f\n", entry.getValue().size(), avgScore));
        }

        List<String> comments = feedbacks.stream()
                .filter(fb -> fb.getUserComment() != null && !fb.getUserComment().isBlank())
                .map(EvaluationFeedback::getUserComment)
                .limit(10)
                .toList();
        if (!comments.isEmpty()) {
            analysis.append("\n### 用户评论\n");
            comments.forEach(c -> analysis.append("- ").append(c).append("\n"));
        }

        List<String> negativeGradients = feedbacks.stream()
                .filter(EvaluationFeedback::isNegative)
                .filter(fb -> fb.getTextualGradient() != null && !fb.getTextualGradient().isBlank())
                .map(EvaluationFeedback::getTextualGradient)
                .limit(5)
                .toList();
        if (!negativeGradients.isEmpty()) {
            analysis.append("\n### 识别出的问题\n");
            negativeGradients.forEach(g -> analysis.append("- ").append(g).append("\n"));
        }

        return analysis.toString();
    }

    /**
     * 反馈汇总信息
     */
    public static class FeedbackSummary {
        public int totalCount = 0;
        public double avgScore = 0.5;
        public double positiveRatio = 0.5;
    }
}
