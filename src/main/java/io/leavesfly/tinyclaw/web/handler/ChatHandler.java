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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 处理聊天 API（/api/chat 和 /api/chat/stream）。
 */
public class ChatHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final AgentLoop agentLoop;
    private final SecurityMiddleware security;

    /**
     * 构造 ChatHandler，注入全局配置、Agent 循环执行器与安全中间件。
     */
    public ChatHandler(Config config, AgentLoop agentLoop, SecurityMiddleware security) {
        this.config = config;
        this.agentLoop = agentLoop;
        this.security = security;
    }

    /**
     * 入口路由：预检通过后，分发到普通模式或流式应答接口。
     */
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

    /**
     * 处理普通聊天请求：解析 message/sessionId，同步调用 Agent 并返回完整响应。
     */
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

    /**
     * 处理流式聊天请求（SSE）：设置响应头并逐递将 Agent 输出推送到客户端。
     * 支持多模态内容，可以接收图片路径列表。
     */
    private void handleChatStream(HttpExchange exchange) throws IOException {
        String body = WebUtils.readRequestBody(exchange);  // 图片可能较大，不限制大小
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String message = json.path("message").asText();
        String sessionId = json.path("sessionId").asText(WebUtils.DEFAULT_SESSION_ID);
        
        // 解析图片列表（多模态支持）
        List<String> images = parseImages(json);

        setupSSEHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        try {
            streamAgentResponse(message, images, sessionId, os);
            writeSSEDone(os);
        } catch (Exception e) {
            logger.error("Chat stream error", Map.of("error", e.getMessage()));
            writeSSEError(os, e.getMessage());
        } finally {
            os.close();
        }
    }
    
    /**
     * 从请求 JSON 中解析图片路径列表。
     * 支持 images 字段为字符串数组（图片路径）。
     */
    private List<String> parseImages(JsonNode json) {
        List<String> images = new ArrayList<>();
        JsonNode imagesNode = json.path("images");
        if (imagesNode.isArray()) {
            for (JsonNode imgNode : imagesNode) {
                String imgPath = imgNode.asText();
                if (imgPath != null && !imgPath.isEmpty()) {
                    images.add(imgPath);
                }
            }
        }
        if (!images.isEmpty()) {
            logger.info("收到图片请求", Map.of(
                    "image_count", images.size(),
                    "image_paths", images));
        }
        return images.isEmpty() ? null : images;
    }

    /**
     * 设置 SSE 必要的响应头（Content-Type、Cache-Control、Connection、CORS）。
     */
    private void setupSSEHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set(WebUtils.HEADER_CONTENT_TYPE, WebUtils.CONTENT_TYPE_SSE);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CACHE_CONTROL, WebUtils.HEADER_NO_CACHE);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CONNECTION, WebUtils.HEADER_KEEP_ALIVE);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CORS, config.getGateway().getCorsOrigin());
    }

    /**
     * 调用 AgentLoop 流式接口，将每个 chunk 逗次写入 SSE 流。
     * 支持多模态内容（文本+图片）。
     * 内部异常尝试向客户端写入错误消息，避免连接空截断。
     */
    private void streamAgentResponse(String message, List<String> images, String sessionId, OutputStream os) {
        try {
            agentLoop.processDirectStream(message, images, sessionId, chunk -> {
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

    /**
     * 将单个文本块包装为 SSE data 事件并刷入输出流。
     */
    private void writeSSEData(OutputStream os, String content) throws IOException {
        String sseData = WebUtils.SSE_PREFIX + escapeSSE(content) + WebUtils.SSE_SUFFIX;
        os.write(sseData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * 向客户端发送 [DONE] 信号，标志流式输出结束。
     */
    private void writeSSEDone(OutputStream os) throws IOException {
        os.write(WebUtils.SSE_DONE.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * 向客户端发送错误事件，内容为错误信息的转义字符串。
     */
    private void writeSSEError(OutputStream os, String errorMessage) throws IOException {
        String errorData = WebUtils.SSE_ERROR_PREFIX + escapeSSE(errorMessage) + WebUtils.SSE_SUFFIX;
        os.write(errorData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * 将内容中的换行符替换为 SSE 安全的占位符，防止协议解析错误。
     */
    private String escapeSSE(String content) {
        if (content == null) return "";
        return content.replace("\n", WebUtils.SSE_NEWLINE_REPLACEMENT);
    }
}
