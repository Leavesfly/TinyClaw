package io.leavesfly.tinyclaw.agent.evolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Prompt 优化器，基于评估反馈迭代优化 System Prompt。
 * <p>
 * 核心公式：Prompt(t+1) = O(Prompt(t), E)
 * <p>
 * 优化流程：
 * 1. 收集近期会话的 EvaluationFeedback
 * 2. 分析反馈中的问题模式，生成文本梯度（优化建议）
 * 3. 应用梯度到当前 Prompt，生成优化版本
 * 4. 保存为候选变体，待评估后决定是否采用
 * <p>
 * Prompt 变体存储结构：
 * <pre>
 * {workspace}/evolution/prompts/
 * ├── PROMPT_VARIANTS.json    # 所有 Prompt 变体及其评分
 * ├── PROMPT_ACTIVE.md        # 当前活跃的优化 Prompt
 * └── PROMPT_HISTORY/         # 历史版本归档
 * </pre>
 */
public class PromptOptimizer {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.optimizer");

    private static final String PROMPTS_DIR = "prompts";
    private static final String VARIANTS_FILE = "PROMPT_VARIANTS.json";
    private static final String ACTIVE_FILE = "PROMPT_ACTIVE.md";
    private static final String HISTORY_DIR = "PROMPT_HISTORY";
    private static final DateTimeFormatter VERSION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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

    // ==================== OPRO 策略模板 ====================

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

    // ==================== Self-Refine 策略模板 ====================

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

    private final LLMProvider provider;
    private final String model;
    private final FeedbackManager feedbackManager;
    private final EvolutionConfig config;

    // ==================== 内聚的 Prompt 存储状态 ====================

    private final String promptsDir;
    private final String variantsFile;
    private final String activeFile;
    private final String historyDir;
    private final ObjectMapper objectMapper;

    /**
     * 内存中的变体缓存
     */
    private final Map<String, PromptVariantInfo> variants;

    /**
     * 当前活跃的优化 Prompt（null 表示使用默认）
     */
    private String activePrompt;

    /**
     * 当前版本号
     */
    private int currentVersion;

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

        this.promptsDir = Paths.get(workspace, "evolution", PROMPTS_DIR).toString();
        this.variantsFile = Paths.get(promptsDir, VARIANTS_FILE).toString();
        this.activeFile = Paths.get(promptsDir, ACTIVE_FILE).toString();
        this.historyDir = Paths.get(promptsDir, HISTORY_DIR).toString();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.variants = new LinkedHashMap<>();
        this.currentVersion = 0;

        ensureDirectoriesExist();
        loadState();
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
     * <p>根据 {@link EvolutionConfig.OptimizationStrategy} 配置选择不同的优化算法：
     * <ul>
     *   <li>{@code TEXTUAL_GRADIENT} — 反馈驱动的文本梯度（需要反馈数据）</li>
     *   <li>{@code OPRO} — 历史轨迹引导优化（需要反馈数据 + 历史变体）</li>
     *   <li>{@code SELF_REFINE} — 自我反思优化（需要会话历史，不依赖反馈）</li>
     * </ul>
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

        EvolutionConfig.OptimizationStrategy strategy = config.getOptimizationStrategy();
        logger.info("Running optimization with strategy", Map.of("strategy", strategy.name()));

        OptimizationResult result;
        switch (strategy) {
            case OPRO:
                result = maybeOptimizeWithOPRO(currentPrompt);
                break;
            case SELF_REFINE:
                result = maybeOptimizeWithSelfRefine(currentPrompt, recentSessionLog);
                break;
            case TEXTUAL_GRADIENT:
            default:
                result = maybeOptimizeWithTextualGradient(currentPrompt);
                break;
        }

        if (result != null) {
            lastOptimizationTimeMs.set(System.currentTimeMillis());
        }
        return result;
    }

    // ==================== 策略一：文本梯度（原有实现） ====================

    /**
     * 文本梯度策略的前置检查与执行。
     */
    private OptimizationResult maybeOptimizeWithTextualGradient(String currentPrompt) {
        int lookbackDays = Math.max(7, config.getOptimizationIntervalHours() / 24 + 1);
        List<EvaluationFeedback> recentFeedbacks = feedbackManager.getRecentAggregatedFeedbacks(lookbackDays);

        if (recentFeedbacks.size() < config.getMinFeedbacksForOptimization()) {
            logger.debug("Not enough feedbacks for TextualGradient optimization", Map.of(
                    "available", recentFeedbacks.size(),
                    "required", config.getMinFeedbacksForOptimization()));
            return null;
        }

        return optimizeWithTextualGradient(currentPrompt, recentFeedbacks);
    }

    /**
     * 执行文本梯度优化（原有 optimize 方法的重命名）。
     *
     * @param currentPrompt 当前的 System Prompt
     * @param feedbacks     评估反馈列表
     * @return 优化结果
     */
    public OptimizationResult optimizeWithTextualGradient(String currentPrompt,
                                                          List<EvaluationFeedback> feedbacks) {
        logger.info("Starting TextualGradient optimization", Map.of("feedback_count", feedbacks.size()));

        try {
            FeedbackSummary summary = summarizeFeedbacks(feedbacks);

            if (summary.avgScore >= config.getAdoptionThreshold()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        String.format("Current performance (%.2f) meets threshold (%.2f)",
                                summary.avgScore, config.getAdoptionThreshold()));
            }

            String textualGradient = generateTextualGradient(currentPrompt, feedbacks, summary);
            if (textualGradient == null || textualGradient.isBlank()) {
                return OptimizationResult.failed(currentPrompt, "Failed to generate textual gradient");
            }

            String optimizedPrompt = applyGradient(currentPrompt, textualGradient);
            if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
                return OptimizationResult.failed(currentPrompt, "Failed to apply gradient");
            }

            OptimizationResult result = OptimizationResult.success(
                    currentPrompt, optimizedPrompt, textualGradient, "TEXTUAL_GRADIENT");
            result.setFeedbackCount(feedbacks.size());
            result.setOriginalScore(summary.avgScore);
            result.setRecommendAdoption(summary.avgScore < config.getAdoptionThreshold());

            String variantId = "tg_" + Instant.now().toEpochMilli();
            saveVariant(variantId, optimizedPrompt, summary.avgScore, Map.of(
                    "strategy", "TEXTUAL_GRADIENT",
                    "feedback_count", feedbacks.size(),
                    "gradient", textualGradient));

            maybeAutoApply(result, variantId);
            return result;
        } catch (Exception e) {
            logger.error("TextualGradient optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
        }
    }


    // ==================== 策略二：OPRO（历史轨迹引导优化） ====================

    /**
     * OPRO 策略的前置检查与执行。
     *
     * <p>OPRO (Optimization by PROmpting) 核心思想：
     * 维护一个 (prompt, score) 的历史轨迹，将轨迹作为上下文让 LLM "看到趋势"，
     * 从而生成下一个可能更好的 Prompt。LLM 能从历史中发现模式，比随机变异更有方向性。</p>
     */
    private OptimizationResult maybeOptimizeWithOPRO(String currentPrompt) {
        int lookbackDays = Math.max(7, config.getOptimizationIntervalHours() / 24 + 1);
        List<EvaluationFeedback> recentFeedbacks = feedbackManager.getRecentAggregatedFeedbacks(lookbackDays);

        if (recentFeedbacks.size() < config.getMinFeedbacksForOptimization()) {
            logger.debug("Not enough feedbacks for OPRO optimization", Map.of(
                    "available", recentFeedbacks.size(),
                    "required", config.getMinFeedbacksForOptimization()));
            return null;
        }

        return optimizeWithOPRO(currentPrompt, recentFeedbacks);
    }

    /**
     * 执行 OPRO 优化。
     *
     * <p>流程：
     * <ol>
     *   <li>构建历史轨迹：从已有变体中提取 (prompt_summary, score) 对</li>
     *   <li>将当前 Prompt 和评分加入轨迹</li>
     *   <li>将完整轨迹 + 当前反馈摘要作为上下文，让 LLM 生成新 Prompt</li>
     *   <li>保存为新变体，按配置决定是否自动采用</li>
     * </ol>
     *
     * @param currentPrompt 当前的 System Prompt
     * @param feedbacks     评估反馈列表
     * @return 优化结果
     */
    public OptimizationResult optimizeWithOPRO(String currentPrompt,
                                               List<EvaluationFeedback> feedbacks) {
        logger.info("Starting OPRO optimization", Map.of(
                "feedback_count", feedbacks.size(),
                "trajectory_size", variants.size()));

        try {
            FeedbackSummary summary = summarizeFeedbacks(feedbacks);

            if (summary.avgScore >= config.getAdoptionThreshold()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        String.format("OPRO: Current performance (%.2f) meets threshold (%.2f)",
                                summary.avgScore, config.getAdoptionThreshold()));
            }

            // 构建历史轨迹文本
            String trajectoryText = buildOptimizationTrajectory(currentPrompt, summary.avgScore);

            // 用 OPRO 模板生成新 Prompt
            String oproPrompt = String.format(OPRO_OPTIMIZATION_TEMPLATE,
                    trajectoryText, summary.avgScore, summary.totalCount,
                    summary.positiveRatio * 100);

            List<Message> messages = List.of(Message.user(oproPrompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", config.getOptimizationMaxTokens(),
                    "temperature", config.getOptimizationTemperature());
            LLMResponse response = provider.chat(messages, null, model, options);
            String optimizedPrompt = response.getContent();

            if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
                return OptimizationResult.failed(currentPrompt, "OPRO: LLM returned empty response");
            }

            OptimizationResult result = OptimizationResult.success(
                    currentPrompt, optimizedPrompt, trajectoryText, "OPRO");
            result.setFeedbackCount(feedbacks.size());
            result.setOriginalScore(summary.avgScore);
            result.setRecommendAdoption(summary.avgScore < config.getAdoptionThreshold());

            String variantId = "opro_" + Instant.now().toEpochMilli();
            saveVariant(variantId, optimizedPrompt, summary.avgScore, Map.of(
                    "strategy", "OPRO",
                    "feedback_count", feedbacks.size(),
                    "trajectory_size", variants.size()));

            maybeAutoApply(result, variantId);

            logger.info("OPRO optimization completed", Map.of(
                    "original_score", summary.avgScore,
                    "trajectory_entries", variants.size(),
                    "recommend_adoption", result.isRecommendAdoption()));

            return result;
        } catch (Exception e) {
            logger.error("OPRO optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
        }
    }

    /**
     * 构建 OPRO 的历史优化轨迹文本。
     *
     * <p>从已有变体中按时间排序，提取每个变体的 Prompt 摘要和评分，
     * 加上当前 Prompt 作为最新条目，形成完整的轨迹上下文。
     * 轨迹条目数受 {@link EvolutionConfig#getOproMaxTrajectorySize()} 限制。</p>
     *
     * @param currentPrompt 当前 Prompt
     * @param currentScore  当前评分
     * @return 格式化的轨迹文本
     */
    private String buildOptimizationTrajectory(String currentPrompt, double currentScore) {
        int maxTrajectorySize = config.getOproMaxTrajectorySize();

        // 按创建时间排序，取最近的 N 个变体
        List<PromptVariantInfo> sortedVariants = variants.values().stream()
                .filter(v -> v.prompt != null && !v.prompt.isBlank())
                .sorted(Comparator.comparing(v -> v.createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        // 如果超过最大轨迹大小，只保留最近的
        if (sortedVariants.size() > maxTrajectorySize - 1) {
            sortedVariants = sortedVariants.subList(
                    sortedVariants.size() - (maxTrajectorySize - 1), sortedVariants.size());
        }

        StringBuilder trajectory = new StringBuilder();
        int index = 1;

        for (PromptVariantInfo variant : sortedVariants) {
            String promptSummary = summarizePromptForTrajectory(variant.prompt);
            trajectory.append(String.format("### 尝试 #%d (评分: %.2f)\n%s\n\n",
                    index++, variant.score, promptSummary));
        }

        // 加入当前 Prompt 作为最新条目
        String currentSummary = summarizePromptForTrajectory(currentPrompt);
        trajectory.append(String.format("### 当前版本 #%d (评分: %.2f) ← 需要改进\n%s\n",
                index, currentScore, currentSummary));

        return trajectory.toString();
    }

    /**
     * 将 Prompt 文本压缩为适合放入轨迹的摘要。
     * 保留前 500 字符，超出部分截断并标注。
     */
    private String summarizePromptForTrajectory(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "(empty)";
        }
        int maxLength = 500;
        if (prompt.length() <= maxLength) {
            return prompt;
        }
        // 在最后一个换行处截断，避免截断到半句话
        String truncated = prompt.substring(0, maxLength);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > truncated.length() / 2) {
            truncated = truncated.substring(0, lastNewline);
        }
        return truncated + "\n... (truncated, total " + prompt.length() + " chars)";
    }

    // ==================== 策略三：Self-Refine（自我反思优化） ====================

    /**
     * Self-Refine 策略的前置检查与执行。
     *
     * <p>Self-Refine 不依赖外部反馈，而是让 Agent 回顾最近的会话交互，
     * 自我评估表现质量并生成改进建议，然后将建议应用到 Prompt 中。</p>
     *
     * @param currentPrompt    当前的 System Prompt
     * @param recentSessionLog 最近的会话交互记录（每个元素是一个会话的格式化文本）
     */
    private OptimizationResult maybeOptimizeWithSelfRefine(String currentPrompt,
                                                           List<String> recentSessionLog) {
        if (recentSessionLog == null || recentSessionLog.isEmpty()) {
            logger.debug("No session history available for Self-Refine optimization");
            return null;
        }

        return optimizeWithSelfRefine(currentPrompt, recentSessionLog);
    }

    /**
     * 执行 Self-Refine 优化。
     *
     * <p>两阶段流程：
     * <ol>
     *   <li><b>反思阶段</b>：将当前 Prompt + 最近会话记录交给 LLM，
     *       让其从帮助性、准确性、简洁性等维度评估表现，输出评分和改进建议</li>
     *   <li><b>应用阶段</b>：将反思结果（评分 + 建议列表）交给 LLM，
     *       让其将建议自然融入 Prompt 中，生成优化版本</li>
     * </ol>
     *
     * @param currentPrompt    当前的 System Prompt
     * @param recentSessionLog 最近的会话交互记录
     * @return 优化结果
     */
    public OptimizationResult optimizeWithSelfRefine(String currentPrompt,
                                                     List<String> recentSessionLog) {
        logger.info("Starting Self-Refine optimization", Map.of(
                "session_count", recentSessionLog.size()));

        try {
            // 阶段一：自我反思
            String sessionLogText = buildSessionLogText(recentSessionLog);
            SelfRefineReflection reflection = performSelfReflection(currentPrompt, sessionLogText);

            if (reflection == null) {
                return OptimizationResult.failed(currentPrompt,
                        "Self-Refine: Failed to perform self-reflection");
            }

            logger.info("Self-reflection completed", Map.of(
                    "score", reflection.score,
                    "suggestion_count", reflection.suggestions.size()));

            // 如果自评分已经很高，无需优化
            if (reflection.score >= config.getAdoptionThreshold()) {
                return OptimizationResult.noImprovementNeeded(currentPrompt,
                        String.format("Self-Refine: Self-assessment score (%.2f) meets threshold (%.2f)",
                                reflection.score, config.getAdoptionThreshold()));
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

            List<Message> messages = List.of(Message.user(applyPrompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", config.getOptimizationMaxTokens(),
                    "temperature", 0.2);
            LLMResponse response = provider.chat(messages, null, model, options);
            String optimizedPrompt = response.getContent();

            if (optimizedPrompt == null || optimizedPrompt.isBlank()) {
                return OptimizationResult.failed(currentPrompt,
                        "Self-Refine: Failed to apply reflection results");
            }

            OptimizationResult result = OptimizationResult.success(
                    currentPrompt, optimizedPrompt, suggestionsText, "SELF_REFINE");
            result.setOriginalScore(reflection.score);
            result.setRecommendAdoption(reflection.score < config.getAdoptionThreshold());

            String variantId = "sr_" + Instant.now().toEpochMilli();
            saveVariant(variantId, optimizedPrompt, reflection.score, Map.of(
                    "strategy", "SELF_REFINE",
                    "self_score", reflection.score,
                    "suggestion_count", reflection.suggestions.size(),
                    "session_count", recentSessionLog.size()));

            maybeAutoApply(result, variantId);

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
     * @return 反思结果（评分 + 建议列表），失败返回 null
     */
    private SelfRefineReflection performSelfReflection(String currentPrompt, String sessionLogText) {
        String reflectionPrompt = String.format(SELF_REFINE_REFLECTION_TEMPLATE,
                currentPrompt, sessionLogText);

        try {
            List<Message> messages = List.of(Message.user(reflectionPrompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", config.getOptimizationMaxTokens(),
                    "temperature", config.getOptimizationTemperature());
            LLMResponse response = provider.chat(messages, null, model, options);
            String result = response.getContent();

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

    // ==================== 公共辅助方法 ====================

    /**
     * 如果配置了自动应用且结果建议采用，则自动激活优化后的 Prompt。
     */
    private void maybeAutoApply(OptimizationResult result, String variantId) {
        if (config.isAutoApplyOptimization() && result.isRecommendAdoption()) {
            setActivePrompt(result.getOptimizedPrompt());
            logger.info("Auto-applied optimized prompt", Map.of(
                    "variant_id", variantId,
                    "strategy", result.getStrategy() != null ? result.getStrategy() : "unknown"));
        }
    }

    // ==================== LLM 调用 ====================

    /**
     * 生成文本梯度（优化建议）。
     */
    public String generateTextualGradient(String currentPrompt,
                                          List<EvaluationFeedback> feedbacks,
                                          FeedbackSummary summary) {
        String feedbackAnalysis = buildFeedbackAnalysis(feedbacks);
        String prompt = String.format(GRADIENT_PROMPT_TEMPLATE,
                currentPrompt, summary.avgScore, summary.totalCount,
                summary.positiveRatio * 100, feedbackAnalysis);

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
            // 应用时使用较低温度，保持改动可控
            Map<String, Object> options = Map.of(
                    "max_tokens", config.getOptimizationMaxTokens(),
                    "temperature", 0.2);
            LLMResponse response = provider.chat(messages, null, model, options);
            return response.getContent();
        } catch (Exception e) {
            logger.error("Failed to apply gradient", Map.of("error", e.getMessage()));
            return null;
        }
    }

    // ==================== 活跃 Prompt 管理 ====================

    /**
     * 检查是否有活跃的优化 Prompt。
     */
    public boolean hasActiveOptimization() {
        return activePrompt != null && !activePrompt.isBlank();
    }

    /**
     * 获取当前活跃的优化 Prompt。
     *
     * @return 活跃 Prompt，无优化时返回 null
     */
    public String getActiveOptimization() {
        return activePrompt;
    }

    /**
     * 手动激活指定变体。
     *
     * @param variantId 变体 ID
     * @return 激活成功返回 true
     */
    public boolean activateVariant(String variantId) {
        PromptVariantInfo variant = variants.get(variantId);
        if (variant == null) {
            return false;
        }
        setActivePrompt(variant.prompt);
        logger.info("Manually activated variant", Map.of("id", variantId));
        return true;
    }

    /**
     * 清除活跃优化，恢复默认。
     */
    public void clearOptimization() {
        if (activePrompt != null) {
            archiveCurrentVersion();
        }
        activePrompt = null;
        currentVersion++;
        try {
            Path activePath = Paths.get(activeFile);
            if (Files.exists(activePath)) {
                Files.delete(activePath);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete active prompt file: " + e.getMessage());
        }
        persistVariants();
        logger.info("Cleared active prompt", Map.of("version", currentVersion));
    }

    /**
     * 获取优化统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("last_optimization_time", lastOptimizationTimeMs.get());
        stats.put("current_version", currentVersion);
        stats.put("has_active_optimization", hasActiveOptimization());
        stats.put("variant_count", variants.size());
        return stats;
    }

    // ==================== 内部：变体存储 ====================

    private void setActivePrompt(String prompt) {
        if (activePrompt != null && !activePrompt.isBlank()) {
            archiveCurrentVersion();
        }
        activePrompt = prompt;
        currentVersion++;
        persistActivePrompt();
        persistVariants();
        logger.info("Set active prompt", Map.of("version", currentVersion));
    }

    private void saveVariant(String variantId, String prompt, double score, Map<String, Object> metadata) {
        PromptVariantInfo info = new PromptVariantInfo(variantId, prompt, score,
                Instant.now(), metadata != null ? metadata : new HashMap<>());
        variants.put(variantId, info);
        persistVariants();
        logger.debug("Saved prompt variant", Map.of("id", variantId, "score", score));
    }

    private void archiveCurrentVersion() {
        if (activePrompt == null || activePrompt.isBlank()) {
            return;
        }
        try {
            String timestamp = VERSION_FORMATTER.format(LocalDateTime.now());
            String fileName = String.format("v%d_%s.md", currentVersion, timestamp);
            Files.writeString(Paths.get(historyDir, fileName), activePrompt);
            cleanupHistory();
        } catch (IOException e) {
            logger.warn("Failed to archive prompt version: " + e.getMessage());
        }
    }

    private void cleanupHistory() {
        try {
            Path historyPath = Paths.get(historyDir);
            if (!Files.exists(historyPath)) {
                return;
            }
            List<Path> versions = Files.list(historyPath)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .collect(Collectors.toList());

            int maxVersions = config.getMaxHistoryVersions();
            for (int i = maxVersions; i < versions.size(); i++) {
                Files.delete(versions.get(i));
            }
        } catch (IOException e) {
            logger.warn("Failed to cleanup history: " + e.getMessage());
        }
    }

    // ==================== 持久化 ====================

    private void loadState() {
        try {
            Path variantsPath = Paths.get(variantsFile);
            if (Files.exists(variantsPath)) {
                String json = Files.readString(variantsPath);
                if (json != null && !json.isBlank()) {
                    VariantsData data = objectMapper.readValue(json, VariantsData.class);
                    if (data.variants != null) {
                        for (PromptVariantInfo info : data.variants) {
                            variants.put(info.id, info);
                        }
                    }
                    currentVersion = data.currentVersion;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load variants: " + e.getMessage());
        }

        try {
            Path activePath = Paths.get(activeFile);
            if (Files.exists(activePath)) {
                String content = Files.readString(activePath);
                activePrompt = (content != null && !content.isBlank()) ? content : null;
            }
        } catch (IOException e) {
            logger.warn("Failed to load active prompt: " + e.getMessage());
        }
    }

    private void persistVariants() {
        try {
            VariantsData data = new VariantsData();
            data.currentVersion = currentVersion;
            data.variants = new ArrayList<>(variants.values());
            Files.writeString(Paths.get(variantsFile), objectMapper.writeValueAsString(data));
        } catch (IOException e) {
            logger.error("Failed to persist variants", Map.of("error", e.getMessage()));
        }
    }

    private void persistActivePrompt() {
        if (activePrompt == null) {
            return;
        }
        try {
            Files.writeString(Paths.get(activeFile), activePrompt);
        } catch (IOException e) {
            logger.error("Failed to persist active prompt", Map.of("error", e.getMessage()));
        }
    }

    private void ensureDirectoriesExist() {
        try {
            Files.createDirectories(Paths.get(promptsDir));
            Files.createDirectories(Paths.get(historyDir));
        } catch (IOException e) {
            logger.warn("Failed to create prompt directories: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

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

    // ==================== 内部数据类 ====================

    /**
     * Self-Refine 反思结果
     */
    static class SelfRefineReflection {
        /**
         * 自评综合评分 (0.0 ~ 1.0)，-1 表示未解析到
         */
        double score = -1.0;
        /**
         * 改进建议列表
         */
        List<String> suggestions = new ArrayList<>();
    }

    /**
     * 反馈汇总信息
     */
    static class FeedbackSummary {
        int totalCount = 0;
        double avgScore = 0.5;
        double positiveRatio = 0.5;
    }

    /**
     * 变体序列化数据结构
     */
    private static class VariantsData {
        public int currentVersion;
        public List<PromptVariantInfo> variants;
    }

    /**
     * Prompt 变体信息
     */
    public static class PromptVariantInfo {
        public String id;
        public String prompt;
        public double score;
        public Instant createdAt;
        public Map<String, Object> metadata;

        public PromptVariantInfo() {
            this.metadata = new HashMap<>();
        }

        public PromptVariantInfo(String id, String prompt, double score,
                                 Instant createdAt, Map<String, Object> metadata) {
            this.id = id;
            this.prompt = prompt;
            this.score = score;
            this.createdAt = createdAt;
            this.metadata = metadata;
        }
    }
}
