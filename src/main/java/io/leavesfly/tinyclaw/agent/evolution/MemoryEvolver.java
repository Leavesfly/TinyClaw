package io.leavesfly.tinyclaw.agent.evolution;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 记忆进化引擎，驱动记忆系统的自动维护和自我进化。
 *
 * 核心职责：
 * - 提炼（Extract）：从每日笔记中提取高价值信息，生成结构化记忆条目
 * - 整合（Consolidate）：合并重复记忆、解决矛盾、压缩冗余
 * - 衰减（Decay）：降低长期未访问记忆的重要性
 * - 归档（Archive）：将低分记忆移入归档文件，释放活跃记忆空间
 *
 * 触发方式：
 * - 心跳服务定时触发（推荐每 6-12 小时一次）
 * - 会话摘要后触发提炼
 * - 手动触发（通过工具调用）
 *
 * 进化流程：
 * 1. extractFromDailyNotes：从最近的每日笔记中提取关键信息 → 生成 MemoryEntry
 * 2. consolidateMemories：对所有活跃记忆进行整合去重
 * 3. decayAndArchive：衰减低分记忆，归档过期记忆
 */
public class MemoryEvolver {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("memory.evolver");

    /** 触发整合的记忆条目数量阈值 */
    private static final int CONSOLIDATION_THRESHOLD = 50;

    /** 归档的综合得分阈值（低于此分的记忆会被归档） */
    private static final double ARCHIVE_SCORE_THRESHOLD = 0.05;

    /** 活跃记忆的最大条目数（超过后强制归档最低分的记忆） */
    private static final int MAX_ACTIVE_ENTRIES = 200;

    /** 提炼时每日笔记的最大回溯天数 */
    private static final int EXTRACT_LOOKBACK_DAYS = 3;

    /** LLM 摘要的最大 token 数 */
    private static final int EVOLUTION_MAX_TOKENS = 2048;

    /** LLM 摘要的温度参数 */
    private static final double EVOLUTION_TEMPERATURE = 0.3;

    /** LLM 密集操作（提炼、整合）的最小冷却间隔（毫秒），默认 6 小时 */
    private static final long EVOLUTION_COOLDOWN_MS = 6 * 60 * 60 * 1000L;

    private final MemoryStore memoryStore;
    private final LLMProvider provider;
    private final String model;

    /** 上次执行 LLM 密集进化的时间戳（提炼+整合），使用 AtomicLong 保证原子性 */
    private final AtomicLong lastFullEvolutionTimeMs = new AtomicLong(0);

    /** 上次进化时的记忆条目数量，用于增量检测，使用 AtomicInteger 保证原子性 */
    private final AtomicInteger entryCountAtLastEvolution = new AtomicInteger(0);

    /**
     * 构造记忆进化引擎。
     *
     * @param memoryStore 记忆存储实例
     * @param provider    LLM 提供商（用于提炼和整合时的文本理解）
     * @param model       使用的模型名称
     */
    public MemoryEvolver(MemoryStore memoryStore, LLMProvider provider, String model) {
        this.memoryStore = memoryStore;
        this.provider = provider;
        this.model = model;
    }

    /**
     * 执行记忆进化周期，内置冷却机制和增量检测。
     *
     * 策略：
     * - 衰减归档（纯计算）：每次心跳都执行，开销极低
     * - 提炼 + 整合（调用 LLM）：受冷却间隔保护，至少间隔 6 小时；
     *   且仅在自上次进化以来有新增记忆条目时才执行，避免无意义的 LLM 调用
     */
    public void evolve() {
        long now = System.currentTimeMillis();
        long lastEvolutionTime = lastFullEvolutionTimeMs.get();
        boolean cooldownExpired = (now - lastEvolutionTime) >= EVOLUTION_COOLDOWN_MS;
        int currentEntryCount = memoryStore.getEntries().size();
        boolean hasNewEntries = currentEntryCount > entryCountAtLastEvolution.get();

        boolean shouldRunFullEvolution = cooldownExpired && hasNewEntries;

        if (shouldRunFullEvolution) {
            logger.info("Starting full memory evolution cycle (extract + consolidate + decay)");
        } else {
            logger.debug("Skipping LLM-intensive phases",
                    Map.of("cooldown_expired", cooldownExpired,
                            "has_new_entries", hasNewEntries,
                            "hours_since_last", (now - lastEvolutionTime) / 3600000.0));
        }

        // 阶段一、二：提炼 + 整合（仅在冷却到期且有新增记忆时执行）
        if (shouldRunFullEvolution) {
            try {
                extractFromDailyNotes();
            } catch (Exception e) {
                logger.error("Extract phase failed", Map.of("error", e.getMessage()));
            }

            try {
                consolidateIfNeeded();
            } catch (Exception e) {
                logger.error("Consolidation phase failed", Map.of("error", e.getMessage()));
            }

            // 原子更新冷却状态
            lastFullEvolutionTimeMs.set(System.currentTimeMillis());
            entryCountAtLastEvolution.set(memoryStore.getEntries().size());
        }

        // 阶段三：衰减归档（纯计算，每次心跳都执行）
        try {
            decayAndArchive();
        } catch (Exception e) {
            logger.error("Decay/archive phase failed", Map.of("error", e.getMessage()));
        }

        logger.info("Memory evolution cycle completed",
                Map.of("full_evolution", shouldRunFullEvolution,
                        "stats", memoryStore.getStats()));
    }

    // ==================== 阶段一：提炼 ====================

    /**
     * 从最近的每日笔记中提取高价值信息，生成结构化记忆条目。
     *
     * 使用 LLM 分析每日笔记内容，识别出值得长期记住的关键信息，
     * 并为每条信息评估重要性和分配标签。
     */
    private void extractFromDailyNotes() {
        String recentNotes = memoryStore.getRecentDailyNotes(EXTRACT_LOOKBACK_DAYS);
        if (StringUtils.isBlank(recentNotes)) {
            logger.debug("No recent daily notes to extract from");
            return;
        }

        // 获取现有记忆摘要，避免提取重复内容
        String existingMemorySummary = buildExistingMemorySummary();

        String extractionPrompt = buildExtractionPrompt(recentNotes, existingMemorySummary);

        try {
            List<Message> messages = List.of(Message.user(extractionPrompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", EVOLUTION_MAX_TOKENS,
                    "temperature", EVOLUTION_TEMPERATURE
            );
            LLMResponse response = provider.chat(messages, null, model, options);
            String result = response.getContent();

            if (StringUtils.isNotBlank(result)) {
                List<MemoryEntry> extracted = parseExtractedEntries(result);
                for (MemoryEntry entry : extracted) {
                    memoryStore.addEntry(entry.getContent(), entry.getImportance(),
                            entry.getTags(), "evolution_extract");
                }
                logger.info("Extracted memories from daily notes",
                        Map.of("count", extracted.size()));
            }
        } catch (Exception e) {
            logger.error("Failed to extract from daily notes", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 构建提炼提示词。
     */
    private String buildExtractionPrompt(String recentNotes, String existingMemorySummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following daily notes and extract key information worth remembering long-term.\n\n");
        prompt.append("For each piece of information, output it in this exact format (one per line):\n");
        prompt.append("MEMORY|importance_score|tag1,tag2|content\n\n");
        prompt.append("Rules:\n");
        prompt.append("- importance_score: 0.0 to 1.0 (1.0 = critical, 0.5 = moderate, 0.1 = minor)\n");
        prompt.append("- Tags: 1-3 short descriptive tags separated by commas\n");
        prompt.append("- Content: concise, self-contained statement (one sentence)\n");
        prompt.append("- Only extract genuinely important information: user preferences, key decisions, ");
        prompt.append("learned facts, recurring patterns, important events\n");
        prompt.append("- Skip trivial greetings, routine operations, and temporary information\n");
        prompt.append("- Do NOT extract information that already exists in the current memories\n\n");

        if (StringUtils.isNotBlank(existingMemorySummary)) {
            prompt.append("## Current Memories (DO NOT duplicate these)\n\n");
            prompt.append(existingMemorySummary);
            prompt.append("\n\n");
        }

        prompt.append("## Daily Notes to Analyze\n\n");
        prompt.append(recentNotes);
        prompt.append("\n\nOutput only MEMORY lines, nothing else. If nothing is worth extracting, output: NONE");

        return prompt.toString();
    }

    /**
     * 解析 LLM 返回的提取结果。
     *
     * 期望格式：MEMORY|importance|tag1,tag2|content
     */
    private List<MemoryEntry> parseExtractedEntries(String llmOutput) {
        List<MemoryEntry> entries = new ArrayList<>();

        if (llmOutput.trim().equals("NONE")) {
            return entries;
        }

        String[] lines = llmOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith("MEMORY|")) {
                continue;
            }

            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) {
                continue;
            }

            try {
                double importance = Double.parseDouble(parts[1].trim());
                List<String> tags = Arrays.stream(parts[2].trim().split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .collect(Collectors.toList());
                String content = parts[3].trim();

                if (StringUtils.isNotBlank(content)) {
                    MemoryEntry entry = new MemoryEntry(content, importance, tags, "evolution_extract");
                    entries.add(entry);
                }
            } catch (NumberFormatException e) {
                // 跳过格式错误的行
                logger.debug("Skipped malformed extraction line: " + line);
            }
        }

        return entries;
    }

    // ==================== 阶段二：整合 ====================

    /**
     * 当记忆条目数量超过阈值时，触发整合。
     *
     * 整合过程使用 LLM 识别重复和可合并的记忆，
     * 将多条相关记忆合并为更精炼的版本。
     */
    private void consolidateIfNeeded() {
        List<MemoryEntry> currentEntries = memoryStore.getEntries();
        if (currentEntries.size() < CONSOLIDATION_THRESHOLD) {
            return;
        }

        logger.info("Consolidating memories", Map.of("current_count", currentEntries.size()));

        String consolidationPrompt = buildConsolidationPrompt(currentEntries);

        try {
            List<Message> messages = List.of(Message.user(consolidationPrompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", EVOLUTION_MAX_TOKENS,
                    "temperature", EVOLUTION_TEMPERATURE
            );
            LLMResponse response = provider.chat(messages, null, model, options);
            String result = response.getContent();

            if (StringUtils.isNotBlank(result)) {
                List<MemoryEntry> consolidated = parseExtractedEntries(result);
                if (!consolidated.isEmpty() && consolidated.size() < currentEntries.size()) {
                    // 保留被整合后的新条目，继承最高的访问计数
                    int maxAccessCount = currentEntries.stream()
                            .mapToInt(MemoryEntry::getAccessCount)
                            .max().orElse(0);
                    for (MemoryEntry entry : consolidated) {
                        entry.setSource("evolution_consolidate");
                        // 继承部分访问历史，避免新整合的记忆立即被衰减
                        entry.setAccessCount(Math.max(1, maxAccessCount / 2));
                    }

                    memoryStore.replaceEntries(consolidated);
                    logger.info("Memories consolidated",
                            Map.of("before", currentEntries.size(), "after", consolidated.size()));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to consolidate memories", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 构建整合提示词。
     */
    private String buildConsolidationPrompt(List<MemoryEntry> currentEntries) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a memory consolidation system. Review the following memory entries ");
        prompt.append("and consolidate them by:\n");
        prompt.append("1. Merging duplicate or highly similar memories into one\n");
        prompt.append("2. Resolving contradictions (keep the most recent/accurate version)\n");
        prompt.append("3. Combining related facts into concise summaries\n");
        prompt.append("4. Removing obsolete or no-longer-relevant information\n");
        prompt.append("5. Preserving all unique and important information\n\n");
        prompt.append("Output each consolidated memory in this exact format (one per line):\n");
        prompt.append("MEMORY|importance_score|tag1,tag2|content\n\n");
        prompt.append("## Current Memories\n\n");

        for (int i = 0; i < currentEntries.size(); i++) {
            MemoryEntry entry = currentEntries.get(i);
            prompt.append(String.format("%d. [importance=%.1f, tags=%s, score=%.3f] %s\n",
                    i + 1, entry.getImportance(), entry.getTags(), entry.computeScore(),
                    entry.getContent()));
        }

        prompt.append("\nOutput only MEMORY lines. Aim to reduce the total count while preserving all key information.");

        return prompt.toString();
    }

    // ==================== 阶段三：衰减与归档 ====================

    /**
     * 对低分记忆进行衰减和归档。
     *
     * 两个归档条件（满足任一即归档）：
     * 1. 综合得分低于 ARCHIVE_SCORE_THRESHOLD
     * 2. 活跃记忆总数超过 MAX_ACTIVE_ENTRIES（归档最低分的记忆直到降到阈值以下）
     */
    private void decayAndArchive() {
        List<MemoryEntry> currentEntries = new ArrayList<>(memoryStore.getEntries());
        if (currentEntries.isEmpty()) {
            return;
        }

        // 按综合得分排序（升序，最低分在前）
        currentEntries.sort(Comparator.comparingDouble(MemoryEntry::computeScore));

        // 使用 Set 跟踪待归档条目，避免 O(n²) 的 contains 检查
        Set<MemoryEntry> toArchiveSet = new LinkedHashSet<>();

        // 条件 1：得分低于阈值的记忆
        for (MemoryEntry entry : currentEntries) {
            if (entry.computeScore() < ARCHIVE_SCORE_THRESHOLD) {
                toArchiveSet.add(entry);
            }
        }

        // 条件 2：超过最大活跃数量限制
        int remainingAfterScoreArchive = currentEntries.size() - toArchiveSet.size();
        if (remainingAfterScoreArchive > MAX_ACTIVE_ENTRIES) {
            int excessCount = remainingAfterScoreArchive - MAX_ACTIVE_ENTRIES;
            for (MemoryEntry entry : currentEntries) {
                if (excessCount <= 0) {
                    break;
                }
                if (!toArchiveSet.contains(entry)) {
                    toArchiveSet.add(entry);
                    excessCount--;
                }
            }
        }

        if (!toArchiveSet.isEmpty()) {
            memoryStore.archiveEntries(new ArrayList<>(toArchiveSet));
            logger.info("Archived low-score memories", Map.of(
                    "archived_count", toArchiveSet.size(),
                    "remaining_count", memoryStore.getEntries().size()));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建现有记忆的摘要，用于提炼时避免重复。
     */
    private String buildExistingMemorySummary() {
        List<MemoryEntry> entries = memoryStore.getEntries();
        if (entries.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        // 只取得分最高的前 30 条作为去重参考，避免提示词过长
        entries.stream()
                .sorted(Comparator.comparingDouble(MemoryEntry::computeScore).reversed())
                .limit(30)
                .forEach(entry -> summary.append("- ").append(entry.getContent()).append("\n"));

        return summary.toString();
    }

    /**
     * 从会话摘要中快速提炼记忆（轻量级，不调用 LLM）。
     *
     * 当会话摘要生成后调用，将摘要作为中等重要性的记忆条目直接存入。
     * 后续由完整进化周期进行整合和去重。
     *
     * @param sessionKey 会话标识
     * @param summary    会话摘要内容
     */
    public void quickExtractFromSummary(String sessionKey, String summary) {
        if (StringUtils.isBlank(summary)) {
            return;
        }

        // 从会话键中提取通道信息作为标签
        String channel = "unknown";
        if (sessionKey != null && sessionKey.contains(":")) {
            channel = sessionKey.substring(0, sessionKey.indexOf(":"));
        }

        List<String> tags = List.of("session", channel);
        memoryStore.addEntry(summary, 0.4, tags, "session_summary");

        logger.debug("Quick-extracted memory from session summary",
                Map.of("session_key", sessionKey));
    }

    // ==================== 基于评估反馈的智能进化 ====================

    /**
     * 基于评估反馈的智能记忆进化（可选启用）。
     *
     * 根据反馈分数采取不同策略：
     * - 高分会话（> 0.8）：提炼为高重要性记忆，学习成功模式
     * - 低分会话（< 0.3）：分析失败原因，生成避坑记忆
     * - 中等分数：标准处理
     *
     * @param feedback 评估反馈
     */
    public void evolveWithFeedback(EvaluationFeedback feedback) {
        if (feedback == null) {
            return;
        }

        double score = feedback.getPrimaryScore();
        String sessionKey = feedback.getSessionKey();

        logger.debug("Evolving with feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", score,
                "mode", feedback.getEvalMode()));

        if (score > 0.8) {
            // 高分会话：提炼高价值记忆
            extractHighValueMemories(feedback);
        } else if (score < 0.3) {
            // 低分会话：提炼教训记忆
            extractLessonsLearned(feedback);
        }
        // 中等分数会话不做特殊处理，由常规进化周期处理
    }

    /**
     * 从高分会话中提炼高价值记忆。
     *
     * 记录成功模式，供未来参考。
     */
    private void extractHighValueMemories(EvaluationFeedback feedback) {
        String sessionKey = feedback.getSessionKey();
        String textualGradient = feedback.getTextualGradient();

        // 如果有文本梯度（用户评论或 LLM 分析），作为记忆内容
        String content;
        if (StringUtils.isNotBlank(textualGradient)) {
            content = "[成功模式] " + textualGradient;
        } else if (StringUtils.isNotBlank(feedback.getUserComment())) {
            content = "[用户正面反馈] " + feedback.getUserComment();
        } else {
            // 没有具体内容，生成通用记录
            content = String.format("[成功会话] 会话 %s 获得高分 (%.2f)，表明当前处理方式有效",
                    sessionKey != null ? sessionKey : "unknown", feedback.getPrimaryScore());
        }

        List<String> tags = new ArrayList<>();
        tags.add("success_pattern");
        tags.add(feedback.getEvalMode() != null ? feedback.getEvalMode().name().toLowerCase() : "implicit");
        if (sessionKey != null && sessionKey.contains(":")) {
            tags.add(sessionKey.substring(0, sessionKey.indexOf(":")));
        }

        // 高分会话的记忆重要性稍高
        memoryStore.addEntry(content, 0.7, tags, "evolution_feedback");

        logger.info("Extracted high-value memory from positive feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", feedback.getPrimaryScore()));
    }

    /**
     * 从低分会话中提炼教训记忆。
     *
     * 记录失败模式，避免重复犯错。
     */
    private void extractLessonsLearned(EvaluationFeedback feedback) {
        String sessionKey = feedback.getSessionKey();
        String textualGradient = feedback.getTextualGradient();

        String content;
        if (StringUtils.isNotBlank(textualGradient)) {
            content = "[避坑经验] " + textualGradient;
        } else if (StringUtils.isNotBlank(feedback.getUserComment())) {
            content = "[用户负面反馈] " + feedback.getUserComment();
        } else {
            // 生成基于指标的通用记录
            StringBuilder sb = new StringBuilder("[待改进] ");
            if (feedback.hasMetric("tool_success_rate")) {
                double toolRate = feedback.getMetric("tool_success_rate");
                if (toolRate < 0.5) {
                    sb.append("工具调用成功率低 (" + String.format("%.0f%%", toolRate * 100) + "); ");
                }
            }
            if (feedback.hasMetric("retry_count")) {
                double retryRatio = feedback.getMetric("retry_count");
                if (retryRatio > 0.4) {
                    sb.append("用户多次重试; ");
                }
            }
            sb.append("会话评分: ").append(String.format("%.2f", feedback.getPrimaryScore()));
            content = sb.toString();
        }

        List<String> tags = new ArrayList<>();
        tags.add("lesson_learned");
        tags.add("improvement_needed");
        if (sessionKey != null && sessionKey.contains(":")) {
            tags.add(sessionKey.substring(0, sessionKey.indexOf(":")));
        }

        // 教训记忆重要性较高，防止重复犯错
        memoryStore.addEntry(content, 0.8, tags, "evolution_feedback");

        logger.info("Extracted lesson from negative feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", feedback.getPrimaryScore()));
    }
}
