package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.providers.*;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReActExecutor 核心循环单元测试
 *
 * <p>使用 mock LLMProvider 验证：
 * <ul>
 *   <li>无工具调用时直接返回文本</li>
 *   <li>工具调用迭代正确执行</li>
 *   <li>最大迭代次数保护</li>
 *   <li>空响应重试与兜底</li>
 *   <li>中断（abort）机制</li>
 * </ul>
 */
@DisplayName("ReActExecutor 核心循环测试")
class ReActExecutorTest {

    private ToolRegistry tools;
    private SessionManager sessions;
    private ReActExecutor executor;

    @BeforeEach
    void setUp() {
        tools = new ToolRegistry();
        sessions = new SessionManager(null); // 内存模式，不落盘
        executor = new ReActExecutor(new MockProvider(), tools, sessions, "test-model", "test", 5);
    }

    // ==================== 基础场景 ====================

    @Test
    @DisplayName("无工具调用：直接返回 LLM 文本响应")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void execute_NoToolCalls_ReturnsText() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("你好"));

        String result = executor.execute(messages, "test:session1");
        assertEquals("这是模拟回复", result);
    }

    @Test
    @DisplayName("工具调用：执行工具后再次调用 LLM 获得最终回复")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void execute_WithToolCall_IteratesAndReturns() throws Exception {
        // 注册一个 echo 工具
        tools.register(new io.leavesfly.tinyclaw.tools.Tool() {
            public String name() { return "echo"; }
            public String description() { return "回显输入"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public String execute(Map<String, Object> args) { return "echo: " + args.get("text"); }
        });

        // 使用会先调用工具再返回文本的 Provider
        executor = new ReActExecutor(new ToolCallThenTextProvider(), tools, sessions, "test-model", "test", 5);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("请回显 hello"));

        String result = executor.execute(messages, "test:session2");
        assertEquals("工具执行完毕，结果是 echo: hello", result);
    }

    @Test
    @DisplayName("最大迭代保护：超过 maxIterations 返回兜底文本")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void execute_ExceedsMaxIterations_ReturnsFallback() throws Exception {
        // 注册一个工具
        tools.register(new io.leavesfly.tinyclaw.tools.Tool() {
            public String name() { return "loop"; }
            public String description() { return "循环工具"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public String execute(Map<String, Object> args) { return "looping"; }
        });

        // Provider 永远返回工具调用（模拟无限循环）
        executor = new ReActExecutor(new AlwaysToolCallProvider(), tools, sessions, "test-model", "test", 3);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("无限循环"));

        String result = executor.execute(messages, "test:session3");
        assertTrue(result.contains("暂时无法处理") || result.contains("中断"),
                "应返回兜底文本，实际: " + result);
    }

    // ==================== 中断机制 ====================

    @Test
    @DisplayName("abort：并发中断正在执行的循环")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void execute_Aborted_ReturnsAbortMessage() throws Exception {
        // 注册一个工具，在执行时触发 abort
        tools.register(new io.leavesfly.tinyclaw.tools.Tool() {
            public String name() { return "abort_trigger"; }
            public String description() { return "触发中断"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public String execute(Map<String, Object> args) {
                executor.abort(); // 在工具执行期间触发中断
                return "triggered";
            }
        });

        // Provider 永远返回工具调用，但工具执行时会 abort
        executor = new ReActExecutor(new AlwaysToolCallProvider("abort_trigger"), tools, sessions, "test-model", "test", 10);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("测试中断"));

        String result = executor.execute(messages, "test:session4");
        assertTrue(result.contains("中断"), "应返回中断提示，实际: " + result);
    }

    // ==================== Mock Providers ====================

    /** 简单 Provider：始终返回固定文本，不调用工具 */
    private static class MockProvider implements LLMProvider {
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) {
            LLMResponse response = new LLMResponse();
            response.setContent("这是模拟回复");
            return response;
        }
        public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options, StreamCallback callback) {
            LLMResponse response = chat(messages, tools, model, options);
            if (callback != null) callback.onChunk(response.getContent());
            return response;
        }
        public String getDefaultModel() { return "test-model"; }
        public String getName() { return "mock"; }
    }

    /** 第一次调用返回工具调用，第二次返回文本 */
    private static class ToolCallThenTextProvider implements LLMProvider {
        private int callCount = 0;
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) {
            callCount++;
            LLMResponse response = new LLMResponse();
            if (callCount == 1) {
                ToolCall tc = new ToolCall("call_1", "echo", Map.of("text", "hello"));
                response.setToolCalls(List.of(tc));
                response.setContent("");
            } else {
                response.setContent("工具执行完毕，结果是 echo: hello");
            }
            return response;
        }
        public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options, StreamCallback callback) {
            return chat(messages, tools, model, options);
        }
        public String getDefaultModel() { return "test-model"; }
        public String getName() { return "tool-call-mock"; }
    }

    /** 永远返回工具调用（模拟无限循环） */
    private static class AlwaysToolCallProvider implements LLMProvider {
        private final String toolName;
        AlwaysToolCallProvider() { this("loop"); }
        AlwaysToolCallProvider(String toolName) { this.toolName = toolName; }
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) {
            LLMResponse response = new LLMResponse();
            ToolCall tc = new ToolCall("call_loop", toolName, Map.of());
            response.setToolCalls(List.of(tc));
            response.setContent("");
            return response;
        }
        public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options, StreamCallback callback) {
            return chat(messages, tools, model, options);
        }
        public String getDefaultModel() { return "test-model"; }
        public String getName() { return "always-tool"; }
    }
}
