package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.Session;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 处理会话管理 API（/api/sessions）。
 */
public class SessionsHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SessionManager sessionManager;
    private final SecurityMiddleware security;

    /**
     * 构造 SessionsHandler，注入全局配置、会话管理器与安全中间件。
     */
    public SessionsHandler(Config config, SessionManager sessionManager, SecurityMiddleware security) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.security = security;
    }

    /**
     * 入口路由：预检通过后，按路径分发列表查询、历史记录获取或删除操作。
     * 路径中的 sessionKey 使用 URL 解码处理。
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_SESSIONS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                ArrayNode sessions = WebUtils.MAPPER.createArrayNode();
                for (String key : sessionManager.getSessionKeys()) {
                    var history = sessionManager.getHistory(key);
                    ObjectNode session = WebUtils.MAPPER.createObjectNode();
                    session.put("key", key);
                    session.put("messageCount", history.size());
                    // 取第一条 user 消息作为会话预览标题
                    String firstMessage = history.stream()
                            .filter(m -> "user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank())
                            .findFirst()
                            .map(m -> m.getContent().length() > 15 ? m.getContent().substring(0, 15) + "…" : m.getContent())
                            .orElse("");
                    session.put("firstMessage", firstMessage);
                    sessions.add(session);
                }
                WebUtils.sendJson(exchange, 200, sessions, corsOrigin);

            } else if (WebUtils.API_SESSIONS.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
                String body = WebUtils.readRequestBodyLimited(exchange);
                JsonNode json = WebUtils.MAPPER.readTree(body);
                String sessionKey = json.path("sessionKey").asText();
                if (sessionKey == null || sessionKey.isBlank()) {
                    WebUtils.sendJson(exchange, 400, WebUtils.errorJson("sessionKey is required"), corsOrigin);
                    return;
                }
                Session session = sessionManager.getOrCreate(sessionKey);
                sessionManager.save(session);
                ObjectNode result = WebUtils.MAPPER.createObjectNode();
                result.put("key", sessionKey);
                result.put("messageCount", 0);
                WebUtils.sendJson(exchange, 200, result, corsOrigin);

            } else if (path.startsWith(WebUtils.API_SESSIONS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_GET.equals(method)) {
                String key = URLDecoder.decode(
                        path.substring(WebUtils.API_SESSIONS.length() + 1), StandardCharsets.UTF_8);
                var history = sessionManager.getHistory(key);
                ArrayNode messages = WebUtils.MAPPER.createArrayNode();
                for (var msg : history) {
                    ObjectNode m = WebUtils.MAPPER.createObjectNode();
                    m.put("role", msg.getRole());
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    // 添加图片字段（多模态支持）
                    if (msg.hasImages()) {
                        ArrayNode imagesArray = m.putArray("images");
                        for (String imgPath : msg.getImages()) {
                            imagesArray.add(imgPath);
                        }
                    }
                    messages.add(m);
                }
                WebUtils.sendJson(exchange, 200, messages, corsOrigin);

            } else if (path.startsWith(WebUtils.API_SESSIONS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_DELETE.equals(method)) {
                String key = URLDecoder.decode(
                        path.substring(WebUtils.API_SESSIONS.length() + 1), StandardCharsets.UTF_8);
                sessionManager.deleteSession(key);
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Session deleted"), corsOrigin);

            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Sessions API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }
}
