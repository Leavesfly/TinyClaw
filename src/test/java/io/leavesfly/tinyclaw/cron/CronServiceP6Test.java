package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6 自动化闭环的调度层测试：时区、执行预览、[SKIPPED] 跳过语义、
 * RunDetailCollector 详情合入、CronPayload 扩展字段与旧 JSON 兼容。
 */
@DisplayName("CronService P6 调度扩展测试")
class CronServiceP6Test {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 时区 ====================

    @Test
    @DisplayName("同一 cron 表达式在不同 tz 下执行点不同（时区生效，非系统默认硬编码）")
    void cronNextRun_RespectsScheduleTimezone() {
        CronService service = new CronService(tempDir.resolve("jobs.json").toString());
        // 每天 08:00 的表达式：东京 +9 与洛杉矶（冬令时 -8）显著不同
        CronSchedule tokyo = CronSchedule.cron("0 8 * * *");
        tokyo.setTz("Asia/Tokyo");
        CronSchedule la = CronSchedule.cron("0 8 * * *");
        la.setTz("America/Los_Angeles");

        List<Long> tokyoRuns = service.previewNextRuns(tokyo, 1);
        List<Long> laRuns = service.previewNextRuns(la, 1);
        assertEquals(1, tokyoRuns.size());
        assertEquals(1, laRuns.size());
        assertEquals(8, ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(tokyoRuns.get(0)), ZoneId.of("Asia/Tokyo")).getHour(),
                "东京时区的执行点应在当地 8 点");
        assertEquals(8, ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(laRuns.get(0)), ZoneId.of("America/Los_Angeles")).getHour(),
                "洛杉矶时区的执行点应在当地 8 点");
        long diffHours = Math.abs(tokyoRuns.get(0) - laRuns.get(0)) / 3_600_000L;
        // 时区偏移差 16h，但两个「下一个当地 8 点」受日界影响可能对齐为 8h（16h ≡ -8h mod 24h）；
        // 若 tz 被忽略（双双回退系统默认），两执行点完全相同 → diff=0，此断言即失效
        assertTrue(diffHours == 8 || diffHours == 16,
                "两个时区的执行点应相差 8 或 16 小时（实际 " + diffHours + "），0 表示 tz 被忽略");

        // 非法时区回退系统默认，不抛异常
        CronSchedule badTz = CronSchedule.cron("0 8 * * *");
        badTz.setTz("Not/A_Zone");
        assertEquals(1, service.previewNextRuns(badTz, 1).size(), "非法时区应回退系统默认而非报错");
    }

    // ==================== 执行预览 ====================

    @Test
    @DisplayName("previewNextRuns：EVERY 返回等间隔序列；AT 已过期返回空；非法 cron 返回空")
    void previewNextRuns_EveryAtAndInvalid() {
        CronService service = new CronService(tempDir.resolve("jobs.json").toString());

        List<Long> everyRuns = service.previewNextRuns(CronSchedule.every(60_000), 3);
        assertEquals(3, everyRuns.size());
        long gap = everyRuns.get(1) - everyRuns.get(0);
        assertTrue(gap >= 60_000L && gap <= 60_001L,
                "间隔应等于 everyMs（±1ms 为预览严格递进引入）：实际 " + gap);

        // AT 在未来：只返回一个点
        CronSchedule futureAt = CronSchedule.at(System.currentTimeMillis() + 3_600_000);
        assertEquals(1, service.previewNextRuns(futureAt, 3).size());

        // AT 已过期：预览为空
        CronSchedule pastAt = CronSchedule.at(System.currentTimeMillis() - 1_000);
        assertTrue(service.previewNextRuns(pastAt, 3).isEmpty());

        // 非法 cron 表达式：空列表（Handler 据此回 400）
        assertTrue(service.previewNextRuns(CronSchedule.cron("not a cron"), 2).isEmpty());

        // count 上限与零值
        assertEquals(10, service.previewNextRuns(CronSchedule.every(1000), 99).size());
        assertTrue(service.previewNextRuns(CronSchedule.every(1000), 0).isEmpty());
    }

    // ==================== [SKIPPED] 与 RunDetailCollector ====================

    @Test
    @DisplayName("handler 返回 [SKIPPED] 前缀：历史记为 skipped 状态而非 ok/error")
    void skippedResult_RecordedAsSkippedStatus() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CronService service = new CronService(tempDir.resolve("skip.json").toString(), job -> {
            latch.countDown();
            return "[SKIPPED] session busy: test";
        });
        service.start();
        try {
            CronJob job = service.addJob("skip-job", CronSchedule.every(50), "m", "", "");
            assertTrue(latch.await(15, TimeUnit.SECONDS), "job 应在超时内触发");
            awaitLastStatus(service, job.getId(), CronRunRecord.STATUS_SKIPPED);

            CronJob stored = service.findJobByName("skip-job");
            CronRunRecord first = stored.getState().getHistory().get(0);
            assertEquals(CronRunRecord.STATUS_SKIPPED, first.getStatus(),
                    "跳过不是失败，也不是成功，应有独立状态");
            assertTrue(first.getResult().contains("session busy"));
            assertNull(first.getError());
            assertEquals(CronRunRecord.STATUS_SKIPPED, stored.getState().getLastStatus());
        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("RunDetailCollector：runId/sessionKey/artifactIds/deliveryStatus 合入历史")
    void runDetailCollector_MergedIntoHistory() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CronService service = new CronService(tempDir.resolve("detail.json").toString(), job -> {
            latch.countDown();
            return "done";
        });
        service.setRunDetailCollector((jobId, startedAtMs) -> new CronService.RunDetail(
                "run1234567890", "cron-" + jobId, List.of("art0011223344", "art5566778899"),
                CronRunRecord.DELIVERY_GENERATED));
        service.start();
        try {
            CronJob job = service.addJob("detail-job", CronSchedule.every(50), "m", "", "");
            assertTrue(latch.await(15, TimeUnit.SECONDS), "job 应在超时内触发");
            awaitHistory(service, job.getId());

            CronRunRecord first = service.findJobByName("detail-job").getState().getHistory().get(0);
            assertEquals("run1234567890", first.getRunId());
            assertEquals("cron-" + job.getId(), first.getSessionKey());
            assertEquals(List.of("art0011223344", "art5566778899"), first.getArtifactIds());
            assertEquals(CronRunRecord.DELIVERY_GENERATED, first.getDeliveryStatus());
            assertEquals("ok", first.getStatus());
        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("收集器返回 null（未启用 P6 编排）：历史无补充字段，行为与旧版一致")
    void runDetailCollector_NullDetail_NoExtraFields() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CronService service = new CronService(tempDir.resolve("nocollector.json").toString(), job -> {
            latch.countDown();
            return "ok";
        });
        service.setRunDetailCollector((jobId, startedAtMs) -> null);
        service.start();
        try {
            CronJob job = service.addJob("plain-job", CronSchedule.every(50), "m", "", "");
            assertTrue(latch.await(15, TimeUnit.SECONDS), "job 应在超时内触发");
            awaitHistory(service, job.getId());

            CronRunRecord first = service.findJobByName("plain-job").getState().getHistory().get(0);
            assertNull(first.getRunId());
            assertNull(first.getSessionKey());
            assertNull(first.getArtifactIds());
            assertEquals("ok", first.getStatus());
        } finally {
            service.stop();
        }
    }

    // ==================== CronPayload 扩展与旧数据兼容 ====================

    @Test
    @DisplayName("CronPayload 旧 JSON（仅 message/channel/to）反序列化：扩展字段为 null，语义不变")
    void legacyPayloadJson_DeserializesWithNullExtras() throws Exception {
        String legacy = "{\"kind\":\"agent_turn\",\"message\":\"hi\",\"channel\":\"telegram\",\"to\":\"123\"}";
        CronPayload payload = MAPPER.readValue(legacy, CronPayload.class);
        assertEquals("hi", payload.getMessage());
        assertEquals("telegram", payload.getChannel());
        assertNull(payload.getRunMode());
        assertNull(payload.getProjectId());
        assertNull(payload.getAttachmentIds());
        assertNull(payload.getOutputTarget());
        assertEquals(CronPayload.RUN_MODE_NEW_SESSION, payload.effectiveRunMode(),
                "旧数据默认新会话模式");
    }

    @Test
    @DisplayName("CronPayload 扩展字段序列化往返 + 非法 runMode 归一化为 NEW_SESSION")
    void payloadExtendedFields_Roundtrip() throws Exception {
        CronPayload payload = new CronPayload("run it", null, null);
        payload.setRunMode("CONTINUE_SESSION");
        payload.setProjectId("proj_ab12");
        payload.setSourceSessionKey("web:1725968000000");
        payload.setAttachmentIds(List.of("att0000000123"));
        payload.setOutputTarget("生成日报并发送摘要");

        CronPayload round = MAPPER.readValue(
                MAPPER.writeValueAsString(payload), CronPayload.class);
        assertEquals("CONTINUE_SESSION", round.getRunMode());
        assertEquals("proj_ab12", round.getProjectId());
        assertEquals("web:1725968000000", round.getSourceSessionKey());
        assertEquals(List.of("att0000000123"), round.getAttachmentIds());
        assertEquals("生成日报并发送摘要", round.getOutputTarget());
        assertEquals(CronPayload.RUN_MODE_CONTINUE_SESSION, round.effectiveRunMode());

        round.setRunMode("WHATEVER");
        assertEquals(CronPayload.RUN_MODE_NEW_SESSION, round.effectiveRunMode(),
                "非法 runMode 归一化为 NEW_SESSION");
    }

    @Test
    @DisplayName("addJob 完整 payload 重载：扩展字段落盘并在重启后保留")
    void addJobWithPayload_PersistsExtendedFields() throws Exception {
        Path store = tempDir.resolve("payload.json");
        CronService service = new CronService(store.toString());
        CronPayload payload = new CronPayload("task message", "cli", "direct");
        payload.setRunMode("NEW_SESSION");
        payload.setProjectId("proj99");
        payload.setAttachmentIds(List.of("aaa000000001"));
        payload.setOutputTarget("输出到日报");
        CronJob job = service.addJob("p6-job", CronSchedule.every(3_600_000), payload);

        // 重启重建
        CronService reloaded = new CronService(store.toString());
        CronJob reread = reloaded.findJobByName("p6-job");
        assertEquals("task message", reread.getPayload().getMessage());
        assertEquals("NEW_SESSION", reread.getPayload().getRunMode());
        assertEquals("proj99", reread.getPayload().getProjectId());
        assertEquals(List.of("aaa000000001"), reread.getPayload().getAttachmentIds());
        assertEquals("输出到日报", reread.getPayload().getOutputTarget());
        assertEquals(job.getId(), reread.getId());
    }

    @Test
    @DisplayName("旧版五参 addJob 创建的任务：payload 扩展字段为空（旧语义不变）")
    void legacyAddJob_NoExtendedFields() {
        CronService service = new CronService(tempDir.resolve("legacy.json").toString());
        CronJob job = service.addJob("old-style", CronSchedule.every(60_000), "m", "c", "t");
        assertNull(job.getPayload().getRunMode());
        assertEquals(CronPayload.RUN_MODE_NEW_SESSION, job.getPayload().effectiveRunMode());
    }

    // ==================== 模板表达式校验（前端模板对应的服务端语义） ====================

    @Test
    @DisplayName("模板表达式：每日 9 点 / 每周一 9 点 / 工作日 9 点均可预览")
    void templateExpressions_Previewable() {
        CronService service = new CronService(tempDir.resolve("tpl.json").toString());
        assertEquals(5, service.previewNextRuns(CronSchedule.cron("0 9 * * *"), 5).size(), "每日");
        assertEquals(5, service.previewNextRuns(CronSchedule.cron("0 9 * * 1"), 5).size(), "每周一");
        assertEquals(5, service.previewNextRuns(CronSchedule.cron("0 9 * * 1-5"), 5).size(), "工作日");
    }

    // ==================== 辅助 ====================

    /** 轮询等待某任务的 lastStatus 到达目标值。 */
    private void awaitLastStatus(CronService service, String jobId, String status)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            CronJob job = findById(service, jobId);
            if (job != null && status.equals(job.getState().getLastStatus())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("status not reached: " + status);
    }

    /** 轮询等待某任务出现执行历史。 */
    private void awaitHistory(CronService service, String jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            CronJob job = findById(service, jobId);
            if (job != null && job.getState().getHistory() != null
                    && !job.getState().getHistory().isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("history not written in time");
    }

    private CronJob findById(CronService service, String jobId) {
        for (CronJob j : service.listJobs(true)) {
            if (j.getId().equals(jobId)) {
                return j;
            }
        }
        return null;
    }
}
