package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 处理聊天 API（/api/chat 和 /api/chat/stream）。
 */
public class ChatHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final AgentLoop agentLoop;
    private final SecurityMiddleware security;

    public ChatHandler(Config config, AgentLoop agentLoop, SecurityMiddleware security) {
        this.config = config;
        this.agentLoop = agentLoop;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_CHAT.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleChatNormal(exchange);
            } else if (WebUtils.API_CHAT_STREAM.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleChatStream(exchange);
            } else {
                WebUtils.sendNotFound(exchange, corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Chat API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void handleChatNormal(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String message = json.path("message").asText();
        String sessionId = json.path("sessionId").asText(WebUtils.DEFAULT_SESSION_ID);

        try {
            String response = agentLoop.processDirect(message, sessionId);
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("response", response);
            result.put("sessionId", sessionId);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (Exception e) {
            logger.error("Agent processing error", Map.of("error", e.getMessage()));
            ObjectNode errorResult = WebUtils.MAPPER.createObjectNode();
            errorResult.put("error", e.getMessage());
            WebUtils.sendJson(exchange, 500, errorResult, corsOrigin);
        }
    }

    private void handleChatStream(HttpExchange exchange) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String message = json.path("message").asText();
        String sessionId = json.path("sessionId").asText(WebUtils.DEFAULT_SESSION_ID);

        setupSSEHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        try {
            streamAgentResponse(message, sessionId, os);
            writeSSEDone(os);
        } catch (Exception e) {
            logger.error("Chat stream error", Map.of("error", e.getMessage()));
            writeSSEError(os, e.getMessage());
        } finally {
            os.close();
        }
    }

    private void setupSSEHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set(WebUtils.HEADER_CONTENT_TYPE, WebUtils.CONTENT_TYPE_SSE);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CACHE_CONTROL, WebUtils.HEADER_NO_CACHE);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CONNECTION, WebUtils.HEADER_KEEP_ALIVE);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CORS, config.getGateway().getCorsOrigin());
    }

    private void streamAgentResponse(String message, String sessionId, OutputStream os) {
        try {
            agentLoop.processDirectStream(message, sessionId, chunk -> {
                try {
                    writeSSEData(os, chunk);
                } catch (IOException e) {
                    logger.error("SSE write error", Map.of("error", e.getMessage()));
                }
            });
        } catch (Exception e) {
            logger.error("Agent stream processing error", Map.of("error", e.getMessage()));
            try {
                writeSSEData(os, "错误: " + e.getMessage());
            } catch (IOException ioException) {
                logger.error("Failed to write error to SSE stream",
                        Map.of("error", ioException.getMessage()));
            }
        }
    }

    private void writeSSEData(OutputStream os, String content) throws IOException {
        String sseData = WebUtils.SSE_PREFIX + escapeSSE(content) + WebUtils.SSE_SUFFIX;
        os.write(sseData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private void writeSSEDone(OutputStream os) throws IOException {
        os.write(WebUtils.SSE_DONE.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private void writeSSEError(OutputStream os, String errorMessage) throws IOException {
        String errorData = WebUtils.SSE_ERROR_PREFIX + escapeSSE(errorMessage) + WebUtils.SSE_SUFFIX;
        os.write(errorData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private String escapeSSE(String content) {
        if (content == null) return "";
        return content.replace("\n", WebUtils.SSE_NEWLINE_REPLACEMENT);
    }
}
