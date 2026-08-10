package io.leavesfly.tinyclaw.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.AgentConfig;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.SessionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 心跳运行器（对齐 OpenClaw heartbeat 模型）。
 *
 * <p>心跳 tick 由 CronService 调度（系统内置 job {@code __heartbeat__}），
 * 本类只负责单轮心跳的执行逻辑：</p>
 * <ul>
 *   <li>busy guard：Agent 正忙时跳过本轮（reason=busy）</li>
 *   <li>空清单跳过：HEARTBEAT.md 缺失或无实质内容时跳过（reason=empty-heartbeat-file）</li>
 *   <li>HEARTBEAT_OK 契约：无事回 HEARTBEAT_OK，剥离后剩余为空或 ≤300 字符则静默</li>
 *   <li>整轮超时：future.get(timeout) 超时则取消并中断当前任务</li>
 *   <li>成本旋钮：isolatedSession / lightContext / 心跳专用模型覆盖</li>
 *   <li>投递契约：target = none | last | 显式 channel；showOk / showAlerts 可见性</li>
 *   <li>activeHours：活跃时段窗口外跳过</li>
 *   <li>per-agent：entries 非空时每个 entry 注册独立 __heartbeat__:&lt;id&gt; job</li>
 * </ul>
 *
 * <p>每轮结果写入 memory/heartbeat-status.json，供 CLI/Web 面板查询。</p>
 */
public class HeartbeatRunner {

    /** 心跳系统 job 名称（per-agent 时追加 ":<agentId>" 后缀） */
    public static final String HEARTBEAT_JOB_NAME = "__heartbeat__";
    /** 记忆进化系统 job 名称 */
    public static final String MEMORY_EVOLUTION_JOB_NAME = "__memory_evolution__";
    /** 心跳 job 名称前缀（含 per-agent 变体） */
    public static final String HEARTBEAT_JOB_PREFIX = HEARTBEAT_JOB_NAME;

    /** HEARTBEAT_OK 响应契约 token */
    static final String OK_TOKEN = "HEARTBEAT_OK";
    /** OK 剥离后剩余内容长度 ≤ 该值视为"无事"，静默丢弃 */
    static final int SILENT_THRESHOLD = 300;
    /** 清单内容硬上限（8 KiB） */
    static final int MAX_CHECKLIST_BYTES = 8 * 1024;

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("heartbeat");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单轮心跳结果状态 */
    public enum Status {
        RAN, SKIPPED_BUSY, SKIPPED_EMPTY, SKIPPED_ALERTS_DISABLED,
        SKIPPED_HOURS, SKIPPED_DISABLED, TIMEOUT, ERROR
    }

    /** 告警投递接口，由 GatewayBootstrap 注入（经 MessageBus 出站） */
    @FunctionalInterface
    public interface AlertSink {
        void deliver(String channel, String chatId, String content);
    }

    private final Config config;
    private final AgentRuntime runtime;
    private final SessionManager sessions;
    private final String workspace;
    private final Supplier<Boolean> busyChecker;
    private final AlertSink alertSink;

    /** 心跳执行专用单线程池，用于整轮超时控制 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-runner");
        t.setDaemon(true);
        return t;
    });

    /** 每个 agent 的最近一次运行状态（agentKey -> 状态信息） */
    private final Map<String, Map<String, Object>> statusMap = new ConcurrentHashMap<>();

    public HeartbeatRunner(Config config, AgentRuntime runtime, SessionManager sessions,
                           String workspace, Supplier<Boolean> busyChecker, AlertSink alertSink) {
        this.config = config;
        this.runtime = runtime;
        this.sessions = sessions;
        this.workspace = workspace;
        this.busyChecker = busyChecker;
        this.alertSink = alertSink;
    }

    // ==================== 系统 job 注册 ====================

    /**
     * 向 CronService 注册/更新系统内置 job（幂等，按 name 查重）。
     *
     * <p>心跳启用时：按 entries 或默认配置注册 {@code __heartbeat__}（或
     * {@code __heartbeat__:<id>}）与 {@code __memory_evolution__}；
     * 禁用时清理残留的系统 job，避免旧 job 继续触发。</p>
     *
     * @param cron 定时服务
     */
    public void registerSystemJobs(CronService cron) {
        AgentConfig agent = config.getAgent();
        AgentConfig.HeartbeatSettings hb = agent != null ? agent.getHeartbeat() : null;
        boolean enabled = agent != null && agent.isHeartbeatEnabled();

        if (!enabled) {
            removeSystemJobs(cron);
            logger.info("Heartbeat disabled, system jobs not registered");
            return;
        }

        Set<String> desired = new HashSet<>();
        Map<String, AgentConfig.HeartbeatSettings> entries = hb.getEntries();
        if (entries != null && !entries.isEmpty()) {
            // per-agent 模式：仅 entries 中的 agent 跑心跳
            for (Map.Entry<String, AgentConfig.HeartbeatSettings> entry : entries.entrySet()) {
                AgentConfig.HeartbeatSettings merged = entry.getValue().mergedOver(hb);
                if (!merged.isEnabled() || merged.getIntervalSeconds() <= 0) {
                    continue;
                }
                String jobName = HEARTBEAT_JOB_NAME + ":" + entry.getKey();
                ensureEveryJob(cron, jobName, merged.getIntervalSeconds() * 1000L);
                desired.add(jobName);
            }
        } else if (hb.getIntervalSeconds() > 0) {
            ensureEveryJob(cron, HEARTBEAT_JOB_NAME, hb.getIntervalSeconds() * 1000L);
            desired.add(HEARTBEAT_JOB_NAME);
        }

        // 清理不再需要的 per-agent 残留 job
        for (CronJob job : cron.listJobs(true)) {
            String name = job.getName();
            if (name != null && name.startsWith(HEARTBEAT_JOB_PREFIX + ":") && !desired.contains(name)) {
                cron.removeJob(job.getId());
                logger.info("Removed stale heartbeat job", Map.of("name", name));
            }
        }

        // 记忆进化独立 job（间隔与心跳一致，内部自带 24h 冷却与门控，天然幂等）
        int evolutionInterval = hb.getIntervalSeconds() > 0 ? hb.getIntervalSeconds() : 1800;
        ensureEveryJob(cron, MEMORY_EVOLUTION_JOB_NAME, evolutionInterval * 1000L);

        logger.info("Heartbeat system jobs registered", Map.of(
                "heartbeat_jobs", desired.size(),
                "interval_seconds", hb.getIntervalSeconds()));
    }

    private void ensureEveryJob(CronService cron, String name, long everyMs) {
        CronJob job = cron.findJobByName(name);
        if (job == null) {
            cron.addJob(name, CronSchedule.every(everyMs), "", "system", null);
            return;
        }
        CronSchedule schedule = job.getSchedule();
        boolean scheduleChanged = schedule == null
                || schedule.getKind() != CronSchedule.ScheduleKind.EVERY
                || schedule.getEveryMs() == null
                || schedule.getEveryMs() != everyMs;
        if (scheduleChanged) {
            cron.updateJobSchedule(job.getId(), CronSchedule.every(everyMs));
        }
        if (!job.isEnabled()) {
            cron.enableJob(job.getId(), true);
        }
    }

    private void removeSystemJobs(CronService cron) {
        for (CronJob job : cron.listJobs(true)) {
            String name = job.getName();
            if (name == null) {
                continue;
            }
            if (name.equals(MEMORY_EVOLUTION_JOB_NAME) || name.startsWith(HEARTBEAT_JOB_PREFIX)) {
                cron.removeJob(job.getId());
                logger.info("Removed system job (heartbeat disabled)", Map.of("name", name));
            }
        }
    }

    // ==================== 执行入口 ====================

    /**
     * 按 cron job 名称执行一轮心跳。
     *
     * @param jobName job 名称（__heartbeat__ 或 __heartbeat__:&lt;agentId&gt;）
     */
    public void runOnceForJob(String jobName) {
        String agentId = null;
        if (jobName != null && jobName.startsWith(HEARTBEAT_JOB_PREFIX + ":")) {
            agentId = jobName.substring(HEARTBEAT_JOB_PREFIX.length() + 1);
        }
        runOnceForAgent(agentId);
    }

    /**
     * 手动触发一次默认 agent 的心跳（CLI heartbeat now / Web 面板）。
     * 异步执行，立即返回。
     */
    public void runNow() {
        executor.submit(() -> runOnceForAgent(null));
    }

    /**
     * 执行指定 agent 的一轮心跳（含全部门控检查）。
     *
     * @param agentId agent 标识，null 表示默认
     */
    public void runOnceForAgent(String agentId) {
        String agentKey = agentId == null ? "default" : agentId;
        long startMs = System.currentTimeMillis();

        AgentConfig agent = config.getAgent();
        AgentConfig.HeartbeatSettings hb = agent != null ? agent.getHeartbeat() : null;
        AgentConfig.HeartbeatSettings settings = resolveSettings(hb, agentId);

        try {
            if (!settings.isEnabled()) {
                recordRun(agentKey, Status.SKIPPED_DISABLED, "disabled", startMs);
                return;
            }
            if (!settings.isShowOk() && !settings.isShowAlerts()) {
                recordRun(agentKey, Status.SKIPPED_ALERTS_DISABLED, "alerts-disabled", startMs);
                logger.info("Heartbeat skipped", Map.of("reason", "alerts-disabled", "agent", agentKey));
                return;
            }
            if (!isWithinActiveHours(settings.getActiveHours())) {
                recordRun(agentKey, Status.SKIPPED_HOURS, "outside-active-hours", startMs);
                logger.info("Heartbeat skipped", Map.of("reason", "outside-active-hours", "agent", agentKey));
                return;
            }
            if (busyChecker != null && Boolean.TRUE.equals(busyChecker.get())) {
                recordRun(agentKey, Status.SKIPPED_BUSY, "busy", startMs);
                logger.info("Heartbeat skipped", Map.of("reason", "busy", "agent", agentKey));
                return;
            }

            String checklist = readChecklist();
            if (checklist == null) {
                recordRun(agentKey, Status.SKIPPED_EMPTY, "empty-heartbeat-file", startMs);
                logger.info("Heartbeat skipped", Map.of("reason", "empty-heartbeat-file", "agent", agentKey));
                return;
            }

            String result = executeRound(agentKey, settings, checklist);
            handleResult(agentKey, settings, result, startMs);
        } catch (TimeoutException e) {
            recordRun(agentKey, Status.TIMEOUT, "timeout", startMs);
        } catch (Exception e) {
            recordRun(agentKey, Status.ERROR, e.getMessage(), startMs);
            logger.error("Heartbeat round failed", Map.of("agent", agentKey, "error", e.getMessage()));
        }
    }

    // ==================== 单轮执行 ====================

    /**
     * 提交 LLM 自省轮次并做整轮超时控制。
     *
     * @return LLM 回复内容
     * @throws TimeoutException 整轮超时（已取消并发送中断信号）
     */
    private String executeRound(String agentKey, AgentConfig.HeartbeatSettings settings,
                                String checklist) throws Exception {
        int timeoutSeconds = settings.effectiveTimeoutSeconds();
        String sessionKey = settings.isIsolatedSession()
                ? "heartbeat:" + agentKey + ":" + System.nanoTime()
                : "heartbeat:" + agentKey;

        // 心跳专用模型覆盖：仅在隔离会话下生效，规避 model bleed
        String modelOverride = settings.getModel();
        if (modelOverride != null && !modelOverride.isEmpty() && !settings.isIsolatedSession()) {
            logger.warn("Heartbeat model override ignored: requires isolatedSession=true",
                    Map.of("agent", agentKey, "model", modelOverride));
            modelOverride = null;
        }
        if (modelOverride != null) {
            runtime.setSessionModelOverride(sessionKey, modelOverride);
        }

        String prompt = buildPrompt(settings, checklist);
        boolean lightContext = settings.isLightContext();

        Future<String> future = executor.submit(() -> runtime.processDirect(prompt, sessionKey, lightContext));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            runtime.abortCurrentTask();
            logger.warn("Heartbeat round timed out, task aborted", Map.of(
                    "agent", agentKey, "timeout_seconds", timeoutSeconds));
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        } finally {
            if (modelOverride != null) {
                runtime.setSessionModelOverride(sessionKey, null);
            }
            if (settings.isIsolatedSession()) {
                try {
                    sessions.deleteSession(sessionKey);
                } catch (Exception ignored) {
                    // 会话清理失败不影响主流程
                }
            }
        }
    }

    /**
     * 构建心跳 prompt：默认指令含 HEARTBEAT_OK 契约，配置 prompt 可整体覆盖指令体。
     */
    String buildPrompt(AgentConfig.HeartbeatSettings settings, String checklist) {
        String body = settings.getPrompt();
        if (body == null || body.isEmpty()) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            body = String.format("""
                    # 心跳检查

                    当前时间: %s

                    阅读下方清单，检查是否有需要关注或行动的事项。
                    规则：
                    1. 如果没有需要行动的事项，只回复 HEARTBEAT_OK。
                    2. 不要从旧聊天记录推断新任务，也不要重复已完成的任务。
                    3. 确定性的周期性任务请注册为 cron 任务，不要依赖心跳执行。
                    """, now);
        }
        return body + "\n\n## 心跳清单（HEARTBEAT.md）\n\n" + checklist;
    }

    // ==================== 结果处理 ====================

    /**
     * 按 HEARTBEAT_OK 契约处理回复：剥离 token 后剩余为空或 ≤300 字符视为无事静默，
     * 否则按 showAlerts/目标投递告警。
     */
    void handleResult(String agentKey, AgentConfig.HeartbeatSettings settings, String result, long startMs) {
        String remaining = stripOkToken(result == null ? "" : result);
        boolean silent = remaining.isBlank() || remaining.length() <= SILENT_THRESHOLD;

        if (silent) {
            recordRun(agentKey, Status.RAN, "ok", startMs);
            if (settings.isShowOk()) {
                deliver(agentKey, settings, "HEARTBEAT_OK"
                        + (remaining.isBlank() ? "" : " " + remaining));
            } else {
                logger.debug("Heartbeat OK, suppressed", Map.of(
                        "agent", agentKey, "remaining_chars", remaining.length()));
            }
            return;
        }

        // 告警内容
        recordRun(agentKey, Status.RAN, "alert", startMs);
        if (settings.isShowAlerts()) {
            deliver(agentKey, settings, remaining);
            logger.info("Heartbeat alert", Map.of("agent", agentKey, "chars", remaining.length()));
        } else {
            logger.debug("Heartbeat alert suppressed", Map.of("agent", agentKey));
        }
    }

    /**
     * 剥离回复首/尾的 HEARTBEAT_OK token。
     */
    static String stripOkToken(String reply) {
        String text = reply.trim();
        if (text.startsWith(OK_TOKEN)) {
            text = text.substring(OK_TOKEN.length()).trim();
        }
        if (text.endsWith(OK_TOKEN)) {
            text = text.substring(0, text.length() - OK_TOKEN.length()).trim();
        }
        return text;
    }

    /**
     * 按投递目标发送内容：none 仅记日志；last 投递最近联系人；显式 channel 名
     * 复用最近联系人中匹配的 chatId。
     */
    private void deliver(String agentKey, AgentConfig.HeartbeatSettings settings, String content) {
        String target = settings.getTarget() == null ? "none" : settings.getTarget();
        if ("none".equalsIgnoreCase(target)) {
            logger.info("Heartbeat output (target=none)", Map.of(
                    "agent", agentKey, "content", content));
            return;
        }
        if (alertSink == null) {
            logger.warn("Heartbeat delivery skipped: no alert sink", Map.of("agent", agentKey));
            return;
        }
        String[] last = LastContact.get();
        if ("last".equalsIgnoreCase(target)) {
            if (last == null) {
                logger.warn("Heartbeat delivery skipped: no last contact", Map.of("agent", agentKey));
                return;
            }
            alertSink.deliver(last[0], last[1], content);
            return;
        }
        // 显式 channel 名：chatId 复用最近联系人（通道必须匹配）
        if (last != null && target.equals(last[0])) {
            alertSink.deliver(last[0], last[1], content);
        } else {
            logger.warn("Heartbeat delivery skipped: no chatId for target channel", Map.of(
                    "agent", agentKey, "target", target));
        }
    }

    // ==================== 清单与配置辅助 ====================

    /**
     * 读取心跳清单，返回 null 表示应跳过整轮（文件缺失或无实质内容）。
     * 内容超过 8 KiB 时截断并 WARN。
     */
    private String readChecklist() {
        Path notesFile = Paths.get(workspace, "memory", "HEARTBEAT.md");
        if (!Files.exists(notesFile)) {
            return null;
        }
        String content;
        try {
            content = Files.readString(notesFile);
        } catch (Exception e) {
            logger.warn("Failed to read HEARTBEAT.md", Map.of("error", e.getMessage()));
            return null;
        }
        if (!hasSubstantiveContent(content)) {
            return null;
        }
        if (content.length() > MAX_CHECKLIST_BYTES) {
            logger.warn("HEARTBEAT.md exceeds 8 KiB limit, truncating", Map.of(
                    "size", content.length(), "limit", MAX_CHECKLIST_BYTES));
            content = content.substring(0, MAX_CHECKLIST_BYTES) + "\n...[truncated]";
        }
        return content;
    }

    /**
     * 判断清单是否有实质内容：剔除空行、Markdown 标题、单行 HTML 注释、
     * 代码围栏标记、空 checklist stub 后仍有剩余行。
     */
    static boolean hasSubstantiveContent(String content) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("<!--") && t.endsWith("-->")) {
                continue;
            }
            if (t.equals("```")) {
                continue;
            }
            if (t.matches("^[-*]\\s*\\[[ xX]\\]\\s*$")) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 解析指定 agent 的生效配置（entry 叠加到顶层默认之上）。
     */
    private AgentConfig.HeartbeatSettings resolveSettings(AgentConfig.HeartbeatSettings hb, String agentId) {
        if (hb == null) {
            return new AgentConfig.HeartbeatSettings();
        }
        if (agentId == null || hb.getEntries() == null) {
            return hb;
        }
        AgentConfig.HeartbeatSettings entry = hb.getEntries().get(agentId);
        return entry == null ? hb : entry.mergedOver(hb);
    }

    /**
     * 判断当前时间是否落在活跃时段窗口内。
     * start == end 视为零宽窗口（全部跳过）；跨午夜窗口按 start > end 处理。
     */
    static boolean isWithinActiveHours(AgentConfig.ActiveHours hours) {
        if (hours == null || hours.getStart() == null || hours.getEnd() == null
                || hours.getStart().isEmpty() || hours.getEnd().isEmpty()) {
            return true;
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(hours.getStart());
            end = LocalTime.parse(hours.getEnd());
        } catch (Exception e) {
            logger.warn("Invalid activeHours format, ignoring window", Map.of(
                    "start", hours.getStart(), "end", hours.getEnd()));
            return true;
        }
        if (start.equals(end)) {
            return false;
        }
        ZoneId zone = ZoneId.systemDefault();
        if (hours.getTimezone() != null && !hours.getTimezone().isEmpty()) {
            try {
                zone = ZoneId.of(hours.getTimezone());
            } catch (Exception e) {
                logger.warn("Invalid activeHours timezone, using system default", Map.of(
                        "timezone", hours.getTimezone()));
            }
        }
        LocalTime now = LocalTime.now(zone);
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    // ==================== 状态记录与查询 ====================

    private void recordRun(String agentKey, Status status, String reason, long startMs) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", status.name());
        info.put("reason", reason);
        info.put("at_ms", System.currentTimeMillis());
        info.put("duration_ms", System.currentTimeMillis() - startMs);
        statusMap.put(agentKey, info);
        saveStatus();
    }

    private void saveStatus() {
        try {
            Path dir = Paths.get(workspace, "memory");
            Files.createDirectories(dir);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(statusMap);
            Files.writeString(dir.resolve("heartbeat-status.json"), json);
        } catch (Exception e) {
            logger.warn("Failed to persist heartbeat status", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取所有 agent 的最近一次运行状态快照。
     *
     * @return agentKey -> 状态信息
     */
    public Map<String, Map<String, Object>> getLastRuns() {
        return new HashMap<>(statusMap);
    }

    /**
     * 停止心跳运行器，释放执行线程池。
     */
    public void stop() {
        executor.shutdownNow();
    }
}
