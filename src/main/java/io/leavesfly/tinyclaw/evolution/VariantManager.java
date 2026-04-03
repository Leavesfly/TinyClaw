package io.leavesfly.tinyclaw.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Prompt 变体版本管理器。
 * <p>
 * 负责 Prompt 变体的创建、保存、加载、评分和文件 I/O 操作。
 * <p>
 * 存储结构：
 * <pre>
 * {workspace}/evolution/prompts/
 * ├── PROMPT_VARIANTS.json    # 所有 Prompt 变体及其评分
 * ├── PROMPT_ACTIVE.md        # 当前活跃的优化 Prompt
 * └── PROMPT_HISTORY/         # 历史版本归档
 * </pre>
 */
public class VariantManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.variant");

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
    private final EvolutionConfig config;

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
     * 构造变体管理器。
     *
     * @param workspace 工作空间路径
     * @param config    进化配置
     */
    public VariantManager(String workspace, EvolutionConfig config) {
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
     * 设置活跃 Prompt。
     *
     * @param prompt 新的活跃 Prompt
     */
    public void setActivePrompt(String prompt) {
        if (activePrompt != null && !activePrompt.isBlank()) {
            archiveCurrentVersion();
        }
        activePrompt = prompt;
        currentVersion++;
        persistActivePrompt();
        persistVariants();
        logger.info("Set active prompt", Map.of("version", currentVersion));
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

    // ==================== 变体管理 ====================

    /**
     * 保存新变体。
     *
     * @param variantId 变体 ID
     * @param prompt    Prompt 文本
     * @param score     评分
     * @param metadata  元数据
     */
    public void saveVariant(String variantId, String prompt, double score, Map<String, Object> metadata) {
        PromptVariantInfo info = new PromptVariantInfo(variantId, prompt, score,
                Instant.now(), metadata != null ? metadata : new HashMap<>());
        variants.put(variantId, info);
        persistVariants();
        logger.debug("Saved prompt variant", Map.of("id", variantId, "score", score));
    }

    /**
     * 获取指定变体。
     *
     * @param variantId 变体 ID
     * @return 变体信息，不存在返回 null
     */
    public PromptVariantInfo getVariant(String variantId) {
        return variants.get(variantId);
    }

    /**
     * 获取所有变体。
     *
     * @return 变体集合（只读视图）
     */
    public Collection<PromptVariantInfo> getAllVariants() {
        return Collections.unmodifiableCollection(variants.values());
    }

    /**
     * 获取变体数量。
     */
    public int getVariantCount() {
        return variants.size();
    }

    /**
     * 获取当前版本号。
     */
    public int getCurrentVersion() {
        return currentVersion;
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
