package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ollama 思考模式关闭验证（临时测试，验证后可删除）。
 *
 * 1. 捕获测试：用本地假服务捕获请求体，验证思考开关对关闭参数的影响
 * 2. 实弹测试：调用本地 ollama 的 qwen3.5:4b，确认回复不含思考内容
 */
class OllamaThinkingModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 捕获测试：假 ollama 端点记录请求体，验证思考开关对请求参数的影响 */
    @Test
    void requestShouldRespectThinkingSwitch() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = fakeCompletionResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        try {
            HTTPProvider provider = new HTTPProvider("", "http://localhost:" + server.getAddress().getPort() + "/v1", "ollama");

            // 场景 1：默认开启思考模式，不注入任何关闭参数
            LLMResponse response = provider.chat(
                    List.of(Message.user("你好")), null, "qwen3.5:4b", null);
            assertEquals("好的", response.getContent());
            JsonNode defaultBody = MAPPER.readTree(capturedBody.get());
            assertFalse(defaultBody.has("reasoning_effort"), "默认开启时不应注入 reasoning_effort");
            assertFalse(defaultBody.has("enable_thinking"), "ollama 不应使用 enable_thinking 参数");
            System.out.println("【捕获测试通过】默认开启：请求体无关闭参数");

            // 场景 2：关闭思考模式，注入 reasoning_effort=none（而非 enable_thinking）
            provider.setThinkingEnabled(false);
            provider.chat(List.of(Message.user("你好")), null, "qwen3.5:4b", null);
            JsonNode disabledBody = MAPPER.readTree(capturedBody.get());
            assertTrue(disabledBody.has("reasoning_effort"), "关闭时请求应携带 reasoning_effort 参数");
            assertEquals("none", disabledBody.get("reasoning_effort").asText(), "reasoning_effort 应为 none");
            assertFalse(disabledBody.has("enable_thinking"), "ollama 不应注入被 /v1 端点忽略的 enable_thinking");
            System.out.println("【捕获测试通过】关闭后 reasoning_effort 字段: " + disabledBody.get("reasoning_effort"));
        } finally {
            server.stop(0);
        }
    }

    /** 实弹测试：关闭思考后真实调用本地 ollama 流式接口，断言无 THINKING 事件；若 ollama 未运行则跳过 */
    @Test
    void liveCallShouldNotContainThinking() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(isOllamaRunning(), "本地 ollama 未运行，跳过实弹测试");

        HTTPProvider provider = new HTTPProvider("", "http://localhost:11434/v1", "ollama");
        provider.setThinkingEnabled(false);

        StringBuilder streamed = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        LLMProvider.EnhancedStreamCallback callback = event -> {
            if (event.getType() == StreamEvent.EventType.THINKING) {
                thinking.append(event.getContent());
            } else if (event.getType() == StreamEvent.EventType.CONTENT) {
                streamed.append(event.getContent());
            }
        };
        LLMResponse response = provider.chatStream(
                List.of(Message.user("9.11和9.8哪个大？直接给答案")),
                null, "qwen3.5:4b", null, callback);

        String content = response.getContent();
        System.out.println("===== 实弹测试：模型回复（关闭思考后）=====");
        System.out.println(content);
        System.out.println("==========================================");

        assertNotNull(content);
        assertFalse(content.isEmpty(), "回复不应为空");
        assertEquals(content, streamed.toString(), "流式拼接内容应与最终回复一致");
        assertTrue(thinking.isEmpty(),
                "关闭思考后不应有思维链输出，实际: " + thinking.substring(0, Math.min(200, thinking.length())));
    }

    /** 解析测试：模拟带 reasoning 字段的 SSE 流，验证 THINKING 事件透出且不混入正文 */
    @Test
    void parserShouldEmitThinkingEvents() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"reasoning":"先比较整数部分"}}]}
    
                data: {"choices":[{"delta":{"reasoning":"，都是 9"}}]}
    
                data: {"choices":[{"delta":{"content":"9.8 更大"}}]}
    
                data: [DONE]
    
                """;
        Buffer source = new Buffer().writeUtf8(sse);
    
        List<String> thinkingChunks = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        LLMProvider.EnhancedStreamCallback callback = event -> {
            if (event.getType() == StreamEvent.EventType.THINKING) {
                thinkingChunks.add(event.getContent());
            } else if (event.getType() == StreamEvent.EventType.CONTENT) {
                content.append(event.getContent());
            }
        };
    
        StreamResponseParser parser = new StreamResponseParser();
        LLMResponse response = parser.parseStreamResponse(source, callback);
    
        // 行缓冲后，无换行的 token 级 chunk 会聚合到流结束一次性透出（避免碎片化事件）
        assertEquals(1, thinkingChunks.size(), "无换行的思维链应聚合为 1 个 THINKING 事件");
        assertEquals("先比较整数部分，都是 9", String.join("", thinkingChunks));
        assertEquals("9.8 更大", content.toString(), "正文不应混入思维链内容");
        assertEquals("9.8 更大", response.getContent(), "最终响应的 content 不含思维链");
        System.out.println("【解析测试通过】THINKING 事件: " + thinkingChunks + ", 正文: " + content);
    }
    
    /**
     * 解析测试：模拟 token 粒度且含换行的 reasoning 流，
     * 验证 THINKING 事件按行聚合透出（而非逐 token 碎片）。
     */
    @Test
    void parserShouldAggregateThinkingByLine() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"reasoning":"先比较整数"}}]}
    
                data: {"choices":[{"delta":{"reasoning":"部分。\\n再比较"}}]}
    
                data: {"choices":[{"delta":{"reasoning":"小数部分。\\n"}}]}
    
                data: {"choices":[{"delta":{"content":"9.8 更大"}}]}
    
                data: [DONE]
    
                """;
        Buffer source = new Buffer().writeUtf8(sse);
    
        List<String> thinkingChunks = new ArrayList<>();
        LLMProvider.EnhancedStreamCallback callback = event -> {
            if (event.getType() == StreamEvent.EventType.THINKING) {
                thinkingChunks.add(event.getContent());
            }
        };
    
        StreamResponseParser parser = new StreamResponseParser();
        parser.parseStreamResponse(source, callback);
    
        assertEquals(2, thinkingChunks.size(), "应按行透出 2 个 THINKING 事件，而非逐 token 碎片");
        assertEquals("先比较整数部分。\n", thinkingChunks.get(0), "第一行应含行尾换行");
        assertEquals("再比较小数部分。\n", thinkingChunks.get(1), "第二行应含行尾换行");
    }

    private static boolean isOllamaRunning() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/tags"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET().build();
            return client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fakeCompletionResponse() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"好的\"},\"finish_reason\":\"stop\"}]}";
    }
}
