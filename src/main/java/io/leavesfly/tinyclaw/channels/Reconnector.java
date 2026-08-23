package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 长连接通道的重连调度器。
 *
 * <p>钉钉 Stream、飞书 WebSocket、Discord Gateway 三个通道都依赖一条长连接，断线后必须自己爬起来。
 * 此前这段逻辑各写一份且已经漂移：飞书只在 {@code onFailure} 重连（对端正常关闭走的是
 * {@code onClosed}，于是最常见的断线场景不重连），Discord 干脆没有重连，钉钉则固定 5 秒重试
 * 10 次后永久放弃。三者断连后 {@code isRunning()} 仍返回 true，对外表现为「健康但收不到消息」。</p>
 *
 * <p>本类把重连策略收敛成唯一实现：<b>指数退避、封顶后无限重试</b>。放弃重连的唯一途径是
 * 调用方明确判定该错误不可恢复（如 Discord 的 token 无效），主动调用 {@link #disable(String)}。</p>
 *
 * <p>使用方式：{@link #start()} 建调度线程 → 连接成功回调里 {@link #onConnected()} 重置退避
 * → 断线回调里 {@link #schedule()} 排队重连 → 通道 stop 时 {@link #stop()}。</p>
 */
public final class Reconnector {

    /** 一次连接尝试。抛出异常表示本次失败，由调度器按退避重新排队。 */
    public interface ConnectAction {
        void connect() throws Exception;
    }

    static final long DEFAULT_INITIAL_DELAY_MS = 1000L;
    static final long DEFAULT_MAX_DELAY_MS = 60_000L;

    private final String description;
    private final TinyClawLogger logger;
    private final BooleanSupplier active;
    private final ConnectAction action;
    private final long initialDelayMs;
    private final long maxDelayMs;

    private final AtomicInteger attempts = new AtomicInteger();
    /** 是否已有一次重连在途，避免同一次断线排出多条重连链 */
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;
    private volatile String disabledReason;

    /**
     * @param description 日志与线程名用的通道描述，如 "飞书 WebSocket"
     * @param logger      所属通道的 logger
     * @param active      通道是否仍希望保持连接；返回 false 时不再重连
     * @param action      执行一次连接的动作
     */
    public Reconnector(String description, TinyClawLogger logger,
                       BooleanSupplier active, ConnectAction action) {
        this(description, logger, active, action, DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS);
    }

    /** 可指定退避区间的构造器，供测试使用以免真的等上几十秒。 */
    Reconnector(String description, TinyClawLogger logger, BooleanSupplier active,
                ConnectAction action, long initialDelayMs, long maxDelayMs) {
        this.description = description;
        this.logger = logger;
        this.active = active;
        this.action = action;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** 创建调度线程。重复调用只保留第一个调度器。 */
    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        disabledReason = null;
        attempts.set(0);
        pending.set(false);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, description + "-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 停止调度并丢弃待执行的重连任务。 */
    public synchronized void stop() {
        ScheduledExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
            executor = null;
        }
        pending.set(false);
        attempts.set(0);
    }

    /** 立即发起一次连接（不计入退避次数），用于首次连接，失败后自动转入退避重试。 */
    public void connectNow() {
        enqueue(0L);
    }

    /** 排队一次重连，延迟按已失败次数指数退避。 */
    public void schedule() {
        if (!canReconnect()) {
            return;
        }
        int attempt = attempts.incrementAndGet();
        long delay = delayFor(attempt);
        logger.info(description + " 将重连", Map.of(
                "attempt", String.valueOf(attempt),
                "delay_ms", String.valueOf(delay)));
        enqueue(delay);
    }

    /** 连接确认可用后调用，重置退避。 */
    public void onConnected() {
        int previous = attempts.getAndSet(0);
        if (previous > 0) {
            logger.info(description + " 已重连成功", Map.of("after_attempts", String.valueOf(previous)));
        }
    }

    /**
     * 永久停止重连。仅用于确定重试无意义的错误（如凭据无效、权限未开通），
     * 继续重试只会持续冲击对端。
     */
    public void disable(String reason) {
        disabledReason = reason;
        pending.set(false);
        logger.error(description + " 遇到不可恢复错误，停止重连", Map.of("reason", reason));
    }

    /** 是否已被 {@link #disable(String)} 永久关停。 */
    public boolean isDisabled() {
        return disabledReason != null;
    }

    /** 当前累计的连续失败次数，连接成功后归零。 */
    public int attempts() {
        return attempts.get();
    }

    /**
     * 计算第 {@code attempt} 次重连的延迟：首次为初始延迟，其后翻倍，到上限后不再增长。
     *
     * <p>用循环逐次翻倍并在触及上限时立刻收敛，因此 attempt 再大也不会把 long 移位成负数
     * —— 无限重连下 attempt 会一直涨，{@code initialDelay << (attempt - 1)} 那种写法迟早溢出，
     * 变成负延迟后退化成不带间隔的忙重连。</p>
     */
    long delayFor(int attempt) {
        long delay = initialDelayMs;
        for (int i = 1; i < attempt && delay < maxDelayMs; i++) {
            delay <<= 1;
        }
        return Math.min(delay, maxDelayMs);
    }

    private boolean canReconnect() {
        return disabledReason == null && active.getAsBoolean();
    }

    private void enqueue(long delayMs) {
        if (!canReconnect()) {
            return;
        }
        ScheduledExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return;
        }
        // onClosed 与 onFailure 可能先后到达，只让一条重连链在途
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        try {
            current.schedule(this::runAttempt, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.set(false);
        }
    }

    private void runAttempt() {
        pending.set(false);
        if (!canReconnect()) {
            return;
        }
        try {
            action.connect();
        } catch (Exception e) {
            logger.error(description + " 连接失败", Map.of(
                    "attempt", String.valueOf(attempts.get()),
                    "error", String.valueOf(e.getMessage())));
            // 失败后继续退避重排，否则通道会静默停摆
            schedule();
        }
    }
}
