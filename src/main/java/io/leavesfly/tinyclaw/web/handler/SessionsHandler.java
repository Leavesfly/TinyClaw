package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
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

    public SessionsHandler(Config config, SessionManager sessionManager, SecurityMiddleware security) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_SESSIONS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                ArrayNode sessions = WebUtils.MAPPER.createArrayNode();
                for (String key : sessionManager.getSessionKeys()) {
                    ObjectNode session = WebUtils.MAPPER.createObjectNode();
                    session.put("key", key);
                    session.put("messageCount", sessionManager.getHistory(key).size());
                    sessions.add(session);
                }
                WebUtils.sendJson(exchange, 200, sessions, corsOrigin);

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
