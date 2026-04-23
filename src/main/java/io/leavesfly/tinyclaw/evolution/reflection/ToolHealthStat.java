package io.leavesfly.tinyclaw.evolution.reflection;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具健康度统计快照。
 *
 * <p>由 {@link ToolHealthAggregator} 按请求生成，描述一个工具在指定时间窗口内
 * 的表现，包含核心指标：
 * <ul>
 *   <li>调用数 / 成功数 / 成功率；</li>
 *   <li>耗时 P50 / P95 / P99（直方图估算）；</li>
 *   <li>错误类型直方图（{@link ToolCallEvent.ErrorType#name()} → count）；</li>
 *   <li>窗口起止时间。</li>
 * </ul>
 *
 * <p>不可变：所有字段 final，创建后不可修改，便于在多个消费者之间安全共享。
 */
public final class ToolHealthStat {

    private final String toolName;
    private final long totalCalls;
    private final long successCalls;
    private final double successRate;
    private final double p50Ms;
    private final double p95Ms;
    private final double p99Ms;
    private final Map<String, Long> errorTypeHistogram;
    private final Instant windowStart;
    private final Instant windowEnd;

    public ToolHealthStat(String toolName, long totalCalls, long successCalls, double successRate,
                          double p50Ms, double p95Ms, double p99Ms,
                          Map<String, Long> errorTypeHistogram,
                          Instant windowStart, Instant windowEnd) {
        this.toolName = toolName;
        this.totalCalls = totalCalls;
        this.successCalls = successCalls;
        this.successRate = successRate;
        this.p50Ms = p50Ms;
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.errorTypeHistogram = errorTypeHistogram != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(errorTypeHistogram))
                : Collections.emptyMap();
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    /** 返回一个空快照（用于从未观察到调用的工具）。 */
    public static ToolHealthStat empty(String toolName) {
        Instant now = Instant.now();
        return new ToolHealthStat(toolName, 0, 0, 1.0, 0, 0, 0,
                Collections.emptyMap(), now, now);
    }

    /** 失败调用数。 */
    public long getFailureCalls() {
        return totalCalls - successCalls;
    }

    /** 转换为 Web API 友好的 Map 结构。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tool", toolName);
        map.put("totalCalls", totalCalls);
        map.put("successCalls", successCalls);
        map.put("failureCalls", getFailureCalls());
        map.put("successRate", round(successRate, 4));
        map.put("p50Ms", round(p50Ms, 1));
        map.put("p95Ms", round(p95Ms, 1));
        map.put("p99Ms", round(p99Ms, 1));
        map.put("errorTypeHistogram", errorTypeHistogram);
        map.put("windowStart", windowStart != null ? windowStart.toString() : null);
        map.put("windowEnd", windowEnd != null ? windowEnd.toString() : null);
        return map;
    }

    private static double round(double v, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }

    // ==================== Getter ====================

    public String getToolName() { return toolName; }
    public long getTotalCalls() { return totalCalls; }
    public long getSuccessCalls() { return successCalls; }
    public double getSuccessRate() { return successRate; }
    public double getP50Ms() { return p50Ms; }
    public double getP95Ms() { return p95Ms; }
    public double getP99Ms() { return p99Ms; }
    public Map<String, Long> getErrorTypeHistogram() { return errorTypeHistogram; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
}
