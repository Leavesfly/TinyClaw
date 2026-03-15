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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 反馈持久化存储，管理评估反馈的保存、查询和清理。
 *
 * 存储结构：
 * - {workspace}/evolution/feedback.json：当前活跃反馈
 * - {workspace}/evolution/feedback_archive.json：归档反馈
 *
 * 核心功能：
 * - 按会话、时间范围查询反馈
 * - 自动清理过期反馈（默认保留 30 天）
 * - 线程安全的并发访问支持
 */
public class FeedbackStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.feedback");

    /** 反馈文件名 */
    private static final String FEEDBACK_FILE = "feedback.json";

    /** 归档反馈文件名 */
    private static final String ARCHIVE_FILE = "feedback_archive.json";

    /** 默认保留天数 */
    private static final int DEFAULT_RETENTION_DAYS = 30;

    /** 触发自动清理的条目数阈值 */
    private static final int AUTO_CLEANUP_THRESHOLD = 500;

    private final String evolutionDir;
    private final String feedbackFile;
    private final String archiveFile;
    private final ObjectMapper objectMapper;
    private final int retentionDays;

    /** 内存缓存，线程安全 */
    private final CopyOnWriteArrayList<EvaluationFeedback> feedbacks;

    /**
     * 构造反馈存储。
     *
     * @param workspace 工作空间路径
     */
    public FeedbackStore(String workspace) {
        this(workspace, DEFAULT_RETENTION_DAYS);
    }

    /**
     * 构造反馈存储，指定保留天数。
     *
     * @param workspace     工作空间路径
     * @param retentionDays 反馈保留天数
     */
    public FeedbackStore(String workspace, int retentionDays) {
        this.evolutionDir = Paths.get(workspace, "evolution").toString();
        this.feedbackFile = Paths.get(evolutionDir, FEEDBACK_FILE).toString();
        this.archiveFile = Paths.get(evolutionDir, ARCHIVE_FILE).toString();
        this.retentionDays = retentionDays;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.feedbacks = new CopyOnWriteArrayList<>();

        ensureDirectoryExists();
        load();
    }

    // ==================== 写入操作 ====================

    /**
     * 保存评估反馈。
     *
     * @param feedback 评估反馈
     */
    public void save(EvaluationFeedback feedback) {
        if (feedback == null) {
            return;
        }
        feedbacks.add(feedback);
        persist();

        logger.debug("Saved feedback", Map.of(
                "id", feedback.getId(),
                "session", feedback.getSessionKey() != null ? feedback.getSessionKey() : "unknown",
                "score", feedback.getPrimaryScore()));

        // 检查是否需要自动清理
        if (feedbacks.size() > AUTO_CLEANUP_THRESHOLD) {
            cleanup();
        }
    }

    /**
     * 批量保存反馈。
     *
     * @param feedbackList 反馈列表
     */
    public void saveAll(List<EvaluationFeedback> feedbackList) {
        if (feedbackList == null || feedbackList.isEmpty()) {
            return;
        }
        feedbacks.addAll(feedbackList);
        persist();

        logger.info("Batch saved feedbacks", Map.of("count", feedbackList.size()));

        if (feedbacks.size() > AUTO_CLEANUP_THRESHOLD) {
            cleanup();
        }
    }

    // ==================== 查询操作 ====================

    /**
     * 获取所有反馈（只读副本）。
     *
     * @return 反馈列表
     */
    public List<EvaluationFeedback> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(feedbacks));
    }

    /**
     * 按会话键查询反馈。
     *
     * @param sessionKey 会话键
     * @return 该会话的所有反馈
     */
    public List<EvaluationFeedback> getBySession(String sessionKey) {
        if (sessionKey == null) {
            return Collections.emptyList();
        }
        return feedbacks.stream()
                .filter(fb -> sessionKey.equals(fb.getSessionKey()))
                .collect(Collectors.toList());
    }

    /**
     * 按时间范围查询反馈。
     *
     * @param from 开始时间（包含）
     * @param to   结束时间（包含）
     * @return 时间范围内的反馈
     */
    public List<EvaluationFeedback> getByTimeRange(Instant from, Instant to) {
        return feedbacks.stream()
                .filter(fb -> {
                    Instant ts = fb.getTimestamp();
                    return ts != null && !ts.isBefore(from) && !ts.isAfter(to);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取最近 N 天的反馈。
     *
     * @param days 天数
     * @return 反馈列表
     */
    public List<EvaluationFeedback> getRecent(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        return feedbacks.stream()
                .filter(fb -> fb.getTimestamp() != null && fb.getTimestamp().isAfter(cutoff))
                .sorted(Comparator.comparing(EvaluationFeedback::getTimestamp).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 按评估模式查询反馈。
     *
     * @param evalMode 评估模式
     * @return 该模式的反馈
     */
    public List<EvaluationFeedback> getByEvalMode(EvaluationFeedback.EvalMode evalMode) {
        return feedbacks.stream()
                .filter(fb -> evalMode.equals(fb.getEvalMode()))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定会话的汇总反馈。
     *
     * @param sessionKey 会话键
     * @return 汇总后的单个反馈，无反馈时返回 null
     */
    public EvaluationFeedback getAggregatedFeedback(String sessionKey) {
        List<EvaluationFeedback> sessionFeedbacks = getBySession(sessionKey);
        if (sessionFeedbacks.isEmpty()) {
            return null;
        }

        EvaluationFeedback aggregated = EvaluationFeedback.builder()
                .sessionKey(sessionKey)
                .evalMode(EvaluationFeedback.EvalMode.MIXED)
                .build();

        for (EvaluationFeedback fb : sessionFeedbacks) {
            aggregated.merge(fb);
        }

        return aggregated;
    }

    /**
     * 获取反馈统计信息。
     *
     * @return 统计 Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_count", feedbacks.size());

        if (feedbacks.isEmpty()) {
            return stats;
        }

        // 按模式统计
        Map<EvaluationFeedback.EvalMode, Long> byMode = feedbacks.stream()
                .filter(fb -> fb.getEvalMode() != null)
                .collect(Collectors.groupingBy(EvaluationFeedback::getEvalMode, Collectors.counting()));
        stats.put("by_mode", byMode);

        // 评分统计
        DoubleSummaryStatistics scoreStats = feedbacks.stream()
                .mapToDouble(EvaluationFeedback::getPrimaryScore)
                .summaryStatistics();
        stats.put("avg_score", String.format("%.2f", scoreStats.getAverage()));
        stats.put("min_score", String.format("%.2f", scoreStats.getMin()));
        stats.put("max_score", String.format("%.2f", scoreStats.getMax()));

        // 正负反馈比例
        long positiveCount = feedbacks.stream().filter(EvaluationFeedback::isPositive).count();
        long negativeCount = feedbacks.stream().filter(EvaluationFeedback::isNegative).count();
        stats.put("positive_count", positiveCount);
        stats.put("negative_count", negativeCount);

        return stats;
    }

    // ==================== 清理操作 ====================

    /**
     * 清理过期反馈，将其移入归档。
     */
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        List<EvaluationFeedback> toArchive = feedbacks.stream()
                .filter(fb -> fb.getTimestamp() != null && fb.getTimestamp().isBefore(cutoff))
                .collect(Collectors.toList());

        if (toArchive.isEmpty()) {
            return;
        }

        // 追加到归档文件
        archive(toArchive);

        // 从活跃列表移除
        feedbacks.removeAll(toArchive);
        persist();

        logger.info("Cleaned up old feedbacks", Map.of(
                "archived", toArchive.size(),
                "remaining", feedbacks.size()));
    }

    /**
     * 删除指定会话的所有反馈。
     *
     * @param sessionKey 会话键
     * @return 删除的数量
     */
    public int removeBySession(String sessionKey) {
        List<EvaluationFeedback> toRemove = feedbacks.stream()
                .filter(fb -> sessionKey.equals(fb.getSessionKey()))
                .collect(Collectors.toList());

        feedbacks.removeAll(toRemove);
        persist();

        return toRemove.size();
    }

    // ==================== 持久化 ====================

    private void load() {
        try {
            Path path = Paths.get(feedbackFile);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                if (json != null && !json.isBlank()) {
                    List<EvaluationFeedback> loaded = objectMapper.readValue(json,
                            new TypeReference<List<EvaluationFeedback>>() {});
                    feedbacks.addAll(loaded);
                    logger.info("Loaded feedbacks", Map.of("count", loaded.size()));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load feedbacks: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            String json = objectMapper.writeValueAsString(new ArrayList<>(feedbacks));
            Files.writeString(Paths.get(feedbackFile), json);
        } catch (IOException e) {
            logger.error("Failed to persist feedbacks", Map.of("error", e.getMessage()));
        }
    }

    private void archive(List<EvaluationFeedback> toArchive) {
        try {
            List<EvaluationFeedback> existing = new ArrayList<>();
            Path archivePath = Paths.get(archiveFile);
            if (Files.exists(archivePath)) {
                String json = Files.readString(archivePath);
                if (json != null && !json.isBlank()) {
                    existing = objectMapper.readValue(json,
                            new TypeReference<List<EvaluationFeedback>>() {});
                }
            }
            existing.addAll(toArchive);

            String json = objectMapper.writeValueAsString(existing);
            Files.writeString(archivePath, json);
        } catch (IOException e) {
            logger.error("Failed to archive feedbacks", Map.of("error", e.getMessage()));
        }
    }

    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(Paths.get(evolutionDir));
        } catch (IOException e) {
            logger.warn("Failed to create evolution directory: " + e.getMessage());
        }
    }
}
