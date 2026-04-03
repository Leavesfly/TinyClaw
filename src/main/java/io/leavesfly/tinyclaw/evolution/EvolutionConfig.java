package io.leavesfly.tinyclaw.evolution;

/**
 * 进化能力配置，控制反馈收集和 Prompt 优化的行为。
 *
 * 所有进化功能默认关闭，需要显式启用。
 *
 * YAML 配置示例：
 * <pre>
 * agent:
 *   evolution:
 *     feedbackEnabled: true
 *     promptOptimizationEnabled: true
 *     optimizationIntervalHours: 24
 *     adoptionThreshold: 0.7
 *     feedbackRetentionDays: 30
 *     minFeedbacksForOptimization: 10
 * </pre>
 */
public class EvolutionConfig {

    // ==================== 优化策略枚举 ====================

    /**
     * Prompt 优化策略枚举。
     */
    public enum OptimizationStrategy {
        /**
         * 自我反思：Agent 回顾最近会话的交互质量，自动生成经验教训并改进 Prompt。
         * 不依赖外部反馈，完全自驱动。
         */
        SELF_REFINE
    }

    // ==================== 反馈收集配置 ====================

    /**
     * 是否启用反馈收集
     */
    private boolean feedbackEnabled = false;

    /**
     * 反馈保留天数
     */
    private int feedbackRetentionDays = 30;

    /**
     * 是否收集隐式反馈（工具成功率等）
     */
    private boolean implicitFeedbackEnabled = true;

    // ==================== 隐式反馈权重配置 ====================

    /**
     * 工具调用成功率对评分的影响权重
     */
    private double toolSuccessWeight = 0.3;

    /**
     * 用户重试次数对评分的惩罚权重
     */
    private double retryPenaltyWeight = 0.2;

    /**
     * 会话消息长度对评分的影响权重
     */
    private double sessionLengthWeight = 0.1;

    // ==================== Prompt 优化配置 ====================

    /**
     * 是否启用 Prompt 优化
     */
    private boolean promptOptimizationEnabled = false;

    /**
     * Prompt 优化策略（默认 SELF_REFINE，即自我反思优化）
     */
    private OptimizationStrategy optimizationStrategy = OptimizationStrategy.SELF_REFINE;

    /**
     * 优化检查间隔（小时）
     */
    private int optimizationIntervalHours = 24;

    /**
     * 新 Prompt 采用的分数阈值（0.0 ~ 1.0）
     * 只有当优化后的评分超过此阈值时才会采用
     */
    private double adoptionThreshold = 0.7;

    // ==================== 高级配置 ====================

    /**
     * 是否自动应用优化（true 时自动采用符合条件的优化，false 时仅生成建议）
     */
    private boolean autoApplyOptimization = false;

    /**
     * 优化时使用的 LLM 温度参数
     */
    private double optimizationTemperature = 0.3;

    /**
     * 优化时的最大 token 数
     */
    private int optimizationMaxTokens = 2048;

    /**
     * 最大保留的优化历史版本数
     */
    private int maxHistoryVersions = 10;

    /**
     * Self-Refine 策略：回顾的最近会话数量
     */
    private int selfRefineSessionCount = 5;

    // ==================== Getters & Setters ====================

    public boolean isFeedbackEnabled() {
        return feedbackEnabled;
    }

    public void setFeedbackEnabled(boolean feedbackEnabled) {
        this.feedbackEnabled = feedbackEnabled;
    }

    public int getFeedbackRetentionDays() {
        return feedbackRetentionDays;
    }

    public void setFeedbackRetentionDays(int feedbackRetentionDays) {
        this.feedbackRetentionDays = feedbackRetentionDays;
    }

    public boolean isImplicitFeedbackEnabled() {
        return implicitFeedbackEnabled;
    }

    public void setImplicitFeedbackEnabled(boolean implicitFeedbackEnabled) {
        this.implicitFeedbackEnabled = implicitFeedbackEnabled;
    }

    public boolean isPromptOptimizationEnabled() {
        return promptOptimizationEnabled;
    }

    public void setPromptOptimizationEnabled(boolean promptOptimizationEnabled) {
        this.promptOptimizationEnabled = promptOptimizationEnabled;
    }

    public int getOptimizationIntervalHours() {
        return optimizationIntervalHours;
    }

    public void setOptimizationIntervalHours(int optimizationIntervalHours) {
        this.optimizationIntervalHours = optimizationIntervalHours;
    }

    public double getAdoptionThreshold() {
        return adoptionThreshold;
    }

    public void setAdoptionThreshold(double adoptionThreshold) {
        this.adoptionThreshold = Math.max(0.0, Math.min(1.0, adoptionThreshold));
    }

    public double getToolSuccessWeight() {
        return toolSuccessWeight;
    }

    public void setToolSuccessWeight(double toolSuccessWeight) {
        this.toolSuccessWeight = toolSuccessWeight;
    }

    public double getRetryPenaltyWeight() {
        return retryPenaltyWeight;
    }

    public void setRetryPenaltyWeight(double retryPenaltyWeight) {
        this.retryPenaltyWeight = retryPenaltyWeight;
    }

    public double getSessionLengthWeight() {
        return sessionLengthWeight;
    }

    public void setSessionLengthWeight(double sessionLengthWeight) {
        this.sessionLengthWeight = sessionLengthWeight;
    }

    public boolean isAutoApplyOptimization() {
        return autoApplyOptimization;
    }

    public void setAutoApplyOptimization(boolean autoApplyOptimization) {
        this.autoApplyOptimization = autoApplyOptimization;
    }

    public double getOptimizationTemperature() {
        return optimizationTemperature;
    }

    public void setOptimizationTemperature(double optimizationTemperature) {
        this.optimizationTemperature = optimizationTemperature;
    }

    public int getOptimizationMaxTokens() {
        return optimizationMaxTokens;
    }

    public void setOptimizationMaxTokens(int optimizationMaxTokens) {
        this.optimizationMaxTokens = optimizationMaxTokens;
    }

    public int getMaxHistoryVersions() {
        return maxHistoryVersions;
    }

    public void setMaxHistoryVersions(int maxHistoryVersions) {
        this.maxHistoryVersions = maxHistoryVersions;
    }

    public OptimizationStrategy getOptimizationStrategy() {
        return optimizationStrategy;
    }

    public void setOptimizationStrategy(OptimizationStrategy optimizationStrategy) {
        this.optimizationStrategy = optimizationStrategy != null
                ? optimizationStrategy : OptimizationStrategy.SELF_REFINE;
    }

    public int getSelfRefineSessionCount() {
        return selfRefineSessionCount;
    }

    public void setSelfRefineSessionCount(int selfRefineSessionCount) {
        this.selfRefineSessionCount = Math.max(1, selfRefineSessionCount);
    }

    // ==================== 便捷方法 ====================

    /**
     * 检查是否启用了任何进化功能。
     *
     * @return 任一功能启用时返回 true
     */
    public boolean isAnyEvolutionEnabled() {
        return feedbackEnabled || promptOptimizationEnabled;
    }

    /**
     * 检查是否可以执行优化。
     *
     * @return 配置允许优化时返回 true
     */
    public boolean canOptimize() {
        return promptOptimizationEnabled;
    }

    @Override
    public String toString() {
        return String.format("EvolutionConfig{feedback=%s, promptOpt=%s, strategy=%s, interval=%dh}",
                feedbackEnabled, promptOptimizationEnabled, optimizationStrategy, optimizationIntervalHours);
    }
}
