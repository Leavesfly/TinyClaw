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
 * 定时服务 - 调度和执行任务
 * 
 * 这是TinyClaw定时任务系统的核心服务，负责：
 * 
 * 核心职责：
 * - 任务调度：解析cron表达式并计算下次执行时间
 * - 任务存储：持久化任务配置和状态信息
 * - 任务执行：按时触发任务执行回调
 * - 状态管理：跟踪任务的启用/禁用状态
 * - 并发控制：确保线程安全的任务操作
 * 
 * 技术实现：
 * - 使用cron-utils库解析和验证cron表达式
 * - 基于文件系统的任务持久化存储
 * - 独立线程运行任务调度循环
 * - 读写锁保护共享数据结构
 * - UUID生成器创建唯一任务标识
 * 
 * 设计特点：
 * - 高可靠性：具备错误恢复和日志记录能力
 * - 可扩展性：支持自定义任务处理器
 * - 性能优化：高效的调度算法和内存管理
 * - 易用性：简洁的API接口和清晰的状态反馈
 * 
 * 使用场景：
 * 1. 为CronTool提供底层调度支持
 * 2. 系统级定时维护任务执行
 * 3. 第三方集成的定时任务需求
 * 4. 复杂业务逻辑的定时触发
 */
public class CronService {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cron");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    // 复用 SecureRandom 实例，避免每次生成 ID 都创建新实例
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private final String storePath;
    private CronStore store;
    private JobHandler onJob;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean running = false;
    private Thread runnerThread;
    
    private final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    );
    
    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception;
    }
    
    public CronService(String storePath, JobHandler onJob) {
        this.storePath = storePath;
        this.onJob = onJob;
        loadStore();
    }
    
    public CronService(String storePath) {
        this(storePath, null);
    }
    
    /**
     * 启动定时服务
     */
    public void start() {
        lock.writeLock().lock();
        try {
            if (running) return;
            
            loadStore();
            recomputeNextRuns();
            saveStoreUnsafe();
            
            running = true;
            runnerThread = new Thread(this::runLoop, "cron-service");
            runnerThread.setDaemon(true);
            runnerThread.start();
            
            logger.info("Cron service started");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 停止定时服务
     */
    public void stop() {
        lock.writeLock().lock();
        try {
            if (!running) return;
            running = false;
            if (runnerThread != null) {
                runnerThread.interrupt();
            }
            logger.info("Cron service stopped");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void runLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
                checkJobs();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in cron loop", Map.of("error", e.getMessage()));
            }
        }
    }
    
    private void checkJobs() {
        List<CronJob> dueJobs;
        
        // 收集到期任务（需要写锁）
        lock.writeLock().lock();  
        try {
            if (!running) return;
            
            long now = System.currentTimeMillis();
            dueJobs = new ArrayList<>();
            
            for (CronJob job : store.getJobs()) {
                if (job.isEnabled() && job.getState().getNextRunAtMs() != null 
                        && job.getState().getNextRunAtMs() <= now) {
                    dueJobs.add(job);
                    // 清除下次运行时间以防止重复执行
                    job.getState().setNextRunAtMs(null);
                }
            }
            
            if (!dueJobs.isEmpty()) {
                saveStoreUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
        
        // 在锁外执行任务（避免长时间持有锁）
        for (CronJob job : dueJobs) {
            executeJob(job);
        }
    }
    
    private void executeJob(CronJob job) {
        long startTime = System.currentTimeMillis();
        String error = null;
        
        try {
            if (onJob != null) {
                onJob.handle(job);
            }
        } catch (Exception e) {
            error = e.getMessage();
            logger.error("Job execution failed", Map.of(
                    "job_id", job.getId(),
                    "error", error
            ));
        }
        
        // 更新任务状态
        lock.writeLock().lock();
        try {
            for (CronJob j : store.getJobs()) {
                if (j.getId().equals(job.getId())) {
                    j.getState().setLastRunAtMs(startTime);
                    j.setUpdatedAtMs(System.currentTimeMillis());
                    
                    if (error != null) {
                        j.getState().setLastStatus("error");
                        j.getState().setLastError(error);
                    } else {
                        j.getState().setLastStatus("ok");
                        j.getState().setLastError(null);
                    }
                    
                    // 计算下次运行时间
                    if (CronSchedule.ScheduleKind.AT == j.getSchedule().getKind()) {
                        if (j.isDeleteAfterRun()) {
                            removeJobUnsafe(j.getId());
                        } else {
                            j.setEnabled(false);
                            j.getState().setNextRunAtMs(null);
                        }
                    } else {
                        Long nextRun = computeNextRun(j.getSchedule(), System.currentTimeMillis());
                        j.getState().setNextRunAtMs(nextRun);
                    }
                    break;
                }
            }
            saveStoreUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private Long computeNextRun(CronSchedule schedule, long nowMs) {
        if (CronSchedule.ScheduleKind.AT == schedule.getKind()) {
            if (schedule.getAtMs() != null && schedule.getAtMs() > nowMs) {
                return schedule.getAtMs();
            }
            return null;
        }
        
        if (CronSchedule.ScheduleKind.EVERY == schedule.getKind()) {
            if (schedule.getEveryMs() == null || schedule.getEveryMs() <= 0) {
                return null;
            }
            return nowMs + schedule.getEveryMs();
        }
        
        if (CronSchedule.ScheduleKind.CRON == schedule.getKind()) {
            if (schedule.getExpr() == null || schedule.getExpr().isEmpty()) {
                return null;
            }
            
            try {
                Cron cron = cronParser.parse(schedule.getExpr());
                ExecutionTime executionTime = ExecutionTime.forCron(cron);
                ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
                Optional<ZonedDateTime> next = executionTime.nextExecution(now);
                if (next.isPresent()) {
                    return next.get().toInstant().toEpochMilli();
                }
            } catch (Exception e) {
                logger.error("Failed to compute next run for cron expr", Map.of(
                        "expr", schedule.getExpr(),
                        "error", e.getMessage()
                ));
            }
        }
        
        return null;
    }
    
    private void recomputeNextRuns() {
        long now = System.currentTimeMillis();
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }
    
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
     * 添加新任务
     */
    public CronJob addJob(String name, CronSchedule schedule, String message, boolean deliver, 
                          String channel, String to) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean deleteAfterRun = CronSchedule.ScheduleKind.AT == schedule.getKind();
            
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
     * 删除任务
     */
    public boolean removeJob(String jobId) {
        lock.writeLock().lock();
        try {
            return removeJobUnsafe(jobId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private boolean removeJobUnsafe(String jobId) {
        boolean removed = store.getJobs().removeIf(j -> j.getId().equals(jobId));
        if (removed) {
            saveStoreUnsafe();
        }
        return removed;
    }
    
    /**
     * 启用或禁用任务
     */
    public CronJob enableJob(String jobId, boolean enabled) {
        lock.writeLock().lock();
        try {
            for (CronJob job : store.getJobs()) {
                if (job.getId().equals(jobId)) {
                    job.setEnabled(enabled);
                    job.setUpdatedAtMs(System.currentTimeMillis());
                    
                    if (enabled) {
                        job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), System.currentTimeMillis()));
                    } else {
                        job.getState().setNextRunAtMs(null);
                    }
                    
                    saveStoreUnsafe();
                    return job;
                }
            }
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 列出所有任务
     */
    public List<CronJob> listJobs(boolean includeDisabled) {
        lock.readLock().lock();
        try {
            if (includeDisabled) {
                return new ArrayList<>(store.getJobs());
            }
            List<CronJob> enabled = new ArrayList<>();
            for (CronJob job : store.getJobs()) {
                if (job.isEnabled()) {
                    enabled.add(job);
                }
            }
            return enabled;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取服务状态
     */
    public Map<String, Object> status() {
        lock.readLock().lock();
        try {
            int enabledCount = 0;
            for (CronJob job : store.getJobs()) {
                if (job.isEnabled()) enabledCount++;
            }
            
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
     * 设置任务处理器
     */
    public void setOnJob(JobHandler handler) {
        this.onJob = handler;
    }
    
    /**
     * 从磁盘加载存储
     */
    public void load() {
        lock.writeLock().lock();
        try {
            loadStore();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private String generateId() {
        byte[] bytes = new byte[8];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    public boolean isRunning() { return running; }
}