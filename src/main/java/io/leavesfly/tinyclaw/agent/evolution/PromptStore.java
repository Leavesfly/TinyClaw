package io.leavesfly.tinyclaw.agent.evolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Prompt 版本管理存储，负责 Prompt 变体的保存、加载和版本控制。
 *
 * 存储结构：
 * <pre>
 * {workspace}/evolution/prompts/
 * ├── PROMPT_VARIANTS.json    # 所有 Prompt 变体及其评分
 * ├── PROMPT_ACTIVE.md        # 当前活跃的优化 Prompt
 * └── PROMPT_HISTORY/         # 历史版本归档
 *     ├── v1_20240315_120000.md
 *     └── v2_20240316_080000.md
 * </pre>
 *
 * 核心功能：
 * - 管理多个 Prompt 变体及其评分
 * - 维护当前活跃的优化 Prompt
 * - 版本历史归档和回滚
 */
public class PromptStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.prompts");

    private static final String PROMPTS_DIR = "prompts";
    private static final String VARIANTS_FILE = "PROMPT_VARIANTS.json";
    private static final String ACTIVE_FILE = "PROMPT_ACTIVE.md";
    private static final String HISTORY_DIR = "PROMPT_HISTORY";
    private static final DateTimeFormatter VERSION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final String promptsDir;
    private final String variantsFile;
    private final String activeFile;
    private final String historyDir;
    private final ObjectMapper objectMapper;
    private final int maxHistoryVersions;

    /** 内存中的变体缓存 */
    private final Map<String, PromptVariantInfo> variants;

    /** 当前活跃的优化 Prompt（null 表示使用默认） */
    private String activePrompt;

    /** 当前版本号 */
    private int currentVersion;

    /**
     * 构造 Prompt 存储。
     *
     * @param workspace          工作空间路径
     * @param maxHistoryVersions 最大历史版本数
     */
    public PromptStore(String workspace, int maxHistoryVersions) {
        this.promptsDir = Paths.get(workspace, "evolution", PROMPTS_DIR).toString();
        this.variantsFile = Paths.get(promptsDir, VARIANTS_FILE).toString();
        this.activeFile = Paths.get(promptsDir, ACTIVE_FILE).toString();
        this.historyDir = Paths.get(promptsDir, HISTORY_DIR).toString();
        this.maxHistoryVersions = maxHistoryVersions;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.variants = new LinkedHashMap<>();
        this.currentVersion = 0;

        ensureDirectoriesExist();
        load();
    }

    /**
     * 构造 Prompt 存储，使用默认历史版本数。
     *
     * @param workspace 工作空间路径
     */
    public PromptStore(String workspace) {
        this(workspace, 10);
    }

    // ==================== 活跃 Prompt 管理 ====================

    /**
     * 获取当前活跃的优化 Prompt。
     *
     * @return 活跃 Prompt，无优化时返回 null
     */
    public String getActivePrompt() {
        return activePrompt;
    }

    /**
     * 检查是否有活跃的优化 Prompt。
     *
     * @return 有活跃优化时返回 true
     */
    public boolean hasActiveOptimization() {
        return activePrompt != null && !activePrompt.isBlank();
    }

    /**
     * 设置活跃的优化 Prompt。
     *
     * @param prompt 新的优化 Prompt
     * @return 新版本号
     */
    public int setActivePrompt(String prompt) {
        // 归档当前版本
        if (this.activePrompt != null && !this.activePrompt.isBlank()) {
            archiveCurrentVersion();
        }

        this.activePrompt = prompt;
        this.currentVersion++;

        // 保存到文件
        saveActivePrompt();
        saveVariants();

        logger.info("Set active prompt", Map.of(
                "version", currentVersion,
                "length", prompt != null ? prompt.length() : 0));

        return currentVersion;
    }

    /**
     * 清除活跃的优化 Prompt（回退到默认）。
     */
    public void clearActivePrompt() {
        if (this.activePrompt != null) {
            archiveCurrentVersion();
        }
        this.activePrompt = null;
        this.currentVersion++;

        try {
            Path activePath = Paths.get(activeFile);
            if (Files.exists(activePath)) {
                Files.delete(activePath);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete active prompt file: " + e.getMessage());
        }

        saveVariants();
        logger.info("Cleared active prompt", Map.of("version", currentVersion));
    }

    // ==================== 变体管理 ====================

    /**
     * 保存 Prompt 变体。
     *
     * @param variantId 变体 ID
     * @param prompt    Prompt 内容
     * @param score     评估分数
     * @param metadata  附加元数据
     */
    public void saveVariant(String variantId, String prompt, double score, Map<String, Object> metadata) {
        PromptVariantInfo info = new PromptVariantInfo();
        info.setId(variantId);
        info.setPrompt(prompt);
        info.setScore(score);
        info.setCreatedAt(Instant.now());
        info.setMetadata(metadata != null ? metadata : new HashMap<>());

        variants.put(variantId, info);
        saveVariants();

        logger.debug("Saved prompt variant", Map.of("id", variantId, "score", score));
    }

    /**
     * 获取指定变体。
     *
     * @param variantId 变体 ID
     * @return 变体信息，不存在时返回 null
     */
    public PromptVariantInfo getVariant(String variantId) {
        return variants.get(variantId);
    }

    /**
     * 获取所有变体。
     *
     * @return 变体信息列表
     */
    public List<PromptVariantInfo> getAllVariants() {
        return new ArrayList<>(variants.values());
    }

    /**
     * 获取评分最高的变体。
     *
     * @return 最高分变体，无变体时返回 null
     */
    public PromptVariantInfo getBestVariant() {
        return variants.values().stream()
                .max(Comparator.comparingDouble(PromptVariantInfo::getScore))
                .orElse(null);
    }

    /**
     * 删除指定变体。
     *
     * @param variantId 变体 ID
     * @return 删除成功返回 true
     */
    public boolean removeVariant(String variantId) {
        if (variants.remove(variantId) != null) {
            saveVariants();
            return true;
        }
        return false;
    }

    /**
     * 清理低分变体，只保留前 N 个。
     *
     * @param keepTop 保留的数量
     */
    public void cleanupVariants(int keepTop) {
        if (variants.size() <= keepTop) {
            return;
        }

        List<String> sortedIds = variants.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().getScore(), a.getValue().getScore()))
                .skip(keepTop)
                .map(Map.Entry::getKey)
                .toList();

        for (String id : sortedIds) {
            variants.remove(id);
        }

        saveVariants();
        logger.info("Cleaned up variants", Map.of(
                "removed", sortedIds.size(),
                "remaining", variants.size()));
    }

    // ==================== 版本历史 ====================

    /**
     * 获取历史版本列表。
     *
     * @return 历史版本文件名列表（按时间倒序）
     */
    public List<String> getHistoryVersions() {
        try {
            Path historyPath = Paths.get(historyDir);
            if (!Files.exists(historyPath)) {
                return Collections.emptyList();
            }

            return Files.list(historyPath)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to list history versions: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 读取历史版本内容。
     *
     * @param versionFileName 版本文件名
     * @return 历史版本内容，不存在时返回 null
     */
    public String readHistoryVersion(String versionFileName) {
        try {
            Path versionPath = Paths.get(historyDir, versionFileName);
            if (Files.exists(versionPath)) {
                return Files.readString(versionPath);
            }
        } catch (IOException e) {
            logger.warn("Failed to read history version: " + e.getMessage());
        }
        return null;
    }

    /**
     * 回滚到指定历史版本。
     *
     * @param versionFileName 版本文件名
     * @return 回滚成功返回 true
     */
    public boolean rollbackTo(String versionFileName) {
        String content = readHistoryVersion(versionFileName);
        if (content == null) {
            return false;
        }

        setActivePrompt(content);
        logger.info("Rolled back to version", Map.of("version", versionFileName));
        return true;
    }

    // ==================== 统计信息 ====================

    /**
     * 获取存储统计信息。
     *
     * @return 统计 Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("current_version", currentVersion);
        stats.put("has_active_optimization", hasActiveOptimization());
        stats.put("variant_count", variants.size());
        stats.put("history_count", getHistoryVersions().size());

        if (!variants.isEmpty()) {
            DoubleSummaryStatistics scoreStats = variants.values().stream()
                    .mapToDouble(PromptVariantInfo::getScore)
                    .summaryStatistics();
            stats.put("avg_variant_score", String.format("%.2f", scoreStats.getAverage()));
            stats.put("best_variant_score", String.format("%.2f", scoreStats.getMax()));
        }

        return stats;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    // ==================== 持久化 ====================

    private void load() {
        // 加载变体信息
        loadVariants();

        // 加载活跃 Prompt
        loadActivePrompt();
    }

    private void loadVariants() {
        try {
            Path path = Paths.get(variantsFile);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                if (json != null && !json.isBlank()) {
                    VariantsData data = objectMapper.readValue(json, VariantsData.class);
                    if (data.variants != null) {
                        for (PromptVariantInfo info : data.variants) {
                            variants.put(info.getId(), info);
                        }
                    }
                    this.currentVersion = data.currentVersion;
                    logger.info("Loaded prompt variants", Map.of(
                            "count", variants.size(),
                            "version", currentVersion));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load variants: " + e.getMessage());
        }
    }

    private void loadActivePrompt() {
        try {
            Path path = Paths.get(activeFile);
            if (Files.exists(path)) {
                this.activePrompt = Files.readString(path);
                if (this.activePrompt != null && this.activePrompt.isBlank()) {
                    this.activePrompt = null;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load active prompt: " + e.getMessage());
        }
    }

    private void saveVariants() {
        try {
            VariantsData data = new VariantsData();
            data.currentVersion = this.currentVersion;
            data.variants = new ArrayList<>(variants.values());

            String json = objectMapper.writeValueAsString(data);
            Files.writeString(Paths.get(variantsFile), json);
        } catch (IOException e) {
            logger.error("Failed to save variants", Map.of("error", e.getMessage()));
        }
    }

    private void saveActivePrompt() {
        if (activePrompt == null) {
            return;
        }
        try {
            Files.writeString(Paths.get(activeFile), activePrompt);
        } catch (IOException e) {
            logger.error("Failed to save active prompt", Map.of("error", e.getMessage()));
        }
    }

    private void archiveCurrentVersion() {
        if (activePrompt == null || activePrompt.isBlank()) {
            return;
        }

        try {
            String timestamp = VERSION_FORMATTER.format(
                    java.time.LocalDateTime.now());
            String fileName = String.format("v%d_%s.md", currentVersion, timestamp);
            Path archivePath = Paths.get(historyDir, fileName);

            Files.writeString(archivePath, activePrompt);
            logger.debug("Archived prompt version", Map.of("file", fileName));

            // 清理过期历史
            cleanupHistory();
        } catch (IOException e) {
            logger.warn("Failed to archive prompt version: " + e.getMessage());
        }
    }

    private void cleanupHistory() {
        List<String> versions = getHistoryVersions();
        if (versions.size() <= maxHistoryVersions) {
            return;
        }

        // 删除最旧的版本
        for (int i = maxHistoryVersions; i < versions.size(); i++) {
            try {
                Files.delete(Paths.get(historyDir, versions.get(i)));
            } catch (IOException e) {
                logger.warn("Failed to delete old history: " + versions.get(i));
            }
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

    // ==================== 内部数据类 ====================

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
        private String id;
        private String prompt;
        private double score;
        private Instant createdAt;
        private Map<String, Object> metadata;

        public PromptVariantInfo() {
            this.metadata = new HashMap<>();
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        @Override
        public String toString() {
            return String.format("PromptVariantInfo{id='%s', score=%.2f}", id, score);
        }
    }
}
