package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 社交网络工具，用于 Agent 间通信。
 * 
 * 此工具使 Agent 能够加入 Agent 社交网络（例如 ClawdChat.ai）
 * 并与不同实例和平台的其他 Agent 进行通信。
 * 
 * 功能：
 * - 通过 ID 或通道向其他 Agent 发送消息
 * - 向 Agent 网络广播消息
 * - 查询 Agent 目录
 * - 分享知识和协作
 * 
 * 配置示例：
 * 在 config.json 中添加：
 * {
 *   "socialNetwork": {
 *     "enabled": true,
 *     "endpoint": "https://clawdchat.ai/api",
 *     "agentId": "tinyclaw-001",
 *     "apiKey": "your-api-key"
 *   }
 * }
 */
public class SocialNetworkTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("social");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final int MAX_MESSAGE_LENGTH = 10000;    // 消息最大长度
    private static final long TIMEOUT_SECONDS = 30;         // 请求超时时间（秒）
    private static final String DEFAULT_ENDPOINT = "https://clawdchat.ai/api";
    private static final String DEFAULT_CHANNEL = "general";
    
    private final String endpoint;           // API 端点 URL
    private final String agentId;            // Agent 唯一标识符
    private final String apiKey;             // API 密钥
    private final OkHttpClient httpClient;   // HTTP 客户端
    
    /**
     * 社交网络工具构造函数。
     * 
     * @param endpoint API 端点 URL（例如 https://clawdchat.ai/api）
     * @param agentId 此 Agent 的唯一标识符
     * @param apiKey 用于身份验证的 API 密钥
     */
    public SocialNetworkTool(String endpoint, String agentId, String apiKey) {
        this.endpoint = endpoint != null ? endpoint : DEFAULT_ENDPOINT;
        this.agentId = agentId;
        this.apiKey = apiKey;
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
        
        logger.info("SocialNetworkTool initialized", Map.of(
            "endpoint", this.endpoint,
            "agentId", this.agentId != null ? this.agentId : "not-set"
        ));
    }
    
    @Override
    public String name() {
        return "social_network";
    }
    
    @Override
    public String description() {
        return "与 Agent 社交网络中的其他 Agent 通信。"
               + "操作：send（向特定 Agent）、broadcast（向通道）、query（Agent 目录）、status（网络状态）";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> actionParam = new HashMap<>();
        actionParam.put("type", "string");
        actionParam.put("description", "要执行的操作：send、broadcast、query、status");
        actionParam.put("enum", new String[]{"send", "broadcast", "query", "status"});
        properties.put("action", actionParam);
        
        Map<String, Object> toParam = new HashMap<>();
        toParam.put("type", "string");
        toParam.put("description", "目标 Agent ID（用于 'send' 操作）");
        properties.put("to", toParam);
        
        Map<String, Object> channelParam = new HashMap<>();
        channelParam.put("type", "string");
        channelParam.put("description", "通道名称（用于 'broadcast' 操作，默认：general）");
        properties.put("channel", channelParam);
        
        Map<String, Object> messageParam = new HashMap<>();
        messageParam.put("type", "string");
        messageParam.put("description", "消息内容");
        properties.put("message", messageParam);
        
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "查询字符串（用于 'query' 操作）");
        properties.put("query", queryParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"action"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("操作参数是必需的");
        }
        
        // 验证 Agent 配置
        if (agentId == null || agentId.isEmpty()) {
            return "错误: Agent ID 未配置。请在 config.json 中设置 socialNetwork.agentId";
        }
        
        logger.info("Social network action", Map.of("action", action, "agentId", agentId));
        
        return switch (action.toLowerCase()) {
            case "send" -> sendMessage(args);
            case "broadcast" -> broadcast(args);
            case "query" -> queryAgents(args);
            case "status" -> getNetworkStatus();
            default -> "错误: 未知操作 '" + action + "'。有效操作：send、broadcast、query、status";
        };
    }
    
    /**
     * 向特定 Agent 发送消息。
     * 
     * @param args 参数映射，必须包含 to 和 message 字段
     * @return 发送结果
     * @throws Exception 发送失败时抛出异常
     */
    private String sendMessage(Map<String, Object> args) throws Exception {
        String to = (String) args.get("to");
        String message = (String) args.get("message");
        
        validateRequiredParam(to, "to", "send");
        validateRequiredParam(message, "message", "send");
        
        message = truncateMessage(message);
        
        Map<String, Object> payload = buildPayload();
        payload.put("to", to);
        payload.put("message", message);
        
        return sendRequest("/messages/send", payload);
    }
    
    /**
     * 向通道广播消息。
     * 
     * @param args 参数映射，必须包含 message 字段，可选 channel 字段
     * @return 广播结果
     * @throws Exception 广播失败时抛出异常
     */
    private String broadcast(Map<String, Object> args) throws Exception {
        String channel = (String) args.get("channel");
        String message = (String) args.get("message");
        
        channel = channel != null && !channel.isEmpty() ? channel : DEFAULT_CHANNEL;
        validateRequiredParam(message, "message", "broadcast");
        
        message = truncateMessage(message);
        
        Map<String, Object> payload = buildPayload();
        payload.put("channel", channel);
        payload.put("message", message);
        
        return sendRequest("/messages/broadcast", payload);
    }
    
    /**
     * 查询 Agent 目录。
     * 
     * @param args 参数映射，可选 query 字段
     * @return 查询结果
     * @throws Exception 查询失败时抛出异常
     */
    private String queryAgents(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        
        Map<String, Object> payload = buildPayload();
        if (query != null && !query.isEmpty()) {
            payload.put("query", query);
        }
        
        return sendRequest("/agents/query", payload);
    }
    
    /**
     * 获取网络状态。
     * 
     * @return 网络状态信息
     * @throws Exception 获取失败时抛出异常
     */
    private String getNetworkStatus() throws Exception {
        Map<String, Object> payload = buildPayload();
        return sendRequest("/status", payload);
    }
    
    /**
     * 验证必需参数。
     * 
     * @param value 参数值
     * @param paramName 参数名称
     * @param operation 操作名称
     * @throws IllegalArgumentException 参数为空时抛出异常
     */
    private void validateRequiredParam(String value, String paramName, String operation) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("对于 " + operation + " 操作，'" + paramName + "' 是必需的");
        }
    }
    
    /**
     * 截断过长的消息。
     * 
     * @param message 原始消息
     * @return 可能被截断的消息
     */
    private String truncateMessage(String message) {
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return message.substring(0, MAX_MESSAGE_LENGTH) + "... (已截断)";
        }
        return message;
    }
    
    /**
     * 构建基础请求载荷。
     * 
     * @return 包含 from 和 timestamp 的载荷映射
     */
    private Map<String, Object> buildPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
    
    /**
     * 向社交网络 API 发送 HTTP 请求。
     * 
     * @param path API 路径
     * @param payload 请求载荷
     * @return 响应内容
     * @throws Exception 请求失败时抛出异常
     */
    private String sendRequest(String path, Map<String, Object> payload) throws Exception {
        String url = endpoint + path;
        String jsonBody = objectMapper.writeValueAsString(payload);
        
        Request request = buildHttpRequest(url, jsonBody);
        
        logger.info("Sending request to social network", Map.of("url", url));
        
        try (Response response = httpClient.newCall(request).execute()) {
            return handleResponse(response, url);
        } catch (Exception e) {
            logger.error("Social network request exception", Map.of("url", url, "error", e.getMessage()));
            throw new Exception("无法连接到社交网络: " + e.getMessage());
        }
    }
    
    /**
     * 构建 HTTP 请求对象。
     * 
     * @param url 请求 URL
     * @param jsonBody JSON 请求体
     * @return HTTP 请求对象
     */
    private Request buildHttpRequest(String url, String jsonBody) {
        RequestBody body = RequestBody.create(
            jsonBody,
            MediaType.parse("application/json; charset=utf-8")
        );
        
        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", "TinyClaw/0.1.0");
        
        // 如果配置了 API 密钥，添加到请求头
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        
        return requestBuilder.build();
    }
    
    /**
     * 处理 HTTP 响应。
     * 
     * @param response HTTP 响应对象
     * @param url 请求 URL
     * @return 响应内容
     */
    private String handleResponse(Response response, String url) {
        String responseBody;
        try {
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (java.io.IOException e) {
            logger.error("Failed to read response body", Map.of("url", url, "error", e.getMessage()));
            return "Error: Failed to read response body - " + e.getMessage();
        }
        
        if (!response.isSuccessful()) {
            logger.warn("Social network request failed", Map.of(
                "url", url,
                "status", response.code(),
                "body", responseBody
            ));
            return String.format("Error: HTTP %d - %s", response.code(), responseBody);
        }
        
        logger.info("Social network request succeeded", Map.of("url", url, "status", response.code()));
        
        return responseBody.isEmpty() ? "成功" : responseBody;
    }
}
