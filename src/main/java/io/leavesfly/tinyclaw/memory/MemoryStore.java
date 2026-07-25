package io.leavesfly.tinyclaw.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 两层记忆存储系统，持久化的 Agent 记忆管理。
 *
 * 架构：
 * - Layer 1 索引层：memory/MEMORY.md，始终注入上下文，Agent 知道"自己记得什么"
 * - Layer 2 内容层：
 *   - memory/topics/*.md，按主题组织的知识文件，按需加载
 *   - memory/MEMORIES.json，带元数据的结构化记忆条目，按评分排序选取
 *
 * 核心能力：
 * - Token 预算控制：索引层始终注入（~200 token），内容层受预算控制
 * - 相关性检索：根据当前对话关键词匹配相关主题文件和结构化记忆
 * - 重要性排序：综合考虑重要性评分、时间衰减、访问频率
 * - 主题文件管理：autoDream 进化引擎自动整合，Agent 也可直接编辑
 */
public class MemoryStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("memory");

    private static final String MEMORIES_FILE = "MEMORIES.json";
    private static final String ARCHIVE_FILE = "MEMORIES_ARCHIVE.json";
    private static final String INDEX_FILE = "MEMORY.md";
    private static final String TOPICS_DIR = "topics";

    /** 默认记忆 token 预算 */
    private static final int DEFAULT_MEMORY_TOKEN_BUDGET = 2048;

    /** 主题文件在记忆上下文中的 token 占比 */
    private static final double TOPICS_TOKEN_RATIO = 0.50;

    /** 结构化记忆在记忆上下文中的 token 占比 */
    private static final double STRUCTURED_MEMORY_TOKEN_RATIO = 0.50;

    /** 每条记忆的最大 token 数 */
    private static final int MAX_SINGLE_ENTRY_TOKENS = 256;

    /** 相关性匹配的权重倍数 */
    private static final double RELEVANCE_BOOST_MULTIPLIER = 2.0;

    private final String workspace;
    private final String memoryDir;
    private final String indexFile;
    private final String topicsDir;
    private final String memoriesJsonFile;
    private final String archiveJsonFile;
    private final ObjectMapper objectMapper;

    /** 写入锁，保护所有写操作的原子性 */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** 内存中的结构化记忆缓存，线程安全 */
    private final CopyOnWriteArrayList<MemoryEntry> entries;

    public MemoryStore(String workspace) {
        this.workspace = workspace;
        this.memoryDir = Paths.get(workspace, "memory").toString();
        this.indexFile = Paths.get(memoryDir, INDEX_FILE).toString();
        this.topicsDir = Paths.get(memoryDir, TOPICS_DIR).toString();
        this.memoriesJsonFile = Paths.get(memoryDir, MEMORIES_FILE).toString();
        this.archiveJsonFile = Paths.get(memoryDir, ARCHIVE_FILE).toString();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.entries = new CopyOnWriteArrayList<>();

        ensureDirectoryExists(Paths.get(memoryDir));
        ensureDirectoryExists(Paths.get(topicsDir));
        loadEntries();
    }

    // ==================== 结构化记忆操作 ====================

    /**
     * 添加一条结构化记忆。
     *
     * @param content    记忆内容
     * @param importance 重要性评分 (0.0 ~ 1.0)
     * @param tags       标签列表
     * @param source     来源标识（如 session_summary, per_turn, evolution, user_explicit）
     */
    public void addEntry(String content, double importance, List<String> tags, String source) {
        writeLock.lock();
        try {
            MemoryEntry entry = new MemoryEntry(content, importance, tags, source);
            entries.add(entry);
            saveEntries();
            logger.debug("Added memory entry", Map.of(
                    "source", source,
                    "importance", importance,
                    "tags", tags != null ? tags.toString() : "[]"));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 获取所有结构化记忆条目（只读副本）。
     */
    public List<MemoryEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * diff 式替换结构化记忆条目（用于进化引擎整合后的批量更新）。
     *
     * 仅移除整合快照中的旧条目，再追加整合结果。LLM 整合期间（可能长达数十秒）
     * 并发写入的新记忆不在快照中，因此不会被误覆盖。
     *
     * @param replacedIds 被整合的原条目 ID 集合（整合前的快照）
     * @param newEntries  整合后的新条目列表
     */
    public void replaceEntries(Set<String> replacedIds, List<MemoryEntry> newEntries) {
        writeLock.lock();
        try {
            int removed = 0;
            if (replacedIds != null && !replacedIds.isEmpty()) {
                int before = entries.size();
                entries.removeIf(entry -> replacedIds.contains(entry.getId()));
                removed = before - entries.size();
            }
            if (newEntries != null) {
                entries.addAll(newEntries);
            }
            saveEntries();
            logger.info("Replaced memory entries", Map.of(
                    "removed", removed,
                    "added", newEntries != null ? newEntries.size() : 0,
                    "total", entries.size()));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 将内存中的条目状态（如访问计数）落盘。
     * 由 autoDream 的 Prune & Index 阶段周期性调用，避免在读路径上无锁写文件。
     */
    public void flush() {
        writeLock.lock();
        try {
            saveEntries();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 移除指定 ID 的记忆条目。
     */
    public MemoryEntry removeEntry(String entryId) {
        writeLock.lock();
        try {
            for (MemoryEntry entry : entries) {
                if (entryId.equals(entry.getId())) {
                    entries.remove(entry);
                    saveEntries();
                    return entry;
                }
            }
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 归档低分记忆条目到归档文件。
     */
    public void archiveEntries(List<MemoryEntry> entriesToArchive) {
        if (entriesToArchive == null || entriesToArchive.isEmpty()) {
            return;
        }
        writeLock.lock();
        try {
            List<MemoryEntry> archived = loadEntriesFromFile(archiveJsonFile);
            archived.addAll(entriesToArchive);
            saveEntriesToFile(archiveJsonFile, archived);

            entries.removeAll(entriesToArchive);
            saveEntries();
            logger.info("Archived memory entries", Map.of("count", entriesToArchive.size()));
        } finally {
            writeLock.unlock();
        }
    }

    // ==================== 主题文件管理 ====================

    /**
     * 读取指定主题文件的内容。
     *
     * @param topicName 主题名称（不含 .md 后缀）
     * @return 主题内容，不存在或失败时返回空字符串
     */
    public String readTopic(String topicName) {
        Path topicFile = Paths.get(topicsDir, topicName + ".md");
        try {
            if (Files.exists(topicFile)) {
                return Files.readString(topicFile);
            }
        } catch (IOException e) {
            logger.warn("Failed to read topic: " + topicName, Map.of("error", e.getMessage()));
        }
        return "";
    }

    /**
     * 写入主题文件（创建或覆盖）。
     *
     * @param topicName 主题名称（不含 .md 后缀）
     * @param content   主题内容
     */
    public void writeTopic(String topicName, String content) {
        writeLock.lock();
        try {
            Path topicFile = Paths.get(topicsDir, topicName + ".md");
            Files.writeString(topicFile, content);
            logger.debug("Wrote topic file", Map.of("topic", topicName));
        } catch (IOException e) {
            logger.error("Failed to write topic: " + topicName, Map.of("error", e.getMessage()));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 列出所有主题文件名（不含 .md 后缀）。
     */
    public List<String> listTopics() {
        Path topicsDirPath = Paths.get(topicsDir);
        if (!Files.exists(topicsDirPath)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(topicsDirPath)) {
            return stream
                    .filter(path -> path.toString().endsWith(".md"))
                    .map(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.substring(0, fileName.length() - 3);
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.warn("Failed to list topics", Map.of("error", e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * 删除指定主题文件。
     */
    public boolean removeTopic(String topicName) {
        writeLock.lock();
        try {
            Path topicFile = Paths.get(topicsDir, topicName + ".md");
            if (Files.exists(topicFile)) {
                Files.delete(topicFile);
                logger.info("Removed topic", Map.of("topic", topicName));
                return true;
            }
            return false;
        } catch (IOException e) {
            logger.error("Failed to remove topic: " + topicName, Map.of("error", e.getMessage()));
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    // ==================== 索引管理 ====================

    /**
     * 读取索引文件 (MEMORY.md)。
     */
    public String readIndex() {
        try {
            if (Files.exists(Paths.get(indexFile))) {
                return Files.readString(Paths.get(indexFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read memory index: " + e.getMessage());
        }
        return "";
    }

    /**
     * 写入索引文件 (MEMORY.md)。
     */
    public void writeIndex(String content) {
        writeLock.lock();
        try {
            Files.writeString(Paths.get(indexFile), content);
            logger.debug("Wrote memory index");
        } catch (IOException e) {
            logger.error("Failed to write memory index", Map.of("error", e.getMessage()));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 根据当前记忆状态重建索引文件。
     * 由 autoDream 的 Prune & Index 阶段调用。
     */
    public void rebuildIndex() {
        StringBuilder index = new StringBuilder();
        index.append("# Agent Memory Index\n\n");

        // 主题摘要
        List<String> topics = listTopics();
        if (!topics.isEmpty()) {
            index.append("## Topics\n\n");
            for (String topic : topics) {
                String content = readTopic(topic);
                String firstLine = extractFirstMeaningfulLine(content);
                index.append("- **").append(topic).append("**: ").append(firstLine).append("\n");
            }
            index.append("\n");
        }

        // 结构化记忆统计
        int entryCount = entries.size();
        if (entryCount > 0) {
            index.append("## Structured Memories\n\n");
            index.append("- Total: ").append(entryCount).append(" entries\n");

            Map<String, Long> sourceCounts = entries.stream()
                    .collect(Collectors.groupingBy(MemoryEntry::getSource, Collectors.counting()));
            for (Map.Entry<String, Long> sourceEntry : sourceCounts.entrySet()) {
                index.append("- ").append(sourceEntry.getKey()).append(": ")
                        .append(sourceEntry.getValue()).append("\n");
            }

            index.append("\n### Top Memories\n\n");
            entries.stream()
                    .sorted(Comparator.comparingDouble(MemoryEntry::computeScore).reversed())
                    .limit(5)
                    .forEach(entry -> {
                        String truncated = entry.getContent().length() > 80
                                ? entry.getContent().substring(0, 80) + "..."
                                : entry.getContent();
                        index.append("- ").append(truncated).append("\n");
                    });
            index.append("\n");
        }

        index.append("## Meta\n\n");
        index.append("- Last rebuilt: ").append(Instant.now().toString()).append("\n");

        writeIndex(index.toString());
        logger.info("Rebuilt memory index", Map.of("topics", topics.size(), "entries", entryCount));
    }

    // ==================== 记忆上下文构建 ====================

    /**
     * 获取格式化的记忆上下文，带 token 预算控制和相关性检索。
     *
     * 构建策略：
     * - 索引层（MEMORY.md）：始终注入，不计入预算
     * - 主题文件：按关键词匹配相关主题，占 50% 预算
     * - 结构化记忆：按评分排序选取，占 50% 预算
     *
     * @param currentMessage 当前用户消息，用于相关性匹配
     * @param tokenBudget    记忆部分的 token 预算上限
     * @return 格式化的记忆上下文
     */
    public String getMemoryContext(String currentMessage, int tokenBudget) {
        if (tokenBudget <= 0) {
            tokenBudget = DEFAULT_MEMORY_TOKEN_BUDGET;
        }

        List<String> keywords = extractKeywords(currentMessage);
        List<String> parts = new ArrayList<>();

        // 索引层：始终注入，不计入预算
        String indexContent = readIndex();
        if (StringUtils.isNotBlank(indexContent)) {
            parts.add(indexContent);
        }

        // 主题文件：按关键词匹配，占 50% 预算
        int topicsBudget = (int) (tokenBudget * TOPICS_TOKEN_RATIO);
        String topicsSection = buildTopicsSection(keywords, topicsBudget);
        if (StringUtils.isNotBlank(topicsSection)) {
            parts.add(topicsSection);
        }

        // 结构化记忆：按评分排序，占 50% 预算
        int structuredBudget = (int) (tokenBudget * STRUCTURED_MEMORY_TOKEN_RATIO);
        String structuredSection = buildStructuredMemorySection(keywords, structuredBudget);
        if (StringUtils.isNotBlank(structuredSection)) {
            parts.add(structuredSection);
        }

        if (parts.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 无参版本，使用默认预算且不做相关性过滤。
     */
    public String getMemoryContext() {
        return getMemoryContext(null, DEFAULT_MEMORY_TOKEN_BUDGET);
    }

    /**
     * 构建主题文件部分。按关键词匹配相关主题，按 token 预算加载。
     */
    private String buildTopicsSection(List<String> keywords, int tokenBudget) {
        List<String> topics = listTopics();
        if (topics.isEmpty() || tokenBudget <= 0) {
            return "";
        }

        List<ScoredTopic> scoredTopics = topics.stream()
                .map(topicName -> {
                    int relevance = computeTopicRelevance(topicName, keywords);
                    return new ScoredTopic(topicName, relevance);
                })
                .filter(scored -> scored.relevance() > 0)
                .sorted(Comparator.comparingInt(ScoredTopic::relevance).reversed())
                .collect(Collectors.toList());

        if (scoredTopics.isEmpty()) {
            return "";
        }

        StringBuilder section = new StringBuilder("## Relevant Topics\n\n");
        int usedTokens = StringUtils.estimateTokens(section.toString());

        for (ScoredTopic scored : scoredTopics) {
            String content = readTopic(scored.topicName());
            if (StringUtils.isBlank(content)) {
                continue;
            }

            int contentTokens = StringUtils.estimateTokens(content);

            if (usedTokens + contentTokens > tokenBudget) {
                int remainingTokens = tokenBudget - usedTokens;
                if (remainingTokens > 50) {
                    int maxChars = remainingTokens * 4;
                    String truncated = content.substring(0, Math.min(content.length(), maxChars));
                    int lastNewline = truncated.lastIndexOf('\n');
                    if (lastNewline > truncated.length() / 2) {
                        truncated = truncated.substring(0, lastNewline);
                    }
                    section.append("### ").append(scored.topicName()).append("\n\n");
                    section.append(truncated).append("\n\n_(truncated)_\n\n");
                }
                break;
            }

            section.append("### ").append(scored.topicName()).append("\n\n");
            section.append(content).append("\n\n");
            usedTokens += contentTokens;
        }

        return section.toString();
    }

    /**
     * 计算主题与关键词的相关性。匹配主题名称和主题文件首行摘要。
     */
    private int computeTopicRelevance(String topicName, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int matchCount = 0;
        String lowerTopicName = topicName.toLowerCase().replace("-", " ").replace("_", " ");

        String content = readTopic(topicName);
        String firstLine = extractFirstMeaningfulLine(content).toLowerCase();

        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();
            if (lowerTopicName.contains(lowerKeyword)) {
                matchCount += 3;
            }
            if (firstLine.contains(lowerKeyword)) {
                matchCount += 1;
            }
        }
        return matchCount;
    }

    /**
     * 构建结构化记忆部分。按综合得分排序选取。
     */
    private String buildStructuredMemorySection(List<String> keywords, int tokenBudget) {
        if (entries.isEmpty() || tokenBudget <= 0) {
            return "";
        }

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

        StringBuilder section = new StringBuilder("## Key Memories\n\n");
        int usedTokens = StringUtils.estimateTokens(section.toString());
        int selectedCount = 0;

        for (ScoredEntry scored : scoredEntries) {
            String entryText = formatEntryForContext(scored.entry());
            int entryTokens = StringUtils.estimateTokens(entryText);

            if (entryTokens > MAX_SINGLE_ENTRY_TOKENS) {
                int maxChars = MAX_SINGLE_ENTRY_TOKENS * 4;
                entryText = entryText.substring(0, Math.min(entryText.length(), maxChars)) + "...";
                entryTokens = MAX_SINGLE_ENTRY_TOKENS;
            }

            if (usedTokens + entryTokens > tokenBudget) {
                break;
            }

            section.append(entryText).append("\n");
            usedTokens += entryTokens;
            selectedCount++;

            // 仅更新内存状态，落盘由 autoDream 的 flush() 周期性完成，
            // 避免读路径上无锁写文件与持锁写入交叉损坏 JSON
            scored.entry().recordAccess();
        }

        if (selectedCount == 0) {
            return "";
        }

        int totalEntries = entries.size();
        if (selectedCount < totalEntries) {
            section.append(String.format("\n_(%d of %d memories shown, filtered by relevance and importance)_\n",
                    selectedCount, totalEntries));
        }

        return section.toString();
    }

    // ==================== 关键词提取 ====================

    private List<String> extractKeywords(String message) {
        if (StringUtils.isBlank(message)) {
            return Collections.emptyList();
        }

        String[] tokens = message.split("[\\s,，。！？!?;；:：、()（）\\[\\]{}\"']+");

        Set<String> stopWords = Set.of(
                "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "can", "shall", "to", "of", "in", "for",
                "on", "with", "at", "by", "from", "as", "into", "about", "it", "its",
                "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
                "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
                "what", "which", "who", "when", "where", "how", "not", "no", "but", "and",
                "or", "if", "then", "so", "just", "also", "very", "too", "here", "there",
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
                "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
                "没有", "看", "好", "自己", "这", "他", "她", "它", "吗", "吧", "呢",
                "啊", "哦", "嗯", "那", "还", "把", "被", "让", "给", "从", "对"
        );

        return Arrays.stream(tokens)
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !stopWords.contains(token.toLowerCase()))
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }

    // ==================== 格式化 ====================

    private String formatEntryForContext(MemoryEntry entry) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("- ");
        if (entry.getTags() != null && !entry.getTags().isEmpty()) {
            String tagStr = entry.getTags().stream()
                    .map(tag -> "[" + tag + "]")
                    .collect(Collectors.joining(""));
            formatted.append(tagStr).append(" ");
        }
        formatted.append(entry.getContent());
        return formatted.toString();
    }

    private String extractFirstMeaningfulLine(String content) {
        if (StringUtils.isBlank(content)) {
            return "(empty)";
        }
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            return trimmed.length() > 100 ? trimmed.substring(0, 100) + "..." : trimmed;
        }
        return "(empty)";
    }

    // ==================== 持久化 ====================

    private void loadEntries() {
        List<MemoryEntry> loaded = loadEntriesFromFile(memoriesJsonFile);
        entries.addAll(loaded);
        if (!loaded.isEmpty()) {
            logger.info("Loaded memory entries", Map.of("count", loaded.size()));
        }
    }

    private void saveEntries() {
        saveEntriesToFile(memoriesJsonFile, new ArrayList<>(entries));
    }

    private List<MemoryEntry> loadEntriesFromFile(String filePath) {
        Path path = Paths.get(filePath);
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path);
                if (StringUtils.isNotBlank(json)) {
                    return objectMapper.readValue(json, new TypeReference<List<MemoryEntry>>() {});
                }
            }
        } catch (IOException e) {
            // 解析失败时备份损坏文件后再返回空列表，避免后续保存静默覆盖全部历史记忆
            logger.warn("Failed to load entries from " + filePath + ": " + e.getMessage());
            backupCorruptFile(path);
        }
        return new ArrayList<>();
    }

    /**
     * 将无法解析的记忆文件重命名为 .corrupt.<时间戳> 备份，便于事后人工恢复。
     */
    private void backupCorruptFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            Path backup = path.resolveSibling(
                    path.getFileName() + ".corrupt." + System.currentTimeMillis());
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            logger.error("Backed up corrupt memory file", Map.of(
                    "source", path.toString(),
                    "backup", backup.toString()));
        } catch (IOException e) {
            logger.error("Failed to backup corrupt memory file: " + path, Map.of("error", e.getMessage()));
        }
    }

    /**
     * 原子写入条目文件：先写临时文件再原子重命名，避免进程中途退出留下半个 JSON。
     */
    private void saveEntriesToFile(String filePath, List<MemoryEntry> entriesToSave) {
        try {
            String json = objectMapper.writeValueAsString(entriesToSave);
            Path target = Paths.get(filePath);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Failed to save entries to " + filePath, Map.of("error", e.getMessage()));
        }
    }

    // ==================== 统计与诊断 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("structured_entries", entries.size());
        stats.put("topics_count", listTopics().size());
        stats.put("index_tokens", StringUtils.estimateTokens(readIndex()));

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

    /**
     * 获取记忆目录路径，供进化引擎存放状态文件。
     */
    public String getMemoryDir() {
        return memoryDir;
    }

    private void ensureDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            logger.warn("Failed to create directory: " + path + " - " + e.getMessage());
        }
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}

    private record ScoredTopic(String topicName, int relevance) {}
}
