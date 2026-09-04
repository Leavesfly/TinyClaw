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
