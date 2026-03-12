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

    public AuthHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

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

        if (gatewayConfig.getUsername().equals(username)
                && gatewayConfig.getPassword().equals(password)) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", true);
            result.put("token", token);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } else {
            logger.warn("Login failed", Map.of("username", username));
            WebUtils.sendJson(exchange, 401, WebUtils.errorJson("Invalid username or password"), corsOrigin);
        }
    }
}
