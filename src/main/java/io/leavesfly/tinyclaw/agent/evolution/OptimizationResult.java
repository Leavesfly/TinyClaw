package io.leavesfly.tinyclaw.agent.evolution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 优化结果封装，记录一次优化操作的完整信息。
 *
 * 包含：
 * - 优化前后的 Prompt
 * - 评估指标变化
 * - 使用的策略和反馈
 * - 是否建议采用
 */
public class OptimizationResult {

    /**
     * 优化状态
     */
    public enum Status {
        /**
         * 优化成功，生成了改进版本
         */
        SUCCESS,

        /**
         * 无需优化，当前版本已足够好
         */
        NO_IMPROVEMENT_NEEDED,

        /**
         * 优化失败，未能生成有效改进
         */
        FAILED,

        /**
         * 等待评估，优化版本已生成但尚未验证
         */
        PENDING_EVALUATION
    }

    // ==================== 核心字段 ====================

    /**
     * 结果 ID
     */
    private String id;

    /**
     * 优化状态
     */
    private Status status;

    /**
     * 原始 Prompt
     */
    private String originalPrompt;

    /**
     * 优化后的 Prompt
     */
    private String optimizedPrompt;

    /**
     * 文本梯度（优化建议）
     */
    private String textualGradient;

    /**
     * 使用的优化策略名称
     */
    private String strategy;

    /**
     * 输入的反馈数量
     */
    private int feedbackCount;

    /**
     * 优化前的预估评分
     */
    private double originalScore;

    /**
     * 优化后的预估评分
     */
    private double optimizedScore;

    /**
     * 评分提升幅度
     */
    private double improvement;

    /**
     * 是否建议采用此优化
     */
    private boolean recommendAdoption;

    /**
     * 不建议采用的原因（如果有）
     */
    private String rejectionReason;

    /**
     * 优化时间
     */
    private Instant timestamp;

    /**
     * 优化过程中的中间变体（如果使用 EVO_PROMPT）
     */
    private List<PromptVariant> variants;

    // ==================== 构造 ====================

    public OptimizationResult() {
        this.id = generateId();
        this.timestamp = Instant.now();
        this.variants = new ArrayList<>();
        this.status = Status.PENDING_EVALUATION;
    }

    /**
     * 创建成功的优化结果。
     *
     * @param original  原始 Prompt
     * @param optimized 优化后的 Prompt
     * @param gradient  文本梯度
     * @param strategy  使用的策略
     * @return 优化结果
     */
    public static OptimizationResult success(String original, String optimized,
                                              String gradient, String strategy) {
        OptimizationResult result = new OptimizationResult();
        result.setStatus(Status.SUCCESS);
        result.setOriginalPrompt(original);
        result.setOptimizedPrompt(optimized);
        result.setTextualGradient(gradient);
        result.setStrategy(strategy);
        return result;
    }

    /**
     * 创建无需优化的结果。
     *
     * @param original 原始 Prompt
     * @param reason   原因
     * @return 优化结果
     */
    public static OptimizationResult noImprovementNeeded(String original, String reason) {
        OptimizationResult result = new OptimizationResult();
        result.setStatus(Status.NO_IMPROVEMENT_NEEDED);
        result.setOriginalPrompt(original);
        result.setOptimizedPrompt(original);
        result.setRejectionReason(reason);
        result.setRecommendAdoption(false);
        return result;
    }

    /**
     * 创建失败的优化结果。
     *
     * @param original 原始 Prompt
     * @param reason   失败原因
     * @return 优化结果
     */
    public static OptimizationResult failed(String original, String reason) {
        OptimizationResult result = new OptimizationResult();
        result.setStatus(Status.FAILED);
        result.setOriginalPrompt(original);
        result.setRejectionReason(reason);
        result.setRecommendAdoption(false);
        return result;
    }

    // ==================== 工具方法 ====================

    private String generateId() {
        return "opt_" + Instant.now().toEpochMilli();
    }

    /**
     * 添加中间变体。
     *
     * @param variant Prompt 变体
     */
    public void addVariant(PromptVariant variant) {
        variants.add(variant);
    }

    /**
     * 计算改进幅度。
     */
    public void calculateImprovement() {
        if (originalScore > 0) {
            this.improvement = optimizedScore - originalScore;
        }
    }

    /**
     * 判断是否有实质性改进。
     *
     * @param threshold 改进阈值
     * @return 改进幅度超过阈值返回 true
     */
    public boolean hasSignificantImprovement(double threshold) {
        return improvement >= threshold;
    }

    // ==================== Getters & Setters ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getOriginalPrompt() {
        return originalPrompt;
    }

    public void setOriginalPrompt(String originalPrompt) {
        this.originalPrompt = originalPrompt;
    }

    public String getOptimizedPrompt() {
        return optimizedPrompt;
    }

    public void setOptimizedPrompt(String optimizedPrompt) {
        this.optimizedPrompt = optimizedPrompt;
    }

    public String getTextualGradient() {
        return textualGradient;
    }

    public void setTextualGradient(String textualGradient) {
        this.textualGradient = textualGradient;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public int getFeedbackCount() {
        return feedbackCount;
    }

    public void setFeedbackCount(int feedbackCount) {
        this.feedbackCount = feedbackCount;
    }

    public double getOriginalScore() {
        return originalScore;
    }

    public void setOriginalScore(double originalScore) {
        this.originalScore = originalScore;
    }

    public double getOptimizedScore() {
        return optimizedScore;
    }

    public void setOptimizedScore(double optimizedScore) {
        this.optimizedScore = optimizedScore;
    }

    public double getImprovement() {
        return improvement;
    }

    public void setImprovement(double improvement) {
        this.improvement = improvement;
    }

    public boolean isRecommendAdoption() {
        return recommendAdoption;
    }

    public void setRecommendAdoption(boolean recommendAdoption) {
        this.recommendAdoption = recommendAdoption;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public List<PromptVariant> getVariants() {
        return variants;
    }

    public void setVariants(List<PromptVariant> variants) {
        this.variants = variants != null ? variants : new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("OptimizationResult{id='%s', status=%s, improvement=%.3f, recommend=%s}",
                id, status, improvement, recommendAdoption);
    }

    // ==================== 内部类：Prompt 变体 ====================

    /**
     * Prompt 变体，用于进化算法中的中间状态。
     */
    public static class PromptVariant {
        private String prompt;
        private double score;
        private int generation;

        public PromptVariant() {}

        public PromptVariant(String prompt, double score, int generation) {
            this.prompt = prompt;
            this.score = score;
            this.generation = generation;
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

        public int getGeneration() {
            return generation;
        }

        public void setGeneration(int generation) {
            this.generation = generation;
        }
    }
}
