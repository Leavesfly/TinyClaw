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
 * EVERY 任务 misfire 重启补跑。</p>
 */
@DisplayName("CronService 定时服务测试")
class CronServiceTest {

    @TempDir
    Path tempDir;

    private CronService service;

    @AfterEach
    void tearDown() {
        if (service != null && service.isRunning()) {
            service.stop();
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
}
