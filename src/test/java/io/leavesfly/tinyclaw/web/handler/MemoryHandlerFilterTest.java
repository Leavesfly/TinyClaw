package io.leavesfly.tinyclaw.web.handler;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryHandler} 记忆列表筛选/分页与来源会话记录测试（P5 可控记忆切片）。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>GET /api/memory 支持 q/scope/tag/source 筛选与 offset/limit 分页，
 *       响应携带 total（过滤后总数），offset 超界与非法 limit 均被钳制；</li>
 *   <li>POST /api/memory 接受可选 sourceSessionKey 并持久化、列表回显；</li>
 *   <li>sourceSessionKey 仅放行安全字符集（字母数字/下划线/连字符），
 *       路径分隔符、URL 等值返回 400 且不入库——来源导航不接受任意路径跳转。</li>
 * </ol></p>
 */
class MemoryHandlerFilterTest {

    @TempDir
    Path workspace;

    private MemoryStore store;
    private MemoryHandler handler;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        SecurityMiddleware security = new SecurityMiddleware(config);
        store = new MemoryStore(workspace.toString());
        // 覆盖 authorize 跳过鉴权：本测试聚焦业务逻辑而非安全外壳（外壳由 BaseHandlerTest 覆盖）
        handler = new MemoryHandler(config, security, store) {
            @Override
            protected boolean authorize(HttpExchange exchange) {
                return true;
            }
        };
    }

    private void seed(String scope, String content, List<String> tags, String source, String sessionKey) {
        store.addEntry(scope, content, 0.5, tags, source, sessionKey);
    }

    private JsonNode get(String query) throws IOException {
        FakeExchange exchange = new FakeExchange("GET", "/api/memory" + (query != null ? "?" + query : ""), null);
        handler.handle(exchange);
        assertEquals(200, exchange.responseCode, "列表请求应成功：" + exchange.responseBody());
        return WebUtils.MAPPER.readTree(exchange.responseBody());
    }

    /** 关键词安全编码后的列表查询（含空格等字符时必须编码，同真实浏览器行为）。 */
    private JsonNode search(String keyword) throws IOException {
        return get("q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8));
    }

    private int postStatus(String body) throws IOException {
        FakeExchange exchange = new FakeExchange("POST", "/api/memory", body);
        handler.handle(exchange);
        return exchange.responseCode;
    }

    // ==================== 筛选 ====================

    @Test
    @DisplayName("q 关键词匹配内容与标签，大小写不敏感")
    void list_KeywordFilter_MatchesContentAndTags() throws IOException {
        seed("_global", "User prefers dark mode", List.of("preference"), "user_explicit", null);
        seed("_global", "Deploy pipeline uses maven", List.of("tooling"), "evolution", null);
        seed("_global", "Nothing relevant here", List.of("misc"), "heartbeat", null);

        JsonNode all = get("q=dark");
        assertEquals(1, all.get("total").asInt(), "内容匹配应命中 1 条");
        assertEquals("User prefers dark mode", all.get("entries").get(0).get("content").asText());

        JsonNode byTag = get("q=TOOLING");
        assertEquals(1, byTag.get("total").asInt(), "标签匹配（大写关键词）应命中 1 条");
        assertEquals("Deploy pipeline uses maven", byTag.get("entries").get(0).get("content").asText());
    }

    @Test
    @DisplayName("scope 与 source 精确过滤")
    void list_ScopeAndSourceFilter() throws IOException {
        seed("_global", "global entry", List.of(), "user_explicit", null);
        seed("user:web/alice", "user entry", List.of(), "user_explicit", null);
        seed("_global", "evolved entry", List.of(), "evolution", null);

        JsonNode byScope = get("scope=user:web/alice");
        assertEquals(1, byScope.get("total").asInt());
        assertEquals("user entry", byScope.get("entries").get(0).get("content").asText());

        JsonNode bySource = get("source=evolution");
        assertEquals(1, bySource.get("total").asInt());
        assertEquals("evolved entry", bySource.get("entries").get(0).get("content").asText());

        JsonNode combined = get("scope=_global&source=user_explicit");
        assertEquals(1, combined.get("total").asInt());
    }

    @Test
    @DisplayName("tag 精确命中标签列表之一")
    void list_TagFilter() throws IOException {
        seed("_global", "entry with tags", List.of("alpha", "beta"), "user_explicit", null);
        seed("_global", "entry without that tag", List.of("gamma"), "user_explicit", null);

        JsonNode byTag = get("tag=beta");
        assertEquals(1, byTag.get("total").asInt());
        assertEquals("entry with tags", byTag.get("entries").get(0).get("content").asText());
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("offset/limit 分页：响应携带 total，第二页内容正确")
    void list_Pagination() throws IOException {
        for (int i = 1; i <= 5; i++) {
            // 创建时间同秒内顺序不稳定，用内容序号断言相对顺序即可（倒序=晚创建在前）
            seed("_global", "memo-" + i, List.of(), "user_explicit", null);
        }

        JsonNode page1 = get("limit=2&offset=0");
        assertEquals(5, page1.get("total").asInt(), "total 应为过滤后总数而非当前页条数");
        assertEquals(2, page1.get("count").asInt());
        assertEquals(0, page1.get("offset").asInt());
        assertEquals(2, page1.get("limit").asInt());

        JsonNode page3 = get("limit=2&offset=4");
        assertEquals(1, page3.get("count").asInt(), "最后一页只剩 1 条");
    }

    @Test
    @DisplayName("offset 超界钳制为空页；limit 非法取默认，超限取上限")
    void list_PaginationClamping() throws IOException {
        seed("_global", "only entry", List.of(), "user_explicit", null);

        JsonNode beyond = get("limit=10&offset=99");
        assertEquals(1, beyond.get("total").asInt());
        assertEquals(0, beyond.get("count").asInt(), "offset 超界应返回空页而非报错");
        assertEquals(1, beyond.get("offset").asInt(), "offset 被钳制到 total");

        JsonNode badLimit = get("limit=abc");
        assertEquals(50, badLimit.get("limit").asInt(), "非法 limit 应回退默认 50");

        JsonNode hugeLimit = get("limit=9999");
        assertEquals(200, hugeLimit.get("limit").asInt(), "超限 limit 应钳制到 200");
    }

    // ==================== 来源会话 ====================

    @Test
    @DisplayName("POST 带 sourceSessionKey 入库并在列表回显")
    void add_SourceSessionKeyPersisted() throws IOException {
        assertEquals(200, postStatus(
                "{\"content\":\"User works on TinyClaw\",\"sourceSessionKey\":\"web-20260910-abc\"}"));

        JsonNode list = get("q=TinyClaw");
        assertEquals(1, list.get("total").asInt());
        assertEquals("web-20260910-abc", list.get("entries").get(0).get("sourceSessionKey").asText());
        assertEquals("web-console", list.get("entries").get(0).get("source").asText());
    }

    @Test
    @DisplayName("不传 sourceSessionKey 时为空，历史条目显示为空而非报错")
    void add_SourceSessionKeyOptional() throws IOException {
        assertEquals(200, postStatus("{\"content\":\"no source session\"}"));

        JsonNode list = search("no source session");
        assertEquals(1, list.get("total").asInt());
        assertTrue(list.get("entries").get(0).get("sourceSessionKey").asText().isEmpty(),
                "未提供来源会话时应回显空字符串，前端按未记录处理");
    }

    @Test
    @DisplayName("sourceSessionKey 含路径分隔符/URL/点号被拒绝且不入库")
    void add_IllegalSourceSessionKeyRejected() throws IOException {
        String[] illegal = {
                "../../etc/passwd",
                "http://evil.example.com/mem",
                "a b c",
                "id;rm -rf",
                "web?query=1",
                "web#fragment"
        };
        for (String bad : illegal) {
            String body = WebUtils.MAPPER.createObjectNode()
                    .put("content", "bad source")
                    .put("sourceSessionKey", bad).toString();
            assertEquals(400, postStatus(body), "非法来源会话键应返回 400：" + bad);
        }
        JsonNode list = search("bad source");
        assertEquals(0, list.get("total").asInt(), "被拒绝的请求不应入库");
    }

    @Test
    @DisplayName("Web 会话 key 形如 web:1725968000000 应被接受（含冒号）")
    void add_WebSessionKeyAccepted() throws IOException {
        assertEquals(200, postStatus(
                "{\"content\":\"From web session\",\"sourceSessionKey\":\"web:1725968000000\"}"));

        JsonNode list = search("From web session");
        assertEquals(1, list.get("total").asInt());
        assertEquals("web:1725968000000", list.get("entries").get(0).get("sourceSessionKey").asText());
    }

    // ==================== 测试用 HttpExchange 桩 ====================

    /** 精简 HttpExchange 桩：支持带 query 的 URI 与 POST 请求体。 */
    private static class FakeExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final byte[] requestBody;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
        int responseCode;

        FakeExchange(String method, String path, String body) {
            this.method = method;
            this.uri = URI.create(path);
            this.requestBody = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        }

        String responseBody() {
            return responseStream.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public Headers getRequestHeaders() { return requestHeaders; }

        @Override
        public Headers getResponseHeaders() { return responseHeaders; }

        @Override
        public URI getRequestURI() { return uri; }

        @Override
        public String getRequestMethod() { return method; }

        @Override
        public InputStream getRequestBody() { return new ByteArrayInputStream(requestBody); }

        @Override
        public OutputStream getResponseBody() { return responseStream; }

        @Override
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }

        @Override
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }

        @Override
        public int getResponseCode() { return responseCode; }

        @Override
        public String getProtocol() { return "HTTP/1.1"; }

        @Override
        public HttpContext getHttpContext() { return null; }

        @Override
        public void close() { /* 无需释放资源 */ }

        @Override
        public Object getAttribute(String name) { return null; }

        @Override
        public void setAttribute(String name, Object value) { /* 测试不使用属性 */ }

        @Override
        public void setStreams(InputStream i, OutputStream o) { /* 测试不替换流 */ }

        @Override
        public HttpPrincipal getPrincipal() { return null; }
    }
}
