package io.leavesfly.tinyclaw.web;

import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.GatewayConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全中间件，提供 CORS 预检、Basic Auth 认证和速率限制能力。
 */
public class SecurityMiddleware {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile long rateLimitWindowStart = System.currentTimeMillis();

    public SecurityMiddleware(Config config) {
        this.config = config;
    }

    /**
     * 统一前置检查：CORS 预检 → 认证 → 速率限制。
     *
     * @return true 表示所有检查通过，false 表示已拦截（已发送响应）
     */
    public boolean preCheck(HttpExchange exchange) throws IOException {
        if (handleCorsPreFlight(exchange)) return false;
        if (!checkAuth(exchange)) return false;
        if (!checkRateLimit(exchange)) return false;
        return true;
    }

    /**
     * 处理 CORS 预检请求（OPTIONS）。
     *
     * @return true 表示是 OPTIONS 请求且已处理
     */
    public boolean handleCorsPreFlight(HttpExchange exchange) throws IOException {
        if (WebUtils.HTTP_METHOD_OPTIONS.equals(exchange.getRequestMethod())) {
            String corsOrigin = config.getGateway().getCorsOrigin();
            exchange.getResponseHeaders().set(WebUtils.HEADER_CORS, corsOrigin);
            exchange.getResponseHeaders().set(WebUtils.HEADER_CORS_HEADERS, WebUtils.HEADER_CORS_HEADERS_VALUE);
            exchange.getResponseHeaders().set(WebUtils.HEADER_CORS_METHODS, WebUtils.HEADER_CORS_METHODS_VALUE);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    /**
     * 检查 Basic Auth 认证。
     *
     * @return true 表示认证通过（或未启用认证），false 表示认证失败（已发送 401 响应）
     */
    public boolean checkAuth(HttpExchange exchange) throws IOException {
        GatewayConfig gatewayConfig = config.getGateway();
        if (!gatewayConfig.isAuthEnabled()) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst(WebUtils.HEADER_AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            sendAuthChallenge(exchange);
            return false;
        }

        String base64Credentials = authHeader.substring("Basic ".length());
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            sendAuthChallenge(exchange);
            return false;
        }

        int colonIndex = credentials.indexOf(':');
        if (colonIndex < 0) {
            sendAuthChallenge(exchange);
            return false;
        }

        String inputUsername = credentials.substring(0, colonIndex);
        String inputPassword = credentials.substring(colonIndex + 1);

        if (gatewayConfig.getUsername().equals(inputUsername)
                && gatewayConfig.getPassword().equals(inputPassword)) {
            return true;
        }

        logger.warn("Authentication failed", Map.of("username", inputUsername));
        sendAuthChallenge(exchange);
        return false;
    }

    /**
     * 发送 401 认证失败响应（不带 WWW-Authenticate 头，避免触发浏览器原生弹窗）。
     */
    public void sendAuthChallenge(HttpExchange exchange) throws IOException {
        WebUtils.sendJson(exchange, 401, WebUtils.errorJson("Authentication required"),
                config.getGateway().getCorsOrigin());
    }

    /**
     * 检查请求速率限制（每分钟滑动窗口）。
     *
     * @return true 表示未超限，false 表示已超限（已发送 429 响应）
     */
    public boolean checkRateLimit(HttpExchange exchange) throws IOException {
        GatewayConfig gatewayConfig = config.getGateway();
        if (!gatewayConfig.isRateLimitEnabled()) {
            return true;
        }

        long now = System.currentTimeMillis();
        long windowStart = rateLimitWindowStart;

        if (now - windowStart >= 60_000) {
            rateLimitWindowStart = now;
            requestCount.set(0);
        }

        int currentCount = requestCount.incrementAndGet();
        if (currentCount > gatewayConfig.getRateLimitPerMinute()) {
            logger.warn("Rate limit exceeded", Map.of(
                    "count", currentCount,
                    "limit", gatewayConfig.getRateLimitPerMinute()));
            WebUtils.sendJson(exchange, 429, WebUtils.errorJson("Rate limit exceeded. Try again later."),
                    config.getGateway().getCorsOrigin());
            return false;
        }

        return true;
    }
}
