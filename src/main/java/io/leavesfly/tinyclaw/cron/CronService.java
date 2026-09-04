package io.leavesfly.tinyclaw.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 定时服务，调度和执行定时任务。
 * 
 * 这是 TinyClaw 定时任务系统的核心服务，负责任务的调度、执行和状态管理。
 * 
 * 核心职责：
 * - 任务调度：解析 cron 表达式并计算下次执行时间
 * - 任务存储：持久化任务配置和状态信息到文件系统
 * - 任务执行：按时触发任务执行回调
 * - 状态管理：跟踪任务的启用、禁用状态和执行历史
 * - 并发控制：使用读写锁确保线程安全
 * 
 * 技术实现：
 * - 使用 cron-utils 库解析和验证 cron 表达式
 * - 基于文件系统的 JSON 格式任务持久化
 * - 独立守护线程运行任务调度循环（1秒检查间隔）
 * - ReentrantReadWriteLock 保护共享数据结构
 * - SecureRandom 生成唯一任务标识符
 * 
 * 设计特点：
 * - 高可靠性：具备错误恢复和完整日志记录
 * - 可扩展性：支持自定义任务处理器
 * - 性能优化：在锁外执行任务，避免阻塞调度循环
 * - 易用性：简洁的 API 接口和清晰的状态反馈
 * 
 * 调度类型：
 * - AT：一次性任务，指定时间执行
 * - EVERY：周期性任务，按固定间隔执行
 * - CRON：cron 表达式任务，按复杂规则执行
 * 
 * 使用场景：
 * 1. 为 CronTool 提供底层调度支持
 * 2. 系统级定时维护任务执行
 * 3. 第三方集成的定时任务需求
 * 4. 复杂业务逻辑的定时触发
 */
public class CronService {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cron");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();  // 复用实例，避免重复创建
    
    private static final long CHECK_INTERVAL_MS = 1000L;  // 任务检查间隔（毫秒）
    private static final long JOB_TIMEOUT_MS = 10 * 60_000L;  // 单个任务执行超时（10 分钟）
    private static final int ID_BYTE_LENGTH = 8;          // ID 字节长度
    private static final String HEX_FORMAT = "%02x";      // 十六进制格式
    
    private static final String STATUS_OK = "ok";         // 执行成功状态
    private static final String STATUS_ERROR = "error";   // 执行失败状态
    private static final String STATUS_TIMEOUT = "timeout"; // 执行超时状态
    
    private static final String TRIGGER_SCHEDULE = "schedule"; // 正常调度触发
    private static final String TRIGGER_MISFIRE = "misfire";   // 停机补跑触发
    private static final String TRIGGER_MANUAL = "manual";     // 手动触发
    
    private static final int MAX_HISTORY_SIZE = 20;       // 每个任务保留的执行历史条数
    private static final int RESULT_MAX_LENGTH = 500;     // 执行结果摘要最大长度
    
    private static final String THREAD_NAME = "cron-service";  // 调度线程名称
    
    private static final String STORE_DIR = "cron";            // 存储目录名
    private static final String STORE_FILE = "jobs.json";      // 存储文件名
    
    private final String storePath;                       // 存储文件路径
    private CronStore store;                              // 任务存储对象
    private JobHandler onJob;                             // 任务处理器
    private final ReentrantReadWriteLock lock;            // 读写锁
    private volatile boolean running;                     // 服务运行状态
    private Thread runnerThread;                          // 调度线程
    private ExecutorService jobExecutor;                  // 任务执行线程池（隔离调度线程，支持超时中断）
    
    private final CronParser cronParser;                  // Cron 表达式解析器
    private final Set<String> misfiredJobIds = new HashSet<>(); // 启动时判定补跑的任务 ID，执行时记为 misfire 触发
    
    private long lastLoadedMtimeMs = -1L;                  // 上次加载时文件的 mtime，用于检测外部改动
    private long lastLoadedSize = -1L;                     // 上次加载时文件的大小，同上
    
    /**
     * 任务处理器接口，定义任务执行逻辑。
     */
    @FunctionalInterface
    public interface JobHandler {
        /**
         * 处理任务。
         * 
         * @param job 要执行的任务
         * @return 执行结果
         * @throws Exception 执行异常
         */
        String handle(CronJob job) throws Exception;
    }
    
    /**
     * 解析工作空间下的默认任务存储路径。
     *
     * <p>该路径是全局唯一的任务事实源。由于 {@link #saveStoreUnsafe()} 采用内存全量覆盖写，
     * 同一路径上并存多个 {@code CronService} 实例会互相覆盖对方的任务，
     * 因此调用方必须共享单一实例，不要各自按字符串拼接路径再 new 一个。</p>
     *
     * @param workspace 工作空间根目录
     * @return 任务存储文件的绝对路径
     */
    public static String defaultStorePath(String workspace) {
        return Paths.get(workspace, STORE_DIR, STORE_FILE).toString();
    }
    
    /**
     * 构造定时服务。
     * 
     * @param storePath 任务存储文件路径
     * @param onJob 任务处理器
     */
    public CronService(String storePath, JobHandler onJob) {
        this.storePath = storePath;
        this.onJob = onJob;
        this.lock = new ReentrantReadWriteLock();
        this.running = false;
        this.cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
        );
        loadStore();
    }
    
    /**
     * 构造定时服务（不指定任务处理器）。
     * 
     * @param storePath 任务存储文件路径
     */
    public CronService(String storePath) {
        this(storePath, null);
    }
    
    /**
     * 启动定时服务。
     * 
     * 加载任务存储，重新计算所有任务的下次执行时间，启动调度线程。
     */
    public void start() {
        lock.writeLock().lock();
        try {
            if (running) {
                return;
            }
            
            loadStore();
            recomputeNextRuns();
            saveStoreUnsafe();
            
            running = true;
            jobExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, THREAD_NAME + "-worker");
                thread.setDaemon(true);
                return thread;
            });
            runnerThread = new Thread(this::runLoop, THREAD_NAME);
            runnerThread.setDaemon(true);
            runnerThread.start();
            
            logger.info("Cron service started");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 停止定时服务。
     * 
     * 停止调度线程，不再执行新任务。
     */
    public void stop() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return;
            }
            
            running = false;
            if (runnerThread != null) {
                runnerThread.interrupt();
            }
            if (jobExecutor != null) {
                jobExecutor.shutdownNow();
                jobExecutor = null;
            }
            
            logger.info("Cron service stopped");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 调度循环，定期检查到期任务。
     * 
     * 每秒检查一次，执行到期的任务。
     */
    private void runLoop() {
        while (running) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
                checkJobs();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in cron loop", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 检查并收集到期任务。
     * 
     * 扫描所有启用的任务，收集到期任务并清除其下次执行时间。
     * 在锁外执行任务，避免长时间持有锁。
     */
    private void checkJobs() {
        for (DueJob due : collectDueJobs()) {
            executeJob(due.job(), due.trigger());
        }
    }
    
    /**
     * 到期任务及其触发方式。
     */
    private record DueJob(CronJob job, String trigger) {}
    
    /**
     * 收集到期的任务。
     * 
     * @return 到期任务列表（含触发方式）
     */
    private List<DueJob> collectDueJobs() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return List.of();
            }
            
            // 先同步外部改动（如 CLI 新增的任务），否则下面的 saveStoreUnsafe 会把它们覆盖掉
            reloadIfChangedUnsafe();
            
            long now = System.currentTimeMillis();
            List<DueJob> dueJobs = new ArrayList<>();
            
            for (CronJob job : store.getJobs()) {
                if (isJobDue(job, now)) {
                    String trigger = misfiredJobIds.remove(job.getId())
                            ? TRIGGER_MISFIRE : TRIGGER_SCHEDULE;
                    dueJobs.add(new DueJob(job, trigger));
                    job.getState().setNextRunAtMs(null);  // 清除下次运行时间，防止重复执行
                }
            }
            
            if (!dueJobs.isEmpty()) {
                saveStoreUnsafe();
            }
            
            return dueJobs;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 检查任务是否到期。
     * 
     * @param job 任务对象
     * @param now 当前时间戳
     * @return 到期返回 true，否则返回 false
     */
    private boolean isJobDue(CronJob job, long now) {
        return job.isEnabled() && 
               job.getState().getNextRunAtMs() != null && 
               job.getState().getNextRunAtMs() <= now;
    }
    
    /**
     * 执行单个任务。
     * 
     * 调用任务处理器执行任务，记录执行历史，更新任务状态和下次执行时间。
     * 
     * @param job 要执行的任务
     * @param trigger 触发方式（schedule / misfire / manual）
     */
    private void executeJob(CronJob job, String trigger) {
        long startTime = System.currentTimeMillis();
        RunOutcome outcome = invokeJobHandler(job);
        long durationMs = System.currentTimeMillis() - startTime;
        
        updateJobState(job, startTime, durationMs, outcome, trigger);
    }
    
    /**
     * 单次执行结果。
     */
    private record RunOutcome(String status, String error, String result) {}
    
    /**
     * 调用任务处理器。
     * 
     * 在独立工作线程池中执行并设置超时，避免卡住的任务阻塞整个调度循环。
     * 
     * @param job 要执行的任务
     * @return 执行结果（状态 / 错误 / 结果摘要）
     */
    private RunOutcome invokeJobHandler(CronJob job) {
        if (onJob == null) {
            return new RunOutcome(STATUS_OK, null, null);
        }
        ExecutorService executor = jobExecutor;
        if (executor == null) {
            return new RunOutcome(STATUS_OK, null, null);
        }
        
        Future<String> future = executor.submit(() -> onJob.handle(job));
        try {
            String result = future.get(JOB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new RunOutcome(STATUS_OK, null, truncateResult(result));
        } catch (TimeoutException e) {
            future.cancel(true);  // 中断卡住的任务，释放工作线程
            String error = "job timed out after " + JOB_TIMEOUT_MS + "ms";
            logger.error("Job execution timed out", Map.of(
                    "job_id", job.getId(),
                    "timeout_ms", JOB_TIMEOUT_MS
            ));
            return new RunOutcome(STATUS_TIMEOUT, error, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new RunOutcome(STATUS_ERROR, "interrupted", null);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String error = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            logger.error("Job execution failed", Map.of(
                    "job_id", job.getId(),
                    "error", error
            ));
            return new RunOutcome(STATUS_ERROR, error, null);
        }
    }
    
    /**
     * 截断执行结果摘要，避免历史撑大存储文件。
     * 
     * @param result 处理器返回结果
     * @return 截断后的结果，null 安全
     */
    private static String truncateResult(String result) {
        if (result == null || result.length() <= RESULT_MAX_LENGTH) {
            return result;
        }
        return result.substring(0, RESULT_MAX_LENGTH);
    }
    
    /**
     * 更新任务状态并追加执行历史。
     * 
     * @param job 任务对象
     * @param startTime 开始执行时间
     * @param durationMs 执行耗时
     * @param outcome 执行结果
     * @param trigger 触发方式
     */
    private void updateJobState(CronJob job, long startTime, long durationMs,
                                RunOutcome outcome, String trigger) {
        lock.writeLock().lock();
        try {
            CronJob storeJob = findJobById(job.getId());
            if (storeJob == null) {
                return;
            }
            
            storeJob.getState().setLastRunAtMs(startTime);
            storeJob.setUpdatedAtMs(System.currentTimeMillis());
            storeJob.getState().setLastStatus(outcome.status());
            storeJob.getState().setLastError(outcome.error());
            
            appendHistory(storeJob, startTime, durationMs, outcome, trigger);
            
            handlePostExecution(storeJob);
            saveStoreUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 追加执行历史记录，最新在前，超过上限截断。
     * 
     * @param job 任务对象
     * @param startTime 开始执行时间
     * @param durationMs 执行耗时
     * @param outcome 执行结果
     * @param trigger 触发方式
     */
    private void appendHistory(CronJob job, long startTime, long durationMs,
                               RunOutcome outcome, String trigger) {
        CronJobState state = job.getState();
        List<CronRunRecord> history = state.getHistory();
        if (history == null) {
            history = new ArrayList<>();
            state.setHistory(history);
        }
        
        CronRunRecord record = new CronRunRecord();
        record.setStartedAtMs(startTime);
        record.setDurationMs(durationMs);
        record.setStatus(outcome.status());
        record.setTrigger(trigger);
        record.setError(outcome.error());
        record.setResult(outcome.result());
        
        history.add(0, record);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.subList(MAX_HISTORY_SIZE, history.size()).clear();
        }
    }
    
    /**
     * 根据 ID 查找任务。
     * 
     * @param jobId 任务 ID
     * @return 任务对象，未找到返回 null
     */
    private CronJob findJobById(String jobId) {
        for (CronJob j : store.getJobs()) {
            if (j.getId().equals(jobId)) {
                return j;
            }
        }
        return null;
    }
    
    /**
     * 处理任务执行后的逻辑。
     * 
     * @param job 任务对象
     */
    private void handlePostExecution(CronJob job) {
        if (CronSchedule.ScheduleKind.AT == job.getSchedule().getKind()) {
            if (job.isDeleteAfterRun()) {
                removeJobUnsafe(job.getId());
            } else {
                job.setEnabled(false);
                job.getState().setNextRunAtMs(null);
            }
        } else {
            Long nextRun = computeNextRun(job.getSchedule(), System.currentTimeMillis());
            if (nextRun == null) {
                // 重算失败时显式禁用并记录错误，避免任务静默永久停摆
                job.setEnabled(false);
                job.getState().setLastStatus(STATUS_ERROR);
                job.getState().setLastError("failed to compute next run, job disabled");
                logger.error("Failed to compute next run, job disabled", Map.of(
                        "job_id", job.getId(),
                        "name", job.getName() != null ? job.getName() : ""
                ));
            }
            job.getState().setNextRunAtMs(nextRun);
        }
    }
    
    /**
     * 计算任务的下次执行时间。
     * 
     * @param schedule 调度配置
     * @param nowMs 当前时间戳（毫秒）
     * @return 下次执行时间戳（毫秒），无法计算返回 null
     */
    private Long computeNextRun(CronSchedule schedule, long nowMs) {
        return switch (schedule.getKind()) {
            case AT -> computeAtNextRun(schedule, nowMs);
            case EVERY -> computeEveryNextRun(schedule, nowMs);
            case CRON -> computeCronNextRun(schedule, nowMs);
        };
    }
    
    /**
     * 计算 AT 类型任务的下次执行时间。
     * 
     * @param schedule 调度配置
     * @param nowMs 当前时间戳
     * @return 下次执行时间戳，已过期返回 null
     */
    private Long computeAtNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getAtMs() != null && schedule.getAtMs() > nowMs) {
            return schedule.getAtMs();
        }
        return null;
    }
    
    /**
     * 计算 EVERY 类型任务的下次执行时间。
     * 
     * @param schedule 调度配置
     * @param nowMs 当前时间戳
     * @return 下次执行时间戳，配置无效返回 null
     */
    private Long computeEveryNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getEveryMs() == null || schedule.getEveryMs() <= 0) {
            return null;
        }
        return nowMs + schedule.getEveryMs();
    }
    
    /**
     * 计算 CRON 表达式任务的下次执行时间。
     * 
     * @param schedule 调度配置
     * @param nowMs 当前时间戳
     * @return 下次执行时间戳，解析失败返回 null
     */
    private Long computeCronNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getExpr() == null || schedule.getExpr().isEmpty()) {
            return null;
        }
        
        try {
            Cron cron = cronParser.parse(schedule.getExpr());
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(nowMs), 
                ZoneId.systemDefault()
            );
            Optional<ZonedDateTime> next = executionTime.nextExecution(now);
            
            return next.map(zonedDateTime -> zonedDateTime.toInstant().toEpochMilli())
                       .orElse(null);
        } catch (Exception e) {
            logger.error("Failed to compute next run for cron expr", Map.of(
                    "expr", schedule.getExpr(),
                    "error", e.getMessage()
            ));
            return null;
        }
    }
    
    /**
     * 重新计算所有启用任务的下次执行时间。
     *
     * <p>misfire 补跑：停机期间错过的执行点在启动后立即补跑一次
     * （EVERY：上次执行后停机超过一个周期；CRON：最近应执行点晚于上次实际执行）；
     * AT 一次性任务不补跑；其余任务按常规重算。</p>
     */
    private void recomputeNextRuns() {
        long now = System.currentTimeMillis();
        misfiredJobIds.clear();
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                if (isJobMisfired(job, now)) {
                    misfiredJobIds.add(job.getId());
                    job.getState().setNextRunAtMs(now);
                    logger.info("Misfired job will run immediately on startup", Map.of(
                            "job_id", job.getId(),
                            "name", job.getName() != null ? job.getName() : ""
                    ));
                } else {
                    job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
                }
            }
        }
    }

    /**
     * 判断任务是否错过了应执行的时间点（misfire）。
     *
     * @param job 任务对象
     * @param now 当前时间戳
     * @return 错过返回 true
     */
    private boolean isJobMisfired(CronJob job, long now) {
        CronSchedule schedule = job.getSchedule();
        if (schedule == null) {
            return false;
        }
        return switch (schedule.getKind()) {
            case EVERY -> isEveryJobMisfired(job, now);
            case CRON -> isCronJobMisfired(job, now);
            case AT -> false;
        };
    }

    /**
     * 判断 EVERY 任务是否错过了应执行的时间点（misfire）。
     *
     * @param job 任务对象
     * @param now 当前时间戳
     * @return 错过返回 true
     */
    private boolean isEveryJobMisfired(CronJob job, long now) {
        CronSchedule schedule = job.getSchedule();
        Long lastRun = job.getState().getLastRunAtMs();
        Long everyMs = schedule.getEveryMs();
        return lastRun != null && everyMs != null && everyMs > 0 && lastRun + everyMs <= now;
    }

    /**
     * 判断 CRON 任务是否错过了应执行的时间点（misfire）。
     *
     * <p>规则：最近一个应执行时间点晚于上次实际执行时间，
     * 说明停机期间至少错过一轮；从未执行过的新任务不视为 misfire。</p>
     *
     * @param job 任务对象
     * @param now 当前时间戳
     * @return 错过返回 true
     */
    private boolean isCronJobMisfired(CronJob job, long now) {
        Long lastRun = job.getState().getLastRunAtMs();
        String expr = job.getSchedule().getExpr();
        if (lastRun == null || expr == null || expr.isEmpty()) {
            return false;
        }
        
        try {
            Cron cron = cronParser.parse(expr);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime nowZdt = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(now),
                ZoneId.systemDefault()
            );
            Optional<ZonedDateTime> last = executionTime.lastExecution(nowZdt);
            
            return last.map(z -> z.toInstant().toEpochMilli() > lastRun).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 从磁盘加载任务存储。
     */
    private void loadStore() {
        store = new CronStore();
        
        try {
            Path path = Paths.get(storePath);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                store = objectMapper.readValue(json, CronStore.class);
                if (store.getJobs() == null) {
                    store.setJobs(new ArrayList<>());
                }
            }
            rememberFileStamp(path);
        } catch (Exception e) {
            logger.warn("Failed to load cron store, using empty", Map.of("error", e.getMessage()));
            store = new CronStore();
        }
    }
    
    /**
     * 若存储文件已被外部改动，则丢弃内存快照重新加载。
     *
     * <p>{@link #saveStoreUnsafe()} 是内存全量覆盖写，因此只要本进程持有的快照旧于磁盘，
     * 下一次写入就会抹掉别人新增的任务。典型场景：常驻网关在跑，用户又执行
     * {@code tinyclaw cron add}（另一个进程）。在每次读取/修改前先按 mtime+size 比对，
     * 发现外部改动就重载，使“先读后写”而不是“直接覆盖”。</p>
     *
     * <p>调用此方法前必须已持有写锁。</p>
     *
     * @return true 表示发生了重载
     */
    private boolean reloadIfChangedUnsafe() {
        Path path = Paths.get(storePath);
        try {
            if (!Files.exists(path)) {
                return false;
            }
            long mtime = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            if (mtime == lastLoadedMtimeMs && size == lastLoadedSize) {
                return false;
            }
        } catch (Exception e) {
            // 无法读取文件属性时保守处理：不重载，避免把内存中的任务弄丢
            return false;
        }
        
        loadStore();
        recomputeNextRuns();
        logger.debug("Cron store reloaded after external change",
                Map.of("jobs", store.getJobs().size()));
        return true;
    }
    
    /**
     * 记录当前存储文件的时间戳与大小，作为外部改动的比对基准。
     *
     * @param path 存储文件路径
     */
    private void rememberFileStamp(Path path) {
        try {
            if (Files.exists(path)) {
                lastLoadedMtimeMs = Files.getLastModifiedTime(path).toMillis();
                lastLoadedSize = Files.size(path);
            } else {
                lastLoadedMtimeMs = -1L;
                lastLoadedSize = -1L;
            }
        } catch (Exception e) {
            lastLoadedMtimeMs = -1L;
            lastLoadedSize = -1L;
        }
    }
    
    /**
     * 保存任务存储到磁盘（不加锁）。
     * 
     * 调用此方法前必须已持有写锁。
     */
    private void saveStoreUnsafe() {
        try {
            Path path = Paths.get(storePath);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(store);
            // 原子写，避免写中途崩溃留下半截 JSON 导致任务全部丢失
            JsonFileStore.writeAtomic(path, json);
            // 记下自己写出的版本，避免下一次比对时把自己的写入误判为外部改动
            rememberFileStamp(path);
        } catch (Exception e) {
            logger.error("Failed to save cron store", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 添加新任务。
     * 
     * @param name 任务名称
     * @param schedule 调度配置
     * @param message 消息内容
     * @param channel 目标通道
     * @param to 目标接收者
     * @return 创建的任务对象
     */
    public CronJob addJob(String name, CronSchedule schedule, String message,
                          String channel, String to) {
        lock.writeLock().lock();
        try {
            // 先读后写：先同步外部改动，避免本次全量写入抹掉别的进程新增的任务
            reloadIfChangedUnsafe();
            
            long now = System.currentTimeMillis();
            boolean deleteAfterRun = CronSchedule.ScheduleKind.AT == schedule.getKind();
            
            CronJob job = createJob(name, schedule, message, channel, to, now, deleteAfterRun);
            
            store.getJobs().add(job);
            saveStoreUnsafe();
            
            logger.info("Added cron job", Map.of(
                    "job_id", job.getId(),
                    "name", name,
                    "kind", schedule.getKind()
            ));
            
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 创建任务对象。
     * 
     * @param name 任务名称
     * @param schedule 调度配置
     * @param message 消息内容
     * @param channel 目标通道
     * @param to 目标接收者
     * @param now 当前时间戳
     * @param deleteAfterRun 执行后是否删除
     * @return 任务对象
     */
    private CronJob createJob(String name, CronSchedule schedule, String message,
                             String channel, String to,
                             long now, boolean deleteAfterRun) {
        CronJob job = new CronJob();
        job.setId(generateId());
        job.setName(name);
        job.setEnabled(true);
        job.setSchedule(schedule);
        job.setPayload(new CronPayload(message, channel, to));
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(deleteAfterRun);
        job.getState().setNextRunAtMs(computeNextRun(schedule, now));
        return job;
    }
    
    /**
     * 按名称查找任务。
     *
     * <p>用于系统内置 job（如 __heartbeat__）的幂等注册查重。</p>
     *
     * @param name 任务名称
     * @return 匹配的任务对象，未找到返回 null
     */
    public CronJob findJobByName(String name) {
        lock.readLock().lock();
        try {
            for (CronJob job : store.getJobs()) {
                if (name != null && name.equals(job.getName())) {
                    return job;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 更新任务的调度配置并重算下次执行时间。
     *
     * @param jobId 任务 ID
     * @param schedule 新的调度配置
     * @return 更新后的任务对象，任务不存在返回 null
     */
    public CronJob updateJobSchedule(String jobId, CronSchedule schedule) {
        return updateJob(jobId, null, schedule, null, null, null);
    }

    /**
     * 更新任务配置（名称 / 调度 / 负载），null 项保留原值。
     *
     * <p>调度变更后若任务启用则重算下次执行时间。</p>
     *
     * @param jobId 任务 ID
     * @param name 新名称，null 保留原值
     * @param schedule 新调度配置，null 保留原值
     * @param message 新消息内容，null 保留原值
     * @param channel 新目标通道，null 保留原值
     * @param to 新目标接收者，null 保留原值
     * @return 更新后的任务对象，任务不存在返回 null
     */
    public CronJob updateJob(String jobId, String name, CronSchedule schedule,
                             String message, String channel, String to) {
        lock.writeLock().lock();
        try {
            reloadIfChangedUnsafe();
            CronJob job = findJobById(jobId);
            if (job == null) {
                return null;
            }

            if (name != null && !name.isEmpty()) {
                job.setName(name);
            }
            if (schedule != null) {
                job.setSchedule(schedule);
            }
            if (message != null) {
                job.getPayload().setMessage(message);
            }
            if (channel != null) {
                job.getPayload().setChannel(channel);
            }
            if (to != null) {
                job.getPayload().setTo(to);
            }
            job.setUpdatedAtMs(System.currentTimeMillis());
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), System.currentTimeMillis()));
            }

            saveStoreUnsafe();
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除任务。
     * 
     * @param jobId 任务 ID
     * @return 删除成功返回 true，任务不存在返回 false
     */
    public boolean removeJob(String jobId) {
        lock.writeLock().lock();
        try {
            reloadIfChangedUnsafe();
            return removeJobUnsafe(jobId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 删除任务（不加锁）。
     * 
     * @param jobId 任务 ID
     * @return 删除成功返回 true，任务不存在返回 false
     */
    private boolean removeJobUnsafe(String jobId) {
        boolean removed = store.getJobs().removeIf(j -> j.getId().equals(jobId));
        if (removed) {
            saveStoreUnsafe();
        }
        return removed;
    }
    
    /**
     * 启用或禁用任务。
     * 
     * @param jobId 任务 ID
     * @param enabled 启用状态
     * @return 更新后的任务对象，任务不存在返回 null
     */
    public CronJob enableJob(String jobId, boolean enabled) {
        lock.writeLock().lock();
        try {
            reloadIfChangedUnsafe();
            CronJob job = findJobById(jobId);
            if (job == null) {
                return null;
            }
            
            job.setEnabled(enabled);
            job.setUpdatedAtMs(System.currentTimeMillis());
            
            if (enabled) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), System.currentTimeMillis()));
            } else {
                job.getState().setNextRunAtMs(null);
            }
            
            saveStoreUnsafe();
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 手动立即触发任务（异步执行，不阻塞调用方）。
     * 
     * @param jobId 任务 ID
     * @return 触发成功返回 true，任务不存在或服务未运行返回 false
     */
    public boolean runJobNow(String jobId) {
        CronJob job;
        lock.readLock().lock();
        try {
            if (!running || jobExecutor == null) {
                return false;
            }
            job = findJobById(jobId);
            if (job == null) {
                return false;
            }
        } finally {
            lock.readLock().unlock();
        }
        
        // 在独立线程执行：避免阻塞调用方，也避免在 jobExecutor 内部自提交死锁
        Thread thread = new Thread(() -> executeJob(job, TRIGGER_MANUAL), THREAD_NAME + "-manual");
        thread.setDaemon(true);
        thread.start();
        return true;
    }
    
    /**
     * 列出所有任务。
     * 
     * @param includeDisabled 是否包含禁用的任务
     * @return 任务列表
     */
    public List<CronJob> listJobs(boolean includeDisabled) {
        lock.readLock().lock();
        try {
            if (includeDisabled) {
                return new ArrayList<>(store.getJobs());
            }
            
            return store.getJobs().stream()
                    .filter(CronJob::isEnabled)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取服务状态。
     * 
     * @return 状态信息 Map
     */
    public Map<String, Object> status() {
        lock.readLock().lock();
        try {
            long enabledCount = store.getJobs().stream()
                    .filter(CronJob::isEnabled)
                    .count();
            
            Map<String, Object> status = new HashMap<>();
            status.put("enabled", running);
            status.put("jobs", store.getJobs().size());
            status.put("enabled_jobs", enabledCount);
            return status;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 设置任务处理器。
     * 
     * @param handler 任务处理器
     */
    public void setOnJob(JobHandler handler) {
        this.onJob = handler;
    }
    
    /**
     * 从磁盘重新加载存储。
     */
    public void load() {
        lock.writeLock().lock();
        try {
            loadStore();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 生成唯一任务 ID。
     * 
     * 使用 SecureRandom 生成 8 字节随机数，转换为 16 位十六进制字符串。
     * 
     * @return 任务 ID
     */
    private String generateId() {
        byte[] bytes = new byte[ID_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(HEX_FORMAT, b));
        }
        return sb.toString();
    }
    
    /**
     * 检查服务是否正在运行。
     * 
     * @return 运行中返回 true，否则返回 false
     */
    public boolean isRunning() { 
        return running; 
    }
}