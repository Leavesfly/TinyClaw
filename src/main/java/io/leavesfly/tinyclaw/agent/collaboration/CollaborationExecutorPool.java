package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 Agent 协同公共线程池
 * 统一管理所有协同策略的并发执行，避免各策略各自创建线程池
 */
public class CollaborationExecutorPool {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private final ExecutorService executor;

    public CollaborationExecutorPool() {
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("collab-pool-" + THREAD_COUNTER.getAndIncrement());
            return thread;
        });
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
