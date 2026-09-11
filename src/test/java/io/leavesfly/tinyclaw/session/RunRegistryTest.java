package io.leavesfly.tinyclaw.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunRegistry} 状态机、幂等与重启恢复测试。
 */
class RunRegistryTest {

    @TempDir
    Path tempDir;

    // ==================== 幂等登记 ====================

    @Test
    @DisplayName("同一 clientRequestId 重复提交返回既有活动执行，不产生新 run")
    void beginRun_SameClientRequestId_ReturnsExisting() {
        RunRegistry registry = new RunRegistry(null); // 纯内存模式
        RunRegistry.BeginResult first = registry.beginRun("cr-1", "web:default", "第一次提交");
        assertFalse(first.isRejected());
        assertFalse(first.duplicated);

        RunRegistry.BeginResult second = registry.beginRun("cr-1", "web:default", "重复提交");
        assertTrue(second.duplicated, "幂等命中应标记 duplicated");
        assertEquals(first.record.id, second.record.id, "应返回同一 runId");
    }

    @Test
    @DisplayName("clientRequestId 终态后可再次提交新执行")
    void beginRun_AfterTerminal_AllowsNewRun() {
        RunRegistry registry = new RunRegistry(null);
        RunRegistry.BeginResult first = registry.beginRun("cr-1", "web:default", "第一次");
        registry.completeRun(first.record.id);

        RunRegistry.BeginResult second = registry.beginRun("cr-1", "web:default", "终态后重发");
        assertFalse(second.duplicated, "终态后同一 clientRequestId 允许新执行");
        assertEquals("COMPLETED", first.record.status);
    }

    @Test
    @DisplayName("每会话同时最多一个活动 Web 根执行，超出返回忙碌拒绝")
    void beginRun_SessionBusy_Rejected() {
        RunRegistry registry = new RunRegistry(null);
        registry.beginRun("cr-1", "web:default", "运行中");

        RunRegistry.BeginResult busy = registry.beginRun("cr-2", "web:default", "第二个任务");
        assertTrue(busy.isRejected(), "同会话第二个并发执行应被拒绝");
        assertNotNull(busy.rejection);
        assertFalse(busy.duplicated);

        // 另一会话不受影响
        RunRegistry.BeginResult other = registry.beginRun("cr-3", "web:other", "其他会话");
        assertFalse(other.isRejected());
    }

    // ==================== 状态机 ====================

    @Test
    @DisplayName("正常完成路径：CREATED → RUNNING → WAITING_USER → RUNNING → COMPLETED")
    void lifecycle_NormalCompletion() {
        RunRegistry registry = new RunRegistry(null);
        RunRegistry.BeginResult r = registry.beginRun(null, "web:default", "任务");
        String runId = r.record.id;

        registry.transition(runId, RunRecord.Status.RUNNING);
        registry.transition(runId, RunRecord.Status.WAITING_USER);
        registry.transition(runId, RunRecord.Status.RUNNING);
        registry.completeRun(runId);

        RunRecord record = registry.get(runId);
        assertEquals("COMPLETED", record.status);
        assertNotNull(record.endedAt);
        assertFalse(registry.isSessionRunning("web:default"), "终态后会话应释放");
    }

    @Test
    @DisplayName("停止路径：CANCELLING 状态下结束记为 CANCELLED，不虚报完成")
    void lifecycle_Cancelling_EndsAsCancelled() {
        RunRegistry registry = new RunRegistry(null);
        RunRegistry.BeginResult r = registry.beginRun(null, "web:default", "任务");
        String runId = r.record.id;

        registry.transition(runId, RunRecord.Status.RUNNING);
        registry.transition(runId, RunRecord.Status.CANCELLING);
        registry.completeRun(runId);
        assertEquals("CANCELLED", registry.get(runId).status, "取消请求已发出时结束不记为 COMPLETED");

        // 异常路径同样如此
        RunRegistry.BeginResult r2 = registry.beginRun(null, "web:b", "任务2");
        registry.transition(r2.record.id, RunRecord.Status.RUNNING);
        registry.transition(r2.record.id, RunRecord.Status.CANCELLING);
        registry.failRun(r2.record.id, "boom");
        assertEquals("CANCELLED", registry.get(r2.record.id).status);
    }

    @Test
    @DisplayName("终态不可再流转；失败摘要落库")
    void terminal_IsImmutable() {
        RunRegistry registry = new RunRegistry(null);
        RunRegistry.BeginResult r = registry.beginRun(null, "web:default", "任务");
        registry.failRun(r.record.id, "连接超时");
        assertEquals("FAILED", r.record.status);

        registry.transition(r.record.id, RunRecord.Status.RUNNING);
        assertEquals("FAILED", r.record.status, "终态不可流转");

        assertEquals("连接超时", r.record.errorSummary);
    }

    // ==================== 持久化与重启恢复 ====================

    @Test
    @DisplayName("重启后遗留非终态记录标记 INTERRUPTED，不自动重试")
    void restart_MarksInterrupted() {
        Path runsDir = tempDir.resolve("runs");
        RunRegistry first = new RunRegistry(runsDir.toString());
        RunRegistry.BeginResult active = first.beginRun("cr-active", "web:default", "运行中的任务");
        first.transition(active.record.id, RunRecord.Status.WAITING_USER);
        // 终态样本用另一会话：同会话第二个活动执行会被忙碌拒绝
        RunRegistry.BeginResult done = first.beginRun("cr-done", "web:done", "已完成的任务");
        first.completeRun(done.record.id);

        // 模拟 JVM 重启：全新实例扫描同一目录
        RunRegistry restarted = new RunRegistry(runsDir.toString());
        RunRecord restoredActive = restarted.findByClientRequestId("cr-active");
        assertNotNull(restoredActive);
        assertEquals("INTERRUPTED", restoredActive.status, "重启后非终态记录应标记中断");
        assertTrue(restoredActive.errorSummary.contains("重启"));

        RunRecord restoredDone = restarted.findByClientRequestId("cr-done");
        assertNotNull(restoredDone);
        assertEquals("COMPLETED", restoredDone.status, "终态记录保持原状");

        assertFalse(restarted.isSessionRunning("web:default"), "重启后无活动执行");
    }

    @Test
    @DisplayName("损坏的记录文件被跳过，不中断启动")
    void restart_SkipsCorruptedFile() throws Exception {
        Path runsDir = tempDir.resolve("runs2");
        java.nio.file.Files.createDirectories(runsDir);
        java.nio.file.Files.writeString(runsDir.resolve("run-broken.json"), "not-json{");

        RunRegistry registry = new RunRegistry(runsDir.toString());
        assertNull(registry.get("run-broken"), "损坏记录不进入内存索引");
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("会话列表按创建序倒序；消息预览截断")
    void listBySession_DescByCreated() {
        RunRegistry registry = new RunRegistry(null);
        for (int i = 0; i < 3; i++) {
            RunRegistry.BeginResult r = registry.beginRun("cr-" + i, "web:default", "消息 " + i);
            registry.completeRun(r.record.id);
        }
        List<RunRecord> list = registry.listBySession("web:default", 10);
        assertEquals(3, list.size());
        assertEquals("消息 2", list.get(0).messagePreview, "最新的排在最前（同毫秒内由 seq 稳定排序）");
        assertTrue(listBySessionIsolated(registry));
    }

    private boolean listBySessionIsolated(RunRegistry registry) {
        return registry.listBySession("web:other", 10).isEmpty();
    }

    @Test
    @DisplayName("超长消息预览截断到 80 字符")
    void messagePreview_Truncated() {
        RunRegistry registry = new RunRegistry(null);
        String longMsg = "x".repeat(300);
        RunRegistry.BeginResult r = registry.beginRun(null, "web:default", longMsg);
        assertTrue(r.record.messagePreview.length() <= 81, "预览应截断（80 字符 + 省略号）");
    }
}
