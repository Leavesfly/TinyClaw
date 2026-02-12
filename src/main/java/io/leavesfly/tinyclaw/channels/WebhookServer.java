package io.leavesfly.tinyclaw.channels;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP Webhook Server - 接收钉钉、飞书、QQ 等平台的 Webhook 回调
 *
 * 提供以下端点：
 * - POST /webhook/dingtalk  → 钉钉消息回调
 * - POST /webhook/feishu    → 飞书消息回调
 * - POST /webhook/qq        → QQ 消息回调
 * - GET  /health            → 健康检查
 *
 * 使用 JDK 内置的 com.sun.net.httpserver.HttpServer，无需额外依赖。
 * 通过 GatewayConfig 中的 host 和 port 配置监听地址。
 */
public class WebhookServer {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("webhook");
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final int THREAD_POOL_SIZE = 4;

    private final String host;
    private final int port;
    private final ChannelManager channelManager;
    private HttpServer httpServer;

    /**
     * 创建 Webhook Server
     *
     * @param host           监听地址
     * @param port           监听端口
     * @param channelManager 通道管理器，用于获取各通道实例
     */
    public WebhookServer(String host, int port, ChannelManager channelManager) {
        this.host = host;
        this.port = port;
        this.channelManager = channelManager;
    }

    /**
     * 启动 Webhook Server
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));

        httpServer.createContext("/webhook/dingtalk", this::handleDingTalk);
        httpServer.createContext("/webhook/feishu", this::handleFeishu);
        httpServer.createContext("/webhook/qq", this::handleQQ);
        httpServer.createContext("/health", this::handleHealth);

        httpServer.start();
        logger.info("Webhook Server 已启动", Map.of("host", host, "port", port));
    }

    /**
     * 停止 Webhook Server
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(2);
            logger.info("Webhook Server 已停止");
        }
    }

    /**
     * 处理钉钉 Webhook 回调
     */
    private void handleDingTalk(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        String requestBody = readRequestBody(exchange);
        logger.debug("收到钉钉 Webhook 回调", Map.of("body_length", requestBody.length()));

        String responseBody;
        int statusCode = 200;

        try {
            Channel channel = channelManager.getChannel("dingtalk").orElse(null);
            if (channel instanceof DingTalkChannel dingTalkChannel) {
                responseBody = dingTalkChannel.handleIncomingMessage(requestBody);
            } else {
                statusCode = 503;
                responseBody = "{\"errcode\":503,\"errmsg\":\"钉钉通道未启用\"}";
                logger.warn("收到钉钉 Webhook 但通道未启用");
            }
        } catch (Exception e) {
            statusCode = 500;
            responseBody = "{\"errcode\":500,\"errmsg\":\"内部错误\"}";
            logger.error("处理钉钉 Webhook 出错", Map.of("error", e.getMessage()));
        }

        sendResponse(exchange, statusCode, responseBody);
    }

    /**
     * 处理飞书 Webhook 回调
     */
    private void handleFeishu(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        String requestBody = readRequestBody(exchange);
        logger.debug("收到飞书 Webhook 回调", Map.of("body_length", requestBody.length()));

        String responseBody;
        int statusCode = 200;

        try {
            // 飞书 URL 验证（challenge 机制）
            if (requestBody.contains("\"challenge\"")) {
                responseBody = handleFeishuChallenge(requestBody);
                sendResponse(exchange, statusCode, responseBody);
                return;
            }

            Channel channel = channelManager.getChannel("feishu").orElse(null);
            if (channel instanceof FeishuChannel feishuChannel) {
                feishuChannel.handleIncomingMessage(requestBody);
                responseBody = "{\"code\":0,\"msg\":\"ok\"}";
            } else {
                statusCode = 503;
                responseBody = "{\"code\":503,\"msg\":\"飞书通道未启用\"}";
                logger.warn("收到飞书 Webhook 但通道未启用");
            }
        } catch (Exception e) {
            statusCode = 500;
            responseBody = "{\"code\":500,\"msg\":\"内部错误\"}";
            logger.error("处理飞书 Webhook 出错", Map.of("error", e.getMessage()));
        }

        sendResponse(exchange, statusCode, responseBody);
    }

    /**
     * 处理飞书 URL 验证的 challenge 请求
     */
    private String handleFeishuChallenge(String requestBody) {
        try {
            com.fasterxml.jackson.databind.JsonNode json =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody);
            String challenge = json.path("challenge").asText("");
            logger.info("飞书 URL 验证", Map.of("challenge", challenge));
            return "{\"challenge\":\"" + challenge + "\"}";
        } catch (Exception e) {
            logger.error("解析飞书 challenge 失败", Map.of("error", e.getMessage()));
            return "{\"challenge\":\"\"}";
        }
    }

    /**
     * 处理 QQ Webhook 回调
     */
    private void handleQQ(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        String requestBody = readRequestBody(exchange);
        logger.debug("收到 QQ Webhook 回调", Map.of("body_length", requestBody.length()));

        String responseBody;
        int statusCode = 200;

        try {
            Channel channel = channelManager.getChannel("qq").orElse(null);
            if (channel instanceof QQChannel qqChannel) {
                qqChannel.handleIncomingMessage(requestBody);
                responseBody = "{\"code\":0,\"msg\":\"ok\"}";
            } else {
                statusCode = 503;
                responseBody = "{\"code\":503,\"msg\":\"QQ 通道未启用\"}";
                logger.warn("收到 QQ Webhook 但通道未启用");
            }
        } catch (Exception e) {
            statusCode = 500;
            responseBody = "{\"code\":500,\"msg\":\"内部错误\"}";
            logger.error("处理 QQ Webhook 出错", Map.of("error", e.getMessage()));
        }

        sendResponse(exchange, statusCode, responseBody);
    }

    /**
     * 健康检查端点
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        String responseBody = "{\"status\":\"ok\",\"service\":\"tinyclaw-webhook\"}";
        sendResponse(exchange, 200, responseBody);
    }

    /**
     * 校验请求方法为 POST，非 POST 返回 405
     *
     * @return true 表示是 POST 请求，可以继续处理
     */
    private boolean requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return false;
        }
        return true;
    }

    /**
     * 读取请求体
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 发送 JSON 响应
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }
}
