package io.leavesfly.tinyclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 基于 Streamable HTTP 的 MCP 客户端实现。
 *
 * MCP Streamable HTTP 传输协议（2025-03-26）：
 * 1. 客户端直接 POST JSON-RPC 消息到 endpoint URL
 * 2. 服务器响应可以是：
 *    - 普通 JSON（Content-Type: application/json）— 直接返回 JSON-RPC 响应
 *    - SSE 流（Content-Type: text/event-stream）— 通过 SSE 事件返回响应
 * 3. 不需要先 GET 建立 SSE 连接，也不需要等待 endpoint 事件
 * 4. 每个请求独立发送，响应在同一个 HTTP 响应中返回
 *
 * 与 SSE 传输的区别：
 * - SSE 传输需要先 GET 建立长连接，等待 endpoint 事件，再 POST 消息
 * - Streamable HTTP 直接 POST 到 endpoint，响应在同一个 HTTP 响应中返回
 */
public class StreamableHttpMCPClient implements MCPClient {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    private static final int MAX_RESPONSE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final int timeoutMs;

    private volatile boolean connected = false;
    /** 服务器返回的 Mcp-Session-Id，后续请求需要携带 */
    private volatile String sessionId;

    public StreamableHttpMCPClient(String endpoint, String apiKey, int timeoutMs) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.objectMapper = new ObjectMapper();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            logger.warn("Already connected to MCP server", Map.of("endpoint", endpoint));
            return;
        }

        validateEndpoint(endpoint);

        // Streamable HTTP 不需要预先建立连接，标记为已连接即可
        // 实际的连接验证会在第一次 sendRequest（initialize）时进行
        connected = true;

        logger.info("Streamable HTTP MCP client ready", Map.of("endpoint", endpoint));
    }

    @Override
    public MCPMessage sendRequest(String method, Map<String, Object> params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }

        String requestId = UUID.randomUUID().toString();
        MCPMessage request = MCPMessage.createRequest(requestId, method, params);

        try {
            MCPMessage response = postAndParseResponse(request);

            // 提取 session id（如果有）
            if (response.isError()) {
                throw new MCPException(
                        response.getError().getCode(),
                        response.getError().getMessage()
                );
            }

            return response;

        } catch (MCPException e) {
            throw e;
        } catch (Exception e) {
            // 如果请求失败，标记为断开
            if (isConnectionError(e)) {
                connected = false;
            }
            throw e;
        }
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }

        MCPMessage notification = MCPMessage.createNotification(method, params);
        String jsonBody = objectMapper.writeValueAsString(notification);

        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE));

        addAuthAndSessionHeaders(requestBuilder);

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            // 必须消费 response body 以释放连接
            if (response.body() != null) {
                response.body().string();
            }
            // 通知不需要响应体，但需要检查 HTTP 状态
            if (!response.isSuccessful() && response.code() != 202 && response.code() != 204) {
                logger.warn("Notification may have failed", Map.of(
                        "method", method,
                        "httpStatus", response.code()
                ));
            }
            extractSessionId(response);
        }

        logger.debug("Sent notification to MCP server", Map.of("method", method));
    }

    /**
     * POST JSON-RPC 消息并解析响应
     * 支持两种响应格式：普通 JSON 和 SSE 流
     */
    private MCPMessage postAndParseResponse(MCPMessage message) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(message);

        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE));

        addAuthAndSessionHeaders(requestBuilder);

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            // 先读取 body 以确保连接被正确释放
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IOException("Streamable HTTP request failed: HTTP " + response.code() + " - " + responseBody);
            }

            extractSessionId(response);

            if (responseBody.isEmpty()) {
                throw new IOException("Empty response from MCP server");
            }

            if (responseBody.length() > MAX_RESPONSE_SIZE) {
                throw new IOException("Response too large: " + responseBody.length() + " bytes");
            }

            String contentType = response.header("Content-Type", "");
            if (contentType.contains("text/event-stream")) {
                // SSE 格式响应：解析 SSE 事件中的 JSON-RPC 消息
                return parseSSEResponse(responseBody, message.getId());
            } else {
                // 普通 JSON 响应
                return objectMapper.readValue(responseBody, MCPMessage.class);
            }
        }
    }

    /**
     * 解析 SSE 格式的响应体，提取目标请求 ID 对应的 JSON-RPC 响应
     */
    private MCPMessage parseSSEResponse(String sseBody, String requestId) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(sseBody));
        StringBuilder eventData = new StringBuilder();
        MCPMessage lastMatchingResponse = null;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data: ")) {
                eventData.append(line.substring(6));
            } else if (line.isEmpty() && eventData.length() > 0) {
                // 空行表示事件结束
                String data = eventData.toString().trim();
                eventData.setLength(0);

                if (data.isEmpty()) {
                    continue;
                }

                try {
                    MCPMessage msg = objectMapper.readValue(data, MCPMessage.class);
                    // 匹配请求 ID 的响应
                    if (msg.isResponse() && requestId != null && requestId.equals(msg.getId())) {
                        lastMatchingResponse = msg;
                    }
                } catch (Exception e) {
                    logger.debug("Skipping non-JSON SSE event data", Map.of("data", data));
                }
            }
        }

        // 处理最后一个未以空行结尾的事件
        if (eventData.length() > 0) {
            String data = eventData.toString().trim();
            if (!data.isEmpty()) {
                try {
                    MCPMessage msg = objectMapper.readValue(data, MCPMessage.class);
                    if (msg.isResponse() && requestId != null && requestId.equals(msg.getId())) {
                        lastMatchingResponse = msg;
                    }
                } catch (Exception e) {
                    logger.debug("Skipping non-JSON SSE event data", Map.of("data", data));
                }
            }
        }

        if (lastMatchingResponse != null) {
            return lastMatchingResponse;
        }

        throw new IOException("No matching JSON-RPC response found in SSE stream for request ID: " + requestId);
    }

    /**
     * 添加认证和会话头
     */
    private void addAuthAndSessionHeaders(Request.Builder requestBuilder) {
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        if (sessionId != null) {
            requestBuilder.header("Mcp-Session-Id", sessionId);
        }
    }

    /**
     * 从响应头中提取 Mcp-Session-Id
     */
    private void extractSessionId(Response response) {
        String newSessionId = response.header("Mcp-Session-Id");
        if (newSessionId != null && !newSessionId.isEmpty()) {
            this.sessionId = newSessionId;
            logger.debug("Received MCP session ID", Map.of("sessionId", newSessionId));
        }
    }

    /**
     * 判断是否为连接级别的错误（需要重连）
     */
    private boolean isConnectionError(Exception e) {
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketTimeoutException) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (
                message.contains("Connection refused")
                        || message.contains("Connection reset")
                        || message.contains("Broken pipe")
        );
    }

    @Override
    public void close() {
        if (!connected) {
            return;
        }

        connected = false;
        sessionId = null;

        logger.info("Disconnected from MCP server (Streamable HTTP)", Map.of("endpoint", endpoint));
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private void validateEndpoint(String endpoint) throws IOException {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IOException("Endpoint cannot be empty");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IOException("Endpoint must start with http:// or https://");
        }
    }
}
