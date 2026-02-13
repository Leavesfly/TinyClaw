package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 社交网络工具，用于 Agent 间通信
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
 * 使用示例：
 * <pre>
 *   // 向另一个 Agent 发送消息
 *   socialNetwork.execute(Map.of(
 *       "action", "send",
 *       "to", "agent-123",
 *       "message", "Hello from TinyClaw!"
 *   ));
 *   
 *   // 广播给所有 Agent
 *   socialNetwork.execute(Map.of(
 *       "action", "broadcast",
 *       "channel", "general",
 *       "message", "TinyClaw is online!"
 *   ));
 * </pre>
 * 
 * 配置：
 * 在 config.json 中添加：
 * <pre>
 * {
 *   "socialNetwork": {
 *     "enabled": true,
 *     "endpoint": "https://clawdchat.ai/api",
 *     "agentId": "tinyclaw-001",
 *     "apiKey": "your-api-key"
 *   }
 * }
 * </pre>
 */
public class SocialNetworkTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("social");
    private static final int MAX_MESSAGE_LENGTH = 10000;
    private static final long TIMEOUT_SECONDS = 30;
    
    private final String endpoint;
    private final String agentId;
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    /**
     * 社交网络工具构造函数
     * 
     * @param endpoint API 端点 URL（例如 https://clawdchat.ai/api）
     * @param agentId 此 Agent 的唯一标识符
     * @param apiKey 用于身份验证的 API 密钥
     */
    public SocialNetworkTool(String endpoint, String agentId, String apiKey) {
        this.endpoint = endpoint != null ? endpoint : "https://clawdchat.ai/api";
        this.agentId = agentId;
        this.apiKey = apiKey;
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
        
        this.objectMapper = new ObjectMapper();
        
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
        
        switch (action.toLowerCase()) {
            case "send":
                return sendMessage(args);
            case "broadcast":
                return broadcast(args);
            case "query":
                return queryAgents(args);
            case "status":
                return getNetworkStatus();
            default:
                return "错误: 未知操作 '" + action + "'。有效操作：send、broadcast、query、status";
        }
    }
    
    /**
     * 向特定 Agent 发送消息
     */
    private String sendMessage(Map<String, Object> args) throws Exception {
        String to = (String) args.get("to");
        String message = (String) args.get("message");
        
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("对于 send 操作，'to'（目标 Agent ID）是必需的");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("对于 send 操作，'message' 是必需的");
        }
        
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "... (已截断)";
        }
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        payload.put("to", to);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        
        return sendRequest("/messages/send", payload);
    }
    
    /**
     * 向通道广播消息
     */
    private String broadcast(Map<String, Object> args) throws Exception {
        String channel = (String) args.get("channel");
        String message = (String) args.get("message");
        
        if (channel == null || channel.isEmpty()) {
            channel = "general";
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("对于 broadcast 操作，'message' 是必需的");
        }
        
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "... (已截断)";
        }
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        payload.put("channel", channel);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        
        return sendRequest("/messages/broadcast", payload);
    }
    
    /**
     * 查询 Agent 目录
     */
    private String queryAgents(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        if (query != null && !query.isEmpty()) {
            payload.put("query", query);
        }
        
        return sendRequest("/agents/query", payload);
    }
    
    /**
     * 获取网络状态
     */
    private String getNetworkStatus() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        
        return sendRequest("/status", payload);
    }
    
    /**
     * 向社交网络 API 发送 HTTP 请求
     */
    private String sendRequest(String path, Map<String, Object> payload) throws Exception {
        String url = endpoint + path;
        
        String jsonBody = objectMapper.writeValueAsString(payload);
        
        RequestBody body = RequestBody.create(
            jsonBody,
            MediaType.parse("application/json; charset=utf-8")
        );
        
        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .post(body);
        
        // 如果配置了 API 密钥，添加到请求头
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        
        requestBuilder.header("User-Agent", "TinyClaw/0.1.0");
        
        Request request = requestBuilder.build();
        
        logger.info("Sending request to social network", Map.of("url", url));
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
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
            
        } catch (Exception e) {
            logger.error("Social network request exception", Map.of("url", url, "error", e.getMessage()));
            throw new Exception("无法连接到社交网络: " + e.getMessage());
        }
    }
}
