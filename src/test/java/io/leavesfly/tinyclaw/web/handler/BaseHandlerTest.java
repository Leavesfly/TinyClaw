package io.leavesfly.tinyclaw.web.handler;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BaseHandler} 模板方法测试。
 *
 * <p>重点是那条安全属性：<b>鉴权由基类无条件执行，子类无法绕过</b>。此前每个 Handler
 * 各自调用 {@code security.preCheck}，漏调不会有任何编译或运行期提示。
 */
class BaseHandlerTest {

    private Config config;
    private SecurityMiddleware security;

    @BeforeEach
    void setUp() {
        config = new Config();
        security = new SecurityMiddleware(config);
    }

    // ==================== 鉴权是默认行为 ====================

    @Test
    @DisplayName("鉴权失败时不进入路由，业务代码一行都不执行")
    void handle_AuthorizationFailure_SkipsRoute() throws IOException {
        RecordingHandler handler = new RecordingHandler(config, security, false);
        FakeExchange exchange = new FakeExchange("GET", "/api/test");

        handler.handle(exchange);

        assertFalse(handler.routeCalled, "鉴权未通过时 route 不应被调用");
        assertEquals(0, exchange.responseCode, "鉴权环节自行决定响应，基类不应再写");
    }

    @Test
    @DisplayName("鉴权通过后进入路由")
    void handle_AuthorizationSuccess_EntersRoute() throws IOException {
        RecordingHandler handler = new RecordingHandler(config, security, true);
        FakeExchange exchange = new FakeExchange("GET", "/api/test");

        handler.handle(exchange);

        assertTrue(handler.routeCalled);
        assertEquals(200, exchange.responseCode);
    }

    @Test
    @DisplayName("默认 authorize 走 SecurityMiddleware.preCheck：OPTIONS 预检被拦下且不进路由")
    void defaultAuthorize_DelegatesToPreCheck() throws IOException {
        // 未覆盖 authorize 的子类：CORS 预检请求应被 preCheck 消化掉
        RecordingHandler handler = new RecordingHandler(config, security, null);
        FakeExchange exchange = new FakeExchange("OPTIONS", "/api/test");

        handler.handle(exchange);

        assertFalse(handler.routeCalled, "OPTIONS 预检不应进入业务路由");
        assertEquals(204, exchange.responseCode, "preCheck 应直接回 204");
    }

    @Test
    @DisplayName("handle 是 final：子类无法覆盖以绕过鉴权")
    void handle_IsFinal() throws NoSuchMethodException {
        assertTrue(java.lang.reflect.Modifier.isFinal(
                        BaseHandler.class.getMethod("handle", HttpExchange.class).getModifiers()),
                "handle 必须是 final，否则子类可以绕开鉴权外壳");
    }

    // ==================== 路由未命中与异常 ====================

    @Test
    @DisplayName("route 返回 false 时基类统一回 404")
    void handle_RouteMiss_Returns404() throws IOException {
        BaseHandler handler = new BaseHandler(config, security) {
            @Override
            protected boolean route(HttpExchange ex, String path, String method, String cors) {
                return false;
            }

            @Override
            protected boolean authorize(HttpExchange ex) {
                return true;
            }
        };
        FakeExchange exchange = new FakeExchange("GET", "/api/unknown");

        handler.handle(exchange);

        assertEquals(404, exchange.responseCode);
        assertTrue(exchange.responseBody().contains("Not found"));
    }

    @Test
    @DisplayName("route 抛异常时基类回 500 且不把异常抛给 HttpServer")
    void handle_RouteThrows_Returns500() throws IOException {
        BaseHandler handler = new BaseHandler(config, security) {
            @Override
            protected boolean route(HttpExchange ex, String path, String method, String cors) {
                throw new IllegalStateException("boom");
            }

            @Override
            protected boolean authorize(HttpExchange ex) {
                return true;
            }
        };
        FakeExchange exchange = new FakeExchange("GET", "/api/test");

        handler.handle(exchange);

        assertEquals(500, exchange.responseCode);
        assertTrue(exchange.responseBody().contains("boom"));
    }

    @Test
    @DisplayName("鉴权自身抛异常也转成 500，而不是穿透到 HttpServer 导致连接重置")
    void handle_AuthorizeThrows_Returns500() throws IOException {
        BaseHandler handler = new BaseHandler(config, security) {
            @Override
            protected boolean route(HttpExchange ex, String path, String method, String cors) {
                return true;
            }

            @Override
            protected boolean authorize(HttpExchange ex) throws IOException {
                throw new IOException("auth backend down");
            }
        };
        FakeExchange exchange = new FakeExchange("GET", "/api/test");

        handler.handle(exchange);

        assertEquals(500, exchange.responseCode);
    }

    // ==================== 鉴权例外清单护栏 ====================

    /**
     * 只有这两个端点允许绕开标准鉴权：
     * <ul>
     *   <li>{@code AuthHandler} —— 登录入口，要求已认证就永远登不进去</li>
     *   <li>{@code FilesHandler} —— {@code <img src>} 无法带 header，靠 {@code ?token=} 认证</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_AUTHORIZE_OVERRIDES =
            Set.of("AuthHandler", "FilesHandler");

    @Test
    @DisplayName("鉴权例外清单：只有 Auth 和 Files 允许覆盖 authorize")
    void authorizeOverrides_AreLimitedToDocumentedExceptions() throws IOException {
        Set<String> actual = new java.util.TreeSet<>();
        Path dir = Path.of("src/main/java/io/leavesfly/tinyclaw/web/handler");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith("Handler.java")).toList()) {
                String name = f.getFileName().toString().replace(".java", "");
                if ("BaseHandler".equals(name)) {
                    continue; // 基类本身定义默认实现
                }
                if (Files.readString(f).contains("protected boolean authorize")) {
                    actual.add(name);
                }
            }
        }

        assertEquals(ALLOWED_AUTHORIZE_OVERRIDES, actual,
                "新增的 authorize 覆盖意味着有端点绕过了标准鉴权；如确属有意，"
                        + "请在 ALLOWED_AUTHORIZE_OVERRIDES 里登记并写明理由");
    }

    // ==================== 派生信息 ====================

    @Test
    @DisplayName("apiName 由类名去掉 Handler 后缀得到，保持原有日志文案")
    void apiName_StripsHandlerSuffix() {
        assertEquals("Recording", new RecordingHandler(config, security, true).apiName());
    }

    @Test
    @DisplayName("corsOrigin 取自 gateway 配置")
    void corsOrigin_ComesFromGatewayConfig() {
        config.getGateway().setCorsOrigin("https://example.com");
        assertEquals("https://example.com",
                new RecordingHandler(config, security, true).corsOrigin());
    }

    // ==================== 测试替身 ====================

    /** 记录 route 是否被调用；authorize 为 null 时使用基类默认实现。 */
    private static class RecordingHandler extends BaseHandler {
        private final Boolean authorizeResult;
        boolean routeCalled;

        RecordingHandler(Config config, SecurityMiddleware security, Boolean authorizeResult) {
            super(config, security);
            this.authorizeResult = authorizeResult;
        }

        @Override
        protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
                throws IOException {
            routeCalled = true;
            WebUtils.sendJson(exchange, 200, WebUtils.successJson("ok"), corsOrigin);
            return true;
        }

        @Override
        protected boolean authorize(HttpExchange exchange) throws IOException {
            return authorizeResult != null ? authorizeResult : super.authorize(exchange);
        }
    }

    /** 最小 HttpExchange 替身：只记录响应码与响应体。 */
    private static class FakeExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
        int responseCode;

        FakeExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create(path);
        }

        String responseBody() {
            return responseStream.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseStream;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            // 无需释放资源
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
            // 测试不使用属性
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            // 测试不替换流
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
