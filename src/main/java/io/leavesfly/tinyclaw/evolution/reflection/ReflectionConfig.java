package io.leavesfly.tinyclaw.evolution.reflection;

/**
 * Reflection 2.0（工具级自我调试）配置。
 *
 * <p>控制以下 4 类行为：
 * <ul>
 *   <li>事件采集：是否开启 / 事件日志保留天数；</li>
 *   <li>失败检测：滑动窗口、成功率阈值、突发阈值；</li>
 *   <li>反思引擎：LLM 反思周期、最小样本量、是否使用独立模型；</li>
 *   <li>自动应用：是否自动通过低影响提案、是否启用 A/B。</li>
 * </ul>
 *
 * <p>所有开关默认关闭，避免增加默认启动负担；一旦在 {@code config.json} 中显式
 * 打开 {@code enabled=true}，{@link ToolCallRecorder} 等组件才会被装配。
 */
public class ReflectionConfig {

    // ==================== 事件采集 ====================

    /** 是否启用 Reflection 2.0 整体功能。 */
    private boolean enabled = false;

    /** 事件日志保留天数（按天清理旧 JSONL 文件）。 */
    private int eventRetentionDays = 14;

    /** 是否在事件中采集参数原文。关闭后仅保留指纹，更隐私友好。 */
    private boolean captureRawArgs = true;

    // ==================== 失败检测 ====================

    /** 滑动窗口分钟数（用于成功率阈值判定）。 */
    private int detectionWindowMinutes = 60;

    /** 窗口内成功率低于此值触发反思。 */
    private double successRateThreshold = 0.7;

    /** 窗口内最小样本量，低于此值不触发反思（避免小样本误报）。 */
    private int minSampleCount = 20;

    /** 同一错误类型在 10 分钟内出现此次数后视为"突发型"失败。 */
    private int burstFailureCount = 5;

    /** 相同参数指纹连续失败此次数后视为"长尾型"失败。 */
    private int tailFailureCount = 3;

    // ==================== 反思引擎 ====================

    /** 两次反思之间的最小间隔（小时），防止高频打扰 LLM。 */
    private int reflectionIntervalHours = 6;

    /** 每次反思给 LLM 的失败样本数上限。 */
    private int maxSamplesPerReflection = 20;

    /** 反思时使用的 LLM 温度，建议较低以产生稳定诊断。 */
    private double reflectionTemperature = 0.2;

    /** 反思 LLM 的最大输出 token。 */
    private int reflectionMaxTokens = 2048;

    /** 若非空，反思使用该模型；否则沿用 Agent 主模型。 */
    private String reflectionModel = "";

    // ==================== 自动应用 ====================

    /** 是否自动通过低影响提案（影响度 < autoApproveImpactBelow 的 DESCRIPTION_REWRITE）。 */
    private boolean autoApproveLowImpact = false;

    /** 自动通过的影响度上限，0~1。 */
    private double autoApproveImpactBelow = 0.3;

    /** 是否启用 A/B 对照评估新变体。 */
    private boolean abTestEnabled = false;

    /** A/B 评估中新变体流量比例，0~1。 */
    private double abTestTrafficRatio = 0.2;

    /** A/B 观察期（小时），期满后根据成功率决定 promote 或 rollback。 */
    private int abObservationHours = 336;

    // ==================== Getter / Setter ====================

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getEventRetentionDays() { return eventRetentionDays; }
    public void setEventRetentionDays(int eventRetentionDays) {
        this.eventRetentionDays = Math.max(1, eventRetentionDays);
    }

    public boolean isCaptureRawArgs() { return captureRawArgs; }
    public void setCaptureRawArgs(boolean captureRawArgs) { this.captureRawArgs = captureRawArgs; }

    public int getDetectionWindowMinutes() { return detectionWindowMinutes; }
    public void setDetectionWindowMinutes(int detectionWindowMinutes) {
        this.detectionWindowMinutes = Math.max(5, Math.min(60, detectionWindowMinutes));
    }

    public double getSuccessRateThreshold() { return successRateThreshold; }
    public void setSuccessRateThreshold(double successRateThreshold) {
        this.successRateThreshold = Math.max(0.0, Math.min(1.0, successRateThreshold));
    }

    public int getMinSampleCount() { return minSampleCount; }
    public void setMinSampleCount(int minSampleCount) {
        this.minSampleCount = Math.max(1, minSampleCount);
    }

    public int getBurstFailureCount() { return burstFailureCount; }
    public void setBurstFailureCount(int burstFailureCount) {
        this.burstFailureCount = Math.max(1, burstFailureCount);
    }

    public int getTailFailureCount() { return tailFailureCount; }
    public void setTailFailureCount(int tailFailureCount) {
        this.tailFailureCount = Math.max(1, tailFailureCount);
    }

    public int getReflectionIntervalHours() { return reflectionIntervalHours; }
    public void setReflectionIntervalHours(int reflectionIntervalHours) {
        this.reflectionIntervalHours = Math.max(1, reflectionIntervalHours);
    }

    public int getMaxSamplesPerReflection() { return maxSamplesPerReflection; }
    public void setMaxSamplesPerReflection(int maxSamplesPerReflection) {
        this.maxSamplesPerReflection = Math.max(1, maxSamplesPerReflection);
    }

    public double getReflectionTemperature() { return reflectionTemperature; }
    public void setReflectionTemperature(double reflectionTemperature) {
        this.reflectionTemperature = Math.max(0.0, Math.min(2.0, reflectionTemperature));
    }

    public int getReflectionMaxTokens() { return reflectionMaxTokens; }
    public void setReflectionMaxTokens(int reflectionMaxTokens) {
        this.reflectionMaxTokens = Math.max(256, reflectionMaxTokens);
    }

    public String getReflectionModel() { return reflectionModel; }
    public void setReflectionModel(String reflectionModel) {
        this.reflectionModel = reflectionModel == null ? "" : reflectionModel;
    }

    public boolean isAutoApproveLowImpact() { return autoApproveLowImpact; }
    public void setAutoApproveLowImpact(boolean autoApproveLowImpact) {
        this.autoApproveLowImpact = autoApproveLowImpact;
    }

    public double getAutoApproveImpactBelow() { return autoApproveImpactBelow; }
    public void setAutoApproveImpactBelow(double autoApproveImpactBelow) {
        this.autoApproveImpactBelow = Math.max(0.0, Math.min(1.0, autoApproveImpactBelow));
    }

    public boolean isAbTestEnabled() { return abTestEnabled; }
    public void setAbTestEnabled(boolean abTestEnabled) { this.abTestEnabled = abTestEnabled; }

    public double getAbTestTrafficRatio() { return abTestTrafficRatio; }
    public void setAbTestTrafficRatio(double abTestTrafficRatio) {
        this.abTestTrafficRatio = Math.max(0.0, Math.min(1.0, abTestTrafficRatio));
    }

    public int getAbObservationHours() { return abObservationHours; }
    public void setAbObservationHours(int abObservationHours) {
        this.abObservationHours = Math.max(1, abObservationHours);
    }
}
