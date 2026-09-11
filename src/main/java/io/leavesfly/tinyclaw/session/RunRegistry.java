package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Web 根执行的登记处（P1：长任务与确认恢复）。
 *
 * <p>在现有 ReAct 执行链路之外提供轻量登记，不更换引擎：</p>
 * <ul>
 *   <li><b>幂等</b>：同一 clientRequestId 重复提交返回既有执行信息，不再运行工具；
 *       双标签 / 断线重发不会产生重复副作用。</li>
 *   <li><b>会话并发约束</b>：每会话同时最多一个活动 Web 根执行，超出返回忙碌信号，
 *       由调用方（ChatHandler）拒绝而非排队。</li>
 *   <li><b>持久化</b>：每次状态变化原子写 {@code <runsDir>/<runId>.json}；
 *       纯内存模式（runsDir 为 null）仅进程内可见，用于测试。</li>
 *   <li><b>重启恢复</b>：构造时扫描目录，遗留非终态记录标记 INTERRUPTED——
 *       页面刷新恢复不等于 JVM 重启续跑，重启后不自动重试。</li>
 * </ul>
 *
 * <p>线程模型：状态变更方法对单条记录同步执行（内存 map + 落盘），无长锁；
 * 不同 run 之间互不阻塞。终态记录保留最近 {@value #MAX_RETAINED_TERMINAL} 条，
 * 超出按 updatedAt 淘汰（登记摘要，非用户数据，可直接清理）。</p>
 */
public class RunRegistry {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /** 保留的终态记录条数上限。 */
    private static final int MAX_RETAINED_TERMINAL = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** runId → 记录。 */
    private final ConcurrentHashMap<String, RunRecord> byId = new ConcurrentHashMap<>();
    /** clientRequestId → runId（幂等索引，终态后保留一段窗口供结果查询）。 */
    private final ConcurrentHashMap<String, String> byClientRequestId = new ConcurrentHashMap<>();
    /** 会话 → 活动（非终态）runId。 */
    private final ConcurrentHashMap<String, String> activeBySession = new ConcurrentHashMap<>();
    /** 单调序列：同毫秒内创建的多条记录仍能稳定倒序。 */
    private final AtomicLong seq = new AtomicLong();

    private final Path runsDir;

    /**
     * @param runsDir 执行记录目录；null 表示纯内存模式（测试用，重启即清空）
     */
    public RunRegistry(String runsDir) {
        this.runsDir = runsDir != null && !runsDir.isBlank() ? Paths.get(runsDir) : null;
        if (this.runsDir != null) {
            try {
                Files.createDirectories(this.runsDir);
            } catch (IOException e) {
                logger.error("Failed to create runs dir, run records will be memory-only",
                        Map.of("path", runsDir, "error", String.valueOf(e.getMessage())));
            }
            restoreFromDisk();
        }
    }

    // ==================== 登记 ====================

    /**
     * 开始一次新执行（或返回幂等命中的既有执行）。
     *
     * @param clientRequestId 客户端幂等键，可为 null（旧客户端无幂等）
     * @param sessionKey      归属会话
     * @param message         提交消息（仅取预览）
     * @return 登记结果：duplicated=true 表示 clientRequestId 命中既有活动执行
     */
    public synchronized BeginResult beginRun(String clientRequestId, String sessionKey, String message) {
        // 幂等：同一 clientRequestId 已有活动执行时直接返回，不重复运行
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            String existingId = byClientRequestId.get(clientRequestId);
            if (existingId != null) {
                RunRecord existing = byId.get(existingId);
                if (existing != null && !existing.isTerminal()) {
                    return new BeginResult(existing, true);
                }
            }
        }

        // 会话并发约束：每会话同时最多一个活动 Web 根执行
        String activeId = activeBySession.get(sessionKey);
        if (activeId != null) {
            RunRecord active = byId.get(activeId);
            if (active != null && !active.isTerminal()) {
                return new BeginResult(active, false, "会话已有任务在运行，请等待完成或先停止");
            }
        }

        String id = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RunRecord record = RunRecord.create(id, clientRequestId, sessionKey, message);
        record.seq = seq.incrementAndGet();
        byId.put(id, record);
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            byClientRequestId.put(clientRequestId, id);
        }
        activeBySession.put(sessionKey, id);
        persist(record);
        return new BeginResult(record, false);
    }

    /** 状态流转并落盘（状态变化立即写盘）。 */
    public void transition(String runId, RunRecord.Status next) {
        transition(runId, next, null);
    }

    /** 状态流转并落盘，附带失败摘要。 */
    public void transition(String runId, RunRecord.Status next, String errorSummary) {
        if (runId == null) {
            return;
        }
        RunRecord record = byId.get(runId);
        if (record == null) {
            return;
        }
        synchronized (this) {
            record.transitionTo(next);
            if (errorSummary != null && !errorSummary.isBlank()) {
                record.errorSummary = summarize(errorSummary);
            }
            if (record.isTerminal()) {
                activeBySession.remove(record.sessionKey, runId);
            }
        }
        persist(record);
    }

    /** 执行正常结束：CANCELLING → CANCELLED，否则 COMPLETED。 */
    public void completeRun(String runId) {
        RunRecord record = byId.get(runId);
        if (record == null || record.isTerminal()) {
            return;
        }
        RunRecord.Status finalStatus = record.statusEnum() == RunRecord.Status.CANCELLING
                ? RunRecord.Status.CANCELLED
                : RunRecord.Status.COMPLETED;
        transition(runId, finalStatus);
    }

    /** 执行异常结束：CANCELLING → CANCELLED，否则 FAILED。 */
    public void failRun(String runId, String error) {
        RunRecord record = byId.get(runId);
        if (record == null || record.isTerminal()) {
            return;
        }
        RunRecord.Status finalStatus = record.statusEnum() == RunRecord.Status.CANCELLING
                ? RunRecord.Status.CANCELLED
                : RunRecord.Status.FAILED;
        transition(runId, finalStatus, error);
    }

    // ==================== 查询 ====================

    /** 按 runId 查询。 */
    public RunRecord get(String runId) {
        return runId == null ? null : byId.get(runId);
    }

    /** 按 clientRequestId 查询（含终态，供断线后结果查询）。 */
    public RunRecord findByClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        String id = byClientRequestId.get(clientRequestId);
        return id == null ? null : byId.get(id);
    }

    /** 查询会话最近执行的列表（按创建序倒序，最多 limit 条）。 */
    public List<RunRecord> listBySession(String sessionKey, int limit) {
        List<RunRecord> result = new ArrayList<>();
        for (RunRecord r : byId.values()) {
            if (sessionKey.equals(r.sessionKey)) {
                result.add(r);
            }
        }
        result.sort(Comparator.comparingLong((RunRecord r) -> r.seq).reversed());
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /** 会话是否有活动（非终态）执行。 */
    public boolean isSessionRunning(String sessionKey) {
        String id = activeBySession.get(sessionKey);
        if (id == null) {
            return false;
        }
        RunRecord r = byId.get(id);
        return r != null && !r.isTerminal();
    }

    // ==================== 持久化与恢复 ====================

    /** 原子写单条记录；写失败仅记录日志，不影响内存状态。 */
    private void persist(RunRecord record) {
        if (runsDir == null || record == null || record.id == null) {
            return;
        }
        try {
            Path target = runsDir.resolve(record.id + ".json");
            JsonFileStore.writeJson(MAPPER, target, record);
        } catch (IOException e) {
            logger.error("Failed to persist run record", Map.of(
                    "runId", record.id, "error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 重启恢复：扫描目录载入记录，遗留的非终态标记 INTERRUPTED。
     * 损坏文件跳过（不中断启动），不动会话转录。
     */
    private void restoreFromDisk() {
        if (runsDir == null || !Files.isDirectory(runsDir)) {
            return;
        }
        int restored = 0;
        int interrupted = 0;
        try (Stream<Path> files = Files.list(runsDir)) {
            for (Path file : files.sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                try {
                    RunRecord record = JsonFileStore.readJson(MAPPER, file, RunRecord.class, () -> null);
                    if (record == null || record.id == null) {
                        continue;
                    }
                    byId.put(record.id, record);
                    if (record.clientRequestId != null && !record.clientRequestId.isBlank()) {
                        byClientRequestId.put(record.clientRequestId, record.id);
                    }
                    if (!record.isTerminal()) {
                        // JVM 重启后执行线程已不存在：标记中断，不自动重跑
                        record.transitionTo(RunRecord.Status.INTERRUPTED);
                        record.errorSummary = "服务重启，任务已中断，不会自动重试";
                        persist(record);
                        interrupted++;
                    } else if (record.statusEnum() == RunRecord.Status.INTERRUPTED) {
                        // 历史中断记录：恢复索引，活动会话指针不设置
                    }
                    restored++;
                    seq.accumulateAndGet(record.seq, Math::max);
                } catch (Exception e) {
                    logger.warn("Skip unreadable run record", Map.of(
                            "file", file.getFileName().toString(),
                            "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            logger.error("Failed to scan runs dir", Map.of(
                    "path", runsDir.toString(), "error", String.valueOf(e.getMessage())));
            return;
        }
        evictOldTerminal();
        if (restored > 0) {
            logger.info("Run records restored", Map.of(
                    "restored", restored, "marked_interrupted", interrupted));
        }
    }

    /** 淘汰过旧的终态记录（仅摘要登记，可直接清理，不影响会话数据）。 */
    private void evictOldTerminal() {
        List<RunRecord> terminal = byId.values().stream()
                .filter(RunRecord::isTerminal)
                .sorted(Comparator.comparingLong((RunRecord r) -> r.updatedAt).reversed())
                .toList();
        for (int i = MAX_RETAINED_TERMINAL; i < terminal.size(); i++) {
            RunRecord stale = terminal.get(i);
            byId.remove(stale.id);
            if (stale.clientRequestId != null) {
                byClientRequestId.remove(stale.clientRequestId, stale.id);
            }
            if (runsDir != null) {
                try {
                    Files.deleteIfExists(runsDir.resolve(stale.id + ".json"));
                } catch (IOException e) {
                    logger.debug("Failed to delete stale run record", Map.of("runId", stale.id));
                }
            }
        }
    }

    /** 失败摘要截断。 */
    private static String summarize(String error) {
        if (error == null) {
            return null;
        }
        String normalized = error.replaceAll("\\s+", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) + "…" : normalized;
    }

    /** beginRun 的返回：记录 + 是否幂等命中 / 忙碌原因。 */
    public static final class BeginResult {
        public final RunRecord record;
        /** true：clientRequestId 命中既有活动执行（幂等重放）。 */
        public final boolean duplicated;
        /** 非空：拒绝原因（如会话忙碌）。 */
        public final String rejection;

        BeginResult(RunRecord record, boolean duplicated) {
            this(record, duplicated, null);
        }

        BeginResult(RunRecord record, boolean duplicated, String rejection) {
            this.record = record;
            this.duplicated = duplicated;
            this.rejection = rejection;
        }

        public boolean isRejected() {
            return rejection != null;
        }
    }
}
