package io.leavesfly.tinyclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 客户端抽象基类
 *
 * 封装 MCPClient 接口的通用实现逻辑，包括：
 * - 请求/响应关联（pendingRequests）
 * - JSON-RPC 消息构建与序列化
 * - 超时处理与错误处理
 * - 连接状态管理
 *
 * 子类只需实现特定传输方式的连接、发送和关闭逻辑。
 */
public abstract class AbstractMCPClient implements MCPClient {

    protected static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    protected static final int MAX_RESPONSE_SIZE = 10 * 1024 * 1024; // 10MB

    protected final ObjectMapper objectMapper;
    protected final int timeoutMs;

    /** 请求 ID 到响应 Future 的映射，用于异步请求/响应关联 */
    protected final Map<String, CompletableFuture<MCPMessage>> pendingRequests = new ConcurrentHashMap<>();

    /** 连接状态标志 */
    protected volatile boolean connected = false;

    /**
     * 构造函数
     *
     * @param timeoutMs 请求超时时间（毫秒）
     */
    protected AbstractMCPClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 连接到 MCP 服务器
     * 子类必须实现具体的连接逻辑
     */
    @Override
    public abstract void connect() throws IOException;

    /**
     * 发送 JSON-RPC 请求并等待响应
     *
     * 默认实现使用 pendingRequests 进行异步请求/响应关联。
     * 如果子类使用同步请求模式（如 StreamableHttpMCPClient），可以覆盖此方法。
     */
    @Override
    public MCPMessage sendRequest(String method, Map<String, Object> params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }

        String requestId = UUID.randomUUID().toString();
        MCPMessage request = MCPMessage.createRequest(requestId, method, params);

        CompletableFuture<MCPMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            String json = objectMapper.writeValueAsString(request);
            doSend(json);

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
            throw new MCPException(-1, "Request timeout after " + timeoutMs + "ms for method: " + method);
        } catch (MCPException e) {
            throw e;
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw e;
        }
    }

    /**
     * 发送 JSON-RPC 通知（无需响应）
     */
    @Override
    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }

        MCPMessage notification = MCPMessage.createNotification(method, params);
        String json = objectMapper.writeValueAsString(notification);
        doSend(json);

        logger.debug("Sent notification", Map.of("method", method));
    }

    /**
     * 关闭连接并释放资源
     *
     * 基类负责清理 pendingRequests，子类在 doClose() 中实现特定清理逻辑。
     */
    @Override
    public void close() {
        if (!connected) {
            return;
        }

        connected = false;

        // 完成所有等待中的请求（以异常结束）
        for (CompletableFuture<MCPMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("Connection closed"));
        }
        pendingRequests.clear();

        doClose();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 处理接收到的 JSON-RPC 响应消息
     *
     * 从 pendingRequests 中匹配请求 ID 并完成对应的 Future。
     * 子类在接收到响应消息后应调用此方法。
     *
     * @param message 接收到的响应消息
     */
    protected void handleResponse(MCPMessage message) {
        if (message.isResponse()) {
            String id = message.getId();
            CompletableFuture<MCPMessage> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(message);
            } else {
                logger.warn("Received response for unknown request", Map.of("id", id));
            }
        } else if (message.isNotification()) {
            logger.debug("Received notification", Map.of("method", message.getMethod()));
        }
    }

    /**
     * 清理所有 pending requests（以连接关闭异常完成）
     *
     * 子类在连接意外断开时应调用此方法。
     *
     * @param reason 关闭原因
     */
    protected void clearPendingRequests(String reason) {
        for (CompletableFuture<MCPMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException(reason));
        }
        pendingRequests.clear();
    }

    /**
     * 底层发送 JSON-RPC 消息
     * 子类必须实现特定传输方式的发送逻辑
     *
     * @param jsonMessage JSON 序列化后的消息
     * @throws IOException 发送失败时抛出
     */
    protected abstract void doSend(String jsonMessage) throws IOException;

    /**
     * 子类特有的关闭清理逻辑
     * 在基类清理 pendingRequests 后调用
     */
    protected abstract void doClose();
}
