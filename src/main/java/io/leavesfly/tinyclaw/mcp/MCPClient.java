package io.leavesfly.tinyclaw.mcp;

import java.io.IOException;
import java.util.Map;

/**
 * MCP 客户端接口
 *
 * 定义与 MCP 服务器通信的统一抽象，支持不同传输方式（SSE、Stdio）。
 * 实现 JSON-RPC 2.0 协议的请求/响应关联。
 */
public interface MCPClient {

    /**
     * 连接到 MCP 服务器
     */
    void connect() throws IOException;

    /**
     * 发送 JSON-RPC 请求并等待响应
     *
     * @param method JSON-RPC 方法名
     * @param params 请求参数
     * @return 响应消息
     */
    MCPMessage sendRequest(String method, Map<String, Object> params) throws Exception;

    /**
     * 发送 JSON-RPC 通知（无需响应）
     *
     * @param method 通知方法名
     * @param params 通知参数
     */
    void sendNotification(String method, Map<String, Object> params) throws IOException;

    /**
     * 关闭连接并释放资源
     */
    void close();

    /**
     * 检查是否已连接
     */
    boolean isConnected();

    /**
     * MCP 协议异常
     */
    class MCPException extends Exception {
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
