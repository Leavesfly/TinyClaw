package io.leavesfly.tinyclaw.evolution.reflection;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Objects;

/**
 * 失败特征聚类器。
 *
 * <p>对 {@link ToolCallLogStore} 中的失败事件进行多维度聚类分析，挖掘系统性的失败模式，
 * 为 {@link ReflectionEngine} 提供结构化的"证据包"。
 *
 * <p>三个聚类维度：
 * <ul>
 *   <li><b>errorType</b>：同一错误类型的事件归为一簇，识别"工具整体就是坏的"场景；</li>
 *   <li><b>argsFingerprint</b>：相同参数结构归为一簇，识别"特定参数模式一直失败"场景；</li>
 *   <li><b>stackHash</b>：相同异常栈归为一簇，识别"同一个 bug 反复触发"场景。</li>
 * </ul>
 *
 * <p>输出 {@link FailureCluster} 列表，按出现次数降序排列，每个 cluster 包含代表性样本
 * （用于喂给 LLM 做根因分析）和聚合统计。
 */
public class PatternMiner {

    /** 每个 cluster 中保留的代表性样本最大数量。 */
    private static final int MAX_REPRESENTATIVE_SAMPLES = 5;

    /** 单工具查询的最大失败事件数。 */
    private static final int MAX_EVENTS_PER_TOOL = 500;

    /** 全局查询的最大失败事件数。 */
    private static final int MAX_EVENTS_GLOBAL = 2000;

    private final ToolCallLogStore logStore;

    public PatternMiner(ToolCallLogStore logStore) {
        this.logStore = Objects.requireNonNull(logStore, "logStore must not be null");
    }

    /**
     * 对指定工具在指定时间范围内的失败事件进行聚类分析。
     *
     * @param toolName      工具名
     * @param fromInclusive 起始时间
     * @param toExclusive   结束时间
     * @param maxClusters   返回的最大聚类数
     * @return 聚类结果列表（按事件数降序）
     */
    public List<FailureCluster> mine(String toolName, Instant fromInclusive,
                                     Instant toExclusive, int maxClusters) {
        List<ToolCallEvent> failures = logStore.query(fromInclusive, toExclusive, toolName, true, MAX_EVENTS_PER_TOOL);
        if (failures.isEmpty()) {
            return Collections.emptyList();
        }

        // 多维度聚类并合并
        Map<String, FailureCluster> clusters = new LinkedHashMap<>();
        clusterByErrorType(failures, clusters);
        clusterByArgsFingerprint(failures, clusters);
        clusterByStackHash(failures, clusters);

        return clusters.values().stream()
                .sorted(Comparator.comparingInt(FailureCluster::getCount).reversed())
                .limit(maxClusters)
                .collect(Collectors.toList());
    }

    /**
     * 对所有工具进行聚类分析（用于全局健康报告）。
     */
    public Map<String, List<FailureCluster>> mineAll(Instant fromInclusive,
                                                      Instant toExclusive, int maxClustersPerTool) {
        List<ToolCallEvent> allFailures = logStore.query(fromInclusive, toExclusive, null, true, MAX_EVENTS_GLOBAL);
        if (allFailures.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<ToolCallEvent>> byTool = allFailures.stream()
                .filter(e -> e.getToolName() != null)
                .collect(Collectors.groupingBy(ToolCallEvent::getToolName));

        Map<String, List<FailureCluster>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ToolCallEvent>> entry : byTool.entrySet()) {
            Map<String, FailureCluster> clusters = new LinkedHashMap<>();
            clusterByErrorType(entry.getValue(), clusters);
            clusterByArgsFingerprint(entry.getValue(), clusters);
            clusterByStackHash(entry.getValue(), clusters);

            List<FailureCluster> sorted = clusters.values().stream()
                    .sorted(Comparator.comparingInt(FailureCluster::getCount).reversed())
                    .limit(maxClustersPerTool)
                    .collect(Collectors.toList());
            if (!sorted.isEmpty()) {
                result.put(entry.getKey(), sorted);
            }
        }
        return result;
    }

    // ==================== 聚类逻辑 ====================

    private void clusterByErrorType(List<ToolCallEvent> events, Map<String, FailureCluster> clusters) {
        Map<String, List<ToolCallEvent>> groups = events.stream()
                .filter(e -> e.getErrorType() != null && e.getErrorType() != ToolCallEvent.ErrorType.NONE)
                .collect(Collectors.groupingBy(e -> "errorType:" + e.getErrorType().name()));

        for (Map.Entry<String, List<ToolCallEvent>> entry : groups.entrySet()) {
            clusters.merge(entry.getKey(),
                    buildCluster(FailureCluster.Dimension.ERROR_TYPE, entry.getKey(), entry.getValue()),
                    FailureCluster::merge);
        }
    }

    private void clusterByArgsFingerprint(List<ToolCallEvent> events, Map<String, FailureCluster> clusters) {
        Map<String, List<ToolCallEvent>> groups = events.stream()
                .filter(e -> e.getArgsFingerprint() != null && !e.getArgsFingerprint().isEmpty())
                .collect(Collectors.groupingBy(e -> "args:" + e.getArgsFingerprint()));

        for (Map.Entry<String, List<ToolCallEvent>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) continue; // 忽略单次失败
            clusters.merge(entry.getKey(),
                    buildCluster(FailureCluster.Dimension.ARGS_FINGERPRINT, entry.getKey(), entry.getValue()),
                    FailureCluster::merge);
        }
    }

    private void clusterByStackHash(List<ToolCallEvent> events, Map<String, FailureCluster> clusters) {
        Map<String, List<ToolCallEvent>> groups = events.stream()
                .filter(e -> e.getStackHash() != null && !e.getStackHash().isEmpty())
                .collect(Collectors.groupingBy(e -> "stack:" + e.getStackHash()));

        for (Map.Entry<String, List<ToolCallEvent>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            clusters.merge(entry.getKey(),
                    buildCluster(FailureCluster.Dimension.STACK_HASH, entry.getKey(), entry.getValue()),
                    FailureCluster::merge);
        }
    }

    private FailureCluster buildCluster(FailureCluster.Dimension dimension, String key,
                                        List<ToolCallEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must not be null or empty");
        }

        // 取最近的 N 个事件作为代表性样本
        List<ToolCallEvent> samples = events.stream()
                .sorted(Comparator.comparing(ToolCallEvent::getTimestamp).reversed())
                .limit(MAX_REPRESENTATIVE_SAMPLES)
                .collect(Collectors.toList());

        String toolName = events.get(0).getToolName();
        return new FailureCluster(dimension, key, toolName, events.size(), samples);
    }

    // ==================== 聚类结果 ====================

    /**
     * 一个失败事件聚类。
     */
    public static final class FailureCluster {

        public enum Dimension {
            ERROR_TYPE,
            ARGS_FINGERPRINT,
            STACK_HASH
        }

        private final Dimension dimension;
        private final String clusterKey;
        private final String toolName;
        private int count;
        private final List<ToolCallEvent> representativeSamples;

        public FailureCluster(Dimension dimension, String clusterKey, String toolName,
                              int count, List<ToolCallEvent> representativeSamples) {
            this.dimension = dimension;
            this.clusterKey = clusterKey;
            this.toolName = toolName;
            this.count = count;
            this.representativeSamples = new ArrayList<>(representativeSamples);
        }

        /** 合并同 key 的聚类。 */
        public FailureCluster merge(FailureCluster other) {
            this.count += other.count;
            for (ToolCallEvent sample : other.representativeSamples) {
                if (this.representativeSamples.size() < MAX_REPRESENTATIVE_SAMPLES) {
                    this.representativeSamples.add(sample);
                }
            }
            return this;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dimension", dimension.name());
            map.put("clusterKey", clusterKey);
            map.put("tool", toolName);
            map.put("count", count);
            map.put("sampleCount", representativeSamples.size());

            List<Map<String, Object>> sampleMaps = new ArrayList<>();
            for (ToolCallEvent event : representativeSamples) {
                Map<String, Object> sampleMap = new LinkedHashMap<>();
                sampleMap.put("eventId", event.getEventId());
                sampleMap.put("errorType", event.getErrorType() != null ? event.getErrorType().name() : null);
                sampleMap.put("errorMessage", event.getErrorMessage());
                sampleMap.put("argsFingerprint", event.getArgsFingerprint());
                sampleMap.put("stackHash", event.getStackHash());
                sampleMap.put("durationMs", event.getDurationMs());
                sampleMap.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : null);
                if (event.getRawArgs() != null && !event.getRawArgs().isEmpty()) {
                    sampleMap.put("rawArgs", event.getRawArgs());
                }
                sampleMaps.add(sampleMap);
            }
            map.put("samples", sampleMaps);
            return Collections.unmodifiableMap(map);
        }

        /** 将代表性样本格式化为 LLM 可读的文本摘要。 */
        public String toReflectionSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("## Cluster: %s (dimension=%s, count=%d)\n",
                    clusterKey, dimension.name(), count));
            sb.append(String.format("Tool: %s\n\n", toolName));

            for (int i = 0; i < representativeSamples.size(); i++) {
                ToolCallEvent sample = representativeSamples.get(i);
                sb.append(String.format("### Sample %d\n", i + 1));
                sb.append(String.format("- ErrorType: %s\n", sample.getErrorType()));
                sb.append(String.format("- ErrorMessage: %s\n",
                        sample.getErrorMessage() != null ? sample.getErrorMessage() : "N/A"));
                sb.append(String.format("- ArgsFingerprint: %s\n",
                        sample.getArgsFingerprint() != null ? sample.getArgsFingerprint() : "N/A"));
                if (sample.getRawArgs() != null && !sample.getRawArgs().isEmpty()) {
                    sb.append(String.format("- RawArgs: %s\n", sample.getRawArgs()));
                }
                if (sample.getUserMessageSnippet() != null) {
                    sb.append(String.format("- UserMessage: %s\n", sample.getUserMessageSnippet()));
                }
                sb.append(String.format("- Duration: %dms\n", sample.getDurationMs()));
                sb.append("\n");
            }
            return sb.toString();
        }

        public Dimension getDimension() { return dimension; }
        public String getClusterKey() { return clusterKey; }
        public String getToolName() { return toolName; }
        public int getCount() { return count; }
        public List<ToolCallEvent> getRepresentativeSamples() { return representativeSamples; }
    }
}
