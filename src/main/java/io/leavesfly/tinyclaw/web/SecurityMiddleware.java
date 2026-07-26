package io.leavesfly.tinyclaw.web;

import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.GatewayConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全中间件，提供 CORS 预检、认证（Bearer Session Token / Basic Auth）和速率限制能力。
 */
public class SecurityMiddleware {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /** Session Token 有效期：24 小时 */
    private static final long SESSION_TOKEN_TTL_MS = 24 * 60 * 60 * 1000L;

    private final Config config;
    /** 按 IP 分桶的速率限制：IP → [windowStart, count] */
    private final ConcurrentHashMap<String, long[]> rateLimitByIp = new ConcurrentHashMap<>();

    /** 服务端 Session Token 存储：token → 过期时间戳 */
    private final ConcurrentHashMap<String, Long> sessionTokens = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public SecurityMiddleware(Config config) {
        this.config = config;
    }

    /**
     * 创建新的 Session Token（登录成功后调用）。
     *
     * @return 随机生成的不透明 token
     */
    public String createSessionToken() {
        // 清理过期 token
        long now = System.currentTimeMillis();
        sessionTokens.entrySet().removeIf(e -> e.getValue() < now);

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessionTokens.put(token, now + SESSION_TOKEN_TTL_MS);
        return token;
    }

    /**
     * 验证 Session Token 是否有效（未过期）。
     *
     * @param token 待验证的 token
     * @return true 表示有效
     */
    public boolean isValidSessionToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long expiry = sessionTokens.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            sessionTokens.remove(token);
            return false;
        }
        return true;
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
     * 检查认证：支持 Bearer Session Token 和 Basic Auth 两种方式。
     *
     * @return true 表示认证通过（或未启用认证），false 表示认证失败（已发送 401 响应）
     */
    public boolean checkAuth(HttpExchange exchange) throws IOException {
        GatewayConfig gatewayConfig = config.getGateway();
        if (!gatewayConfig.isAuthEnabled()) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst(WebUtils.HEADER_AUTHORIZATION);

        // Bearer Session Token 方式（推荐）
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length());
            if (isValidSessionToken(token)) {
                return true;
            }
            sendAuthChallenge(exchange);
            return false;
        }

        // Basic Auth 方式（兼容旧版前端）
        if (authHeader != null && authHeader.startsWith("Basic ")) {
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

            // 常量时间比较，防止时序侧信道攻击
            boolean usernameMatch = MessageDigest.isEqual(
                    gatewayConfig.getUsername().getBytes(StandardCharsets.UTF_8),
                    inputUsername.getBytes(StandardCharsets.UTF_8));
            boolean passwordMatch = MessageDigest.isEqual(
                    gatewayConfig.getPassword().getBytes(StandardCharsets.UTF_8),
                    inputPassword.getBytes(StandardCharsets.UTF_8));

            if (usernameMatch && passwordMatch) {
                return true;
            }

            logger.warn("Authentication failed", Map.of("username", inputUsername));
            sendAuthChallenge(exchange);
            return false;
        }

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
     * 检查请求速率限制（按 IP 分桶，每分钟滑动窗口）。
     *
     * @return true 表示未超限，false 表示已超限（已发送 429 响应）
     */
    public boolean checkRateLimit(HttpExchange exchange) throws IOException {
        GatewayConfig gatewayConfig = config.getGateway();
        if (!gatewayConfig.isRateLimitEnabled()) {
            return true;
        }

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();

        long[] window = rateLimitByIp.compute(clientIp, (key, existing) -> {
            if (existing == null || now - existing[0] >= 60_000) {
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });

        if (window[1] > gatewayConfig.getRateLimitPerMinute()) {
            logger.warn("Rate limit exceeded", Map.of(
                    "ip", clientIp,
                    "count", window[1],
                    "limit", gatewayConfig.getRateLimitPerMinute()));
            WebUtils.sendJson(exchange, 429, WebUtils.errorJson("Rate limit exceeded. Try again later."),
                    config.getGateway().getCorsOrigin());
            return false;
        }

        return true;
    }
}
