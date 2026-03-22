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
 *
 * 核心公式：Prompt(t+1) = O(Prompt(t), E)
 *
 * 优化流程：
 * 1. 收集近期会话的 EvaluationFeedback
 * 2. 分析反馈中的问题模式，生成文本梯度（优化建议）
 * 3. 应用梯度到当前 Prompt，生成优化版本
 * 4. 保存为候选变体，待评估后决定是否采用
 *
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
    private final FeedbackManager feedbackManager;
    private final EvolutionConfig config;

    // ==================== 内聚的 Prompt 存储状态 ====================

    private final String promptsDir;
    private final String variantsFile;
    private final String activeFile;
    private final String historyDir;
    private final ObjectMapper objectMapper;

    /** 内存中的变体缓存 */
    private final Map<String, PromptVariantInfo> variants;

    /** 当前活跃的优化 Prompt（null 表示使用默认） */
    private String activePrompt;

    /** 当前版本号 */
    private int currentVersion;

    /** 上次优化时间戳 */
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
     * 检查是否需要优化并执行。
     *
     * 触发条件：
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

        int lookbackDays = Math.max(7, config.getOptimizationIntervalHours() / 24 + 1);
        List<EvaluationFeedback> recentFeedbacks = feedbackManager.getRecentAggregatedFeedbacks(lookbackDays);

        if (recentFeedbacks.size() < config.getMinFeedbacksForOptimization()) {
            logger.debug("Not enough feedbacks for optimization", Map.of(
                    "available", recentFeedbacks.size(),
                    "required", config.getMinFeedbacksForOptimization()));
            return null;
        }

        OptimizationResult result = optimize(currentPrompt, recentFeedbacks);
        lastOptimizationTimeMs.set(System.currentTimeMillis());
        return result;
    }

    /**
     * 执行 Prompt 优化（基于文本梯度）。
     *
     * @param currentPrompt 当前的 System Prompt
     * @param feedbacks     评估反馈列表
     * @return 优化结果
     */
    public OptimizationResult optimize(String currentPrompt, List<EvaluationFeedback> feedbacks) {
        logger.info("Starting prompt optimization", Map.of("feedback_count", feedbacks.size()));

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
                    currentPrompt, optimizedPrompt, textualGradient, null);
            result.setFeedbackCount(feedbacks.size());
            result.setOriginalScore(summary.avgScore);
            result.setRecommendAdoption(summary.avgScore < config.getAdoptionThreshold());

            String variantId = "tg_" + Instant.now().toEpochMilli();
            saveVariant(variantId, optimizedPrompt, summary.avgScore, Map.of(
                    "feedback_count", feedbacks.size(),
                    "gradient", textualGradient));

            if (config.isAutoApplyOptimization() && result.isRecommendAdoption()) {
                setActivePrompt(optimizedPrompt);
                logger.info("Auto-applied optimized prompt", Map.of("variant_id", variantId));
            }

            logger.info("Optimization completed", Map.of(
                    "original_score", summary.avgScore,
                    "recommend_adoption", result.isRecommendAdoption()));

            return result;
        } catch (Exception e) {
            logger.error("Optimization failed", Map.of("error", e.getMessage()));
            return OptimizationResult.failed(currentPrompt, e.getMessage());
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

    /** 反馈汇总信息 */
    static class FeedbackSummary {
        int totalCount = 0;
        double avgScore = 0.5;
        double positiveRatio = 0.5;
    }

    /** 变体序列化数据结构 */
    private static class VariantsData {
        public int currentVersion;
        public List<PromptVariantInfo> variants;
    }

    /** Prompt 变体信息 */
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
