package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Social Network Tool for Agent-to-Agent Communication
 * 
 * This tool enables agents to join the Agent Social Network (e.g., ClawdChat.ai)
 * and communicate with other agents across different instances and platforms.
 * 
 * Features:
 * - Send messages to other agents by ID or channel
 * - Broadcast messages to agent network
 * - Query agent directory
 * - Share knowledge and collaborate
 * 
 * Usage example:
 * <pre>
 *   // Send a message to another agent
 *   socialNetwork.execute(Map.of(
 *       "action", "send",
 *       "to", "agent-123",
 *       "message", "Hello from TinyClaw!"
 *   ));
 *   
 *   // Broadcast to all agents
 *   socialNetwork.execute(Map.of(
 *       "action", "broadcast",
 *       "channel", "general",
 *       "message", "TinyClaw is online!"
 *   ));
 * </pre>
 * 
 * Configuration:
 * In config.json, add:
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
     * Constructor for Social Network Tool
     * 
     * @param endpoint API endpoint URL (e.g., https://clawdchat.ai/api)
     * @param agentId Unique identifier for this agent
     * @param apiKey API key for authentication
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
        return "Communicate with other agents in the Agent Social Network. " +
               "Actions: send (to specific agent), broadcast (to channel), query (agent directory), status (network status)";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> actionParam = new HashMap<>();
        actionParam.put("type", "string");
        actionParam.put("description", "Action to perform: send, broadcast, query, status");
        actionParam.put("enum", new String[]{"send", "broadcast", "query", "status"});
        properties.put("action", actionParam);
        
        Map<String, Object> toParam = new HashMap<>();
        toParam.put("type", "string");
        toParam.put("description", "Target agent ID (for 'send' action)");
        properties.put("to", toParam);
        
        Map<String, Object> channelParam = new HashMap<>();
        channelParam.put("type", "string");
        channelParam.put("description", "Channel name (for 'broadcast' action, default: general)");
        properties.put("channel", channelParam);
        
        Map<String, Object> messageParam = new HashMap<>();
        messageParam.put("type", "string");
        messageParam.put("description", "Message content");
        properties.put("message", messageParam);
        
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "Query string (for 'query' action)");
        properties.put("query", queryParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"action"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("action is required");
        }
        
        // Validate agent configuration
        if (agentId == null || agentId.isEmpty()) {
            return "Error: Agent ID not configured. Please set socialNetwork.agentId in config.json";
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
                return "Error: Unknown action '" + action + "'. Valid actions: send, broadcast, query, status";
        }
    }
    
    /**
     * Send a message to a specific agent
     */
    private String sendMessage(Map<String, Object> args) throws Exception {
        String to = (String) args.get("to");
        String message = (String) args.get("message");
        
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("'to' (target agent ID) is required for send action");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("'message' is required for send action");
        }
        
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "... (truncated)";
        }
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        payload.put("to", to);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        
        return sendRequest("/messages/send", payload);
    }
    
    /**
     * Broadcast a message to a channel
     */
    private String broadcast(Map<String, Object> args) throws Exception {
        String channel = (String) args.get("channel");
        String message = (String) args.get("message");
        
        if (channel == null || channel.isEmpty()) {
            channel = "general";
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("'message' is required for broadcast action");
        }
        
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "... (truncated)";
        }
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        payload.put("channel", channel);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        
        return sendRequest("/messages/broadcast", payload);
    }
    
    /**
     * Query the agent directory
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
     * Get network status
     */
    private String getNetworkStatus() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        
        return sendRequest("/status", payload);
    }
    
    /**
     * Send HTTP request to social network API
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
        
        // Add API key if configured
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
            
            return responseBody.isEmpty() ? "Success" : responseBody;
            
        } catch (Exception e) {
            logger.error("Social network request exception", Map.of("url", url, "error", e.getMessage()));
            throw new Exception("Failed to connect to social network: " + e.getMessage());
        }
    }
}
