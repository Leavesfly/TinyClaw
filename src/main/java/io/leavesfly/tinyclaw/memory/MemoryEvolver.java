package io.leavesfly.tinyclaw.memory;

import io.leavesfly.tinyclaw.evolution.EvaluationFeedback;
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
 * 记忆进化引擎（autoDream），驱动记忆系统的自动维护和自我进化。
 *
 * 三阶段进化流程：
 * 1. Gather：收集 MEMORIES.json 中未整合的结构化记忆条目
 * 2. Consolidate：调用 LLM 整合记忆到主题文件 + 去重压缩结构化记忆
 * 3. Prune & Index：衰减归档低分记忆 + 重建 MEMORY.md 索引
 *
 * 触发条件：
 * - 距上次 autoDream ≥ 24h 且累计 ≥ 5 个新会话（新增记忆条目）
 * - 衰减归档（纯计算）每次心跳都执行
 */
public class MemoryEvolver {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("memory.evolver");

    /** 触发整合的记忆条目数量阈值 */
    private static final int CONSOLIDATION_THRESHOLD = 50;

    /** 归档的综合得分阈值 */
    private static final double ARCHIVE_SCORE_THRESHOLD = 0.10;

    /** 活跃记忆的最大条目数 */
    private static final int MAX_ACTIVE_ENTRIES = 200;

    /** LLM 整合的最大 token 数 */
    private static final int EVOLUTION_MAX_TOKENS = 2048;

    /** LLM 整合的温度参数 */
    private static final double EVOLUTION_TEMPERATURE = 0.3;

    /** autoDream 的最小冷却间隔（毫秒），默认 24 小时 */
    private static final long EVOLUTION_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    /** 触发 autoDream 所需的最小新增记忆条目数 */
    private static final int MIN_NEW_ENTRIES_FOR_EVOLUTION = 5;

    private final MemoryStore memoryStore;
    private final LLMProvider provider;
    private final String model;

    /** 上次执行 autoDream 的时间戳 */
    private final AtomicLong lastEvolutionTimeMs = new AtomicLong(0);

    /** 上次进化时的记忆条目数量，用于增量检测 */
    private final AtomicInteger entryCountAtLastEvolution = new AtomicInteger(0);

    public MemoryEvolver(MemoryStore memoryStore, LLMProvider provider, String model) {
        this.memoryStore = memoryStore;
        this.provider = provider;
        this.model = model;
    }

    /**
     * 执行记忆进化周期。
     *
     * - Prune & Index（纯计算）：每次心跳都执行
     * - Gather + Consolidate（调用 LLM）：≥24h 且 ≥5 条新增记忆时才执行
     */
    public void evolve() {
        long now = System.currentTimeMillis();
        long lastTime = lastEvolutionTimeMs.get();
        boolean cooldownExpired = (now - lastTime) >= EVOLUTION_COOLDOWN_MS;
        int currentEntryCount = memoryStore.getEntries().size();
        int newEntryCount = currentEntryCount - entryCountAtLastEvolution.get();
        boolean hasEnoughNewEntries = newEntryCount >= MIN_NEW_ENTRIES_FOR_EVOLUTION;

        boolean shouldRunFullEvolution = cooldownExpired && hasEnoughNewEntries;

        if (shouldRunFullEvolution) {
            logger.info("Starting autoDream cycle (gather + consolidate + prune)",
                    Map.of("new_entries", newEntryCount));
        } else {
            logger.debug("Skipping autoDream consolidation phase",
                    Map.of("cooldown_expired", cooldownExpired,
                            "new_entries", newEntryCount,
                            "hours_since_last", (now - lastTime) / 3600000.0));
        }

        // Phase 1+2: Gather + Consolidate（调用 LLM，受冷却保护）
        if (shouldRunFullEvolution) {
            try {
                gatherAndConsolidate();
            } catch (Exception e) {
                logger.error("Gather and consolidate phase failed", Map.of("error", e.getMessage()));
            }

            lastEvolutionTimeMs.set(System.currentTimeMillis());
            entryCountAtLastEvolution.set(memoryStore.getEntries().size());
        }

        // Phase 3: Prune & Index（纯计算，每次心跳都执行）
        try {
            pruneAndIndex();
        } catch (Exception e) {
            logger.error("Prune and index phase failed", Map.of("error", e.getMessage()));
        }

        logger.info("Memory evolution cycle completed",
                Map.of("full_evolution", shouldRunFullEvolution,
                        "stats", memoryStore.getStats()));
    }

    // ==================== Phase 1+2: Gather + Consolidate ====================

    /**
     * 收集结构化记忆并调用 LLM 整合。
     *
     * 整合任务：
     * - 将相关记忆归类到主题文件（topics/*.md）
     * - 合并重复记忆、解决矛盾、压缩冗余
     * - 生成或更新主题文件内容
     */
    private void gatherAndConsolidate() {
        List<MemoryEntry> currentEntries = memoryStore.getEntries();
        if (currentEntries.isEmpty()) {
            logger.debug("No entries to consolidate");
            return;
        }

        boolean needsConsolidation = currentEntries.size() >= CONSOLIDATION_THRESHOLD;
        String prompt = buildConsolidatePrompt(currentEntries, needsConsolidation);

        try {
            List<Message> messages = List.of(Message.user(prompt));
            Map<String, Object> options = Map.of(
                    "max_tokens", EVOLUTION_MAX_TOKENS,
                    "temperature", EVOLUTION_TEMPERATURE
            );
            LLMResponse response = provider.chat(messages, null, model, options);
            String result = response.getContent();

            if (StringUtils.isBlank(result)) {
                return;
            }

            // 解析主题文件输出
            parseAndWriteTopics(result);

            // 如果需要整合，解析整合后的记忆列表
            if (needsConsolidation) {
                List<MemoryEntry> consolidated = parseMemoryLines(result, "MEMORY", "evolution_consolidate");
                if (!consolidated.isEmpty() && consolidated.size() < currentEntries.size()) {
                    int maxAccessCount = currentEntries.stream()
                            .mapToInt(MemoryEntry::getAccessCount)
                            .max().orElse(0);
                    for (MemoryEntry entry : consolidated) {
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
    private String buildConsolidatePrompt(List<MemoryEntry> currentEntries, boolean needsConsolidation) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个记忆管理系统。请分析以下结构化记忆，完成指定任务。\n\n");

        // 任务一：归类到主题文件
        prompt.append("## 任务一：归类到主题文件\n\n");
        prompt.append("将下方记忆按主题归类，为每个主题生成一个 Markdown 内容块。\n");
        prompt.append("每个主题按以下格式输出：\n");
        prompt.append("TOPIC|主题名称（英文短横线命名，如 user-preferences）\n");
        prompt.append("主题内容（Markdown 格式，简洁的要点列表）\n");
        prompt.append("END_TOPIC\n\n");
        prompt.append("常见主题示例：user-preferences, project-patterns, lessons-learned, key-facts\n");
        prompt.append("- 每个主题应包含 2-10 条相关要点\n");
        prompt.append("- 合并重复信息，保留最新/最准确的版本\n");
        prompt.append("- 如果某条记忆不属于任何主题，可以跳过\n\n");

        // 任务二：整合记忆（条件触发）
        if (needsConsolidation) {
            prompt.append("## 任务二：整合结构化记忆\n\n");
            prompt.append("当前记忆数量较多（").append(currentEntries.size()).append(" 条），请同时进行整合：\n");
            prompt.append("- 合并重复项、解决矛盾信息、移除已归类到主题文件的内容\n");
            prompt.append("- 保留尚未归类的独特记忆\n");
            prompt.append("- 每条整合后的记忆按以下格式输出：MEMORY|重要性评分|标签1,标签2|内容\n");
            prompt.append("- 重要性评分：0.0 到 1.0\n\n");
        }

        // 当前记忆列表
        prompt.append("## 当前结构化记忆\n\n");
        for (int i = 0; i < currentEntries.size(); i++) {
            MemoryEntry entry = currentEntries.get(i);
            prompt.append(String.format("%d. [重要性=%.1f, 标签=%s, 来源=%s] %s\n",
                    i + 1, entry.getImportance(), entry.getTags(),
                    entry.getSource(), entry.getContent()));
        }

        // 现有主题文件列表（避免重复创建）
        List<String> existingTopics = memoryStore.listTopics();
        if (!existingTopics.isEmpty()) {
            prompt.append("\n## 已有主题文件（请更新而非重复创建）\n\n");
            for (String topic : existingTopics) {
                String content = memoryStore.readTopic(topic);
                String firstLine = content.split("\n")[0];
                prompt.append("- ").append(topic).append(": ").append(firstLine).append("\n");
            }
        }

        return prompt.toString();
    }

    /**
     * 解析 LLM 输出中的主题文件块并写入。
     */
    private void parseAndWriteTopics(String llmOutput) {
        String[] lines = llmOutput.split("\n");
        String currentTopicName = null;
        StringBuilder currentTopicContent = null;
        int topicsWritten = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("TOPIC|")) {
                currentTopicName = trimmed.substring("TOPIC|".length()).trim()
                        .toLowerCase().replaceAll("[^a-z0-9\\-]", "-");
                currentTopicContent = new StringBuilder();
            } else if ("END_TOPIC".equals(trimmed) && currentTopicName != null && currentTopicContent != null) {
                String content = currentTopicContent.toString().trim();
                if (StringUtils.isNotBlank(content)) {
                    // 如果主题已存在，合并内容
                    String existing = memoryStore.readTopic(currentTopicName);
                    if (StringUtils.isNotBlank(existing)) {
                        content = existing + "\n\n---\n\n" + content;
                    }
                    memoryStore.writeTopic(currentTopicName, content);
                    topicsWritten++;
                }
                currentTopicName = null;
                currentTopicContent = null;
            } else if (currentTopicContent != null) {
                currentTopicContent.append(line).append("\n");
            }
        }

        if (topicsWritten > 0) {
            logger.info("Wrote topic files from consolidation", Map.of("count", topicsWritten));
        }
    }

    /**
     * 解析 LLM 输出中指定前缀的记忆行。
     * 格式：{prefix}|importance|tag1,tag2|content
     */
    private List<MemoryEntry> parseMemoryLines(String llmOutput, String prefix, String source) {
        List<MemoryEntry> entries = new ArrayList<>();
        String[] lines = llmOutput.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith(prefix + "|")) {
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
                    MemoryEntry entry = new MemoryEntry(content, importance, tags, source);
                    entries.add(entry);
                }
            } catch (NumberFormatException e) {
                logger.debug("Skipped malformed memory line: " + line);
            }
        }

        return entries;
    }

    // ==================== Phase 3: Prune & Index ====================

    /**
     * 衰减归档低分记忆 + 重建索引。
     * 纯计算操作，每次心跳都执行。
     */
    private void pruneAndIndex() {
        decayAndArchive();
        memoryStore.rebuildIndex();
    }

    /**
     * 对低分记忆进行衰减和归档。
     *
     * 两个归档条件（满足任一即归档）：
     * 1. 综合得分低于 ARCHIVE_SCORE_THRESHOLD
     * 2. 活跃记忆总数超过 MAX_ACTIVE_ENTRIES
     */
    private void decayAndArchive() {
        List<MemoryEntry> currentEntries = new ArrayList<>(memoryStore.getEntries());
        if (currentEntries.isEmpty()) {
            return;
        }

        currentEntries.sort(Comparator.comparingDouble(MemoryEntry::computeScore));

        Set<MemoryEntry> toArchiveSet = new LinkedHashSet<>();

        // 条件 1：得分低于阈值
        for (MemoryEntry entry : currentEntries) {
            if (entry.computeScore() < ARCHIVE_SCORE_THRESHOLD) {
                toArchiveSet.add(entry);
            }
        }

        // 条件 2：超过最大活跃数量
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

    // ==================== 基于评估反馈的智能进化 ====================

    /**
     * 基于评估反馈的智能记忆进化。
     *
     * 根据反馈分数采取不同策略：
     * - 高分会话（> 0.8）：提炼为高重要性记忆，学习成功模式
     * - 低分会话（< 0.3）：分析失败原因，生成避坑记忆
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
            extractHighValueMemories(feedback);
        } else if (score < 0.3) {
            extractLessonsLearned(feedback);
        }
    }

    private void extractHighValueMemories(EvaluationFeedback feedback) {
        String sessionKey = feedback.getSessionKey();
        String textualGradient = feedback.getTextualGradient();

        String content;
        if (StringUtils.isNotBlank(textualGradient)) {
            content = "[成功模式] " + textualGradient;
        } else {
            content = String.format("[成功会话] 会话 %s 获得高分 (%.2f)，表明当前处理方式有效",
                    sessionKey != null ? sessionKey : "unknown", feedback.getPrimaryScore());
        }

        List<String> tags = new ArrayList<>();
        tags.add("success_pattern");
        tags.add(feedback.getEvalMode() != null ? feedback.getEvalMode().name().toLowerCase() : "implicit");
        if (sessionKey != null && sessionKey.contains(":")) {
            tags.add(sessionKey.substring(0, sessionKey.indexOf(":")));
        }

        memoryStore.addEntry(content, 0.7, tags, "evolution_feedback");
        logger.info("Extracted high-value memory from positive feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", feedback.getPrimaryScore()));
    }

    private void extractLessonsLearned(EvaluationFeedback feedback) {
        String sessionKey = feedback.getSessionKey();
        String textualGradient = feedback.getTextualGradient();

        String content;
        if (StringUtils.isNotBlank(textualGradient)) {
            content = "[避坑经验] " + textualGradient;
        } else {
            StringBuilder sb = new StringBuilder("[待改进] ");
            if (feedback.hasMetric("tool_success_rate")) {
                double toolRate = feedback.getMetric("tool_success_rate");
                if (toolRate < 0.5) {
                    sb.append("工具调用成功率低 (").append(String.format("%.0f%%", toolRate * 100)).append("); ");
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

        memoryStore.addEntry(content, 0.8, tags, "evolution_feedback");
        logger.info("Extracted lesson from negative feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", feedback.getPrimaryScore()));
    }
}
