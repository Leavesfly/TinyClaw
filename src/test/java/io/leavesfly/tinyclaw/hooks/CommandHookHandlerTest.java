package io.leavesfly.tinyclaw.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommandHookHandler} 的真实进程集成测试。
 *
 * <p>由于 handler 依赖 {@code sh -c} 进程通信，这里仅在类 Unix 系统（macOS / Linux）上运行。
 * Windows 会被 {@link EnabledOnOs} 跳过——Windows 下走的是 {@code cmd /c} 分支，
 * 其语义与这里测试的 shell 脚本不完全兼容，故单独忽略而非编写重复用例。</p>
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class CommandHookHandlerTest {

    private static HookContext preToolCtx() {
        return HookContext.builder(HookEvent.PRE_TOOL_USE)
                .sessionKey("cli:default")
                .toolName("exec")
                .toolInput(Map.of("command", "ls -la"))
                .build();
    }

    @Test
    void exit0_noStdout_returnsCont() {
        CommandHookHandler h = new CommandHookHandler("true", 0, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertFalse(d.isDeny());
        assertNull(d.getModifiedInput());
        assertNull(d.getModifiedOutput());
        assertNull(d.getAdditionalContext());
    }

    @Test
    void exit2_emptyStderr_deniesWithDefaultReason() {
        CommandHookHandler h = new CommandHookHandler("exit 2", 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertTrue(d.isDeny());
        assertNotNull(d.getReason());
        assertTrue(d.getReason().toLowerCase().contains("block")
                        || d.getReason().toLowerCase().contains("hook"),
                "reason should hint at hook denial, got: " + d.getReason());
    }

    @Test
    void exit2_withStderr_usesStderrAsReason() {
        CommandHookHandler h = new CommandHookHandler(
                "echo 'dangerous command' 1>&2; exit 2", 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertTrue(d.isDeny());
        assertEquals("dangerous command", d.getReason());
    }

    @Test
    void exit1_failsOpenWithCont() {
        // 非 0 非 2 的 exit code：fail-open
        CommandHookHandler h = new CommandHookHandler("exit 1", 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertFalse(d.isDeny());
        assertSame(HookDecision.cont(), d);
    }

    @Test
    void exit0_withStdoutJsonDeny_deniesWithReason() {
        // 用 printf 输出纯 JSON 到 stdout，exit 0
        String cmd = "printf '%s' '{\"hookSpecificOutput\":"
                + "{\"permissionDecision\":\"deny\","
                + "\"permissionDecisionReason\":\"rm -rf blocked\"}}'";
        CommandHookHandler h = new CommandHookHandler(cmd, 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertTrue(d.isDeny());
        assertEquals("rm -rf blocked", d.getReason());
    }

    @Test
    void exit0_withStdoutModifyInput_returnsNewInput() {
        String cmd = "printf '%s' '{\"hookSpecificOutput\":"
                + "{\"modifiedInput\":{\"command\":\"ls -la\",\"safe\":true}}}'";
        CommandHookHandler h = new CommandHookHandler(cmd, 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertFalse(d.isDeny());
        assertNotNull(d.getModifiedInput());
        assertEquals("ls -la", d.getModifiedInput().get("command"));
        assertEquals(Boolean.TRUE, d.getModifiedInput().get("safe"));
    }

    @Test
    void exit0_withStdoutModifyOutput() {
        String cmd = "printf '%s' '{\"hookSpecificOutput\":"
                + "{\"modifiedOutput\":\"hello world\"}}'";
        CommandHookHandler h = new CommandHookHandler(cmd, 5_000, null, null);
        HookContext ctx = HookContext.builder(HookEvent.POST_TOOL_USE)
                .toolName("exec")
                .toolOutput("orig")
                .build();
        HookDecision d = h.invoke(ctx);
        assertFalse(d.isDeny());
        assertEquals("hello world", d.getModifiedOutput());
    }

    @Test
    void exit0_withStdoutAdditionalContext() {
        String cmd = "printf '%s' '{\"hookSpecificOutput\":"
                + "{\"additionalContext\":\"hint from hook\"}}'";
        CommandHookHandler h = new CommandHookHandler(cmd, 5_000, null, null);
        HookDecision d = h.invoke(
                HookContext.builder(HookEvent.SESSION_START).build());
        assertEquals("hint from hook", d.getAdditionalContext());
    }

    @Test
    void exit0_withInvalidStdoutJson_failsOpen() {
        CommandHookHandler h = new CommandHookHandler(
                "echo 'not-json-just-text'", 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertFalse(d.isDeny());
        assertSame(HookDecision.cont(), d);
    }

    @Test
    void timeout_failsOpen() {
        // 让脚本睡 3s，但给 500ms 超时
        CommandHookHandler h = new CommandHookHandler("sleep 3", 500, null, null);
        long start = System.currentTimeMillis();
        HookDecision d = h.invoke(preToolCtx());
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(d.isDeny(), "timeout must fail-open, not deny");
        assertSame(HookDecision.cont(), d);
        // 允许一些调度抖动，保证确实在超时后被杀掉，不会拖到 3s
        assertTrue(elapsed < 2_500,
                "invoke() should return soon after timeout, but took " + elapsed + "ms");
    }

    @Test
    void stdinReceivesJsonPayload() {
        // handler 把 stdin 原样回显到 stdout 的 hookSpecificOutput.modifiedOutput 里
        // cat 读取 stdin，再用 jq? —— 不依赖 jq，改用更可靠的办法：
        //   把 stdin 内容写到 stderr 看，然后 deny 并把 stdin 内容作为 reason
        // 注意 stdin 可能包含双引号/花括号，直接塞到 shell 里会很危险，所以这里选：
        //   用 cat 读 stdin 到一个变量（通过 command substitution），再 echo 到 stderr + exit 2
        String cmd = "STDIN=$(cat); echo \"$STDIN\" 1>&2; exit 2";
        CommandHookHandler h = new CommandHookHandler(cmd, 5_000, null, null);
        HookDecision d = h.invoke(preToolCtx());
        assertTrue(d.isDeny());
        String reason = d.getReason();
        assertNotNull(reason);
        // 校验几个关键字段都被序列化进了 stdin
        assertTrue(reason.contains("\"hookEventName\""),
                "stdin should contain hookEventName, got: " + reason);
        assertTrue(reason.contains("\"PreToolUse\""),
                "stdin should contain PreToolUse, got: " + reason);
        assertTrue(reason.contains("\"tool_name\""),
                "stdin should contain tool_name, got: " + reason);
        assertTrue(reason.contains("\"exec\""),
                "stdin should contain exec, got: " + reason);
    }

    @Test
    void blankCommand_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommandHookHandler(null, 1000, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandHookHandler("", 1000, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandHookHandler("   ", 1000, null, null));
    }

    @Test
    void zeroTimeout_fallsBackToDefault() {
        CommandHookHandler h = new CommandHookHandler("true", 0, null, null);
        assertEquals(30_000L, h.getTimeoutMs(), "timeout=0 should fall back to 30s default");
    }

    @Test
    void negativeTimeout_fallsBackToDefault() {
        CommandHookHandler h = new CommandHookHandler("true", -1, null, null);
        assertEquals(30_000L, h.getTimeoutMs(), "negative timeout should fall back to 30s default");
    }
}
