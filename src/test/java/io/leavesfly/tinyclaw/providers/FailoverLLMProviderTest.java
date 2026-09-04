package io.leavesfly.tinyclaw.providers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailoverLLMProvider 单元测试
 *
 * <p>覆盖：chat 按链降级、全失败上抛、流式未透出前降级、
 * 流式已透出后不降级（防半截重复输出）。</p>
 */
@DisplayName("FailoverLLMProvider 降级装饰器测试")
class FailoverLLMProviderTest {

    /**
     * 可配置失败行为的 stub provider，记录收到的模型名。
     */
    private static class StubProvider implements LLMProvider {
        final String name;
        RuntimeException chatError;
        RuntimeException streamErrorBefore;  // 透出内容前抛
        RuntimeException streamErrorAfter;   // 透出内容后抛
        final List<String> chatModels = new ArrayList<>();
        final List<String> streamModels = new ArrayList<>();

        StubProvider(String name) {
            this.name = name;
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools,
                                String model, Map<String, Object> options) {
            chatModels.add(model);
            if (chatError != null) {
                throw chatError;
            }
            return new LLMResponse("ok:" + name);
        }

        @Override
        public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools,
                                      String model, Map<String, Object> options, StreamCallback callback) {
            streamModels.add(model);
            if (streamErrorBefore != null) {
                throw streamErrorBefore;
            }
            callback.onChunk("hi");
            if (streamErrorAfter != null) {
                throw streamErrorAfter;
            }
            return new LLMResponse("hi");
        }

        @Override
        public String getDefaultModel() {
            return name + "-default";
        }

        @Override
        public String getName() {
            return name;
        }
    }

    @Test
    @DisplayName("chat: 主 Provider 失败时降级到备用，且备用使用自带模型名")
    void chat_FailsOverToBackupWithOwnModel() {
        StubProvider primary = new StubProvider("openai");
        primary.chatError = new LLMException("rate limited");
        StubProvider fallback = new StubProvider("dashscope");

        FailoverLLMProvider provider = new FailoverLLMProvider(
                List.of(primary, fallback), List.of("gpt-4o", "qwen3.8-flash"));

        LLMResponse response = provider.chat(List.of(), null, "gpt-4o", null);

        assertEquals("ok:dashscope", response.getContent());
        assertEquals(List.of("gpt-4o"), primary.chatModels);
        assertEquals(List.of("qwen3.8-flash"), fallback.chatModels, "备用应使用自带模型名");
    }

    @Test
    @DisplayName("chat: 全部失败时抛出聚合异常")
    void chat_AllFail_Throws() {
        StubProvider primary = new StubProvider("a");
        primary.chatError = new LLMException("down");
        StubProvider fallback = new StubProvider("b");
        fallback.chatError = new LLMException("also down");

        FailoverLLMProvider provider = new FailoverLLMProvider(
                List.of(primary, fallback), List.of("m1", "m2"));

        LLMException e = assertThrows(LLMException.class,
                () -> provider.chat(List.of(), null, "m1", null));
        assertTrue(e.getMessage().contains("all providers failed"));
    }

    @Test
    @DisplayName("chatStream: 未透出内容前失败可降级")
    void chatStream_FailsOverBeforeEmit() {
        StubProvider primary = new StubProvider("a");
        primary.streamErrorBefore = new LLMException("connect refused");
        StubProvider fallback = new StubProvider("b");

        FailoverLLMProvider provider = new FailoverLLMProvider(
                List.of(primary, fallback), List.of("m1", "m2"));

        List<String> chunks = new ArrayList<>();
        LLMResponse response = provider.chatStream(List.of(), null, "m1", null, chunks::add);

        assertEquals("hi", response.getContent());
        assertEquals(List.of("hi"), chunks);
        assertEquals(List.of("m2"), fallback.streamModels);
    }

    @Test
    @DisplayName("chatStream: 已透出内容后失败不降级，避免半截重复输出")
    void chatStream_NoFailoverAfterEmit() {
        StubProvider primary = new StubProvider("a");
        primary.streamErrorAfter = new LLMException("died mid-stream");
        StubProvider fallback = new StubProvider("b");

        FailoverLLMProvider provider = new FailoverLLMProvider(
                List.of(primary, fallback), List.of("m1", "m2"));

        assertThrows(LLMException.class,
                () -> provider.chatStream(List.of(), null, "m1", null, chunk -> { }));
        assertTrue(fallback.streamModels.isEmpty(), "已透出内容后不得降级");
    }

    @Test
    @DisplayName("getName: 主名称附带降级数量")
    void getName_IncludesFallbackCount() {
        FailoverLLMProvider provider = new FailoverLLMProvider(
                List.of(new StubProvider("openai"), new StubProvider("ollama")),
                List.of("m1", "m2"));
        assertEquals("openai+failover:1", provider.getName());
    }
}
