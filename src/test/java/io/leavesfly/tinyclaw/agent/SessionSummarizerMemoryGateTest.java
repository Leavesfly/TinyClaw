package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.session.SessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SessionSummarizer} 会话记忆模式写路径测试（P5）。
 *
 * <p>计划验收要求「OFF 会话的自动读写测试均为零」：读路径（记忆注入跳过）已由
 * ContextBuilderTest 覆盖；本测试覆盖写路径——摘要压缩仍正常发生（聊天历史保存），
 * 但 memoryMode=OFF 的会话<b>不得</b>把摘要写入结构化记忆。</p>
 *
 * <p>触发链路走真实公开入口 {@code maybeSummarize}（>200 条消息阈值），摘要执行为
 * 单线程异步，测试轮询等待完成。</p>
 */
@DisplayName("SessionSummarizer 记忆模式写路径测试")
class SessionSummarizerMemoryGateTest {

    @TempDir
    Path sessionsDir;

    @TempDir
    Path workspace;

    private static final int MESSAGES_TO_SEED = 210; // 超过 SUMMARIZE_MESSAGE_THRESHOLD(200)

    @Test
    @DisplayName("memoryMode=OFF 会话：摘要压缩照常执行，但不写入结构化记忆")
    void summarize_MemoryModeOff_SkipsMemoryWrite() throws Exception {
        SessionManager sessions = new SessionManager(sessionsDir.toString());
        MemoryStore memoryStore = new MemoryStore(workspace.toString());
        LLMProvider provider = stubProvider();
        AtomicInteger chatCalls = new AtomicInteger();
        when(provider.chat(any(), any(), anyString(), any())).thenAnswer(inv -> {
            chatCalls.incrementAndGet();
            return new LLMResponse("OFF 会话的压缩摘要");
        });

        SessionSummarizer summarizer = new SessionSummarizer(
                sessions, provider, "test-model", 128000, memoryStore, null,
                key -> !"web:off-session".equals(key)); // OFF：web:off-session 关闭记忆

        seedMessages(sessions, "web:off-session");
        summarizer.maybeSummarize("web:off-session");
        awaitSummarize(summarizer);

        // 摘要压缩本身必须发生（聊天历史仍保存，token 阈值由 shouldSummarize 判定）
        assertTrue(chatCalls.get() > 0, "OFF 会话的摘要压缩照常执行（不因关闭记忆而停摆）");
        // 但结构化记忆零写入
        assertEquals(0, memoryStore.getEntries().size(),
                "OFF 会话不得把摘要写入结构化记忆（自动提取写入为零）");
        summarizer.shutdown();
    }

    @Test
    @DisplayName("memoryMode=DEFAULT 会话：摘要照常写入结构化记忆（回归对照）")
    void summarize_MemoryModeDefault_WritesMemory() throws Exception {
        SessionManager sessions = new SessionManager(sessionsDir.toString());
        MemoryStore memoryStore = new MemoryStore(workspace.toString());
        LLMProvider provider = stubProvider();
        when(provider.chat(any(), any(), anyString(), any()))
                .thenReturn(new LLMResponse("DEFAULT 会话的压缩摘要"));

        SessionSummarizer summarizer = new SessionSummarizer(
                sessions, provider, "test-model", 128000, memoryStore, null,
                key -> true); // DEFAULT：全部启用

        seedMessages(sessions, "web:on-session");
        summarizer.maybeSummarize("web:on-session");
        awaitSummarize(summarizer);

        assertTrue(memoryStore.getEntries().size() > 0,
                "DEFAULT 会话摘要后应写入结构化记忆");
        assertTrue(memoryStore.getEntries().stream()
                        .anyMatch(e -> "session_summary".equals(e.getSource())),
                "写入的记忆来源应为 session_summary");
        // 写入归属该会话的聊天域，而非全局域
        assertTrue(memoryStore.getEntries().stream()
                        .allMatch(e -> e.getScope().contains("web")),
                "会话摘要应归属聊天域，不得写成全局可见");
        summarizer.shutdown();
    }

    @Test
    @DisplayName("gate 为 null（未配置）时保持旧行为：摘要照常写入")
    void summarize_NoGate_KeepsLegacyBehavior() throws Exception {
        SessionManager sessions = new SessionManager(sessionsDir.toString());
        MemoryStore memoryStore = new MemoryStore(workspace.toString());
        LLMProvider provider = stubProvider();
        when(provider.chat(any(), any(), anyString(), any()))
                .thenReturn(new LLMResponse("旧配置的压缩摘要"));

        SessionSummarizer summarizer = new SessionSummarizer(
                sessions, provider, "test-model", 128000, memoryStore, null, null);

        seedMessages(sessions, "web:legacy-session");
        summarizer.maybeSummarize("web:legacy-session");
        awaitSummarize(summarizer);

        assertTrue(memoryStore.getEntries().size() > 0,
                "未配置门时保持旧行为：摘要写入记忆");
        summarizer.shutdown();
    }

    // ==================== 辅助 ====================

    /** Mockito 桩 LLMProvider（chat 由各用例自行 when 打桩）。 */
    private LLMProvider stubProvider() {
        return mock(LLMProvider.class);
    }

    /** 灌入超过摘要阈值的消息（user/assistant 交替，内容带序号保证 token 估算非零）。 */
    private void seedMessages(SessionManager sessions, String sessionKey) {
        for (int i = 0; i < MESSAGES_TO_SEED; i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            sessions.addMessage(sessionKey, role, "message content number " + i + " about topic details");
        }
        sessions.save(sessionKey);
    }

    /** 轮询等待异步摘要完成（shutdown 让线程池排空任务后终止）。 */
    private void awaitSummarize(SessionSummarizer summarizer) throws InterruptedException {
        summarizer.shutdown();
        boolean done = summarizer.awaitTerminationQuietly(10, TimeUnit.SECONDS);
        assertTrue(done, "异步摘要应在超时内完成");
    }
}
