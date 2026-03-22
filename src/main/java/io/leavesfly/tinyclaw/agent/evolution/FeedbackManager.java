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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 反馈管理器，统一负责反馈的收集、聚合和持久化。
 *
 * 合并了原 FeedbackCollector（收集逻辑）和 FeedbackStore（存储逻辑），
 * 对外提供单一入口，降低调用方的依赖复杂度。
 *
 * 支持两类反馈：
 * - 显式反馈：用户主动提供的点赞/点踩、星级评分、文字评论
 * - 隐式反馈：从工具成功率、重试次数、会话长度自动推断
 *
 * 存储结构：
 * - {workspace}/evolution/feedback.json：当前活跃反馈
 * - {workspace}/evolution/feedback_archive.json：归档反馈
 */
public class FeedbackManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.feedback");

    private static final String FEEDBACK_FILE = "feedback.json";
    private static final String ARCHIVE_FILE = "feedback_archive.json";

    /** 触发自动清理的条目数阈值 */
    private static final int AUTO_CLEANUP_THRESHOLD = 500;

    private final String evolutionDir;
    private final String feedbackFile;
    private final String archiveFile;
    private final ObjectMapper objectMapper;
    private final EvolutionConfig config;

    /** 持久化反馈列表，线程安全 */
    private final CopyOnWriteArrayList<EvaluationFeedback> feedbacks;

    /** 会话级别的隐式指标追踪，线程安全 */
    private final Map<String, SessionMetrics> sessionMetrics;

    /**
     * 构造反馈管理器。
     *
     * @param workspace 工作空间路径
     * @param config    进化配置（含隐式反馈权重）
     */
    public FeedbackManager(String workspace, EvolutionConfig config) {
        this.evolutionDir = Paths.get(workspace, "evolution").toString();
        this.feedbackFile = Paths.get(evolutionDir, FEEDBACK_FILE).toString();
        this.archiveFile = Paths.get(evolutionDir, ARCHIVE_FILE).toString();
        this.config = config;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.feedbacks = new CopyOnWriteArrayList<>();
        this.sessionMetrics = new ConcurrentHashMap<>();

        ensureDirectoryExists();
        load();
    }

    // ==================== 显式反馈收集 ====================

    /**
     * 记录用户显式反馈（点赞/点踩/文字评论）。
     *
     * @param sessionKey 会话键
     * @param messageId  消息 ID（可为 null）
     * @param type       反馈类型
     * @param comment    用户评论（可为 null）
     */
    public void recordExplicitFeedback(String sessionKey, String messageId,
                                        FeedbackType type, String comment) {
        if (!type.isExplicit()) {
            logger.warn("Attempted to record non-explicit feedback as explicit",
                    Map.of("type", type.name()));
            return;
        }

        EvaluationFeedback feedback = EvaluationFeedback.builder()
                .sessionKey(sessionKey)
                .messageId(messageId)
                .feedbackType(type)
                .evalMode(EvaluationFeedback.EvalMode.USER_EXPLICIT)
                .primaryScore(type.getDefaultScore())
                .userComment(comment)
                .build();

        if (type == FeedbackType.THUMBS_UP) {
            feedback.putMetric("user_satisfaction", 1.0);
        } else if (type == FeedbackType.THUMBS_DOWN) {
            feedback.putMetric("user_satisfaction", 0.0);
        }

        save(feedback);

        logger.info("Recorded explicit feedback", Map.of(
                "session", sessionKey,
                "type", type.name(),
                "has_comment", comment != null && !comment.isBlank()));
    }

    /**
     * 记录用户星级评分（0-5 星）。
     *
     * @param sessionKey 会话键
     * @param messageId  消息 ID（可为 null）
     * @param stars      评分（0-5）
     * @param comment    用户评论（可为 null）
     */
    public void recordStarRating(String sessionKey, String messageId,
                                  int stars, String comment) {
        double normalizedScore = Math.max(0.0, Math.min(1.0, stars / 5.0));

        EvaluationFeedback feedback = EvaluationFeedback.builder()
                .sessionKey(sessionKey)
                .messageId(messageId)
                .feedbackType(FeedbackType.STAR_RATING)
                .evalMode(EvaluationFeedback.EvalMode.USER_EXPLICIT)
                .primaryScore(normalizedScore)
                .userComment(comment)
                .metric("star_rating", normalizedScore)
                .build();

        save(feedback);

        logger.info("Recorded star rating", Map.of(
                "session", sessionKey,
                "stars", stars,
                "normalized_score", normalizedScore));
    }

    // ==================== 隐式反馈收集 ====================

    /**
     * 记录工具调用结果。
     *
     * @param sessionKey 会话键
     * @param toolName   工具名称
     * @param success    是否成功
     */
    public void recordToolResult(String sessionKey, String toolName, boolean success) {
        getOrCreateSessionMetrics(sessionKey).recordToolCall(toolName, success);
        logger.debug("Recorded tool result", Map.of(
                "session", sessionKey, "tool", toolName, "success", success));
    }

    /**
     * 记录用户重试（重新提问相似问题）。
     *
     * @param sessionKey 会话键
     */
    public void recordUserRetry(String sessionKey) {
        getOrCreateSessionMetrics(sessionKey).incrementRetryCount();
        logger.debug("Recorded user retry", Map.of("session", sessionKey));
    }

    /**
     * 记录会话消息交互。
     *
     * @param sessionKey 会话键
     */
    public void recordMessageExchange(String sessionKey) {
        getOrCreateSessionMetrics(sessionKey).incrementMessageCount();
    }

    /**
     * 记录会话结束，自动生成并保存隐式反馈。
     *
     * @param sessionKey 会话键
     */
    public void recordSessionEnd(String sessionKey) {
        SessionMetrics metrics = sessionMetrics.get(sessionKey);
        if (metrics == null) {
            return;
        }
        metrics.markEnded();

        EvaluationFeedback feedback = buildImplicitFeedback(sessionKey, metrics);
        if (feedback != null) {
            save(feedback);
            logger.info("Generated implicit feedback for ended session", Map.of(
                    "session", sessionKey, "score", feedback.getPrimaryScore()));
        }
    }

    // ==================== 反馈查询与聚合 ====================

    /**
     * 获取最近 N 天的聚合反馈列表（按会话去重）。
     *
     * @param days 天数
     * @return 聚合后的反馈列表
     */
    public List<EvaluationFeedback> getRecentAggregatedFeedbacks(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<EvaluationFeedback> recentFeedbacks = feedbacks.stream()
                .filter(fb -> fb.getTimestamp() != null && fb.getTimestamp().isAfter(cutoff))
                .collect(Collectors.toList());

        Map<String, List<EvaluationFeedback>> bySession = recentFeedbacks.stream()
                .filter(fb -> fb.getSessionKey() != null)
                .collect(Collectors.groupingBy(EvaluationFeedback::getSessionKey));

        List<EvaluationFeedback> aggregated = new ArrayList<>();
        for (Map.Entry<String, List<EvaluationFeedback>> entry : bySession.entrySet()) {
            EvaluationFeedback merged = entry.getValue().get(0);
            for (int i = 1; i < entry.getValue().size(); i++) {
                merged.merge(entry.getValue().get(i));
            }
            aggregated.add(merged);
        }
        return aggregated;
    }

    /**
     * 汇总指定会话的所有反馈（含实时隐式指标）。
     *
     * @param sessionKey 会话键
     * @return 汇总后的反馈，无反馈时返回 null
     */
    public EvaluationFeedback aggregateSessionFeedback(String sessionKey) {
        List<EvaluationFeedback> sessionFeedbacks = feedbacks.stream()
                .filter(fb -> sessionKey.equals(fb.getSessionKey()))
                .collect(Collectors.toList());

        SessionMetrics metrics = sessionMetrics.get(sessionKey);
        EvaluationFeedback implicitFeedback = metrics != null
                ? buildImplicitFeedback(sessionKey, metrics) : null;

        if (sessionFeedbacks.isEmpty() && implicitFeedback == null) {
            return null;
        }

        EvaluationFeedback result = EvaluationFeedback.builder()
                .sessionKey(sessionKey)
                .evalMode(EvaluationFeedback.EvalMode.MIXED)
                .build();

        for (EvaluationFeedback fb : sessionFeedbacks) {
            result.merge(fb);
        }
        if (implicitFeedback != null) {
            result.merge(implicitFeedback);
        }
        return result;
    }

    /**
     * 获取反馈统计信息。
     *
     * @return 统计 Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_count", feedbacks.size());
        stats.put("tracked_sessions", sessionMetrics.size());

        if (feedbacks.isEmpty()) {
            return stats;
        }

        DoubleSummaryStatistics scoreStats = feedbacks.stream()
                .mapToDouble(EvaluationFeedback::getPrimaryScore)
                .summaryStatistics();
        stats.put("avg_score", String.format("%.2f", scoreStats.getAverage()));
        stats.put("positive_count", feedbacks.stream().filter(EvaluationFeedback::isPositive).count());
        stats.put("negative_count", feedbacks.stream().filter(EvaluationFeedback::isNegative).count());

        return stats;
    }

    // ==================== 清理 ====================

    /**
     * 清理过期反馈，将其移入归档。
     */
    public void cleanup() {
        int retentionDays = config.getFeedbackRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        List<EvaluationFeedback> toArchive = feedbacks.stream()
                .filter(fb -> fb.getTimestamp() != null && fb.getTimestamp().isBefore(cutoff))
                .collect(Collectors.toList());

        if (toArchive.isEmpty()) {
            return;
        }

        archive(toArchive);
        feedbacks.removeAll(toArchive);
        persist();

        logger.info("Cleaned up old feedbacks", Map.of(
                "archived", toArchive.size(), "remaining", feedbacks.size()));
    }

    /**
     * 清理已结束会话的内存指标（释放内存）。
     */
    public void cleanupEndedSessions() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        sessionMetrics.entrySet().removeIf(entry ->
                entry.getValue().isEnded() && entry.getValue().getEndTime().isBefore(cutoff));
    }

    // ==================== 内部方法 ====================

    private void save(EvaluationFeedback feedback) {
        feedbacks.add(feedback);
        persist();
        if (feedbacks.size() > AUTO_CLEANUP_THRESHOLD) {
            cleanup();
        }
    }

    private SessionMetrics getOrCreateSessionMetrics(String sessionKey) {
        return sessionMetrics.computeIfAbsent(sessionKey, k -> new SessionMetrics());
    }

    /**
     * 基于会话指标和配置权重构建隐式反馈。
     */
    private EvaluationFeedback buildImplicitFeedback(String sessionKey, SessionMetrics metrics) {
        double score = 0.5;

        double toolSuccessRate = metrics.getToolSuccessRate();
        if (toolSuccessRate >= 0) {
            score += (toolSuccessRate - 0.5) * config.getToolSuccessWeight();
        }

        int retryCount = metrics.getRetryCount();
        if (retryCount > 0) {
            score -= Math.min(0.3, retryCount * 0.1) * config.getRetryPenaltyWeight();
        }

        int messageCount = metrics.getMessageCount();
        if (messageCount >= 4 && messageCount <= 20) {
            score += 0.1 * config.getSessionLengthWeight();
        } else if (messageCount > 20) {
            score -= 0.05 * config.getSessionLengthWeight();
        }

        score = Math.max(0.0, Math.min(1.0, score));

        EvaluationFeedback feedback = EvaluationFeedback.builder()
                .sessionKey(sessionKey)
                .evalMode(EvaluationFeedback.EvalMode.IMPLICIT)
                .primaryScore(score)
                .sampleCount(1)
                .build();

        if (toolSuccessRate >= 0) {
            feedback.putMetric("tool_success_rate", toolSuccessRate);
        }
        feedback.putMetric("retry_count", Math.min(1.0, retryCount / 5.0));
        feedback.putMetric("message_count", Math.min(1.0, messageCount / 30.0));

        return feedback;
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
            Files.writeString(Paths.get(feedbackFile),
                    objectMapper.writeValueAsString(new ArrayList<>(feedbacks)));
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
            Files.writeString(archivePath, objectMapper.writeValueAsString(existing));
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

    // ==================== 会话指标内部类 ====================

    /**
     * 会话级别的隐式指标追踪（线程安全）。
     */
    private static class SessionMetrics {
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        private final AtomicInteger toolSuccessCount = new AtomicInteger(0);
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private volatile boolean ended = false;
        private volatile Instant endTime = null;

        void recordToolCall(String toolName, boolean success) {
            toolCallCount.incrementAndGet();
            if (success) {
                toolSuccessCount.incrementAndGet();
            }
        }

        void incrementRetryCount() { retryCount.incrementAndGet(); }
        void incrementMessageCount() { messageCount.incrementAndGet(); }

        void markEnded() {
            ended = true;
            endTime = Instant.now();
        }

        boolean isEnded() { return ended; }
        Instant getEndTime() { return endTime; }

        double getToolSuccessRate() {
            int total = toolCallCount.get();
            return total == 0 ? -1 : (double) toolSuccessCount.get() / total;
        }

        int getRetryCount() { return retryCount.get(); }
        int getMessageCount() { return messageCount.get(); }
    }
}
