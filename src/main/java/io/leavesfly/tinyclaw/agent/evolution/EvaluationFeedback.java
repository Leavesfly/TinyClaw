package io.leavesfly.tinyclaw.agent.evolution;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一评估反馈，是进化系统的核心数据结构。
 *
 * 对应 EvoX 论文中的 E（Evaluation Feedback），用于驱动迭代优化：
 * Target(t+1) = O(Target(t), E)
 *
 * 评估反馈包含：
 * - 主要评分：综合评估结果 (0.0 ~ 1.0)
 * - 评估模式：反馈来源（用户显式、隐式推断、LLM 评判）
 * - 文本梯度：LLM 生成的优化建议，指导改进方向
 * - 多维指标：accuracy、helpfulness、conciseness 等细分维度
 *
 * 使用示例：
 * <pre>
 * EvaluationFeedback feedback = EvaluationFeedback.builder()
 *     .primaryScore(0.85)
 *     .evalMode(EvalMode.USER_EXPLICIT)
 *     .sessionKey("telegram:12345")
 *     .textualGradient("Be more specific in instructions")
 *     .build();
 * feedback.putMetric("helpfulness", 0.9);
 * </pre>
 */
public class EvaluationFeedback {

    /**
     * 评估模式枚举
     */
    public enum EvalMode {
        /**
         * 用户显式反馈（点赞、评分、文字评价）
         */
        USER_EXPLICIT,

        /**
         * 隐式推断（工具成功率、会话长度、重试次数）
         */
        IMPLICIT,

        /**
         * LLM 评判（使用 LLM 对回复质量进行评估）
         */
        LLM_JUDGE,

        /**
         * 混合模式（综合多种反馈来源）
         */
        MIXED
    }

    // ==================== 核心字段 ====================

    /**
     * 唯一标识
     */
    private String id;

    /**
     * 主要评分 (0.0 ~ 1.0)，综合反映回复质量
     */
    private double primaryScore;

    /**
     * 评估模式
     */
    private EvalMode evalMode;

    /**
     * 样本数量（汇总了多少条反馈）
     */
    private int sampleCount;

    /**
     * 文本梯度：LLM 生成的优化建议
     * 例如："Be more concise", "Add more examples", "Improve tool selection"
     */
    private String textualGradient;

    /**
     * 多维度指标
     */
    private Map<String, Double> metrics;

    /**
     * 评估时间
     */
    private Instant timestamp;

    /**
     * 关联的会话键
     */
    private String sessionKey;

    /**
     * 关联的消息 ID（如果是针对单条消息的反馈）
     */
    private String messageId;

    /**
     * 原始反馈类型
     */
    private FeedbackType feedbackType;

    /**
     * 用户文字评论（如果有）
     */
    private String userComment;

    // ==================== 构造与构建 ====================

    public EvaluationFeedback() {
        this.id = generateId();
        this.metrics = new HashMap<>();
        this.timestamp = Instant.now();
        this.sampleCount = 1;
        this.evalMode = EvalMode.IMPLICIT;
    }

    /**
     * 创建构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从单个反馈类型快速创建。
     *
     * @param sessionKey   会话键
     * @param feedbackType 反馈类型
     * @return 评估反馈实例
     */
    public static EvaluationFeedback fromType(String sessionKey, FeedbackType feedbackType) {
        return builder()
                .sessionKey(sessionKey)
                .feedbackType(feedbackType)
                .primaryScore(feedbackType.getDefaultScore())
                .evalMode(feedbackType.isExplicit() ? EvalMode.USER_EXPLICIT : EvalMode.IMPLICIT)
                .build();
    }

    // ==================== 指标操作 ====================

    /**
     * 添加或更新指标。
     *
     * @param name  指标名称
     * @param value 指标值 (0.0 ~ 1.0)
     */
    public void putMetric(String name, double value) {
        metrics.put(name, Math.max(0.0, Math.min(1.0, value)));
    }

    /**
     * 获取指标值。
     *
     * @param name 指标名称
     * @return 指标值，不存在时返回 -1
     */
    public double getMetric(String name) {
        return metrics.getOrDefault(name, -1.0);
    }

    /**
     * 检查是否有指定指标。
     *
     * @param name 指标名称
     * @return 存在返回 true
     */
    public boolean hasMetric(String name) {
        return metrics.containsKey(name);
    }

    // ==================== 合并操作 ====================

    /**
     * 合并另一个反馈到当前实例。
     *
     * @param other 另一个反馈
     * @return 合并后的当前实例
     */
    public EvaluationFeedback merge(EvaluationFeedback other) {
        if (other == null) {
            return this;
        }

        // 加权平均评分
        double totalSamples = this.sampleCount + other.sampleCount;
        this.primaryScore = (this.primaryScore * this.sampleCount + other.primaryScore * other.sampleCount)
                / totalSamples;
        this.sampleCount = (int) totalSamples;

        // 合并指标（取平均）
        for (Map.Entry<String, Double> entry : other.metrics.entrySet()) {
            String key = entry.getKey();
            if (this.metrics.containsKey(key)) {
                this.metrics.put(key, (this.metrics.get(key) + entry.getValue()) / 2);
            } else {
                this.metrics.put(key, entry.getValue());
            }
        }

        // 合并文本梯度
        if (other.textualGradient != null && !other.textualGradient.isBlank()) {
            if (this.textualGradient == null || this.textualGradient.isBlank()) {
                this.textualGradient = other.textualGradient;
            } else {
                this.textualGradient = this.textualGradient + "; " + other.textualGradient;
            }
        }

        // 更新模式为混合
        if (this.evalMode != other.evalMode) {
            this.evalMode = EvalMode.MIXED;
        }

        return this;
    }

    // ==================== 工具方法 ====================

    private String generateId() {
        return "fb_" + Instant.now().toEpochMilli() + "_" +
                Integer.toHexString(Objects.hash(System.nanoTime()));
    }

    /**
     * 判断是否为正向反馈。
     *
     * @return 评分 >= 0.6 返回 true
     */
    public boolean isPositive() {
        return primaryScore >= 0.6;
    }

    /**
     * 判断是否为负向反馈。
     *
     * @return 评分 < 0.4 返回 true
     */
    public boolean isNegative() {
        return primaryScore < 0.4;
    }

    // ==================== Getters & Setters ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getPrimaryScore() {
        return primaryScore;
    }

    public void setPrimaryScore(double primaryScore) {
        this.primaryScore = Math.max(0.0, Math.min(1.0, primaryScore));
    }

    public EvalMode getEvalMode() {
        return evalMode;
    }

    public void setEvalMode(EvalMode evalMode) {
        this.evalMode = evalMode;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public String getTextualGradient() {
        return textualGradient;
    }

    public void setTextualGradient(String textualGradient) {
        this.textualGradient = textualGradient;
    }

    public Map<String, Double> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Double> metrics) {
        this.metrics = metrics != null ? metrics : new HashMap<>();
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public FeedbackType getFeedbackType() {
        return feedbackType;
    }

    public void setFeedbackType(FeedbackType feedbackType) {
        this.feedbackType = feedbackType;
    }

    public String getUserComment() {
        return userComment;
    }

    public void setUserComment(String userComment) {
        this.userComment = userComment;
    }

    @Override
    public String toString() {
        return String.format("EvaluationFeedback{id='%s', score=%.2f, mode=%s, samples=%d, session='%s'}",
                id, primaryScore, evalMode, sampleCount, sessionKey);
    }

    // ==================== Builder ====================

    public static class Builder {
        private final EvaluationFeedback feedback = new EvaluationFeedback();

        public Builder primaryScore(double score) {
            feedback.setPrimaryScore(score);
            return this;
        }

        public Builder evalMode(EvalMode mode) {
            feedback.setEvalMode(mode);
            return this;
        }

        public Builder sampleCount(int count) {
            feedback.setSampleCount(count);
            return this;
        }

        public Builder textualGradient(String gradient) {
            feedback.setTextualGradient(gradient);
            return this;
        }

        public Builder sessionKey(String key) {
            feedback.setSessionKey(key);
            return this;
        }

        public Builder messageId(String id) {
            feedback.setMessageId(id);
            return this;
        }

        public Builder feedbackType(FeedbackType type) {
            feedback.setFeedbackType(type);
            return this;
        }

        public Builder userComment(String comment) {
            feedback.setUserComment(comment);
            return this;
        }

        public Builder metric(String name, double value) {
            feedback.putMetric(name, value);
            return this;
        }

        public EvaluationFeedback build() {
            return feedback;
        }
    }
}
