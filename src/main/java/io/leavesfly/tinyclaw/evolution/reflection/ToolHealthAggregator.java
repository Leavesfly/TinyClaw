package io.leavesfly.tinyclaw.evolution.reflection;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具健康度聚合器（内存滑动窗口）。
 *
 * <p>在 {@link ToolCallRecorder#record(ToolCallEvent)} 的同步路径上被调用，
 * 维护每个工具最近 N 分钟的成功率、耗时分布、错误类型分布等关键指标。
 *
 * <p>实现思路：
 * <ul>
 *   <li>每工具一个环形的"分桶数组"（默认 60 个桶，每桶 1 分钟）；</li>
 *   <li>事件到达时根据时间戳定位到对应桶，原子更新计数；</li>
 *   <li>查询时合并窗口内所有桶，返回 {@link ToolHealthStat}；</li>
 *   <li>耗时采用粗粒度直方图估算 P50/P95（9 个桶：10/50/100/300/800/2000/5000/15000/+∞ ms）。</li>
 * </ul>
 *
 * <p>这种方式避免了锁和大对象 GC，亚毫秒响应，适合挂在热路径上。
 */
public class ToolHealthAggregator {

    private static final int BUCKET_COUNT = 60;
    private static final long BUCKET_SIZE_MS = 60_000L;  // 1 分钟
    /** 耗时直方图边界（毫秒） */
    private static final long[] LATENCY_BOUNDARIES = {10, 50, 100, 300, 800, 2000, 5000, 15000, Long.MAX_VALUE};

    /** toolName -> 滑动窗口状态 */
    private final Map<String, ToolWindow> windows = new ConcurrentHashMap<>();

    /**
     * 接收一个事件并更新聚合状态。
     */
    public void onEvent(ToolCallEvent event) {
        if (event == null || event.getToolName() == null) return;
        ToolWindow window = windows.computeIfAbsent(event.getToolName(), k -> new ToolWindow());
        window.record(event);
    }

    /**
     * 查询指定工具在最近 windowMinutes 分钟内的健康度。
     *
     * @param toolName      工具名
     * @param windowMinutes 窗口分钟数（1 ~ BUCKET_COUNT）
     * @return 健康度统计，无数据时 totalCalls=0
     */
    public ToolHealthStat query(String toolName, int windowMinutes) {
        windowMinutes = Math.max(1, Math.min(BUCKET_COUNT, windowMinutes));
        ToolWindow window = windows.get(toolName);
        if (window == null) {
            return ToolHealthStat.empty(toolName);
        }
        return window.snapshot(toolName, windowMinutes);
    }

    /**
     * 查询所有已观察到的工具的健康度。
     */
    public List<ToolHealthStat> queryAll(int windowMinutes) {
        List<ToolHealthStat> result = new ArrayList<>(windows.size());
        for (String name : windows.keySet()) {
            result.add(query(name, windowMinutes));
        }
        result.sort(Comparator.comparing(ToolHealthStat::getSuccessRate));
        return result;
    }

    // ==================== 内部类 ====================

    /**
     * 单个工具的滑动窗口状态。
     */
    private static final class ToolWindow {
        /** 桶数组，每个桶对应一分钟 */
        private final Bucket[] buckets = new Bucket[BUCKET_COUNT];

        ToolWindow() {
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = new Bucket();
            }
        }

        void record(ToolCallEvent event) {
            long tsMs = (event.getTimestamp() != null ? event.getTimestamp() : Instant.now()).toEpochMilli();
            int idx = (int) ((tsMs / BUCKET_SIZE_MS) % BUCKET_COUNT);
            Bucket b = buckets[idx];
            // 检测桶过期（被环形覆盖），若过期则重置
            b.maybeResetIfExpired(tsMs);
            b.record(event);
        }

        ToolHealthStat snapshot(String toolName, int windowMinutes) {
            long nowMs = System.currentTimeMillis();
            long windowStartMs = nowMs - windowMinutes * BUCKET_SIZE_MS;

            long total = 0, success = 0;
            long[] latencyHist = new long[LATENCY_BOUNDARIES.length];
            Map<String, Long> errorHist = new LinkedHashMap<>();

            for (Bucket b : buckets) {
                if (b.lastUpdateMs < windowStartMs) continue;
                total += b.total.get();
                success += b.success.get();
                for (int i = 0; i < latencyHist.length; i++) {
                    latencyHist[i] += b.latencyHist[i].get();
                }
                for (Map.Entry<String, AtomicLong> e : b.errorHist.entrySet()) {
                    errorHist.merge(e.getKey(), e.getValue().get(), Long::sum);
                }
            }

            double successRate = total == 0 ? 1.0 : (double) success / total;
            double p50 = estimatePercentile(latencyHist, 0.50);
            double p95 = estimatePercentile(latencyHist, 0.95);
            double p99 = estimatePercentile(latencyHist, 0.99);

            return new ToolHealthStat(toolName, total, success, successRate,
                    p50, p95, p99, errorHist,
                    Instant.ofEpochMilli(windowStartMs), Instant.ofEpochMilli(nowMs));
        }

        private static double estimatePercentile(long[] hist, double p) {
            long total = 0;
            for (long c : hist) total += c;
            if (total == 0) return 0;
            long target = (long) Math.ceil(total * p);
            long cum = 0;
            for (int i = 0; i < hist.length; i++) {
                cum += hist[i];
                if (cum >= target) {
                    return LATENCY_BOUNDARIES[i] == Long.MAX_VALUE
                            ? LATENCY_BOUNDARIES[i - 1] * 2.0
                            : LATENCY_BOUNDARIES[i];
                }
            }
            return 0;
        }
    }

    /**
     * 单个分钟级别的桶。
     */
    private static final class Bucket {
        final AtomicLong total = new AtomicLong(0);
        final AtomicLong success = new AtomicLong(0);
        final AtomicLong[] latencyHist = new AtomicLong[LATENCY_BOUNDARIES.length];
        final Map<String, AtomicLong> errorHist = new ConcurrentHashMap<>();
        volatile long lastUpdateMs = 0;

        Bucket() {
            for (int i = 0; i < latencyHist.length; i++) {
                latencyHist[i] = new AtomicLong(0);
            }
        }

        synchronized void maybeResetIfExpired(long tsMs) {
            // 若上次更新距离当前超过 BUCKET_COUNT 分钟，说明桶被环形覆盖，重置
            if (lastUpdateMs != 0 && tsMs - lastUpdateMs > BUCKET_COUNT * BUCKET_SIZE_MS) {
                total.set(0);
                success.set(0);
                for (AtomicLong a : latencyHist) a.set(0);
                errorHist.clear();
            }
            lastUpdateMs = tsMs;
        }

        void record(ToolCallEvent event) {
            total.incrementAndGet();
            if (event.isSuccess()) {
                success.incrementAndGet();
            } else {
                String key = event.getErrorType() != null
                        ? event.getErrorType().name() : "UNKNOWN_ERROR";
                errorHist.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            }
            // 耗时直方图
            long d = Math.max(0, event.getDurationMs());
            for (int i = 0; i < LATENCY_BOUNDARIES.length; i++) {
                if (d <= LATENCY_BOUNDARIES[i]) {
                    latencyHist[i].incrementAndGet();
                    break;
                }
            }
        }
    }
}
