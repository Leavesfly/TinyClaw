package io.leavesfly.tinyclaw.evolution.reflection;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具调用事件记录器（异步单线程 writer）。
 *
 * <p>设计目标：零侵入 / 不阻塞主链路 / 失败降级不影响业务。
 *
 * <p>工作模式：
 * <ol>
 *   <li>主线程调用 {@link #record(ToolCallEvent)} 将事件投递到有界队列；</li>
 *   <li>队列满时丢弃并计数，永不阻塞业务（drop-oldest 策略）；</li>
 *   <li>后台单线程 writer 从队列取出事件，批量落盘到 {@link ToolCallLogStore}；</li>
 *   <li>{@link #close()} 会 flush 剩余事件后退出。</li>
 * </ol>
 *
 * <p>同时提供一个可选的实时健康度聚合回调，便于在内存中维护 N 分钟滑动窗口的
 * 统计指标（由 {@link ToolHealthAggregator} 消费）。
 */
public class ToolCallRecorder implements AutoCloseable {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("reflection.recorder");

    /** 有界队列容量（经验值，足够应对瞬时 10k/s 峰值） */
    private static final int QUEUE_CAPACITY = 8192;
    /** 批量写入大小 */
    private static final int BATCH_SIZE = 64;
    /** 批量 flush 间隔（毫秒） */
    private static final long FLUSH_INTERVAL_MS = 1000L;

    private final ToolCallLogStore store;
    private final ToolHealthAggregator aggregator;
    private final BlockingQueue<ToolCallEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** 失败检测器（可选，注入后每个事件会同步触发失败检测）。 */
    private volatile FailureDetector failureDetector;

    /** 被丢弃的事件数（队列满时） */
    private final AtomicLong droppedCount = new AtomicLong(0);
    /** 成功落盘数 */
    private final AtomicLong persistedCount = new AtomicLong(0);

    public ToolCallRecorder(ToolCallLogStore store, ToolHealthAggregator aggregator) {
        this.store = store;
        this.aggregator = aggregator;
        this.worker = new Thread(this::drainLoop, "tinyclaw-reflection-recorder");
        this.worker.setDaemon(true);
        this.worker.start();
        logger.info("ToolCallRecorder started", Map.of("queue_capacity", QUEUE_CAPACITY));
    }

    /**
     * 注入失败检测器，使每个事件在实时聚合后触发失败检测。
     */
    public void setFailureDetector(FailureDetector failureDetector) {
        this.failureDetector = failureDetector;
    }

    /**
     * 投递一条事件。
     *
     * <p>队列满时静默丢弃并计数，永不抛异常、永不阻塞。
     */
    public void record(ToolCallEvent event) {
        if (event == null || !running.get()) return;
        if (!queue.offer(event)) {
            droppedCount.incrementAndGet();
        }
        // 实时聚合（内存操作，极快）
        if (aggregator != null) {
            try {
                aggregator.onEvent(event);
            } catch (Exception e) {
                logger.warn("Aggregator onEvent failed: " + e.getMessage());
            }
        }
        // 实时失败检测（内存操作，极快）
        FailureDetector detector = this.failureDetector;
        if (detector != null) {
            try {
                detector.onEvent(event);
            } catch (Exception e) {
                logger.warn("FailureDetector onEvent failed: " + e.getMessage());
            }
        }
    }

    /** 返回丢弃的事件数（监控用）。 */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /** 返回已持久化的事件数（监控用）。 */
    public long getPersistedCount() {
        return persistedCount.get();
    }

    // ==================== 内部 worker ====================

    private void drainLoop() {
        java.util.List<ToolCallEvent> batch = new java.util.ArrayList<>(BATCH_SIZE);
        while (running.get() || !queue.isEmpty()) {
            try {
                ToolCallEvent head = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (head != null) {
                    batch.add(head);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                }
                if (!batch.isEmpty()) {
                    try {
                        store.appendBatch(batch);
                        persistedCount.addAndGet(batch.size());
                    } catch (Exception e) {
                        logger.error("Failed to persist batch",
                                Map.of("size", batch.size(), "error", e.getMessage()));
                    } finally {
                        batch.clear();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Recorder worker error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try {
            worker.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("ToolCallRecorder stopped",
                Map.of("persisted", persistedCount.get(), "dropped", droppedCount.get()));
    }
}
