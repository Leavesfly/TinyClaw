package io.leavesfly.tinyclaw.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
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
    private static final int ID_BYTE_LENGTH = 8;          // ID 字节长度
    private static final String HEX_FORMAT = "%02x";      // 十六进制格式
    
    private static final String STATUS_OK = "ok";         // 执行成功状态
    private static final String STATUS_ERROR = "error";   // 执行失败状态
    
    private static final String THREAD_NAME = "cron-service";  // 调度线程名称
    
    private final String storePath;                       // 存储文件路径
    private CronStore store;                              // 任务存储对象
    private JobHandler onJob;                             // 任务处理器
    private final ReentrantReadWriteLock lock;            // 读写锁
    private volatile boolean running;                     // 服务运行状态
    private Thread runnerThread;                          // 调度线程
    
    private final CronParser cronParser;                  // Cron 表达式解析器
    
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
        List<CronJob> dueJobs = collectDueJobs();
        
        for (CronJob job : dueJobs) {
            executeJob(job);
        }
    }
    
    /**
     * 收集到期的任务。
     * 
     * @return 到期任务列表
     */
    private List<CronJob> collectDueJobs() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return List.of();
            }
            
            long now = System.currentTimeMillis();
            List<CronJob> dueJobs = new ArrayList<>();
            
            for (CronJob job : store.getJobs()) {
                if (isJobDue(job, now)) {
                    dueJobs.add(job);
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
     * 调用任务处理器执行任务，更新任务状态和下次执行时间。
     * 
     * @param job 要执行的任务
     */
    private void executeJob(CronJob job) {
        long startTime = System.currentTimeMillis();
        String error = invokeJobHandler(job);
        
        updateJobState(job, startTime, error);
    }
    
    /**
     * 调用任务处理器。
     * 
     * @param job 要执行的任务
     * @return 错误信息，成功返回 null
     */
    private String invokeJobHandler(CronJob job) {
        try {
            if (onJob != null) {
                onJob.handle(job);
            }
            return null;
        } catch (Exception e) {
            String error = e.getMessage();
            logger.error("Job execution failed", Map.of(
                    "job_id", job.getId(),
                    "error", error
            ));
            return error;
        }
    }
    
    /**
     * 更新任务状态。
     * 
     * @param job 任务对象
     * @param startTime 开始执行时间
     * @param error 错误信息，成功为 null
     */
    private void updateJobState(CronJob job, long startTime, String error) {
        lock.writeLock().lock();
        try {
            CronJob storeJob = findJobById(job.getId());
            if (storeJob == null) {
                return;
            }
            
            storeJob.getState().setLastRunAtMs(startTime);
            storeJob.setUpdatedAtMs(System.currentTimeMillis());
            
            if (error != null) {
                storeJob.getState().setLastStatus(STATUS_ERROR);
                storeJob.getState().setLastError(error);
            } else {
                storeJob.getState().setLastStatus(STATUS_OK);
                storeJob.getState().setLastError(null);
            }
            
            handlePostExecution(storeJob);
            saveStoreUnsafe();
        } finally {
            lock.writeLock().unlock();
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
     */
    private void recomputeNextRuns() {
        long now = System.currentTimeMillis();
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
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
        } catch (Exception e) {
            logger.warn("Failed to load cron store, using empty", Map.of("error", e.getMessage()));
            store = new CronStore();
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
            Files.createDirectories(path.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(store);
            Files.writeString(path, json);
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
     * @param deliver 是否推送到通道
     * @param channel 目标通道
     * @param to 目标接收者
     * @return 创建的任务对象
     */
    public CronJob addJob(String name, CronSchedule schedule, String message, boolean deliver, 
                          String channel, String to) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean deleteAfterRun = CronSchedule.ScheduleKind.AT == schedule.getKind();
            
            CronJob job = createJob(name, schedule, message, deliver, channel, to, now, deleteAfterRun);
            
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
     * @param deliver 是否推送
     * @param channel 目标通道
     * @param to 目标接收者
     * @param now 当前时间戳
     * @param deleteAfterRun 执行后是否删除
     * @return 任务对象
     */
    private CronJob createJob(String name, CronSchedule schedule, String message, 
                             boolean deliver, String channel, String to, 
                             long now, boolean deleteAfterRun) {
        CronJob job = new CronJob();
        job.setId(generateId());
        job.setName(name);
        job.setEnabled(true);
        job.setSchedule(schedule);
        job.setPayload(new CronPayload(message, deliver, channel, to));
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(deleteAfterRun);
        job.getState().setNextRunAtMs(computeNextRun(schedule, now));
        return job;
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