package io.leavesfly.tinyclaw.agent.evolution;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反馈收集器，负责收集、聚合和转换用户反馈。
 *
 * 职责：
 * - 收集用户显式反馈（点赞/点踩、评分、文字反馈）
 * - 收集隐式反馈（工具调用成功率、会话长度、重试次数）
 * - 将原始反馈转换为标准化的 EvaluationFeedback
 *
 * 线程安全：使用 ConcurrentHashMap 存储会话级别的反馈数据。
 */
public class FeedbackCollector {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("evolution.collector");

    /** 工具成功率影响评分的权重 */
    private static final double TOOL_SUCCESS_WEIGHT = 0.3;

    /** 重试次数影响评分的权重 */
    private static final double RETRY_PENALTY_WEIGHT = 0.2;

    /** 会话长度影响评分的权重 */
    private static final double SESSION_LENGTH_WEIGHT = 0.1;

    /** 显式反馈权重（高于隐式） */
    private static final double EXPLICIT_FEEDBACK_WEIGHT = 2.0;

    private final FeedbackStore feedbackStore;

    /** 会话级别的隐式反馈追踪 */
    private final Map<String, SessionMetrics> sessionMetrics;

    /**
     * 构造反馈收集器。
     *
     * @param feedbackStore 反馈存储
     */
    public FeedbackCollector(FeedbackStore feedbackStore) {
        this.feedbackStore = feedbackStore;
        this.sessionMetrics = new ConcurrentHashMap<>();
    }

    // ==================== 显式反馈收集 ====================

    /**
     * 记录用户显式反馈。
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

        // 如果是点赞/点踩，添加相应指标
        if (type == FeedbackType.THUMBS_UP) {
            feedback.putMetric("user_satisfaction", 1.0);
        } else if (type == FeedbackType.THUMBS_DOWN) {
            feedback.putMetric("user_satisfaction", 0.0);
        }

        feedbackStore.save(feedback);

        logger.info("Recorded explicit feedback", Map.of(
                "session", sessionKey,
                "type", type.name(),
                "has_comment", comment != null && !comment.isBlank()));
    }

    /**
     * 记录用户评分（0-5 星）。
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

        feedbackStore.save(feedback);

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
        SessionMetrics metrics = getOrCreateSessionMetrics(sessionKey);
        metrics.recordToolCall(toolName, success);

        logger.debug("Recorded tool result", Map.of(
                "session", sessionKey,
                "tool", toolName,
                "success", success));
    }

    /**
     * 记录用户重试（重新提问相似问题）。
     *
     * @param sessionKey 会话键
     */
    public void recordUserRetry(String sessionKey) {
        SessionMetrics metrics = getOrCreateSessionMetrics(sessionKey);
        metrics.incrementRetryCount();

        logger.debug("Recorded user retry", Map.of(
                "session", sessionKey,
                "retry_count", metrics.getRetryCount()));
    }

    /**
     * 记录会话消息交互。
     *
     * @param sessionKey 会话键
     */
    public void recordMessageExchange(String sessionKey) {
        SessionMetrics metrics = getOrCreateSessionMetrics(sessionKey);
        metrics.incrementMessageCount();
    }

    /**
     * 记录会话结束。
     *
     * @param sessionKey 会话键
     */
    public void recordSessionEnd(String sessionKey) {
        SessionMetrics metrics = sessionMetrics.get(sessionKey);
        if (metrics == null) {
            return;
        }

        metrics.markEnded();

        // 生成隐式反馈
        EvaluationFeedback feedback = aggregateImplicitFeedback(sessionKey, metrics);
        if (feedback != null) {
            feedbackStore.save(feedback);
            logger.info("Generated implicit feedback for ended session", Map.of(
                    "session", sessionKey,
                    "score", feedback.getPrimaryScore()));
        }
    }

    // ==================== 反馈聚合 ====================

    /**
     * 汇总指定会话的所有反馈为单个 EvaluationFeedback。
     *
     * @param sessionKey 会话键
     * @return 汇总后的反馈，无反馈时返回 null
     */
    public EvaluationFeedback aggregateSessionFeedback(String sessionKey) {
        // 获取存储的反馈
        EvaluationFeedback storedFeedback = feedbackStore.getAggregatedFeedback(sessionKey);

        // 获取当前会话的实时隐式指标
        SessionMetrics metrics = sessionMetrics.get(sessionKey);
        EvaluationFeedback implicitFeedback = null;
        if (metrics != null) {
            implicitFeedback = aggregateImplicitFeedback(sessionKey, metrics);
        }

        // 合并
        if (storedFeedback == null && implicitFeedback == null) {
            return null;
        }
        if (storedFeedback == null) {
            return implicitFeedback;
        }
        if (implicitFeedback == null) {
            return storedFeedback;
        }

        return storedFeedback.merge(implicitFeedback);
    }

    /**
     * 获取最近的聚合反馈列表。
     *
     * @param days 天数
     * @return 聚合后的反馈列表（按会话去重）
     */
    public List<EvaluationFeedback> getRecentAggregatedFeedbacks(int days) {
        List<EvaluationFeedback> recentFeedbacks = feedbackStore.getRecent(days);

        // 按会话分组聚合
        Map<String, List<EvaluationFeedback>> bySession = recentFeedbacks.stream()
                .filter(fb -> fb.getSessionKey() != null)
                .collect(java.util.stream.Collectors.groupingBy(EvaluationFeedback::getSessionKey));

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

    // ==================== 内部方法 ====================

    private SessionMetrics getOrCreateSessionMetrics(String sessionKey) {
        return sessionMetrics.computeIfAbsent(sessionKey, k -> new SessionMetrics());
    }

    private EvaluationFeedback aggregateImplicitFeedback(String sessionKey, SessionMetrics metrics) {
        double baseScore = 0.5; // 中性起点

        // 工具成功率影响
        double toolSuccessRate = metrics.getToolSuccessRate();
        if (toolSuccessRate >= 0) {
            baseScore += (toolSuccessRate - 0.5) * TOOL_SUCCESS_WEIGHT;
        }

        // 重试次数惩罚
        int retryCount = metrics.getRetryCount();
        if (retryCount > 0) {
            baseScore -= Math.min(0.3, retryCount * 0.1) * RETRY_PENALTY_WEIGHT;
        }

        // 会话长度加成（适度的长会话可能表示深度交互）
        int messageCount = metrics.getMessageCount();
        if (messageCount >= 4 && messageCount <= 20) {
            baseScore += 0.1 * SESSION_LENGTH_WEIGHT;
        } else if (messageCount > 20) {
            // 过长的会话可能表示问题难以解决
            baseScore -= 0.05 * SESSION_LENGTH_WEIGHT;
        }

        // 限制在 [0, 1] 范围内
        baseScore = Math.max(0.0, Math.min(1.0, baseScore));

        EvaluationFeedback feedback = EvaluationFeedback.builder()
                .sessionKey(sessionKey)
                .evalMode(EvaluationFeedback.EvalMode.IMPLICIT)
                .primaryScore(baseScore)
                .sampleCount(1)
                .build();

        // 添加细分指标
        if (toolSuccessRate >= 0) {
            feedback.putMetric("tool_success_rate", toolSuccessRate);
        }
        feedback.putMetric("retry_count", Math.min(1.0, retryCount / 5.0));
        feedback.putMetric("message_count", Math.min(1.0, messageCount / 30.0));

        return feedback;
    }

    /**
     * 清理已结束会话的指标数据（释放内存）。
     */
    public void cleanupEndedSessions() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 1 小时前结束的会话
        sessionMetrics.entrySet().removeIf(entry ->
                entry.getValue().isEnded() && entry.getValue().getEndTime().isBefore(cutoff));
    }

    /**
     * 获取当前追踪的会话数。
     *
     * @return 会话数
     */
    public int getTrackedSessionCount() {
        return sessionMetrics.size();
    }

    // ==================== 会话指标内部类 ====================

    /**
     * 会话级别的隐式指标追踪。
     */
    private static class SessionMetrics {
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        private final AtomicInteger toolSuccessCount = new AtomicInteger(0);
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private final Map<String, Boolean> lastToolResults = new ConcurrentHashMap<>();
        private volatile boolean ended = false;
        private volatile Instant endTime = null;

        void recordToolCall(String toolName, boolean success) {
            toolCallCount.incrementAndGet();
            if (success) {
                toolSuccessCount.incrementAndGet();
            }
            lastToolResults.put(toolName, success);
        }

        void incrementRetryCount() {
            retryCount.incrementAndGet();
        }

        void incrementMessageCount() {
            messageCount.incrementAndGet();
        }

        void markEnded() {
            ended = true;
            endTime = Instant.now();
        }

        boolean isEnded() {
            return ended;
        }

        Instant getEndTime() {
            return endTime;
        }

        double getToolSuccessRate() {
            int total = toolCallCount.get();
            if (total == 0) {
                return -1; // 无工具调用
            }
            return (double) toolSuccessCount.get() / total;
        }

        int getRetryCount() {
            return retryCount.get();
        }

        int getMessageCount() {
            return messageCount.get();
        }
    }
}
