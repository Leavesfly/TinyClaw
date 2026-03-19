package io.leavesfly.tinyclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Token 消耗数据存储，负责将每次 LLM 调用的 token 使用情况持久化到 JSON 文件。
 *
 * <p>数据按月分片存储，文件路径格式为：{workspace}/token-usage/YYYY-MM.json</p>
 * <p>线程安全，支持并发读写。</p>
 */
public class TokenUsageStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent.token");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SUBDIR = "token-usage";

    private final Path storageDir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public TokenUsageStore(String workspace) {
        this.storageDir = Paths.get(workspace, SUBDIR);
        ensureDirectoryExists();
    }

    /**
     * 记录一次 LLM 调用的 token 消耗。
     *
     * @param provider        提供商名称
     * @param model           模型名称
     * @param promptTokens    输入 token 数
     * @param completionTokens 输出 token 数
     */
    public void record(String provider, String model, int promptTokens, int completionTokens) {
        if (promptTokens <= 0 && completionTokens <= 0) {
            return;
        }

        long timestamp = Instant.now().toEpochMilli();
        String month = LocalDate.now().format(MONTH_FORMATTER);
        Path filePath = storageDir.resolve(month + ".json");

        lock.writeLock().lock();
        try {
            ArrayNode records = loadRecords(filePath);
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("timestamp", timestamp);
            entry.put("provider", provider != null ? provider : "unknown");
            entry.put("model", model != null ? model : "unknown");
            entry.put("promptTokens", promptTokens);
            entry.put("completionTokens", completionTokens);
            entry.put("totalTokens", promptTokens + completionTokens);
            records.add(entry);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), records);
        } catch (IOException e) {
            logger.error("Failed to record token usage", Map.of("error", e.getMessage()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 查询指定日期范围内的 token 消耗统计。
     *
     * @param startDate 开始日期（含），格式 yyyy-MM-dd
     * @param endDate   结束日期（含），格式 yyyy-MM-dd
     * @return 统计结果，包含总量、按模型分组、按日期分组
     */
    public TokenStats query(String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);

        long startMs = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // 收集需要读取的月份文件
        Set<String> months = new LinkedHashSet<>();
        LocalDate cursor = start.withDayOfMonth(1);
        while (!cursor.isAfter(end)) {
            months.add(cursor.format(MONTH_FORMATTER));
            cursor = cursor.plusMonths(1);
        }

        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        long totalCalls = 0;

        // key: "provider::model"
        Map<String, long[]> byModel = new LinkedHashMap<>();
        // key: "yyyy-MM-dd"
        Map<String, long[]> byDate = new LinkedHashMap<>();

        lock.readLock().lock();
        try {
            for (String month : months) {
                Path filePath = storageDir.resolve(month + ".json");
                if (!Files.exists(filePath)) {
                    continue;
                }
                ArrayNode records = loadRecords(filePath);
                for (var node : records) {
                    long ts = node.path("timestamp").asLong();
                    if (ts < startMs || ts >= endMs) {
                        continue;
                    }

                    int promptTokens = node.path("promptTokens").asInt();
                    int completionTokens = node.path("completionTokens").asInt();
                    String provider = node.path("provider").asText("unknown");
                    String model = node.path("model").asText("unknown");

                    totalPromptTokens += promptTokens;
                    totalCompletionTokens += completionTokens;
                    totalCalls++;

                    // 按模型聚合：[promptTokens, completionTokens, callCount]
                    String modelKey = provider + "::" + model;
                    byModel.computeIfAbsent(modelKey, k -> new long[3]);
                    byModel.get(modelKey)[0] += promptTokens;
                    byModel.get(modelKey)[1] += completionTokens;
                    byModel.get(modelKey)[2]++;

                    // 按日期聚合
                    String dateKey = LocalDate.ofInstant(
                            Instant.ofEpochMilli(ts), ZoneId.systemDefault()
                    ).format(DATE_FORMATTER);
                    byDate.computeIfAbsent(dateKey, k -> new long[3]);
                    byDate.get(dateKey)[0] += promptTokens;
                    byDate.get(dateKey)[1] += completionTokens;
                    byDate.get(dateKey)[2]++;
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        return new TokenStats(totalPromptTokens, totalCompletionTokens, totalCalls, byModel, byDate);
    }

    // ==================== 内部工具方法 ====================

    private ArrayNode loadRecords(Path filePath) {
        if (!Files.exists(filePath)) {
            return MAPPER.createArrayNode();
        }
        try {
            return (ArrayNode) MAPPER.readTree(filePath.toFile());
        } catch (IOException e) {
            logger.warn("Failed to load token usage file, starting fresh", Map.of(
                    "file", filePath.toString(), "error", e.getMessage()));
            return MAPPER.createArrayNode();
        }
    }

    private void ensureDirectoryExists() {
        File dir = storageDir.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create token usage directory", Map.of("path", storageDir.toString()));
        }
    }

    // ==================== 统计结果数据类 ====================

    /**
     * Token 消耗统计结果。
     */
    public static class TokenStats {
        public final long totalPromptTokens;
        public final long totalCompletionTokens;
        public final long totalCalls;
        /** key: "provider::model", value: [promptTokens, completionTokens, callCount] */
        public final Map<String, long[]> byModel;
        /** key: "yyyy-MM-dd", value: [promptTokens, completionTokens, callCount] */
        public final Map<String, long[]> byDate;

        public TokenStats(long totalPromptTokens, long totalCompletionTokens, long totalCalls,
                          Map<String, long[]> byModel, Map<String, long[]> byDate) {
            this.totalPromptTokens = totalPromptTokens;
            this.totalCompletionTokens = totalCompletionTokens;
            this.totalCalls = totalCalls;
            this.byModel = byModel;
            this.byDate = byDate;
        }
    }
}
