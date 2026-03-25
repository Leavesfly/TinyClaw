package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;
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
    private final AgentRuntime agentRuntime;
    private final SecurityMiddleware security;

    /**
     * 构造 ChatHandler，注入全局配置、Agent 循环执行器与安全中间件。
     */
    public ChatHandler(Config config, AgentRuntime agentRuntime, SecurityMiddleware security) {
        this.config = config;
        this.agentRuntime = agentRuntime;
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
            String response = agentRuntime.processDirect(message, sessionId);
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
     * 调用 AgentRuntime 流式接口，将每个事件序列化为 JSON 后写入 SSE 流。
     * 使用 EnhancedStreamCallback 接收结构化事件（工具调用、子代理、普通内容等），
     * 前端通过 JSON 中的 type 字段区分事件类型并渲染不同 UI 组件。
     */
    private void streamAgentResponse(String message, List<String> images, String sessionId, OutputStream os) {
        LLMProvider.EnhancedStreamCallback enhancedCallback = event -> {
            try {
                writeSSEJson(os, event);
            } catch (IOException e) {
                logger.error("SSE write error", Map.of("error", e.getMessage()));
            }
        };

        try {
            agentRuntime.processDirectStream(message, images, sessionId, enhancedCallback);
        } catch (Exception e) {
            logger.error("Agent stream processing error", Map.of("error", e.getMessage()));
            try {
                writeSSEJson(os, StreamEvent.content("错误: " + e.getMessage()));
            } catch (IOException ioException) {
                logger.error("Failed to write error to SSE stream",
                        Map.of("error", ioException.getMessage()));
            }
        }
    }

    /**
     * 将 StreamEvent 序列化为单行 JSON 后包装为 SSE data 事件并刷入输出流。
     * toJson() 输出紧凑单行 JSON，不含真实换行符，无需做换行替换，
     * 保证前端能直接 JSON.parse 整行 data 字段。
     */
    private void writeSSEJson(OutputStream os, StreamEvent event) throws IOException {
        String json = event.toJson();
        // 确保 JSON 是单行（移除任何真实换行符，防止 SSE 协议解析错误）
        String singleLineJson = json.replace("\n", "\\n").replace("\r", "\\r");
        String sseData = WebUtils.SSE_PREFIX + singleLineJson + WebUtils.SSE_SUFFIX;
        os.write(sseData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * 将单个文本块包装为 CONTENT 类型的 JSON SSE 事件并刷入输出流。
     * 保留此方法供内部降级使用。
     */
    private void writeSSEData(OutputStream os, String content) throws IOException {
        writeSSEJson(os, StreamEvent.content(content));
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
