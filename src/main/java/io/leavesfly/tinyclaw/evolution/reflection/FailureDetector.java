package io.leavesfly.tinyclaw.evolution.reflection;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 工具调用失败检测器。
 *
 * <p>基于 {@link ToolHealthAggregator} 的滑动窗口数据和实时事件流，检测三种失败模式：
 * <ul>
 *   <li><b>成功率下降型</b>：窗口内成功率低于阈值（如 70%），表明工具整体健康度恶化；</li>
 *   <li><b>突发型</b>：同一 errorType 在短窗口（10 分钟）内出现 N 次以上，表明突然爆发的新问题；</li>
 *   <li><b>长尾型</b>：相同参数指纹连续失败 N 次，表明特定参数模式持续不可用。</li>
 * </ul>
 *
 * <p>每种模式检测到时生成 {@link DetectionResult}，供 {@link ReflectionEngine} 消费并启动反思。
 *
 * <p>设计约束：
 * <ul>
 *   <li>不阻塞主调用路径——{@link #onEvent} 在 {@link ToolCallRecorder} 的聚合回调中同步调用，
 *       必须亚毫秒完成，只做内存计数和简单判定；</li>
 *   <li>检测结果通过轮询 {@link #drainDetections()} 获取，由外部定时器（如 {@code runEvolutionCycle}）驱动。</li>
 * </ul>
 */
public class FailureDetector {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("reflection.detector");

    /** 突发检测的短窗口（毫秒），默认 10 分钟。 */
    private static final long BURST_WINDOW_MS = 10 * 60 * 1000L;

    private final ReflectionConfig config;
    private final ToolHealthAggregator aggregator;

    /** 突发检测：(toolName + errorType) -> 事件时间戳队列 */
    private final Map<String, Deque<Long>> burstTracker = new ConcurrentHashMap<>();

    /** 长尾检测：(toolName + argsFingerprint) -> 连续失败计数 */
    private final Map<String, Integer> tailTracker = new ConcurrentHashMap<>();

    /** 成功率下降冷却追踪：toolName -> 上次检测时间戳 */
    private final Map<String, Long> successRateDropCooldown = new ConcurrentHashMap<>();

    /** 成功率下降检测冷却时间（毫秒），默认 5 分钟 */
    private static final long SUCCESS_RATE_DROP_COOLDOWN_MS = 5 * 60 * 1000L;

    /** 已检测到但尚未消费的结果 */
    private final ConcurrentLinkedDeque<DetectionResult> pendingDetections = new ConcurrentLinkedDeque<>();

    public FailureDetector(ReflectionConfig config, ToolHealthAggregator aggregator) {
        this.config = config;
        this.aggregator = aggregator;
    }

    /**
     * 接收一个事件并进行实时失败检测。
     *
     * <p>在 {@link ToolCallRecorder#record} 的同步路径上被调用（经由 {@link ToolHealthAggregator#onEvent}
     * 之后），因此必须保持极低延迟。
     */
    public void onEvent(ToolCallEvent event) {
        if (event == null || event.getToolName() == null) return;

        String toolName = event.getToolName();

        if (event.isSuccess()) {
            clearTailTracker(toolName, event.getArgsFingerprint());
            return;
        }

        // 失败事件处理
        checkBurstFailure(toolName, event);
        checkTailFailure(toolName, event);
        checkSuccessRateDrop(toolName);
    }

    /**
     * 消费所有已检测到的结果（非阻塞，清空队列）。
     *
     * @return 检测结果列表，无结果时返回空列表
     */
    public List<DetectionResult> drainDetections() {
        List<DetectionResult> results = new ArrayList<>();
        DetectionResult result;
        while ((result = pendingDetections.pollFirst()) != null) {
            results.add(result);
        }
        return results;
    }

    /**
     * 获取当前排队中的检测数量（监控用）。
     */
    public int pendingCount() {
        return pendingDetections.size();
    }

    // ==================== 三种检测逻辑 ====================

    /**
     * 突发型检测：同一 (tool, errorType) 在 BURST_WINDOW_MS 内出现 burstFailureCount 次。
     */
    private void checkBurstFailure(String toolName, ToolCallEvent event) {
        String errorKey = event.getErrorType() != null ? event.getErrorType().name() : "UNKNOWN";
        String key = toolName + "::" + errorKey;

        Deque<Long> timestamps = burstTracker.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        long nowMs = event.getTimestamp() != null ? event.getTimestamp().toEpochMilli() : System.currentTimeMillis();
        timestamps.addLast(nowMs);

        // 清除窗口外的旧时间戳
        long cutoff = nowMs - BURST_WINDOW_MS;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= config.getBurstFailureCount()) {
            int burstCount = timestamps.size();
            DetectionResult detection = new DetectionResult(
                    DetectionResult.Type.BURST,
                    toolName,
                    String.format("Error type [%s] burst: %d occurrences in %d minutes",
                            errorKey, burstCount, BURST_WINDOW_MS / 60000),
                    Map.of("errorType", errorKey, "count", burstCount));
            pendingDetections.addLast(detection);
            // 原子性清空：逐个 poll 而非 clear()，避免并发 addLast 丢失
            while (timestamps.pollFirst() != null) { /* drain */ }
            logger.warn("Burst failure detected", Map.of("tool", toolName, "errorType", errorKey,
                    "count", burstCount));
        }
    }

    /**
     * 长尾型检测：相同 (tool, argsFingerprint) 连续失败 tailFailureCount 次。
     */
    private void checkTailFailure(String toolName, ToolCallEvent event) {
        String fingerprint = event.getArgsFingerprint();
        if (fingerprint == null || fingerprint.isEmpty()) return;

        String key = toolName + "::" + fingerprint;
        int threshold = config.getTailFailureCount();

        // 原子性地 merge+检查+移除，避免竞态导致重复触发
        int[] countHolder = {0};
        tailTracker.compute(key, (k, existing) -> {
            int newCount = (existing == null ? 0 : existing) + 1;
            countHolder[0] = newCount;
            // 达到阈值时移除（返回 null 即删除 key），同时触发检测
            return newCount >= threshold ? null : newCount;
        });

        if (countHolder[0] >= threshold) {
            DetectionResult detection = new DetectionResult(
                    DetectionResult.Type.TAIL,
                    toolName,
                    String.format("Args pattern [%s] failed %d consecutive times",
                            truncate(fingerprint, 80), countHolder[0]),
                    Map.of("argsFingerprint", fingerprint, "consecutiveFailures", countHolder[0]));
            pendingDetections.addLast(detection);
            logger.warn("Tail failure detected", Map.of("tool", toolName,
                    "fingerprint", truncate(fingerprint, 40), "count", countHolder[0]));
        }
    }

    /**
     * 成功率下降检测：基于 ToolHealthAggregator 的窗口统计判定。
     *
     * <p>包含冷却机制：同一工具在 {@link #SUCCESS_RATE_DROP_COOLDOWN_MS} 内只触发一次。
     */
    private void checkSuccessRateDrop(String toolName) {
        // 冷却检查：避免同一工具短时间内重复触发
        Long lastDetectedMs = successRateDropCooldown.get(toolName);
        long nowMs = System.currentTimeMillis();
        if (lastDetectedMs != null && nowMs - lastDetectedMs < SUCCESS_RATE_DROP_COOLDOWN_MS) {
            return;
        }

        ToolHealthStat stat = aggregator.query(toolName, config.getDetectionWindowMinutes());
        if (stat.getTotalCalls() < config.getMinSampleCount()) {
            return; // 样本量不足，不做判定
        }
        if (stat.getSuccessRate() < config.getSuccessRateThreshold()) {
            DetectionResult detection = new DetectionResult(
                    DetectionResult.Type.SUCCESS_RATE_DROP,
                    toolName,
                    String.format("Success rate %.1f%% below threshold %.1f%% (%d calls in %d min window)",
                            stat.getSuccessRate() * 100, config.getSuccessRateThreshold() * 100,
                            stat.getTotalCalls(), config.getDetectionWindowMinutes()),
                    Map.of("successRate", stat.getSuccessRate(),
                            "threshold", config.getSuccessRateThreshold(),
                            "totalCalls", stat.getTotalCalls()));
            pendingDetections.addLast(detection);
            successRateDropCooldown.put(toolName, nowMs); // 标记冷却
            logger.warn("Success rate drop detected", Map.of("tool", toolName,
                    "successRate", String.format("%.2f", stat.getSuccessRate())));
        }
    }

    /**
     * 成功调用时清除对应的长尾追踪，并清理过期的突发追踪条目。
     */
    private void clearTailTracker(String toolName, String fingerprint) {
        if (fingerprint != null && !fingerprint.isEmpty()) {
            tailTracker.remove(toolName + "::" + fingerprint);
        }
        // 顺带清理过期的 burstTracker 条目（避免内存无限增长）
        cleanupStaleBurstEntries();
    }

    /**
     * 清理 burstTracker 中所有时间戳已全部过期的条目。
     * 在成功事件路径上被调用，频率适中，不会过度开销。
     */
    private void cleanupStaleBurstEntries() {
        long cutoff = System.currentTimeMillis() - BURST_WINDOW_MS;
        burstTracker.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            return timestamps.isEmpty();
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 检测结果 ====================

    /**
     * 一次失败检测的结果。
     */
    public static final class DetectionResult {

        public enum Type {
            /** 窗口内成功率低于阈值 */
            SUCCESS_RATE_DROP,
            /** 同一错误类型在短窗口内突发 */
            BURST,
            /** 相同参数指纹连续失败 */
            TAIL
        }

        private final Type type;
        private final String toolName;
        private final String description;
        private final Map<String, Object> evidence;
        private final Instant detectedAt;

        public DetectionResult(Type type, String toolName, String description, Map<String, Object> evidence) {
            this.type = type;
            this.toolName = toolName;
            this.description = description;
            this.evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
            this.detectedAt = Instant.now();
        }

        public Type getType() { return type; }
        public String getToolName() { return toolName; }
        public String getDescription() { return description; }
        public Map<String, Object> getEvidence() { return evidence; }
        public Instant getDetectedAt() { return detectedAt; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type.name());
            map.put("tool", toolName);
            map.put("description", description);
            map.put("evidence", evidence);
            map.put("detectedAt", detectedAt.toString());
            return Collections.unmodifiableMap(map);
        }
    }
}
