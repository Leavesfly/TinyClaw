package io.leavesfly.tinyclaw.heartbeat;

import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.AgentConfig;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * HeartbeatRunner 单元测试
 *
 * <p>覆盖：busy guard 跳过、空清单跳过、HEARTBEAT_OK 剥离与抑制、
 * 告警投递、activeHours 门控、整轮超时、系统 job 幂等注册。</p>
 */
@DisplayName("HeartbeatRunner 心跳运行器测试")
class HeartbeatRunnerTest {

    @TempDir
    Path tempDir;

    private final List<String> delivered = new ArrayList<>();

    private HeartbeatRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.stop();
        }
    }

    // ==================== 辅助方法 ====================

    private Config enabledConfig() {
        Config config = Config.defaultConfig();
        config.getAgent().setHeartbeatEnabled(true);
        return config;
    }

    private HeartbeatRunner newRunner(Config config, AgentRuntime runtime,
                                      SessionManager sessions, java.util.function.Supplier<Boolean> busyChecker) {
        runner = new HeartbeatRunner(config, runtime, sessions, tempDir.toString(),
                busyChecker, (channel, chatId, content) -> delivered.add(channel + ":" + chatId + ":" + content));
        return runner;
    }

    private void writeChecklist(String content) throws Exception {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("HEARTBEAT.md"), content);
    }

    // ==================== 静态契约方法测试 ====================

    @Test
    @DisplayName("stripOkToken: 剥离首/尾 HEARTBEAT_OK")
    void stripOkToken_StripsToken() {
        assertEquals("", HeartbeatRunner.stripOkToken("HEARTBEAT_OK"));
        assertEquals("一切正常", HeartbeatRunner.stripOkToken("HEARTBEAT_OK 一切正常"));
        assertEquals("一切正常", HeartbeatRunner.stripOkToken("一切正常 HEARTBEAT_OK"));
        assertEquals("磁盘告警", HeartbeatRunner.stripOkToken("磁盘告警"));
    }

    @Test
    @DisplayName("hasSubstantiveContent: 空清单各形态均判定为无实质内容")
    void hasSubstantiveContent_DetectsEmptyVariants() {
        assertFalse(HeartbeatRunner.hasSubstantiveContent(""));
        assertFalse(HeartbeatRunner.hasSubstantiveContent("# 心跳清单\n\n"));
        assertFalse(HeartbeatRunner.hasSubstantiveContent("# 标题\n- [ ] \n- [x]\n"));
        assertFalse(HeartbeatRunner.hasSubstantiveContent("<!-- 注释 -->\n```\n```"));
        assertTrue(HeartbeatRunner.hasSubstantiveContent("- [ ] 检查部署状态"));
        assertTrue(HeartbeatRunner.hasSubstantiveContent("关注磁盘用量"));
    }

    @Test
    @DisplayName("isWithinActiveHours: 零宽窗口全部跳过，普通窗口按时间判断")
    void activeHours_WindowChecks() {
        AgentConfig.ActiveHours zero = new AgentConfig.ActiveHours("09:00", "09:00", null);
        assertFalse(HeartbeatRunner.isWithinActiveHours(zero));

        // null 视为不限制
        assertTrue(HeartbeatRunner.isWithinActiveHours(null));

        AgentConfig.ActiveHours full = new AgentConfig.ActiveHours("00:00", "23:59", null);
        assertTrue(HeartbeatRunner.isWithinActiveHours(full));
    }

    // ==================== 门控跳过测试 ====================

    @Test
    @DisplayName("busy guard: Agent 忙时跳过本轮")
    void busyGuard_SkipsWhenBusy() {
        HeartbeatRunner r = newRunner(enabledConfig(), null, null, () -> true);
        r.runOnceForAgent(null);

        Map<String, Object> last = r.getLastRuns().get("default");
        assertNotNull(last);
        assertEquals("SKIPPED_BUSY", last.get("status"));
        assertEquals("busy", last.get("reason"));
    }

    @Test
    @DisplayName("空清单跳过: HEARTBEAT.md 缺失或无实质内容时跳过")
    void emptyChecklist_SkipsRound() throws Exception {
        // 文件缺失
        HeartbeatRunner r = newRunner(enabledConfig(), null, null, () -> false);
        r.runOnceForAgent(null);
        assertEquals("SKIPPED_EMPTY", r.getLastRuns().get("default").get("status"));
        assertEquals("empty-heartbeat-file", r.getLastRuns().get("default").get("reason"));

        // 无实质内容
        writeChecklist("# 心跳清单\n- [ ] \n");
        r.runOnceForAgent(null);
        assertEquals("SKIPPED_EMPTY", r.getLastRuns().get("default").get("status"));
    }

    @Test
    @DisplayName("可见性开关全关: 整轮跳过")
    void alertsDisabled_SkipsRound() throws Exception {
        Config config = enabledConfig();
        config.getAgent().getHeartbeat().setShowOk(false);
        config.getAgent().getHeartbeat().setShowAlerts(false);
        writeChecklist("检查部署状态");

        HeartbeatRunner r = newRunner(config, null, null, () -> false);
        r.runOnceForAgent(null);
        assertEquals("SKIPPED_ALERTS_DISABLED", r.getLastRuns().get("default").get("status"));
    }

    @Test
    @DisplayName("activeHours 零宽窗口: 整轮跳过")
    void activeHours_ZeroWidthSkips() throws Exception {
        Config config = enabledConfig();
        config.getAgent().getHeartbeat().setActiveHours(
                new AgentConfig.ActiveHours("08:00", "08:00", null));
        writeChecklist("检查部署状态");

        HeartbeatRunner r = newRunner(config, null, null, () -> false);
        r.runOnceForAgent(null);
        assertEquals("SKIPPED_HOURS", r.getLastRuns().get("default").get("status"));
    }

    // ==================== 结果契约测试 ====================

    @Test
    @DisplayName("HEARTBEAT_OK 抑制: 剥离后剩余 ≤300 字符静默，showOk=false 不投递")
    void okResponse_Suppressed() {
        Config config = enabledConfig();
        HeartbeatRunner r = newRunner(config, null, null, () -> false);
        AgentConfig.HeartbeatSettings settings = config.getAgent().getHeartbeat();

        r.handleResult("default", settings, "HEARTBEAT_OK", System.currentTimeMillis());
        assertTrue(delivered.isEmpty());

        Map<String, Object> last = r.getLastRuns().get("default");
        assertEquals("RAN", last.get("status"));
        assertEquals("ok", last.get("reason"));
    }

    @Test
    @DisplayName("告警投递: 超长内容经 target=last 投递到最近联系人")
    void alertResponse_DeliveredToLastContact() {
        Config config = enabledConfig();
        config.getAgent().getHeartbeat().setTarget("last");
        HeartbeatRunner r = newRunner(config, null, null, () -> false);
        AgentConfig.HeartbeatSettings settings = config.getAgent().getHeartbeat();

        LastContact.update("telegram", "chat-123");
        String longAlert = "x".repeat(HeartbeatRunner.SILENT_THRESHOLD + 50);
        r.handleResult("default", settings, longAlert, System.currentTimeMillis());

        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0).startsWith("telegram:chat-123:"));
        assertEquals("alert", r.getLastRuns().get("default").get("reason"));
    }

    @Test
    @DisplayName("告警 target=none: 不投递，仅记日志")
    void alertResponse_TargetNoneDoesNotDeliver() {
        Config config = enabledConfig();
        HeartbeatRunner r = newRunner(config, null, null, () -> false);
        AgentConfig.HeartbeatSettings settings = config.getAgent().getHeartbeat();

        String longAlert = "x".repeat(HeartbeatRunner.SILENT_THRESHOLD + 50);
        r.handleResult("default", settings, longAlert, System.currentTimeMillis());

        assertTrue(delivered.isEmpty());
    }

    // ==================== 超时测试 ====================

    @Test
    @DisplayName("整轮超时: 超时后取消任务并发送中断信号")
    void timeout_CancelsAndAborts() throws Exception {
        Config config = enabledConfig();
        config.getAgent().getHeartbeat().setTimeoutSeconds(1);
        writeChecklist("检查部署状态");

        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.processDirect(anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    Thread.sleep(5000);
                    return "late";
                });
        SessionManager sessions = mock(SessionManager.class);

        HeartbeatRunner r = newRunner(config, runtime, sessions, () -> false);
        r.runOnceForAgent(null);

        Map<String, Object> last = r.getLastRuns().get("default");
        assertEquals("TIMEOUT", last.get("status"));
        verify(runtime, atLeastOnce()).abortCurrentTask();
    }

    // ==================== 系统 job 注册测试 ====================

    @Test
    @DisplayName("registerSystemJobs: 重复调用幂等，不重复注册")
    void registerSystemJobs_Idempotent() {
        Path store = tempDir.resolve("jobs.json");
        CronService cron = new CronService(store.toString());

        HeartbeatRunner r = newRunner(enabledConfig(), null, null, () -> false);
        r.registerSystemJobs(cron);
        r.registerSystemJobs(cron);

        long heartbeatCount = cron.listJobs(true).stream()
                .filter(j -> HeartbeatRunner.HEARTBEAT_JOB_NAME.equals(j.getName()))
                .count();
        long evolutionCount = cron.listJobs(true).stream()
                .filter(j -> HeartbeatRunner.MEMORY_EVOLUTION_JOB_NAME.equals(j.getName()))
                .count();
        assertEquals(1, heartbeatCount);
        assertEquals(1, evolutionCount);
    }

    @Test
    @DisplayName("registerSystemJobs: per-agent entries 注册独立 job")
    void registerSystemJobs_PerAgentEntries() {
        Path store = tempDir.resolve("jobs.json");
        CronService cron = new CronService(store.toString());

        Config config = enabledConfig();
        Map<String, AgentConfig.HeartbeatSettings> entries = new HashMap<>();
        entries.put("monitor", new AgentConfig.HeartbeatSettings());
        config.getAgent().getHeartbeat().setEntries(entries);

        HeartbeatRunner r = newRunner(config, null, null, () -> false);
        r.registerSystemJobs(cron);

        List<CronJob> jobs = cron.listJobs(true);
        assertTrue(jobs.stream().anyMatch(j -> "__heartbeat__:monitor".equals(j.getName())));
        // entries 模式下不注册默认 __heartbeat__ job
        assertTrue(jobs.stream().noneMatch(j -> HeartbeatRunner.HEARTBEAT_JOB_NAME.equals(j.getName())));
    }

    @Test
    @DisplayName("registerSystemJobs: 禁用时清理系统 job")
    void registerSystemJobs_DisabledRemovesJobs() {
        Path store = tempDir.resolve("jobs.json");
        CronService cron = new CronService(store.toString());

        // 先启用注册
        HeartbeatRunner r = newRunner(enabledConfig(), null, null, () -> false);
        r.registerSystemJobs(cron);
        assertFalse(cron.listJobs(true).isEmpty());

        // 禁用后清理
        Config disabled = Config.defaultConfig();
        disabled.getAgent().setHeartbeatEnabled(false);
        HeartbeatRunner r2 = newRunner(disabled, null, null, () -> false);
        r2.registerSystemJobs(cron);
        assertTrue(cron.listJobs(true).isEmpty());
    }

    @Test
    @DisplayName("buildPrompt: 自定义 prompt 覆盖默认指令体，清单始终附加")
    void buildPrompt_CustomOverride() {
        HeartbeatRunner r = newRunner(enabledConfig(), null, null, () -> false);
        AgentConfig.HeartbeatSettings settings = new AgentConfig.HeartbeatSettings();

        String defaultPrompt = r.buildPrompt(settings, "清单内容");
        assertTrue(defaultPrompt.contains("HEARTBEAT_OK"));
        assertTrue(defaultPrompt.contains("清单内容"));

        settings.setPrompt("自定义指令");
        String custom = r.buildPrompt(settings, "清单内容");
        assertTrue(custom.startsWith("自定义指令"));
        assertTrue(custom.contains("清单内容"));
    }
}
