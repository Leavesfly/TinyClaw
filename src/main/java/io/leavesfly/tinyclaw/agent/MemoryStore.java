package io.leavesfly.tinyclaw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 自进化记忆存储系统，持久化的 Agent 记忆管理。
 *
 * 提供三层记忆架构：
 * - 结构化长期记忆：memory/MEMORIES.json，带元数据的记忆条目（重要性、标签、衰减）
 * - 传统长期记忆：memory/MEMORY.md，兼容旧格式的 Markdown 记忆（Agent 可直接编辑）
 * - 每日笔记：memory/YYYYMM/YYYYMMDD.md，按日期组织的日常记录
 *
 * 核心能力：
 * - Token 预算控制：根据上下文窗口分配记忆预算，防止上下文爆炸
 * - 相关性检索：根据当前对话内容，优先选取相关记忆
 * - 重要性排序：综合考虑重要性评分、时间衰减、访问频率
 * - 结构化存储：每条记忆带有元数据，支持自动进化
 */
public class MemoryStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("memory");

    /** 结构化记忆文件名 */
    private static final String MEMORIES_FILE = "MEMORIES.json";

    /** 归档记忆文件名 */
    private static final String ARCHIVE_FILE = "MEMORIES_ARCHIVE.json";

    /** 默认记忆 token 预算（当调用方未指定时） */
    private static final int DEFAULT_MEMORY_TOKEN_BUDGET = 1024;

    /** 每日笔记在记忆上下文中的最大 token 占比 */
    private static final double DAILY_NOTES_TOKEN_RATIO = 0.3;

    /** 结构化记忆在记忆上下文中的最大 token 占比 */
    private static final double STRUCTURED_MEMORY_TOKEN_RATIO = 0.5;

    /** 传统 MEMORY.md 在记忆上下文中的最大 token 占比 */
    private static final double LEGACY_MEMORY_TOKEN_RATIO = 0.2;

    /** 每条记忆的最大 token 数，超过此值的单条记忆会被截断 */
    private static final int MAX_SINGLE_ENTRY_TOKENS = 256;

    /** 相关性匹配的权重倍数（相关记忆的综合得分乘以此值） */
    private static final double RELEVANCE_BOOST_MULTIPLIER = 3.0;

    private final String workspace;
    private final String memoryDir;
    private final String memoryFile;
    private final String memoriesJsonFile;
    private final String archiveJsonFile;
    private final ObjectMapper objectMapper;

    /** 内存中的结构化记忆缓存，线程安全 */
    private final CopyOnWriteArrayList<MemoryEntry> entries;

    /**
     * 构造 MemoryStore 实例。
     *
     * @param workspace 工作空间根路径
     */
    public MemoryStore(String workspace) {
        this.workspace = workspace;
        this.memoryDir = Paths.get(workspace, "memory").toString();
        this.memoryFile = Paths.get(memoryDir, "MEMORY.md").toString();
        this.memoriesJsonFile = Paths.get(memoryDir, MEMORIES_FILE).toString();
        this.archiveJsonFile = Paths.get(memoryDir, ARCHIVE_FILE).toString();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.entries = new CopyOnWriteArrayList<>();

        ensureDirectoryExists(Paths.get(memoryDir));
        loadEntries();
    }

    // ==================== 结构化记忆操作 ====================

    /**
     * 添加一条结构化记忆。
     *
     * @param content    记忆内容
     * @param importance 重要性评分 (0.0 ~ 1.0)
     * @param tags       标签列表
     * @param source     来源标识（如 session_summary, heartbeat, user_explicit, evolution）
     */
    public void addEntry(String content, double importance, List<String> tags, String source) {
        MemoryEntry entry = new MemoryEntry(content, importance, tags, source);
        entries.add(entry);
        saveEntries();
        logger.debug("Added memory entry", Map.of(
                "source", source,
                "importance", importance,
                "tags", tags != null ? tags.toString() : "[]"));
    }

    /**
     * 获取所有结构化记忆条目（只读副本）。
     *
     * @return 记忆条目列表
     */
    public List<MemoryEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * 替换所有结构化记忆条目（用于进化引擎整合后的批量更新）。
     *
     * @param newEntries 新的记忆条目列表
     */
    public void replaceEntries(List<MemoryEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        saveEntries();
        logger.info("Replaced all memory entries", Map.of("count", entries.size()));
    }

    /**
     * 移除指定 ID 的记忆条目。
     *
     * @param entryId 要移除的条目 ID
     * @return 被移除的条目，未找到返回 null
     */
    public MemoryEntry removeEntry(String entryId) {
        for (MemoryEntry entry : entries) {
            if (entryId.equals(entry.getId())) {
                entries.remove(entry);
                saveEntries();
                return entry;
            }
        }
        return null;
    }

    /**
     * 归档低分记忆条目到归档文件。
     *
     * @param entriesToArchive 要归档的条目列表
     */
    public void archiveEntries(List<MemoryEntry> entriesToArchive) {
        if (entriesToArchive == null || entriesToArchive.isEmpty()) {
            return;
        }

        // 读取现有归档
        List<MemoryEntry> archived = loadEntriesFromFile(archiveJsonFile);
        archived.addAll(entriesToArchive);

        // 写入归档文件
        saveEntriesToFile(archiveJsonFile, archived);

        // 从活跃记忆中移除
        entries.removeAll(entriesToArchive);
        saveEntries();

        logger.info("Archived memory entries", Map.of("count", entriesToArchive.size()));
    }

    // ==================== 记忆上下文构建（核心重构） ====================

    /**
     * 获取格式化的记忆上下文，带 token 预算控制和相关性检索。
     *
     * 这是记忆系统的核心消费接口。根据当前对话内容和 token 预算，
     * 智能选取最相关、最重要的记忆注入上下文。
     *
     * 预算分配策略：
     * - 50% 给结构化记忆（按相关性+重要性排序）
     * - 30% 给每日笔记（最近 1-3 天，按 token 预算动态调整天数）
     * - 20% 给传统 MEMORY.md（截断到预算内）
     *
     * @param currentMessage 当前用户消息，用于相关性匹配（可为 null）
     * @param tokenBudget    记忆部分的 token 预算上限
     * @return 格式化的记忆上下文，如果没有记忆则返回空字符串
     */
    public String getMemoryContext(String currentMessage, int tokenBudget) {
        if (tokenBudget <= 0) {
            tokenBudget = DEFAULT_MEMORY_TOKEN_BUDGET;
        }

        List<String> keywords = extractKeywords(currentMessage);
        List<String> parts = new ArrayList<>();

        // 1. 结构化记忆（按相关性+重要性排序，占 50% 预算）
        int structuredBudget = (int) (tokenBudget * STRUCTURED_MEMORY_TOKEN_RATIO);
        String structuredSection = buildStructuredMemorySection(keywords, structuredBudget);
        if (StringUtils.isNotBlank(structuredSection)) {
            parts.add(structuredSection);
        }

        // 2. 每日笔记（占 30% 预算）
        int dailyNotesBudget = (int) (tokenBudget * DAILY_NOTES_TOKEN_RATIO);
        String dailyNotesSection = buildDailyNotesSection(dailyNotesBudget);
        if (StringUtils.isNotBlank(dailyNotesSection)) {
            parts.add(dailyNotesSection);
        }

        // 3. 传统 MEMORY.md（占 20% 预算）
        int legacyBudget = (int) (tokenBudget * LEGACY_MEMORY_TOKEN_RATIO);
        String legacySection = buildLegacyMemorySection(legacyBudget);
        if (StringUtils.isNotBlank(legacySection)) {
            parts.add(legacySection);
        }

        if (parts.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 向后兼容的无参版本，使用默认预算且不做相关性过滤。
     *
     * @return 格式化的记忆上下文
     */
    public String getMemoryContext() {
        return getMemoryContext(null, DEFAULT_MEMORY_TOKEN_BUDGET);
    }

    /**
     * 构建结构化记忆部分。
     *
     * 按综合得分（重要性 × 衰减 × 访问频率 × 相关性加成）排序，
     * 从高到低选取记忆条目，直到用完 token 预算。
     *
     * @param keywords    当前对话的关键词
     * @param tokenBudget 此部分的 token 预算
     * @return 格式化的结构化记忆字符串
     */
    private String buildStructuredMemorySection(List<String> keywords, int tokenBudget) {
        if (entries.isEmpty() || tokenBudget <= 0) {
            return "";
        }

        // 计算每条记忆的综合得分（基础分 + 相关性加成）
        List<ScoredEntry> scoredEntries = entries.stream()
                .map(entry -> {
                    double baseScore = entry.computeScore();
                    int relevance = entry.computeRelevance(keywords);
                    double finalScore = relevance > 0
                            ? baseScore * (1.0 + relevance * RELEVANCE_BOOST_MULTIPLIER)
                            : baseScore;
                    return new ScoredEntry(entry, finalScore);
                })
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .collect(Collectors.toList());

        // 按预算选取记忆
        StringBuilder section = new StringBuilder("## Key Memories\n\n");
        int usedTokens = StringUtils.estimateTokens(section.toString());
        int selectedCount = 0;

        for (ScoredEntry scored : scoredEntries) {
            String entryText = formatEntryForContext(scored.entry());
            int entryTokens = StringUtils.estimateTokens(entryText);

            // 单条记忆超过限制则截断
            if (entryTokens > MAX_SINGLE_ENTRY_TOKENS) {
                int maxChars = MAX_SINGLE_ENTRY_TOKENS * 4; // 粗略估算
                entryText = entryText.substring(0, Math.min(entryText.length(), maxChars)) + "...";
                entryTokens = MAX_SINGLE_ENTRY_TOKENS;
            }

            if (usedTokens + entryTokens > tokenBudget) {
                break;
            }

            section.append(entryText).append("\n");
            usedTokens += entryTokens;
            selectedCount++;

            // 记录访问
            scored.entry().recordAccess();
        }

        if (selectedCount == 0) {
            return "";
        }

        // 如果有未选中的记忆，提示 Agent
        int totalEntries = entries.size();
        if (selectedCount < totalEntries) {
            section.append(String.format("\n_(%d of %d memories shown, filtered by relevance and importance)_\n",
                    selectedCount, totalEntries));
        }

        // 异步保存访问计数更新
        saveEntriesAsync();

        return section.toString();
    }

    /**
     * 构建每日笔记部分，根据 token 预算动态调整天数。
     *
     * @param tokenBudget 此部分的 token 预算
     * @return 格式化的每日笔记字符串
     */
    private String buildDailyNotesSection(int tokenBudget) {
        if (tokenBudget <= 0) {
            return "";
        }

        StringBuilder section = new StringBuilder("## Recent Activity\n\n");
        int usedTokens = StringUtils.estimateTokens(section.toString());
        LocalDate today = LocalDate.now();

        // 从今天开始，逐天加载，直到预算用完或最多 7 天
        int maxDays = 7;
        boolean hasContent = false;

        for (int dayOffset = 0; dayOffset < maxDays; dayOffset++) {
            LocalDate date = today.minusDays(dayOffset);
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String monthDir = dateStr.substring(0, 6);
            String filePath = Paths.get(memoryDir, monthDir, dateStr + ".md").toString();

            try {
                if (!Files.exists(Paths.get(filePath))) {
                    continue;
                }

                String noteContent = Files.readString(Paths.get(filePath));
                int noteTokens = StringUtils.estimateTokens(noteContent);

                if (usedTokens + noteTokens > tokenBudget) {
                    // 预算不够放完整笔记，截断到剩余预算
                    int remainingTokens = tokenBudget - usedTokens;
                    if (remainingTokens > 50) { // 至少留 50 token 才值得截断
                        int maxChars = remainingTokens * 4;
                        String truncated = noteContent.substring(0, Math.min(noteContent.length(), maxChars));
                        section.append(truncated).append("...\n");
                        hasContent = true;
                    }
                    break;
                }

                section.append(noteContent).append("\n\n---\n\n");
                usedTokens += noteTokens;
                hasContent = true;

            } catch (IOException e) {
                // 忽略单个笔记的读取错误
            }
        }

        return hasContent ? section.toString() : "";
    }

    /**
     * 构建传统 MEMORY.md 部分，截断到 token 预算内。
     *
     * @param tokenBudget 此部分的 token 预算
     * @return 格式化的传统记忆字符串
     */
    private String buildLegacyMemorySection(int tokenBudget) {
        if (tokenBudget <= 0) {
            return "";
        }

        String longTerm = readLongTerm();
        if (StringUtils.isBlank(longTerm)) {
            return "";
        }

        int longTermTokens = StringUtils.estimateTokens(longTerm);
        if (longTermTokens <= tokenBudget) {
            return "## Long-term Memory\n\n" + longTerm;
        }

        // 截断到预算内
        int headerTokens = StringUtils.estimateTokens("## Long-term Memory\n\n");
        int contentBudget = tokenBudget - headerTokens;
        if (contentBudget <= 0) {
            return "";
        }

        int maxChars = contentBudget * 4;
        String truncated = longTerm.substring(0, Math.min(longTerm.length(), maxChars));

        // 尝试在最后一个换行处截断，避免截断到半句话
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > truncated.length() / 2) {
            truncated = truncated.substring(0, lastNewline);
        }

        return "## Long-term Memory\n\n" + truncated +
                "\n\n_(truncated to fit context budget)_";
    }

    // ==================== 关键词提取 ====================

    /**
     * 从消息中提取关键词，用于记忆相关性匹配。
     *
     * 采用简单的分词 + 停用词过滤策略。
     *
     * @param message 用户消息
     * @return 关键词列表
     */
    private List<String> extractKeywords(String message) {
        if (StringUtils.isBlank(message)) {
            return Collections.emptyList();
        }

        // 中英文混合分词：按空格、标点分割
        String[] tokens = message.split("[\\s,，。！？!?;；:：、()（）\\[\\]{}\"']+");

        Set<String> stopWords = Set.of(
                // 英文停用词
                "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "can", "shall", "to", "of", "in", "for",
                "on", "with", "at", "by", "from", "as", "into", "about", "it", "its",
                "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
                "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
                "what", "which", "who", "when", "where", "how", "not", "no", "but", "and",
                "or", "if", "then", "so", "just", "also", "very", "too", "here", "there",
                // 中文停用词
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
                "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
                "没有", "看", "好", "自己", "这", "他", "她", "它", "吗", "吧", "呢",
                "啊", "哦", "嗯", "那", "还", "把", "被", "让", "给", "从", "对"
        );

        return Arrays.stream(tokens)
                .map(String::trim)
                .filter(token -> token.length() >= 2) // 过滤单字符
                .filter(token -> !stopWords.contains(token.toLowerCase()))
                .distinct()
                .limit(20) // 最多 20 个关键词
                .collect(Collectors.toList());
    }

    // ==================== 格式化 ====================

    /**
     * 将记忆条目格式化为上下文中的文本。
     *
     * @param entry 记忆条目
     * @return 格式化的文本
     */
    private String formatEntryForContext(MemoryEntry entry) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("- ");

        // 添加标签前缀
        if (entry.getTags() != null && !entry.getTags().isEmpty()) {
            String tagStr = entry.getTags().stream()
                    .map(tag -> "[" + tag + "]")
                    .collect(Collectors.joining(""));
            formatted.append(tagStr).append(" ");
        }

        formatted.append(entry.getContent());
        return formatted.toString();
    }

    // ==================== 传统接口（向后兼容） ====================

    /**
     * 读取传统长期记忆文件 (MEMORY.md)。
     *
     * @return 长期记忆内容，失败时返回空字符串
     */
    public String readLongTerm() {
        try {
            if (Files.exists(Paths.get(memoryFile))) {
                return Files.readString(Paths.get(memoryFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read long-term memory: " + e.getMessage());
        }
        return "";
    }

    /**
     * 写入传统长期记忆文件 (MEMORY.md)。
     *
     * @param content 要写入的长期记忆内容
     */
    public void writeLongTerm(String content) {
        try {
            Files.writeString(Paths.get(memoryFile), content);
            logger.debug("Wrote long-term memory");
        } catch (IOException e) {
            logger.error("Failed to write long-term memory", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 读取今日笔记。
     *
     * @return 今日笔记内容，失败时返回空字符串
     */
    public String readToday() {
        String todayFile = getTodayFile();
        try {
            if (Files.exists(Paths.get(todayFile))) {
                return Files.readString(Paths.get(todayFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read today's note: " + e.getMessage());
        }
        return "";
    }

    /**
     * 追加内容到今日笔记。
     *
     * @param content 要追加的内容
     */
    public void appendToday(String content) {
        String todayFile = getTodayFile();

        try {
            Path monthDirPath = Paths.get(todayFile).getParent();
            if (monthDirPath != null) {
                Files.createDirectories(monthDirPath);
            }

            String existingContent = "";
            if (Files.exists(Paths.get(todayFile))) {
                existingContent = Files.readString(Paths.get(todayFile));
            }

            String newContent;
            if (existingContent.isEmpty()) {
                String header = "# " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "\n\n";
                newContent = header + content;
            } else {
                newContent = existingContent + "\n" + content;
            }

            Files.writeString(Paths.get(todayFile), newContent);
            logger.debug("Appended to today's note");
        } catch (IOException e) {
            logger.error("Failed to append to today's note", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取最近 N 天的每日笔记。
     *
     * @param days 要获取的天数
     * @return 合并后的笔记内容
     */
    public String getRecentDailyNotes(int days) {
        List<String> notes = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(i);
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String monthDir = dateStr.substring(0, 6);
            String filePath = Paths.get(memoryDir, monthDir, dateStr + ".md").toString();

            try {
                if (Files.exists(Paths.get(filePath))) {
                    notes.add(Files.readString(Paths.get(filePath)));
                }
            } catch (IOException e) {
                // 忽略单个笔记的读取错误
            }
        }

        if (notes.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", notes);
    }

    // ==================== 持久化 ====================

    /**
     * 从 MEMORIES.json 加载结构化记忆到内存。
     */
    private void loadEntries() {
        List<MemoryEntry> loaded = loadEntriesFromFile(memoriesJsonFile);
        entries.addAll(loaded);
        if (!loaded.isEmpty()) {
            logger.info("Loaded memory entries", Map.of("count", loaded.size()));
        }
    }

    /**
     * 将内存中的结构化记忆保存到 MEMORIES.json。
     */
    private void saveEntries() {
        saveEntriesToFile(memoriesJsonFile, new ArrayList<>(entries));
    }

    /**
     * 异步保存记忆条目（用于访问计数更新等非关键写入）。
     */
    private void saveEntriesAsync() {
        Thread saveThread = new Thread(() -> {
            try {
                saveEntries();
            } catch (Exception e) {
                logger.warn("Async save failed: " + e.getMessage());
            }
        });
        saveThread.setDaemon(true);
        saveThread.start();
    }

    /**
     * 从指定文件加载记忆条目列表。
     *
     * @param filePath JSON 文件路径
     * @return 记忆条目列表，失败时返回空列表
     */
    private List<MemoryEntry> loadEntriesFromFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                if (StringUtils.isNotBlank(json)) {
                    return objectMapper.readValue(json, new TypeReference<List<MemoryEntry>>() {});
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load entries from " + filePath + ": " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 将记忆条目列表保存到指定文件。
     *
     * @param filePath JSON 文件路径
     * @param entriesToSave 要保存的条目列表
     */
    private void saveEntriesToFile(String filePath, List<MemoryEntry> entriesToSave) {
        try {
            String json = objectMapper.writeValueAsString(entriesToSave);
            Files.writeString(Paths.get(filePath), json);
        } catch (IOException e) {
            logger.error("Failed to save entries to " + filePath, Map.of("error", e.getMessage()));
        }
    }

    // ==================== 统计与诊断 ====================

    /**
     * 获取记忆系统的统计信息。
     *
     * @return 包含各项统计数据的 Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("structured_entries", entries.size());
        stats.put("legacy_memory_tokens", StringUtils.estimateTokens(readLongTerm()));
        stats.put("today_notes_tokens", StringUtils.estimateTokens(readToday()));

        if (!entries.isEmpty()) {
            DoubleSummaryStatistics importanceStats = entries.stream()
                    .mapToDouble(MemoryEntry::getImportance)
                    .summaryStatistics();
            stats.put("avg_importance", String.format("%.2f", importanceStats.getAverage()));
            stats.put("max_importance", String.format("%.2f", importanceStats.getMax()));

            DoubleSummaryStatistics scoreStats = entries.stream()
                    .mapToDouble(MemoryEntry::computeScore)
                    .summaryStatistics();
            stats.put("avg_score", String.format("%.3f", scoreStats.getAverage()));
        }

        return stats;
    }

    // ==================== 工具方法 ====================

    private String getTodayFile() {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String monthDir = dateStr.substring(0, 6);
        return Paths.get(memoryDir, monthDir, dateStr + ".md").toString();
    }

    private void ensureDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            logger.warn("Failed to create directory: " + path + " - " + e.getMessage());
        }
    }

    /** 内部记录类，用于排序时携带得分 */
    private record ScoredEntry(MemoryEntry entry, double score) {}
}