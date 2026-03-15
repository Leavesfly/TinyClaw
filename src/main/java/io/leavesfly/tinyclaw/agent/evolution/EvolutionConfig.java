package io.leavesfly.tinyclaw.agent.evolution;

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
 *     strategy: TEXT_GRADIENT
 *     feedbackRetentionDays: 30
 *     minFeedbacksForOptimization: 10
 * </pre>
 */
public class EvolutionConfig {

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

    // ==================== Prompt 优化配置 ====================

    /**
     * 是否启用 Prompt 优化
     */
    private boolean promptOptimizationEnabled = false;

    /**
     * 优化检查间隔（小时）
     */
    private int optimizationIntervalHours = 24;

    /**
     * 新 Prompt 采用的分数阈值（0.0 ~ 1.0）
     * 只有当优化后的评分超过此阈值时才会采用
     */
    private double adoptionThreshold = 0.7;

    /**
     * 触发优化所需的最小反馈数量
     */
    private int minFeedbacksForOptimization = 10;

    /**
     * 优化策略
     */
    private OptimizationStrategy strategy = OptimizationStrategy.TEXT_GRADIENT;

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
     * 是否保留优化历史
     */
    private boolean keepOptimizationHistory = true;

    /**
     * 最大保留的优化历史版本数
     */
    private int maxHistoryVersions = 10;

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

    public int getMinFeedbacksForOptimization() {
        return minFeedbacksForOptimization;
    }

    public void setMinFeedbacksForOptimization(int minFeedbacksForOptimization) {
        this.minFeedbacksForOptimization = minFeedbacksForOptimization;
    }

    public OptimizationStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(OptimizationStrategy strategy) {
        this.strategy = strategy;
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

    public boolean isKeepOptimizationHistory() {
        return keepOptimizationHistory;
    }

    public void setKeepOptimizationHistory(boolean keepOptimizationHistory) {
        this.keepOptimizationHistory = keepOptimizationHistory;
    }

    public int getMaxHistoryVersions() {
        return maxHistoryVersions;
    }

    public void setMaxHistoryVersions(int maxHistoryVersions) {
        this.maxHistoryVersions = maxHistoryVersions;
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
        return promptOptimizationEnabled && feedbackEnabled;
    }

    @Override
    public String toString() {
        return String.format("EvolutionConfig{feedback=%s, promptOpt=%s, strategy=%s, interval=%dh}",
                feedbackEnabled, promptOptimizationEnabled, strategy, optimizationIntervalHours);
    }
}
