package io.leavesfly.tinyclaw.collaboration;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 Agent 协同公共线程池
 * <p>统一管理所有协同策略的并发执行，避免各策略各自创建线程池。
 * 使用有界线程池（核心 4 / 最大 16）防止高并发协同场景下线程爆炸，
 * 当线程池满时采用 CallerRunsPolicy 降级为同步执行，保证任务不丢失。
 */
public class CollaborationExecutorPool {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    /** 核心线程数 */
    private static final int CORE_POOL_SIZE = 4;

    /** 最大线程数 */
    private static final int MAX_POOL_SIZE = 16;

    /** 空闲线程存活时间（秒） */
    private static final long KEEP_ALIVE_SECONDS = 60;

    /** 任务队列容量 */
    private static final int QUEUE_CAPACITY = 64;

    private final ExecutorService executor;

    public CollaborationExecutorPool() {
        this(CORE_POOL_SIZE, MAX_POOL_SIZE);
    }

    /**
     * 可配置的构造函数，允许外部指定线程池大小
     *
     * @param coreSize 核心线程数
     * @param maxSize  最大线程数
     */
    public CollaborationExecutorPool(int coreSize, int maxSize) {
        this.executor = new ThreadPoolExecutor(
                coreSize,
                maxSize,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName("collab-pool-" + THREAD_COUNTER.getAndIncrement());
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        logger.info("协同线程池已创建", Map.of(
                "coreSize", coreSize,
                "maxSize", maxSize,
                "queueCapacity", QUEUE_CAPACITY
        ));
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * 关闭线程池，等待已提交任务完成
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                logger.warn("协同线程池强制关闭");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
