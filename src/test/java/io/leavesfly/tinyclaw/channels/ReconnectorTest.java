package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Reconnector} 测试。
 *
 * <p>重点是三条以前无法验证的可靠性属性：<b>退避不会溢出成负延迟</b>、<b>重试没有次数上限</b>、
 * <b>同一次断线不会排出多条重连链</b>。此前这段逻辑散在各通道的 WebSocket 回调里，
 * 只能靠真的断网才能观察。</p>
 */
class ReconnectorTest {

    private static final TinyClawLogger LOGGER = TinyClawLogger.getLogger("test.reconnect");

    private Reconnector reconnector;

    @AfterEach
    void tearDown() {
        if (reconnector != null) {
            reconnector.stop();
        }
    }

    /** 构造一个退避区间极小的实例，避免测试真的等上几十秒。 */
    private Reconnector fast(Reconnector.ConnectAction action, AtomicBoolean active,
                            long initialMs, long maxMs) {
        reconnector = new Reconnector("test", LOGGER, active::get, action, initialMs, maxMs);
        return reconnector;
    }

    // ==================== 退避曲线 ====================

    @Test
    @DisplayName("退避按次数翻倍，到上限后不再增长")
    void delayFor_DoublesUntilCapped() {
        Reconnector r = fast(() -> { }, new AtomicBoolean(true), 1000L, 60_000L);

        assertEquals(1000L, r.delayFor(1));
        assertEquals(2000L, r.delayFor(2));
        assertEquals(4000L, r.delayFor(3));
        assertEquals(8000L, r.delayFor(4));
        assertEquals(16_000L, r.delayFor(5));
        assertEquals(32_000L, r.delayFor(6));
        assertEquals(60_000L, r.delayFor(7), "64s 应被压到 60s 上限");
        assertEquals(60_000L, r.delayFor(8));
    }

    @Test
    @DisplayName("次数极大时退避仍为正且等于上限：无限重连下不能移位溢出成负延迟")
    void delayFor_NeverOverflows() {
        Reconnector r = fast(() -> { }, new AtomicBoolean(true), 1000L, 60_000L);

        // 用 `initial << (attempt - 1)` 写法时，attempt 到 64 附近就会溢出成负数，
        // 负延迟会让调度器立刻执行，退化成不带间隔的忙重连
        for (int attempt : new int[]{31, 32, 33, 63, 64, 65, 1000, Integer.MAX_VALUE}) {
            long delay = r.delayFor(attempt);
            assertTrue(delay > 0, "attempt=" + attempt + " 的退避必须为正，实际 " + delay);
            assertEquals(60_000L, delay, "attempt=" + attempt + " 应停在上限");
        }
    }

    // ==================== 无限重连 ====================

    @Test
    @DisplayName("连接持续失败时无限重试，不存在次数上限")
    void schedule_RetriesWithoutAttemptLimit() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        // 旧实现在第 10 次后永久放弃；这里断言远超 10 次仍在重试
        CountDownLatch past10 = new CountDownLatch(13);
        Reconnector r = fast(() -> {
            invocations.incrementAndGet();
            past10.countDown();
            throw new IllegalStateException("connect refused");
        }, new AtomicBoolean(true), 1L, 2L);
        r.start();

        r.schedule();

        assertTrue(past10.await(5, TimeUnit.SECONDS),
                "应持续重试到 13 次以上，实际只有 " + invocations.get() + " 次");
        assertTrue(r.attempts() >= 13, "失败计数应随重试累加");
    }

    @Test
    @DisplayName("连接成功后退避归零，下次断线重新从初始延迟开始")
    void onConnected_ResetsBackoff() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch reset = new CountDownLatch(1);
        // 前两次失败、第三次成功。onConnected 由连接动作所在线程调用，
        // 与真实用法（onOpen 回调线程）一致；成功后不再排新任务，
        // 重连链自然收敛，不会在断言之后又把计数加回去
        Reconnector r = fast(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("still down");
            }
            reconnector.onConnected();
            reset.countDown();
        }, new AtomicBoolean(true), 1L, 5L);
        r.start();

        r.schedule();

        assertTrue(reset.await(5, TimeUnit.SECONDS),
                "第三次尝试应连接成功，实际尝试次数 " + calls.get());
        assertEquals(0, r.attempts(), "连接成功后失败计数必须归零");
        assertEquals(1L, r.delayFor(1), "下次断线应回到初始延迟");
    }

    // ==================== 在途去重 ====================

    @Test
    @DisplayName("同一次断线重复排队只产生一次重连：onClosed 与 onFailure 可能先后到达")
    void schedule_CoalescesConcurrentRequests() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        Reconnector r = fast(() -> {
            invocations.incrementAndGet();
            first.countDown();
        }, new AtomicBoolean(true), 50L, 50L);
        r.start();

        r.schedule();
        r.schedule();
        r.schedule();

        // 先等首次重连真的跑起来（避免因调度延迟而空跑过），
        // 再给被重复排入的任务足够时间暴露
        assertTrue(first.await(5, TimeUnit.SECONDS), "首次重连未执行");
        Thread.sleep(200);
        assertEquals(1, invocations.get(), "三次排队应合并为一次重连尝试");
    }

    // ==================== 停止与禁用 ====================

    @Test
    @DisplayName("stop 后不再发起重连")
    void stop_CancelsPendingReconnect() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        Reconnector r = fast(invocations::incrementAndGet, new AtomicBoolean(true), 100L, 100L);
        r.start();

        r.schedule();
        r.stop();

        Thread.sleep(300);
        assertEquals(0, invocations.get(), "stop 应丢弃待执行的重连任务");
    }

    @Test
    @DisplayName("stop 之后再排队不抛异常也不重连")
    void schedule_AfterStop_IsNoop() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        Reconnector r = fast(invocations::incrementAndGet, new AtomicBoolean(true), 1L, 1L);
        r.start();
        r.stop();

        r.schedule();
        r.connectNow();

        Thread.sleep(100);
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("disable 后永久停止重连：不可恢复错误不该持续冲击对端")
    void disable_StopsReconnectingPermanently() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        Reconnector r = fast(invocations::incrementAndGet, new AtomicBoolean(true), 1L, 1L);
        r.start();

        r.disable("invalid token");

        r.schedule();
        r.connectNow();
        Thread.sleep(100);

        assertTrue(r.isDisabled());
        assertEquals(0, invocations.get(), "被禁用后不应再发起任何连接");
    }

    @Test
    @DisplayName("通道已不再运行时不重连")
    void schedule_WhenInactive_IsNoop() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        AtomicBoolean active = new AtomicBoolean(true);
        Reconnector r = fast(invocations::incrementAndGet, active, 1L, 1L);
        r.start();

        active.set(false);
        r.schedule();
        r.connectNow();

        Thread.sleep(100);
        assertEquals(0, invocations.get());
    }

    // ==================== 首次连接 ====================

    @Test
    @DisplayName("connectNow 立即连接且不计入退避次数")
    void connectNow_ExecutesImmediatelyWithoutCountingAttempt() throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        Reconnector r = fast(connected::countDown, new AtomicBoolean(true), 60_000L, 60_000L);
        r.start();

        r.connectNow();

        assertTrue(connected.await(2, TimeUnit.SECONDS), "首次连接不应等待退避延迟");
        assertEquals(0, r.attempts(), "首次连接不应计入失败次数");
    }

    @Test
    @DisplayName("首次连接失败后自动转入退避重试")
    void connectNow_FailureFallsBackToBackoff() throws InterruptedException {
        CountDownLatch attempted = new CountDownLatch(3);
        Reconnector r = fast(() -> {
            attempted.countDown();
            throw new IllegalStateException("register failed");
        }, new AtomicBoolean(true), 1L, 3L);
        r.start();

        r.connectNow();

        assertTrue(attempted.await(5, TimeUnit.SECONDS), "首连失败后应继续重试");
    }

    @Test
    @DisplayName("start 后重新可用：stop 再 start 能继续工作")
    void restart_ResumesReconnecting() throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        Reconnector r = fast(connected::countDown, new AtomicBoolean(true), 1L, 1L);
        r.start();
        r.stop();
        r.start();

        r.connectNow();

        assertTrue(connected.await(2, TimeUnit.SECONDS));
        assertFalse(r.isDisabled());
    }

    // ==================== 通道接线护栏 ====================

    /** 依赖长连接的通道：它们的 onClosed 必须导向重连。 */
    private static final List<String> WEBSOCKET_CHANNELS =
            List.of("DingTalkChannel", "FeishuChannel", "DiscordChannel");

    /**
     * 锁住引发本次修复的那条不变量。
     *
     * <p>飞书通道曾只在 {@code onFailure} 重连，而对端主动关连走的是 {@code onClosed}，
     * 于是最常见的断线场景下通道会静默停摆；而 {@code isRunning()} 仍返回 true，
     * 对外表现为“健康但收不到消息”。这类缺失既不报错也无法单测，只能靠结构断言卡住。</p>
     */
    @Test
    @DisplayName("三个长连接通道的 onClosed 均必须导向重连或显式禁用")
    void webSocketChannels_ReconnectOnGracefulClose() throws IOException {
        for (String channel : WEBSOCKET_CHANNELS) {
            String body = onClosedBody(channel);
            assertTrue(body.contains("reconnector.schedule()") || body.contains("reconnector.disable("),
                    channel + " 的 onClosed 未导向重连：对端正常关连后通道会静默停摆。"
                            + "当前 onClosed 体为：" + body);
        }
    }

    @Test
    @DisplayName("三个长连接通道的 onFailure 均必须导向重连")
    void webSocketChannels_ReconnectOnFailure() throws IOException {
        for (String channel : WEBSOCKET_CHANNELS) {
            String body = methodBody(channel, "public void onFailure(");
            assertTrue(body.contains("reconnector.schedule()"),
                    channel + " 的 onFailure 未导向重连，当前体为：" + body);
        }
    }

    private String onClosedBody(String channel) throws IOException {
        return methodBody(channel, "public void onClosed(");
    }

    /** 取出指定通道源文件中某方法的方法体（按大括号配平截取）。 */
    private String methodBody(String channel, String signature) throws IOException {
        Path source = Path.of("src/main/java/io/leavesfly/tinyclaw/channels/" + channel + ".java");
        String src = Files.readString(source);
        int start = src.indexOf(signature);
        assertTrue(start >= 0, channel + " 找不到 " + signature + "：长连接通道必须处理该回调");

        int depth = 0;
        for (int i = src.indexOf('{', start); i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError(channel + " 的 " + signature + " 方法体大括号不配平");
    }
}
