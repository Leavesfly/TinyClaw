package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.GatewayConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 处理认证相关 API（/api/auth/login、/api/auth/check）。
 */
public class AuthHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;

    /**
     * 构造 AuthHandler，注入全局配置与安全中间件。
     */
    public AuthHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    /**
     * 入口路由：处理 CORS 预检后，按请求路径分发到
     * {@link #handleAuthCheck} 或 {@link #handleAuthLogin}。
     */
    public void handle(HttpExchange exchange) throws IOException {
        try {
            // Auth 端点只做 CORS 预检，不强制认证
            if (security.handleCorsPreFlight(exchange)) return;

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String corsOrigin = config.getGateway().getCorsOrigin();

            if ("/api/auth/check".equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleAuthCheck(exchange, corsOrigin);
            } else if ("/api/auth/login".equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleAuthLogin(exchange, corsOrigin);
            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not Found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Auth handler error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson("Internal error"),
                    config.getGateway().getCorsOrigin());
        }
    }

    /**
     * 检查当前请求是否已通过认证。
     * 若认证未启用，直接返回 authenticated=true；否则委托 SecurityMiddleware 校验 Token。
     */
    private void handleAuthCheck(HttpExchange exchange, String corsOrigin) throws IOException {
        GatewayConfig gatewayConfig = config.getGateway();
        if (!gatewayConfig.isAuthEnabled()) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("authenticated", true);
            result.put("authEnabled", false);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }
        // checkAuth 失败时会自动发送 401 响应
        if (security.checkAuth(exchange)) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("authenticated", true);
            result.put("authEnabled", true);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        }
    }

    /**
     * 处理登录请求：解析 username/password，匹配成功后返回 Base64 编码的 Token。
     * 认证未启用时直接返回成功。
     */
    private void handleAuthLogin(HttpExchange exchange, String corsOrigin) throws IOException {
        GatewayConfig gatewayConfig = config.getGateway();
        if (!gatewayConfig.isAuthEnabled()) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", true);
            result.put("message", "Authentication not enabled");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }

        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String username = json.path("username").asText("");
        String password = json.path("password").asText("");

        // 常量时间比较，防止时序侧信道攻击
        boolean usernameMatch = java.security.MessageDigest.isEqual(
                gatewayConfig.getUsername().getBytes(StandardCharsets.UTF_8),
                username.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatch = java.security.MessageDigest.isEqual(
                gatewayConfig.getPassword().getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));

        if (usernameMatch && passwordMatch) {
            // 签发随机不透明 Session Token（不再返回 Base64 凭据）
            String token = security.createSessionToken();
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", true);
            result.put("token", token);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } else {
            // 不记录用户提交的凭据内容，避免日志泄露敏感信息（用户可能误输密码到用户名框）
            logger.warn("Login failed", Map.of(
                    "remote", String.valueOf(exchange.getRemoteAddress())));
            WebUtils.sendJson(exchange, 401, WebUtils.errorJson("Invalid username or password"), corsOrigin);
        }
    }
}
