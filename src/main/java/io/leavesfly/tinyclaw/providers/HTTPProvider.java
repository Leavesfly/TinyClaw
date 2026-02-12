package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-based LLM Provider implementation
 * Supports OpenAI-compatible APIs (OpenRouter, Anthropic, Zhipu, etc.)
 */
public class HTTPProvider implements LLMProvider {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("provider");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 120;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final int MAX_ERROR_RESPONSE_LENGTH = 500;
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private static final String AUTHORIZATION_PREFIX = "Bearer ";
    
    private final String apiKey;
    private final String apiBase;
    private final OkHttpClient httpClient;
    
    public HTTPProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }
    
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options) throws Exception {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }
        
        // 构建 request body
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        
        // Add messages
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.getRole());
            
            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }
            
            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
            
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.getToolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.getId());
                    tcNode.put("type", tc.getType() != null ? tc.getType() : "function");
                    ObjectNode funcNode = tcNode.putObject("function");
                    funcNode.put("name", tc.getName());
                    if (tc.getArguments() != null) {
                        funcNode.put("arguments", objectMapper.writeValueAsString(tc.getArguments()));
                    }
                }
            }
        }
        
        // Add tools
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", tool.getType());
                ObjectNode funcNode = toolNode.putObject("function");
                funcNode.put("name", tool.getFunction().getName());
                funcNode.put("description", tool.getFunction().getDescription());
                funcNode.set("parameters", objectMapper.valueToTree(tool.getFunction().getParameters()));
            }
            requestBody.put("tool_choice", "auto");
        }
        
        // Add options
        if (options != null) {
            if (options.containsKey("max_tokens")) {
                // 处理 different models' max_tokens parameter names
                String lowerModel = model.toLowerCase();
                if (lowerModel.contains("glm") || lowerModel.contains("o1")) {
                    requestBody.put("max_completion_tokens", ((Number) options.get("max_tokens")).intValue());
                } else {
                    requestBody.put("max_tokens", ((Number) options.get("max_tokens")).intValue());
                }
            }
            if (options.containsKey("temperature")) {
                requestBody.put("temperature", ((Number) options.get("temperature")).doubleValue());
            }
        }
        
        String requestJson = objectMapper.writeValueAsString(requestBody);
        logger.debug("LLM request", Map.of(
                "model", model,
                "messages_count", messages.size(),
                "tools_count", tools != null ? tools.size() : 0,
                "request_length", requestJson.length()
        ));
        
        // 构建 HTTP request
        String url = apiBase + CHAT_COMPLETIONS_ENDPOINT;
        RequestBody body = RequestBody.create(requestJson, JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");
        
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", AUTHORIZATION_PREFIX + apiKey);
        }
        
        // 执行 request
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("LLM API error", Map.of(
                        "status_code", response.code(),
                        "response", responseBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, responseBody.length()))
                ));
                throw new IOException("LLM API error (status " + response.code() + "): " + responseBody);
            }
            
            return parseResponse(responseBody);
        }
    }
    
    private LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        LLMResponse response = new LLMResponse();
        
        // Parse usage
        if (root.has("usage")) {
            JsonNode usageNode = root.get("usage");
            LLMResponse.UsageInfo usage = new LLMResponse.UsageInfo();
            usage.setPromptTokens(usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0);
            usage.setCompletionTokens(usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0);
            usage.setTotalTokens(usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : 0);
            response.setUsage(usage);
        }
        
        // Parse choices
        if (!root.has("choices") || !root.get("choices").isArray() || root.get("choices").isEmpty()) {
            response.setContent("");
            response.setFinishReason("stop");
            return response;
        }
        
        JsonNode choice = root.get("choices").get(0);
        JsonNode messageNode = choice.get("message");
        
        response.setFinishReason(choice.has("finish_reason") ? choice.get("finish_reason").asText() : "stop");
        response.setContent(messageNode.has("content") && !messageNode.get("content").isNull() 
                ? messageNode.get("content").asText() : "");
        
        // Parse tool calls
        if (messageNode.has("tool_calls") && messageNode.get("tool_calls").isArray()) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode tcNode : messageNode.get("tool_calls")) {
                ToolCall toolCall = new ToolCall();
                toolCall.setId(tcNode.has("id") ? tcNode.get("id").asText() : UUID.randomUUID().toString());
                toolCall.setType(tcNode.has("type") ? tcNode.get("type").asText() : "function");
                
                if (tcNode.has("function")) {
                    JsonNode funcNode = tcNode.get("function");
                    String name = funcNode.has("name") ? funcNode.get("name").asText() : "";
                    String argsStr = funcNode.has("arguments") ? funcNode.get("arguments").asText() : "{}";
                    
                    toolCall.setName(name);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = objectMapper.readValue(argsStr, Map.class);
                        toolCall.setArguments(args);
                    } catch (Exception e) {
                        Map<String, Object> args = new HashMap<>();
                        args.put("raw", argsStr);
                        toolCall.setArguments(args);
                    }
                }
                
                toolCalls.add(toolCall);
            }
            response.setToolCalls(toolCalls);
        }
        
        logger.debug("LLM response", Map.of(
                "content_length", response.getContent() != null ? response.getContent().length() : 0,
                "tool_calls_count", response.hasToolCalls() ? response.getToolCalls().size() : 0,
                "finish_reason", response.getFinishReason()
        ));
        
        return response;
    }
    
    @Override
    public String getDefaultModel() {
        return "";
    }
    
    /**
     * Create a provider based on configuration
     */
    public static LLMProvider createProvider(Config config) {
        String model = config.getAgents().getDefaults().getModel();
        String apiKey = null;
        String apiBase = null;
        
        String lowerModel = model.toLowerCase();
        
        // Determine provider based on model name
        if (model.startsWith("openrouter/") || model.startsWith("anthropic/") || 
            model.startsWith("openai/") || model.startsWith("meta-llama/") || 
            model.startsWith("deepseek/") || model.startsWith("google/")) {
            apiKey = config.getProviders().getOpenrouter().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getOpenrouter().getApiBase(), "https://openrouter.ai/api/v1");
        } else if ((lowerModel.contains("claude") || model.startsWith("anthropic/")) && 
                   config.getProviders().getAnthropic().getApiKey() != null && 
                   !config.getProviders().getAnthropic().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getAnthropic().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getAnthropic().getApiBase(), "https://api.anthropic.com/v1");
        } else if ((lowerModel.contains("gpt") || model.startsWith("openai/")) && 
                   config.getProviders().getOpenai().getApiKey() != null && 
                   !config.getProviders().getOpenai().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getOpenai().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getOpenai().getApiBase(), "https://api.openai.com/v1");
        } else if ((lowerModel.contains("gemini") || model.startsWith("google/")) && 
                   config.getProviders().getGemini().getApiKey() != null && 
                   !config.getProviders().getGemini().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getGemini().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getGemini().getApiBase(), "https://generativelanguage.googleapis.com/v1beta");
        } else if ((lowerModel.contains("glm") || lowerModel.contains("zhipu") || lowerModel.contains("zai")) && 
                   config.getProviders().getZhipu().getApiKey() != null && 
                   !config.getProviders().getZhipu().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getZhipu().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getZhipu().getApiBase(), "https://open.bigmodel.cn/api/paas/v4");
        } else if ((lowerModel.contains("qwen") || lowerModel.contains("dashscope")) && 
                   config.getProviders().getDashscope().getApiKey() != null && 
                   !config.getProviders().getDashscope().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getDashscope().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getDashscope().getApiBase(), "https://dashscope.aliyuncs.com/compatible-mode/v1");
        } else if ((lowerModel.contains("groq") || model.startsWith("groq/")) && 
                   config.getProviders().getGroq().getApiKey() != null && 
                   !config.getProviders().getGroq().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getGroq().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getGroq().getApiBase(), "https://api.groq.com/openai/v1");
        } else if (config.getProviders().getVllm().getApiBase() != null && 
                   !config.getProviders().getVllm().getApiBase().isEmpty()) {
            apiKey = config.getProviders().getVllm().getApiKey();
            apiBase = config.getProviders().getVllm().getApiBase();
        } else if (config.getProviders().getOpenrouter().getApiKey() != null && 
                   !config.getProviders().getOpenrouter().getApiKey().isEmpty()) {
            apiKey = config.getProviders().getOpenrouter().getApiKey();
            apiBase = resolveApiBase(config.getProviders().getOpenrouter().getApiBase(), "https://openrouter.ai/api/v1");
        }
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key configured for model: " + model);
        }
        
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("No API base configured for provider (model: " + model + ")");
        }
        
        logger.info("Created HTTP provider", Map.of(
                "api_base", apiBase,
                "model", model
        ));
        
        return new HTTPProvider(apiKey, apiBase);
    }
    
    private static String resolveApiBase(String configuredBase, String defaultBase) {
        return (configuredBase != null && !configuredBase.isEmpty()) ? configuredBase : defaultBase;
    }
}