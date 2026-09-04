package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMException;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.tools.InteractionBroker;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 处理聊天 API（/api/chat 和 /api/chat/stream）。
 */
public class ChatHandler extends BaseHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final AgentRuntime agentRuntime;

    /**
     * 构造 ChatHandler，注入全局配置、Agent 循环执行器与安全中间件。
     */
    public ChatHandler(Config config, AgentRuntime agentRuntime, SecurityMiddleware security) {
        super(config, security);
        this.agentRuntime = agentRuntime;
    }

    /**
     * 分发到普通模式或流式应答接口。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (WebUtils.API_CHAT.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleChatNormal(exchange);
        } else if (WebUtils.API_CHAT_STREAM.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleChatStream(exchange);
        } else if (WebUtils.API_CHAT_ABORT.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleChatAbort(exchange);
        } else if (WebUtils.API_CHAT_STATUS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
            handleChatStatus(exchange);
        } else if (WebUtils.API_CHAT_INTERACTION.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleInteraction(exchange);
        } else {
            return false;
        }
        return true;
    }

    /**
     * 处理中断请求：中断当前正在执行的 LLM 任务。
     *
     * <p>请求体带 sessionId 时只中断该会话；不带时退回全局中断，兼容旧前端。</p>
     */
    private void handleChatAbort(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        try {
            String sessionId = readOptionalSessionId(exchange);
            boolean aborted = agentRuntime.abortCurrentTask(sessionId);
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", aborted);
            result.put("message", aborted ? "Abort signal sent" : "No active task to abort");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (Exception e) {
            logger.error("Abort error", Map.of("error", String.valueOf(e.getMessage())));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    /**
     * 从请求体中读取可选的 sessionId，缺失或解析失败时返回 null。
     */
    private String readOptionalSessionId(HttpExchange exchange) {
        try {
            String body = WebUtils.readRequestBodyLimited(exchange);
            if (body == null || body.isBlank()) {
                return null;
            }
            String sessionId = WebUtils.MAPPER.readTree(body).path("sessionId").asText("");
            return sessionId.isEmpty() ? null : sessionId;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 处理运行状态查询：返回当前是否有任务正在运行。
     *
     * <p>带 sessionId 查询参数时只看该会话，避免其他通道的任务让前端输入区误锁。</p>
     */
    private void handleChatStatus(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        String sessionId = querySessionId(exchange);
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("running", agentRuntime.isTaskRunning(sessionId));
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 从查询串中取 sessionId（URL 解码），缺失时返回 null 表示全局语义。
     */
    private String querySessionId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith("sessionId=")) {
                String raw = param.substring("sessionId=".length());
                if (raw.isEmpty()) {
                    return null;
                }
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 处理 HITL 交互回传：用户在前端对危险命令审批或 ask_user 提问做出决策后调用。
     *
     * <p>请求体：{@code {"requestId":"...", "approved":true|false, "response":"可选文本"}}。
     * 审批类用 {@code approved}，提问类用 {@code response}。broker 未就绪返回 501；
     * requestId 缺失返回 400；对应交互不存在或已超时返回 200 且 {@code resolved=false}，
     * 供前端提示“已失效”。</p>
     */
    private void handleInteraction(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        InteractionBroker broker = agentRuntime != null ? agentRuntime.getInteractionBroker() : null;
        if (broker == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Interaction broker is not available"), corsOrigin);
            return;
        }
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String requestId = json.path("requestId").asText("");
        if (requestId.isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("requestId is required"), corsOrigin);
            return;
        }
        boolean approved = json.path("approved").asBoolean(false);
        String response = json.path("response").asText(null);
        boolean resolved = broker.resolve(requestId, approved, response);

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("resolved", resolved);
        result.put("message", resolved ? "Interaction resolved" : "No pending interaction (may have timed out)");
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
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
            logger.error("Agent processing error", Map.of(
                    "session", sessionId,
                    "error_type", e.getClass().getName(),
                    "root_cause", LLMException.rootCauseMessage(e)
            ), e);
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
        String body = WebUtils.readRequestBodyLimited(exchange);
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
            logger.error("Chat stream error", Map.of(
                    "session", sessionId,
                    "error_type", e.getClass().getName(),
                    "root_cause", LLMException.rootCauseMessage(e)
            ), e);
            writeSSEError(os, e.getMessage());
        } finally {
            // 响应头已发出，此处再抛异常会让外层 handle() 试图二次 sendResponseHeaders，
            // 客户端提前断开时 close() 报错属于正常情形，吸掉即可
            try {
                os.close();
            } catch (IOException e) {
                logger.debug("SSE stream close failed (client likely disconnected)",
                        Map.of("session", sessionId, "error", String.valueOf(e.getMessage())));
            }
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
            logger.error("Agent stream processing error", Map.of(
                    "session", sessionId,
                    "error_type", e.getClass().getName(),
                    "root_cause", LLMException.rootCauseMessage(e)
            ), e);
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
     *
     * <p>写入必须串行：多 Agent 并行协同（{@code TasksStrategy} / {@code ParallelNodeExecutor}）
     * 共用同一个回调向同一个输出流发事件，{@code OutputStream} 本身不保证线程安全，
     * 并发 write 会让两帧字节交错，前端 JSON.parse 直接报错并丢次内容。</p>
     */
    private void writeSSEJson(OutputStream os, StreamEvent event) throws IOException {
        String json = event.toJson();
        // 确保 JSON 是单行（移除任何真实换行符，防止 SSE 协议解析错误）
        String singleLineJson = json.replace("\n", "\\n").replace("\r", "\\r");
        String sseData = WebUtils.SSE_PREFIX + singleLineJson + WebUtils.SSE_SUFFIX;
        byte[] payload = sseData.getBytes(StandardCharsets.UTF_8);
        synchronized (os) {
            os.write(payload);
            os.flush();
        }
    }

    /**
     * 向客户端发送 [DONE] 信号，标志流式输出结束。
     */
    private void writeSSEDone(OutputStream os) throws IOException {
        byte[] payload = WebUtils.SSE_DONE.getBytes(StandardCharsets.UTF_8);
        synchronized (os) {
            os.write(payload);
            os.flush();
        }
    }

    /**
     * 向客户端发送错误事件，内容为错误信息的转义字符串。
     */
    private void writeSSEError(OutputStream os, String errorMessage) throws IOException {
        String errorData = WebUtils.SSE_ERROR_PREFIX + escapeSSE(errorMessage) + WebUtils.SSE_SUFFIX;
        byte[] payload = errorData.getBytes(StandardCharsets.UTF_8);
        synchronized (os) {
            os.write(payload);
            os.flush();
        }
    }

    /**
     * 将内容中的换行符替换为 SSE 安全的占位符，防止协议解析错误。
     */
    private String escapeSSE(String content) {
        if (content == null) return "";
        return content.replace("\n", WebUtils.SSE_NEWLINE_REPLACEMENT);
    }
}
