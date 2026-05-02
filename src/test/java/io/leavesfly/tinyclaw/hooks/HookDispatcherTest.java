package io.leavesfly.tinyclaw.hooks;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HookDispatcherTest {

    /** 用极简内存型 handler 替代 CommandHookHandler，保证测试不依赖 shell。 */
    private static HookEntry entry(String matcher, HookHandler... handlers) {
        return new HookEntry(HookMatcher.of(matcher), List.of(handlers));
    }

    private static HookDispatcher dispatcher(HookEvent event, HookEntry... entries) {
        Map<HookEvent, List<HookEntry>> map = new EnumMap<>(HookEvent.class);
        map.put(event, List.of(entries));
        return new HookDispatcher(new HookRegistry(map));
    }

    @Test
    void emptyRegistry_returnsCont() {
        HookDispatcher disp = HookDispatcher.noop();
        HookContext ctx = HookContext.builder(HookEvent.PRE_TOOL_USE).toolName("exec").build();
        HookDecision decision = disp.fire(HookEvent.PRE_TOOL_USE, ctx);
        assertSame(HookDecision.cont(), decision);
    }

    @Test
    void nullInputs_returnCont() {
        HookDispatcher disp = HookDispatcher.noop();
        assertSame(HookDecision.cont(), disp.fire(null, HookContext.builder(HookEvent.STOP).build()));
        assertSame(HookDecision.cont(), disp.fire(HookEvent.STOP, null));
    }

    @Test
    void matcherMiss_doesNotInvokeHandler() {
        AtomicInteger counter = new AtomicInteger();
        HookHandler handler = ctx -> {
            counter.incrementAndGet();
            return HookDecision.deny("should not run");
        };
        HookDispatcher disp = dispatcher(HookEvent.PRE_TOOL_USE, entry("write_file", handler));

        HookContext ctx = HookContext.builder(HookEvent.PRE_TOOL_USE).toolName("exec").build();
        HookDecision decision = disp.fire(HookEvent.PRE_TOOL_USE, ctx);
        assertFalse(decision.isDeny());
        assertEquals(0, counter.get());
    }

    @Test
    void denyShortCircuits_skipsSubsequentHandlers() {
        AtomicInteger secondInvocations = new AtomicInteger();
        HookHandler first = ctx -> HookDecision.deny("stop right here");
        HookHandler second = ctx -> {
            secondInvocations.incrementAndGet();
            return HookDecision.cont();
        };
        HookDispatcher disp = dispatcher(HookEvent.PRE_TOOL_USE, entry("*", first, second));

        HookDecision decision = disp.fire(HookEvent.PRE_TOOL_USE,
                HookContext.builder(HookEvent.PRE_TOOL_USE).toolName("exec").build());

        assertTrue(decision.isDeny());
        assertEquals("stop right here", decision.getReason());
        assertEquals(0, secondInvocations.get(), "Second handler must not run after deny");
    }

    @Test
    void modifyInput_accumulatesAcrossHandlers() {
        HookHandler first = ctx -> HookDecision.modifyInput(Map.of("cmd", "ls -l"));
        HookHandler second = ctx -> {
            // 验证 second 收到的 input 是 first 修改过的
            assertNotNull(ctx.getToolInput());
            assertEquals("ls -l", ctx.getToolInput().get("cmd"));
            return HookDecision.modifyInput(Map.of("cmd", "ls -la"));
        };
        HookDispatcher disp = dispatcher(HookEvent.PRE_TOOL_USE, entry("*", first, second));

        HookDecision decision = disp.fire(HookEvent.PRE_TOOL_USE,
                HookContext.builder(HookEvent.PRE_TOOL_USE)
                        .toolName("exec")
                        .toolInput(Map.of("cmd", "ls"))
                        .build());

        assertFalse(decision.isDeny());
        assertNotNull(decision.getModifiedInput());
        assertEquals("ls -la", decision.getModifiedInput().get("cmd"));
    }

    @Test
    void modifyOutput_finalValueWins() {
        HookHandler first = ctx -> HookDecision.modifyOutput("v1");
        HookHandler second = ctx -> {
            assertEquals("v1", ctx.getToolOutput());
            return HookDecision.modifyOutput("v2");
        };
        HookDispatcher disp = dispatcher(HookEvent.POST_TOOL_USE, entry("*", first, second));

        HookDecision decision = disp.fire(HookEvent.POST_TOOL_USE,
                HookContext.builder(HookEvent.POST_TOOL_USE)
                        .toolName("exec")
                        .toolOutput("orig")
                        .build());

        assertEquals("v2", decision.getModifiedOutput());
    }

    @Test
    void modifyPrompt_finalValueWins() {
        HookHandler h = ctx -> HookDecision.modifyPrompt("rewritten");
        HookDispatcher disp = dispatcher(HookEvent.USER_PROMPT_SUBMIT, entry("*", h));

        HookDecision decision = disp.fire(HookEvent.USER_PROMPT_SUBMIT,
                HookContext.builder(HookEvent.USER_PROMPT_SUBMIT).prompt("hello").build());

        assertEquals("rewritten", decision.getModifiedPrompt());
    }

    @Test
    void additionalContext_concatenatedWithBlankLine() {
        HookHandler first = ctx -> HookDecision.addContext("note A");
        HookHandler second = ctx -> HookDecision.addContext("note B");
        HookDispatcher disp = dispatcher(HookEvent.SESSION_START, entry("*", first, second));

        HookDecision decision = disp.fire(HookEvent.SESSION_START,
                HookContext.builder(HookEvent.SESSION_START).build());

        assertEquals("note A\n\nnote B", decision.getAdditionalContext());
    }

    @Test
    void handlerThrows_failOpenAndContinue() {
        HookHandler bad = ctx -> { throw new RuntimeException("boom"); };
        HookHandler good = ctx -> HookDecision.modifyOutput("ok");
        HookDispatcher disp = dispatcher(HookEvent.POST_TOOL_USE, entry("*", bad, good));

        HookDecision decision = disp.fire(HookEvent.POST_TOOL_USE,
                HookContext.builder(HookEvent.POST_TOOL_USE)
                        .toolName("exec")
                        .toolOutput("orig")
                        .build());

        assertFalse(decision.isDeny());
        assertEquals("ok", decision.getModifiedOutput());
    }

    @Test
    void handlerReturnsNull_treatedAsCont() {
        HookHandler returnsNull = ctx -> null;
        HookHandler good = ctx -> HookDecision.modifyOutput("ok");
        HookDispatcher disp = dispatcher(HookEvent.POST_TOOL_USE, entry("*", returnsNull, good));

        HookDecision decision = disp.fire(HookEvent.POST_TOOL_USE,
                HookContext.builder(HookEvent.POST_TOOL_USE)
                        .toolName("exec")
                        .toolOutput("orig")
                        .build());

        assertEquals("ok", decision.getModifiedOutput());
    }

    @Test
    void multipleEntries_allMatchingRun() {
        AtomicInteger counter = new AtomicInteger();
        HookHandler h1 = ctx -> { counter.incrementAndGet(); return HookDecision.cont(); };
        HookHandler h2 = ctx -> { counter.incrementAndGet(); return HookDecision.cont(); };

        Map<HookEvent, List<HookEntry>> map = new EnumMap<>(HookEvent.class);
        map.put(HookEvent.PRE_TOOL_USE, List.of(
                new HookEntry(HookMatcher.of("exec"), List.of(h1)),
                new HookEntry(HookMatcher.of(".*"), List.of(h2))));
        HookDispatcher disp = new HookDispatcher(new HookRegistry(map));

        disp.fire(HookEvent.PRE_TOOL_USE,
                HookContext.builder(HookEvent.PRE_TOOL_USE).toolName("exec").build());

        assertEquals(2, counter.get());
    }

    @Test
    void noModifications_returnsCanonicalCont() {
        HookHandler h = ctx -> HookDecision.cont();
        HookDispatcher disp = dispatcher(HookEvent.STOP, entry("*", h));

        HookDecision decision = disp.fire(HookEvent.STOP,
                HookContext.builder(HookEvent.STOP).build());
        assertSame(HookDecision.cont(), decision);
    }
}
