package io.leavesfly.tinyclaw.cron;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CronService 单元测试
 *
 * <p>覆盖：findJobByName 按名查找、updateJobSchedule 调度更新、
 * EVERY/CRON 任务 misfire 重启补跑、执行历史记录与手动触发、
 * 多实例共享同一存储时的丢失更新防护。</p>
 */
@DisplayName("CronService 定时服务测试")
class CronServiceTest {

    @TempDir
    Path tempDir;

    private CronService service;
    private CronService secondService;

    @AfterEach
    void tearDown() {
        if (service != null && service.isRunning()) {
            service.stop();
        }
        if (secondService != null && secondService.isRunning()) {
            secondService.stop();
        }
    }

    // ==================== findJobByName / updateJobSchedule ====================

    @Test
    @DisplayName("findJobByName: 按名称查找任务")
    void findJobByName_FindsAddedJob() {
        service = new CronService(tempDir.resolve("jobs.json").toString());
        service.addJob("__heartbeat__", CronSchedule.every(1800_000), "", "system", null);

        CronJob found = service.findJobByName("__heartbeat__");
        assertNotNull(found);
        assertEquals("__heartbeat__", found.getName());

        assertNull(service.findJobByName("not-exists"));
    }

    @Test
    @DisplayName("updateJobSchedule: 更新调度并重算下次运行时间")
    void updateJobSchedule_ChangesSchedule() {
        service = new CronService(tempDir.resolve("jobs.json").toString());
        CronJob job = service.addJob("t", CronSchedule.every(60_000), "m", "", "");

        CronJob updated = service.updateJobSchedule(job.getId(), CronSchedule.every(120_000));
        assertNotNull(updated);
        assertEquals(120_000L, updated.getSchedule().getEveryMs());
        assertNotNull(updated.getState().getNextRunAtMs());

        assertNull(service.updateJobSchedule("no-such-id", CronSchedule.every(1000)));
    }

    // ==================== misfire 补跑 ====================

    /**
     * 直接构造 jobs.json，模拟"上次执行后停机超过一个周期"的场景。
     */
    private Path writeStoreWithLastRun(String name, long everyMs, long lastRunAtMs) throws Exception {
        Path store = tempDir.resolve("jobs.json");
        String json = String.format("""
                {"jobs":[{"id":"fixed-id","name":"%s","enabled":true,
                "schedule":{"kind":"every","everyMs":%d},
                "payload":{"kind":"agent_turn","message":"hi","channel":"","to":""},
                "state":{"lastRunAtMs":%d},
                "createdAtMs":1,"updatedAtMs":1,"deleteAfterRun":false}]}
                """, name, everyMs, lastRunAtMs);
        Files.writeString(store, json);
        return store;
    }

    @Test
    @DisplayName("misfire: 停机超过一个周期的 EVERY 任务启动后立即补跑")
    void misfire_RunsImmediatelyOnStartup() throws Exception {
        long now = System.currentTimeMillis();
        // 间隔 60s，上次运行在 2 分钟前 → 错过一轮
        Path store = writeStoreWithLastRun("misfire-test", 60_000, now - 120_000);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        service = new CronService(store.toString(), job -> {
            runCount.incrementAndGet();
            latch.countDown();
            return "ok";
        });
        service.start();

        // 启动后重算应将 nextRunAtMs 设为 now（而非 now + interval）
        CronJob job = service.findJobByName("misfire-test");
        assertNotNull(job.getState().getNextRunAtMs());
        assertTrue(job.getState().getNextRunAtMs() <= now + 5000,
                "misfire 任务应在启动后立即到期");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "misfire 任务应在启动后尽快补跑一次");
        assertEquals(1, runCount.get());
    }

    @Test
    @DisplayName("无 misfire: 上次运行在一个周期内的任务按常规重算")
    void noMisfire_RecomputesNormally() throws Exception {
        long now = System.currentTimeMillis();
        // 间隔 60s，上次运行在 10 秒前 → 未错过
        Path store = writeStoreWithLastRun("fresh-test", 60_000, now - 10_000);

        service = new CronService(store.toString(), job -> "ok");
        service.start();

        CronJob job = service.findJobByName("fresh-test");
        assertNotNull(job.getState().getNextRunAtMs());
        assertTrue(job.getState().getNextRunAtMs() >= now + 40_000,
                "未错过周期的任务应重算为 now + interval");
    }

    // ==================== 多实例共享存储：丢失更新防护 ====================

    /**
     * 回归：同一份 jobs.json 上存在另一个实例（如常驻网关进程外的
     * {@code tinyclaw cron add}）时，后写方不得抹掉先写方的任务。
     *
     * <p>saveStoreUnsafe 是内存全量覆盖写，修复前两个实例会互相覆盖，
     * 表现为用户创建的定时任务静默消失。</p>
     */
    @Test
    @DisplayName("多实例: 后写方不会覆盖先写方新增的任务")
    void concurrentInstances_DoNotClobberEachOther() {
        String storePath = tempDir.resolve("jobs.json").toString();

        // 实例 A（模拟网关）：先注册系统 job
        service = new CronService(storePath);
        service.addJob("__heartbeat__", CronSchedule.every(1800_000), "", "system", null);

        // 实例 B（模拟 CLI 进程）：加载后新增用户 job
        secondService = new CronService(storePath);
        secondService.addJob("user-job", CronSchedule.every(60_000), "hi", "cli", "me");

        // B 写入后，系统 job 必须仍在
        assertNotNull(secondService.findJobByName("__heartbeat__"),
                "B 的写入不应抹掉 A 注册的系统 job");

        // A 再次发生写入（如任务状态更新）时，应先同步到 B 的新增，不得抹掉 user-job
        service.addJob("another", CronSchedule.every(60_000), "x", "", "");

        CronService verifier = new CronService(storePath);
        assertNotNull(verifier.findJobByName("__heartbeat__"), "__heartbeat__ 应存在");
        assertNotNull(verifier.findJobByName("user-job"), "user-job 应存在（不得被 A 覆盖）");
        assertNotNull(verifier.findJobByName("another"), "another 应存在");
    }

    @Test
    @DisplayName("defaultStorePath: 统一解析工作空间下的任务存储路径")
    void defaultStorePath_ResolvesUnderWorkspace() {
        String path = CronService.defaultStorePath(tempDir.toString());
        assertTrue(path.endsWith("cron" + java.io.File.separator + "jobs.json"),
                "应为 workspace/cron/jobs.json，实际：" + path);
        assertTrue(path.startsWith(tempDir.toString()), "应位于工作空间内");
    }

    // ==================== 任务编辑 ====================

    @Test
    @DisplayName("updateJob: 更新名称/消息/调度，null 项保留原值")
    void updateJob_UpdatesFieldsAndKeepsNulls() {
        service = new CronService(tempDir.resolve("jobs.json").toString());
        CronJob job = service.addJob("orig", CronSchedule.every(60_000), "msg", "chan", "to");

        CronJob updated = service.updateJob(job.getId(), "renamed",
                CronSchedule.cron("0 8 * * *"), null, null, null);
        assertNotNull(updated);
        assertEquals("renamed", updated.getName());
        assertEquals("msg", updated.getPayload().getMessage(), "null 应保留原消息");
        assertEquals("chan", updated.getPayload().getChannel());
        assertEquals(CronSchedule.ScheduleKind.CRON, updated.getSchedule().getKind());
        assertEquals("0 8 * * *", updated.getSchedule().getExpr());
        assertNotNull(updated.getState().getNextRunAtMs(), "启用任务改调度后应重算下次运行");

        CronJob msgUpdated = service.updateJob(job.getId(), null, null, "new-msg", null, null);
        assertEquals("renamed", msgUpdated.getName(), "null 应保留原名称");
        assertEquals("new-msg", msgUpdated.getPayload().getMessage());

        assertNull(service.updateJob("no-such-id", "x", null, null, null, null));
    }

    // ==================== 执行历史与手动触发 ====================

    /**
     * 轮询等待执行历史达到期望条数（历史在 handler 返回后异步追加）。
     */
    private CronJob waitForHistory(String jobId, int minSize) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            CronJob job = service.listJobs(true).stream()
                    .filter(j -> j.getId().equals(jobId)).findFirst().orElse(null);
            if (job != null && job.getState().getHistory() != null
                    && job.getState().getHistory().size() >= minSize) {
                return job;
            }
            Thread.sleep(50);
        }
        fail("执行历史未在期限内达到期望条数：" + minSize);
        return null;
    }

    @Test
    @DisplayName("runJobNow: 手动触发记录执行历史（trigger=manual、status ok、结果摘要）")
    void runJobNow_RecordsManualHistory() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        service = new CronService(tempDir.resolve("jobs.json").toString(), job -> {
            latch.countDown();
            return "done";
        });
        service.start();
        CronJob job = service.addJob("manual-test", CronSchedule.every(3600_000), "hi", "", "");

        assertTrue(service.runJobNow(job.getId()));
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        CronJob updated = waitForHistory(job.getId(), 1);
        CronRunRecord record = updated.getState().getHistory().get(0);
        assertEquals("manual", record.getTrigger());
        assertEquals("ok", record.getStatus());
        assertEquals("done", record.getResult());
        assertEquals("ok", updated.getState().getLastStatus());

        assertFalse(service.runJobNow("no-such-id"), "不存在的任务应返回 false");
    }

    @Test
    @DisplayName("runJobNow: handler 抛异常记录 error 状态与错误信息")
    void runJobNow_RecordsErrorHistory() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        service = new CronService(tempDir.resolve("jobs.json").toString(), job -> {
            latch.countDown();
            throw new RuntimeException("boom");
        });
        service.start();
        CronJob job = service.addJob("fail-test", CronSchedule.every(3600_000), "hi", "", "");

        assertTrue(service.runJobNow(job.getId()));
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        CronJob updated = waitForHistory(job.getId(), 1);
        CronRunRecord record = updated.getState().getHistory().get(0);
        assertEquals("error", record.getStatus());
        assertNotNull(record.getError());
        assertTrue(record.getError().contains("boom"));
        assertEquals("error", updated.getState().getLastStatus());
    }

    @Test
    @DisplayName("执行历史: 上限 20 条，超出截断")
    void history_CappedAtTwenty() throws Exception {
        service = new CronService(tempDir.resolve("jobs.json").toString(), job -> "ok");
        service.start();
        CronJob job = service.addJob("cap-test", CronSchedule.every(3600_000), "hi", "", "");

        for (int i = 0; i < 25; i++) {
            assertTrue(service.runJobNow(job.getId()));
        }

        CronJob updated = waitForHistory(job.getId(), 20);
        // 等待所有手动触发落盘后仍不得超过上限
        Thread.sleep(300);
        assertTrue(updated.getState().getHistory().size() <= 20,
                "历史应截断在 20 条以内");
    }

    @Test
    @DisplayName("misfire: 停机错过的 CRON 任务启动后补跑并记为 misfire")
    void cronMisfire_RunsImmediatelyAndRecorded() throws Exception {
        long now = System.currentTimeMillis();
        Path store = tempDir.resolve("jobs.json");
        // 每小时整点执行，上次运行在 2 小时前 → 停机期间至少错过一轮
        String json = String.format("""
                {"jobs":[{"id":"cron-fixed","name":"cron-misfire","enabled":true,
                "schedule":{"kind":"cron","expr":"0 * * * *"},
                "payload":{"kind":"agent_turn","message":"hi","channel":"","to":""},
                "state":{"lastRunAtMs":%d},
                "createdAtMs":1,"updatedAtMs":1,"deleteAfterRun":false}]}
                """, now - 2 * 3600_000);
        Files.writeString(store, json);

        CountDownLatch latch = new CountDownLatch(1);
        service = new CronService(store.toString(), job -> {
            latch.countDown();
            return "ok";
        });
        service.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "停机错过的 CRON 任务应在启动后补跑");
        CronJob updated = waitForHistory("cron-fixed", 1);
        assertEquals("misfire", updated.getState().getHistory().get(0).getTrigger());
    }
}
