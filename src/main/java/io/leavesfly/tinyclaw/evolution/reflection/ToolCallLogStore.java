package io.leavesfly.tinyclaw.evolution.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工具调用事件日志存储（JSON Lines 格式）。
 *
 * <p>存储路径：{@code {workspace}/evolution/reflection/events/YYYY-MM-DD.jsonl}
 *
 * <p>选择 JSONL 而非单一大 JSON 数组的原因：
 * <ul>
 *   <li>追加写入 O(1)，避免每次重写整个文件；</li>
 *   <li>按天分片，便于按时间窗口扫描和归档；</li>
 *   <li>行级解析，即使单条损坏也不影响其他记录。</li>
 * </ul>
 *
 * <p>线程安全：使用读写锁保护文件 I/O。写入路径会被
 * {@link ToolCallRecorder} 的单线程 writer 串行调用，锁只为防止 query
 * 与 write 冲突。
 */
public class ToolCallLogStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("reflection.log");
    private static final String SUBDIR = "evolution/reflection/events";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path storageDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ToolCallLogStore(String workspace) {
        this.storageDir = Paths.get(workspace, SUBDIR);
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ensureDirectoryExists();
    }

    /**
     * 追加一条事件记录。
     *
     * @param event 事件
     */
    public void append(ToolCallEvent event) {
        if (event == null) return;
        Path file = filePathFor(event.getTimestamp() != null ? event.getTimestamp() : Instant.now());
        lock.writeLock().lock();
        try {
            String line = mapper.writeValueAsString(event);
            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
            }
        } catch (IOException e) {
            logger.error("Failed to append tool call event", Map.of("error", e.getMessage()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量追加。
     */
    public void appendBatch(List<ToolCallEvent> events) {
        if (events == null || events.isEmpty()) return;
        // 按日期分组后写入，减少打开文件次数
        Map<Path, List<ToolCallEvent>> byFile = new LinkedHashMap<>();
        for (ToolCallEvent ev : events) {
            Path f = filePathFor(ev.getTimestamp() != null ? ev.getTimestamp() : Instant.now());
            byFile.computeIfAbsent(f, k -> new ArrayList<>()).add(ev);
        }
        lock.writeLock().lock();
        try {
            for (Map.Entry<Path, List<ToolCallEvent>> entry : byFile.entrySet()) {
                try (BufferedWriter w = Files.newBufferedWriter(
                        entry.getKey(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (ToolCallEvent ev : entry.getValue()) {
                        w.write(mapper.writeValueAsString(ev));
                        w.newLine();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to batch append events", Map.of("error", e.getMessage()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 查询指定时间范围内的事件。
     *
     * @param fromInclusive 起始时间（含）
     * @param toExclusive   结束时间（不含）
     * @param toolName      可选，指定工具名过滤，null 表示不过滤
     * @param onlyFailure   仅返回失败事件
     * @param limit         最大返回条数，<=0 表示不限
     * @return 事件列表（按时间升序）
     */
    public List<ToolCallEvent> query(Instant fromInclusive, Instant toExclusive,
                                     String toolName, boolean onlyFailure, int limit) {
        List<ToolCallEvent> result = new ArrayList<>();
        LocalDate fromDate = LocalDate.ofInstant(fromInclusive, ZoneId.systemDefault());
        LocalDate toDate = LocalDate.ofInstant(toExclusive, ZoneId.systemDefault());

        lock.readLock().lock();
        try {
            LocalDate cursor = fromDate;
            while (!cursor.isAfter(toDate)) {
                Path file = storageDir.resolve(cursor.format(DATE_FORMATTER) + ".jsonl");
                if (Files.exists(file)) {
                    readFile(file, fromInclusive, toExclusive, toolName, onlyFailure, limit, result);
                    if (limit > 0 && result.size() >= limit) {
                        break;
                    }
                }
                cursor = cursor.plusDays(1);
            }
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    /**
     * 删除早于指定时间的事件文件（按天粒度）。
     *
     * @param retentionDays 保留天数
     * @return 删除的文件数
     */
    public int cleanup(int retentionDays) {
        if (retentionDays <= 0) return 0;
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int deleted = 0;
        lock.writeLock().lock();
        try {
            if (!Files.exists(storageDir)) return 0;
            try (var stream = Files.list(storageDir)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".jsonl")) continue;
                    try {
                        LocalDate d = LocalDate.parse(name.substring(0, name.length() - 6), DATE_FORMATTER);
                        if (d.isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                            deleted++;
                        }
                    } catch (Exception ignore) {
                        // 文件名不合法，跳过
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to cleanup event logs: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        return deleted;
    }

    // ==================== 内部方法 ====================

    private void readFile(Path file, Instant from, Instant to, String toolName,
                          boolean onlyFailure, int limit, List<ToolCallEvent> result) {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    ToolCallEvent ev = mapper.readValue(line, ToolCallEvent.class);
                    if (ev.getTimestamp() == null) continue;
                    if (ev.getTimestamp().isBefore(from) || !ev.getTimestamp().isBefore(to)) continue;
                    if (toolName != null && !toolName.equals(ev.getToolName())) continue;
                    if (onlyFailure && ev.isSuccess()) continue;
                    result.add(ev);
                    if (limit > 0 && result.size() >= limit) return;
                } catch (IOException parseErr) {
                    // 单行解析错误不影响整体
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read event file: " + file + " : " + e.getMessage());
        }
    }

    private Path filePathFor(Instant instant) {
        LocalDate date = LocalDate.ofInstant(instant, ZoneId.systemDefault());
        return storageDir.resolve(date.format(DATE_FORMATTER) + ".jsonl");
    }

    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            logger.warn("Failed to create reflection event dir: " + e.getMessage());
        }
    }

    /** 暴露给上层获取根目录（供 Handler 展示或调试）。 */
    public Path getStorageDir() {
        return storageDir;
    }
}
