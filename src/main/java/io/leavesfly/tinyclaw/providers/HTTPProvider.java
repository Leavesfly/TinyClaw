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
 * 基于 HTTP 的 LLM Provider 实现
 * 支持 OpenAI 兼容的 API（OpenRouter、Anthropic、智谱等）
 *
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
        
        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        
        // 添加消息
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
        
        // 添加工具
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
        
        // 添加选项
        if (options != null) {
            if (options.containsKey("max_tokens")) {
                // 处理不同模型的 max_tokens 参数名称
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
        
        // 构建 HTTP 请求
        String url = apiBase + CHAT_COMPLETIONS_ENDPOINT;
        RequestBody body = RequestBody.create(requestJson, JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");
        
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", AUTHORIZATION_PREFIX + apiKey);
        }
        
        // 执行请求
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
        
        // 解析使用量统计
        if (root.has("usage")) {
            JsonNode usageNode = root.get("usage");
            LLMResponse.UsageInfo usage = new LLMResponse.UsageInfo();
            usage.setPromptTokens(usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0);
            usage.setCompletionTokens(usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0);
            usage.setTotalTokens(usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : 0);
            response.setUsage(usage);
        }
        
        // 解析响应选项
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
        
        // 解析工具调用
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
     * 根据配置创建 Provider
     * 
     * 路由机制：
     * 1. 从 models.definitions 中精确匹配模型
     * 2. 根据 provider 名称获取对应的 API Key 和 API Base
     */
    public static LLMProvider createProvider(Config config) {
        String modelName = config.getAgents().getDefaults().getModel();
        
        // 精确匹配：从 models.definitions 中查找
        var modelDef = config.getModels().getDefinitions().get(modelName);
        
        if (modelDef == null) {
            // 生成友好的错误提示，列出所有可用模型
            var availableModels = config.getModels().getDefinitions().keySet();
            String availableList = availableModels.isEmpty() 
                ? "无可用模型" 
                : String.join(", ", availableModels);
            
            throw new IllegalStateException(
                "未知模型: " + modelName + "\n" +
                "请在 config.models.definitions 中定义此模型\n" +
                "可用模型: " + availableList
            );
        }
        
        String providerName = modelDef.getProvider();
        logger.info("创建 Provider", Map.of(
            "model", modelName,
            "provider", providerName,
            "max_context", modelDef.getMaxContextSize() != null ? modelDef.getMaxContextSize() : "unknown"
        ));
        
        // 获取 provider 配置
        String apiKey = null;
        String apiBase = null;
        
        switch (providerName) {
            case "dashscope" -> {
                var provider = config.getProviders().getDashscope();
                apiKey = provider.getApiKey();
                apiBase = resolveApiBase(provider.getApiBase(), "https://dashscope.aliyuncs.com/compatible-mode/v1");
            }
            case "openai" -> {
                var provider = config.getProviders().getOpenai();
                apiKey = provider.getApiKey();
                apiBase = resolveApiBase(provider.getApiBase(), "https://api.openai.com/v1");
            }
            case "anthropic" -> {
                var provider = config.getProviders().getAnthropic();
                apiKey = provider.getApiKey();
                apiBase = resolveApiBase(provider.getApiBase(), "https://api.anthropic.com/v1");
            }
            case "zhipu" -> {
                var provider = config.getProviders().getZhipu();
                apiKey = provider.getApiKey();
                apiBase = resolveApiBase(provider.getApiBase(), "https://open.bigmodel.cn/api/paas/v4");
            }
            case "gemini" -> {
                var provider = config.getProviders().getGemini();
                apiKey = provider.getApiKey();
                apiBase = resolveApiBase(provider.getApiBase(), "https://generativelanguage.googleapis.com/v1beta");
            }
            case "ollama" -> {
                var provider = config.getProviders().getOllama();
                apiKey = ""; // Ollama 不需要 API Key
                apiBase = resolveApiBase(provider.getApiBase(), "http://localhost:11434/v1");
            }
            case "openrouter" -> {
                var provider = config.getProviders().getOpenrouter();
                apiKey = provider.getApiKey();
                apiBase = resolveApiBase(provider.getApiBase(), "https://openrouter.ai/api/v1");
            }
            default -> {
                throw new IllegalStateException(
                    "不支持的 provider: " + providerName + ". 请检查配置"
                );
            }
        }
        
        // 验证配置
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException(
                "Provider " + providerName + " 的 apiBase 未配置 (model: " + modelName + ")"
            );
        }
        
        // 对于非本地服务，检查 apiKey
        // 本地服务（ollama, vllm）只需要 apiBase，不需要 apiKey
        boolean isLocalService = "ollama".equals(providerName) || "vllm".equals(providerName);
        if (!isLocalService && (apiKey == null || apiKey.isEmpty())) {
            throw new IllegalStateException(
                "Provider " + providerName + " 的 apiKey 未配置 (model: " + modelName + ")"
            );
        }
        
        logger.info("Created HTTP provider", Map.of(
            "provider", providerName,
            "model", modelName,
            "api_base", apiBase
        ));
        
        return new HTTPProvider(apiKey, apiBase);
    }
    
    private static String resolveApiBase(String configuredBase, String defaultBase) {
        return (configuredBase != null && !configuredBase.isEmpty()) ? configuredBase : defaultBase;
    }
}