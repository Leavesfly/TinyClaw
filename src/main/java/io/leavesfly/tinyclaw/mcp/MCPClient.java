package io.leavesfly.tinyclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * MCP 客户端
 * 
 * 通过 SSE (Server-Sent Events) 与 MCP 服务器通信
 * 实现 JSON-RPC 2.0 协议的请求/响应关联
 */
public class MCPClient {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    private static final int MAX_RESPONSE_SIZE = 10 * 1024 * 1024; // 10MB
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final int timeoutMs;
    
    private volatile boolean connected = false;
    private volatile Response sseResponse;
    private volatile Thread readerThread;
    
    // 请求/响应关联映射
    private final Map<String, CompletableFuture<MCPMessage>> pendingRequests;
    
    public MCPClient(String endpoint, String apiKey, int timeoutMs) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.objectMapper = new ObjectMapper();
        this.pendingRequests = new ConcurrentHashMap<>();
        
        // 创建 HTTP 客户端,配置超时
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }
    
    /**
     * 连接到 MCP 服务器
     */
    public void connect() throws IOException {
        if (connected) {
            logger.warn("Already connected to MCP server", Map.of("endpoint", endpoint));
            return;
        }
        
        validateEndpoint();
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .header("Accept", "text/event-stream")
                .get();
        
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + maskApiKey(apiKey));
        }
        
        Request request = requestBuilder.build();
        
        try {
            sseResponse = httpClient.newCall(request).execute();
            
            if (!sseResponse.isSuccessful()) {
                throw new IOException("SSE connection failed: " + sseResponse.code());
            }
            
            connected = true;
            
            // 启动后台线程读取 SSE 事件
            readerThread = new Thread(this::readSSEStream, "mcp-sse-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            
            logger.info("Connected to MCP server", Map.of("endpoint", endpoint));
            
        } catch (IOException e) {
            logger.error("Failed to connect to MCP server", Map.of(
                    "endpoint", endpoint,
                    "error", e.getMessage()
            ));
            throw e;
        }
    }
    
    /**
     * 发送请求并等待响应
     */
    public MCPMessage sendRequest(String method, Map<String, Object> params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }
        
        String requestId = generateRequestId();
        MCPMessage request = MCPMessage.createRequest(requestId, method, params);
        
        CompletableFuture<MCPMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        try {
            // 发送请求(通过 SSE 的反向通道或 POST 端点)
            // 注意: SSE 本身是单向的,这里简化处理,实际需要配合 POST 端点
            sendRequestMessage(request);
            
            // 等待响应,带超时
            MCPMessage response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            
            if (response.isError()) {
                throw new MCPException(
                        response.getError().getCode(),
                        response.getError().getMessage()
                );
            }
            
            return response;
            
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new MCPException(-1, "Request timeout after " + timeoutMs + "ms");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw e;
        }
    }
    
    /**
     * 发送请求消息
     * 
     * 注意: SSE 是单向的,实际实现中需要通过额外的 POST 端点发送请求
     * 这里简化处理,假设服务器支持双向通信
     */
    private void sendRequestMessage(MCPMessage request) throws IOException {
        // 实际实现: 使用 POST 到服务器的写入端点
        String jsonRequest = objectMapper.writeValueAsString(request);
        
        // 这里简化处理,实际需要根据 MCP 服务器的具体实现来发送
        // 可能需要 POST 到 /messages 或其他端点
        logger.debug("Sending request", Map.of(
                "id", request.getId(),
                "method", request.getMethod()
        ));
    }
    
    /**
     * 读取 SSE 流
     */
    private void readSSEStream() {
        try {
            BufferedSource source = sseResponse.body().source();
            
            StringBuilder eventData = new StringBuilder();
            
            while (connected && !source.exhausted()) {
                String line = source.readUtf8Line();
                
                if (line == null) {
                    break;
                }
                
                if (line.startsWith("data: ")) {
                    eventData.append(line.substring(6));
                } else if (line.isEmpty() && eventData.length() > 0) {
                    // 事件完成,处理数据
                    handleMessage(eventData.toString());
                    eventData.setLength(0);
                }
            }
            
        } catch (IOException e) {
            if (connected) {
                logger.error("SSE stream read error", Map.of("error", e.getMessage()));
            }
        } finally {
            connected = false;
        }
    }
    
    /**
     * 处理接收到的消息
     */
    private void handleMessage(String data) {
        try {
            // 检查数据大小
            if (data.length() > MAX_RESPONSE_SIZE) {
                logger.warn("Response too large, truncating", Map.of(
                        "size", data.length(),
                        "max", MAX_RESPONSE_SIZE
                ));
                return;
            }
            
            MCPMessage message = objectMapper.readValue(data, MCPMessage.class);
            
            if (message.isResponse()) {
                // 响应消息,匹配对应的请求
                String id = message.getId();
                CompletableFuture<MCPMessage> future = pendingRequests.remove(id);
                
                if (future != null) {
                    future.complete(message);
                } else {
                    logger.warn("Received response for unknown request", Map.of("id", id));
                }
                
            } else if (message.isNotification()) {
                // 通知消息,记录日志
                logger.debug("Received notification", Map.of("method", message.getMethod()));
            }
            
        } catch (Exception e) {
            logger.error("Failed to parse message", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        if (!connected) {
            return;
        }
        
        connected = false;
        
        // 取消所有待处理的请求
        for (CompletableFuture<MCPMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("Connection closed"));
        }
        pendingRequests.clear();
        
        // 关闭响应
        if (sseResponse != null) {
            sseResponse.close();
        }
        
        // 等待读取线程结束
        if (readerThread != null && readerThread.isAlive()) {
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("Disconnected from MCP server", Map.of("endpoint", endpoint));
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * 验证端点 URL
     */
    private void validateEndpoint() throws IOException {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IOException("Endpoint cannot be empty");
        }
        
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IOException("Endpoint must start with http:// or https://");
        }
    }
    
    /**
     * 生成请求 ID
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * 隐藏 API Key(用于日志)
     */
    private String maskApiKey(String key) {
        if (key == null || key.length() <= 8) {
            return "***";
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
    
    /**
     * MCP 异常
     */
    public static class MCPException extends Exception {
        private final int code;
        
        public MCPException(int code, String message) {
            super(message);
            this.code = code;
        }
        
        public int getCode() {
            return code;
        }
    }
}
