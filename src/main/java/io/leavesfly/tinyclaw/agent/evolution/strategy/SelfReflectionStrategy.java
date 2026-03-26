package io.leavesfly.tinyclaw.agent.evolution.strategy;

import io.leavesfly.tinyclaw.agent.evolution.OptimizationResult;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自我反思（Self-Refine）优化策略。
 * <p>
 * 不依赖外部反馈，而是让 Agent 回顾最近的会话交互，
 * 自我评估表现质量并生成改进建议，然后将建议应用到 Prompt 中。
 * <p>
 * 两阶段流程：
 * <ol>
 *   <li><b>反思阶段</b>：将当前 Prompt + 最近会话记录交给 LLM，
 *       让其从帮助性、准确性、简洁性等维度评估表现，输出评分和改进建议</li>
 *   <li><b>应用阶段</b>：将反思结果（评分 + 建议列表）交给 LLM，
 *       让其将建议自然融入 Prompt 中，生成优化版本</li>
 * </ol>
 */
public class SelfReflectionStrategy implements OptimizationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.strategy.selfreflection");

    /**
     * Self-Refine：自我反思生成经验教训的模板
     */
    private static final String SELF_REFINE_REFLECTION_TEMPLATE = """
            你是一位 AI 助手质量分析师。请回顾以下最近的对话记录，分析助手的表现质量。
                    
            ## 当前系统提示词
            %s
                    
            ## 最近对话记录
            %s
                    
            ## 分析任务
            请从以下维度评估助手的表现：
            1. **帮助性**：是否有效解决了用户的问题？
            2. **准确性**：回答是否准确、无误导？
            3. **简洁性**：是否避免了冗余和废话？
            4. **工具使用**：是否合理使用了可用工具？
            5. **主动性**：是否主动提供了有价值的额外信息？
                    
            请输出：
            1. 一个 0.0~1.0 的综合评分（第一行，格式：SCORE|0.xx）
            2. 具体的改进建议列表（每条以 SUGGESTION| 开头）
                    
            示例输出格式：
            SCORE|0.65
            SUGGESTION|在回答技术问题时应该提供代码示例
            SUGGESTION|应该在回答开头先确认理解了用户的问题
            """;

    /**
     * Self-Refine：将反思结果应用到 Prompt 的模板
     */
    private static final String SELF_REFINE_APPLY_TEMPLATE = """
            你是一位专业的 Prompt 工程师。请根据 AI 助手的自我反思结果，改进系统提示词。
                    
            ## 原始系统提示词
            %s
                    
            ## 自我反思发现的问题和建议
            - 综合评分: %.2f
            - 改进建议:
            %s
                    
            ## 任务
            将以上改进建议自然地融入系统提示词中：
            - 保持核心身份和目的不变
            - 将建议转化为具体的行为指令
            - 保持格式和结构一致
            - 不要添加冗长的解释
            - 仅输出改进后的提示词文本
            """;

    @Override
    public String name() {
        return "SELF_REFINE";
    }

    @Override
    public boolean canOptimize(OptimizationContext context) {
        List<String> sessionLog = context.getRecentSessionLog();
        if (sessionLog == null || sessionLog.isEmpty()) {
            logger.debug("No session history available for Self-Refine optimization");
            return false;
        }
        return true;
    }

    @Override
    public OptimizationResult optimize(String currentPrompt, OptimizationContext context) {
        List<String> recentSessionLog = context.getRecentSessionLog();
        logger.info("Starting Self-Refine optimization", Map.of(
                "session_count", recentSessionLog.size()));

        try {
            // 阶段一：自我反思
            String sessionLogText = buildSessionLogText(recentSessionLog);
            SelfRefineReflection reflection = performSelfReflection(currentPrompt, sessionLogText, context);

            if (reflection == null) {
                return OptimizationResult.failed(currentPrompt,
                        "Self-Refine: Failed to perform self-reflection");
            }

            logger.info("Self-reflection completed", Map.of(
                    "score", reflection.score,
                    "suggestion_count", reflection.suggestions.size()));

            // 如果自评分已经很高，无需优化
            if (reflection.score >= context.getAdoptionThreshold()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        String.format("Self-Refine: Self-assessment score (%.2f) meets threshold (%.2f)",
                                reflection.score, context.getAdoptionThreshold()));
            }

            // 如果没有具体建议，无法优化
            if (reflection.suggestions.isEmpty()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        "Self-Refine: No specific improvement suggestions generated");
            }

            // 阶段二：应用反思结果
            String suggestionsText = reflection.suggestions.stream()
                    .map(s -> "  - " + s)
                    .collect(Collectors.joining("\n"));

            String applyPrompt = String.format(SELF_REFINE_APPLY_TEMPLATE,
                    currentPrompt, reflection.score, suggestionsText);

            String optimizedPrompt = context.chatWithLowTemperature(applyPrompt);

            if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
                return OptimizationResult.failed(currentPrompt,
                        "Self-Refine: Failed to apply reflection results");
            }

            OptimizationResult result = OptimizationResult.success(
                    currentPrompt, optimizedPrompt, suggestionsText, name());
            result.setOriginalScore(reflection.score);
            result.setRecommendAdoption(reflection.score < context.getAdoptionThreshold());

            // 保存变体
            String variantId = "sr_" + Instant.now().toEpochMilli();
            context.getVariantManager().saveVariant(variantId, optimizedPrompt, reflection.score, Map.of(
                    "strategy", name(),
                    "self_score", reflection.score,
                    "suggestion_count", reflection.suggestions.size(),
                    "session_count", recentSessionLog.size()));

            logger.info("Self-Refine optimization completed", Map.of(
                    "self_score", reflection.score,
                    "suggestions", reflection.suggestions.size(),
                    "recommend_adoption", result.isRecommendAdoption()));

            return result;
        } catch (Exception e) {
            logger.error("Self-Refine optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
        }
    }

    /**
     * 执行自我反思：让 LLM 评估 Agent 在最近会话中的表现。
     *
     * @param currentPrompt  当前 Prompt
     * @param sessionLogText 格式化的会话记录文本
     * @param context        优化上下文
     * @return 反思结果（评分 + 建议列表），失败返回 null
     */
    private SelfRefineReflection performSelfReflection(String currentPrompt, String sessionLogText,
                                                       OptimizationContext context) {
        String reflectionPrompt = String.format(SELF_REFINE_REFLECTION_TEMPLATE,
                currentPrompt, sessionLogText);

        try {
            String result = context.chatWithOptimizationParams(reflectionPrompt);
            if (result == null || result.isBlank()) {
                return null;
            }
            return parseSelfReflectionResult(result);
        } catch (Exception e) {
            logger.error("Self-reflection LLM call failed", Map.of("error", e.getMessage()));
            return null;
        }
    }

    /**
     * 解析 Self-Refine 反思结果。
     *
     * <p>期望格式：
     * <pre>
     * SCORE|0.65
     * SUGGESTION|具体建议1
     * SUGGESTION|具体建议2
     * </pre>
     */
    private SelfRefineReflection parseSelfReflectionResult(String result) {
        SelfRefineReflection reflection = new SelfRefineReflection();
        String[] lines = result.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("SCORE|")) {
                try {
                    reflection.score = Double.parseDouble(trimmed.substring("SCORE|".length()).trim());
                    reflection.score = Math.max(0.0, Math.min(1.0, reflection.score));
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse self-reflection score: " + trimmed);
                }
            } else if (trimmed.startsWith("SUGGESTION|")) {
                String suggestion = trimmed.substring("SUGGESTION|".length()).trim();
                if (!suggestion.isBlank()) {
                    reflection.suggestions.add(suggestion);
                }
            }
        }

        // 如果 LLM 没有严格遵循格式，尝试从自由文本中提取
        if (reflection.score < 0 && reflection.suggestions.isEmpty()) {
            logger.warn("Self-reflection result did not follow expected format, attempting fallback parse");
            reflection.score = 0.5; // 默认中等评分
            // 将非空行作为建议
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && trimmed.length() > 10
                        && !trimmed.startsWith("#") && !trimmed.startsWith("SCORE")) {
                    reflection.suggestions.add(trimmed);
                }
            }
        }

        return reflection;
    }

    /**
     * 将会话记录列表合并为格式化文本，控制总长度。
     */
    private String buildSessionLogText(List<String> recentSessionLog) {
        int maxTotalChars = 3000;
        StringBuilder logText = new StringBuilder();
        int sessionIndex = 1;

        for (String sessionLog : recentSessionLog) {
            String header = String.format("--- 会话 %d ---\n", sessionIndex++);
            int remaining = maxTotalChars - logText.length();

            if (remaining <= header.length() + 50) {
                break;
            }

            logText.append(header);
            if (sessionLog.length() > remaining - header.length()) {
                logText.append(sessionLog, 0, remaining - header.length());
                logText.append("\n... (truncated)\n\n");
                break;
            } else {
                logText.append(sessionLog).append("\n\n");
            }
        }

        return logText.toString();
    }

    // ==================== 内部数据类 ====================

    /**
     * Self-Refine 反思结果
     */
    private static class SelfRefineReflection {
        /**
         * 自评综合评分 (0.0 ~ 1.0)，-1 表示未解析到
         */
        double score = -1.0;
        /**
         * 改进建议列表
         */
        List<String> suggestions = new ArrayList<>();
    }
}
