package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 基于 HTTP 的 LLM Provider 实现。
 * 
 * 支持 OpenAI 兼容的 API 接口，包括但不限于：
 * - OpenAI、Anthropic、OpenRouter
 * - 智谱 AI、阿里云 DashScope
 * - Ollama、Gemini 等本地和云端服务
 * 
 * 核心功能：
 * - 支持流式和非流式对话
 * - 支持工具调用（Tool Calls）
 * - 自动路由不同服务商的 API
 * - 统一的错误处理和日志记录
 */
public class HTTPProvider implements LLMProvider {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("provider");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    // 超时配置
    private static final int CONNECT_TIMEOUT_SECONDS = 30;     // 连接超时
    private static final int READ_TIMEOUT_SECONDS = 120;       // 读取超时
    private static final int WRITE_TIMEOUT_SECONDS = 30;       // 写入超时
    
    // 其他常量
    private static final int MAX_ERROR_RESPONSE_LENGTH = 500;  // 错误响应最大长度
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private static final String AUTHORIZATION_PREFIX = "Bearer ";
    
    private final String apiKey;              // API 密钥
    private final String apiBase;             // API 基础 URL
    private final OkHttpClient httpClient;    // HTTP 客户端
    
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
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, 
                                  Map<String, Object> options, StreamCallback callback) throws Exception {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }
        
        // 构建请求体并启用流式输出
        ObjectNode requestBody = buildRequestBody(messages, tools, model, options);
        requestBody.put("stream", true);
        
        String requestJson = objectMapper.writeValueAsString(requestBody);
        logger.debug("LLM stream request", Map.of(
                "model", model,
                "messages_count", messages.size(),
                "tools_count", tools != null ? tools.size() : 0
        ));
        
        // 构建并执行 HTTP 请求
        Request request = buildHttpRequest(requestJson);
        try (Response response = httpClient.newCall(request).execute()) {
            validateResponse(response);
            return parseStreamResponse(response.body().source(), callback);
        }
    }
    
    /**
     * 解析流式响应。
     * 
     * 处理 SSE（Server-Sent Events）格式的流式数据，
     * 支持增量内容和工具调用的实时解析。
     * 
     * @param source 响应数据源
     * @param callback 流式内容回调函数
     * @return 完整的 LLM 响应对象
     * @throws IOException 解析失败时抛出异常
     */
    private LLMResponse parseStreamResponse(BufferedSource source, StreamCallback callback) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        String finishReason = "stop";
        LLMResponse.UsageInfo usage = null;
        
        try {
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                
                // SSE 格式: "data: {json}"
                if (!line.startsWith("data: ")) {
                    continue;
                }
                
                String data = line.substring(6).trim();
                
                // 结束标记
                if (data.equals("[DONE]")) {
                    break;
                }
                
                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    
                    // 解析 usage 信息
                    if (chunk.has("usage")) {
                        usage = parseUsage(chunk.get("usage"));
                    }
                    
                    if (!chunk.has("choices") || chunk.get("choices").isEmpty()) {
                        continue;
                    }
                    
                    JsonNode choice = chunk.get("choices").get(0);
                    
                    // 更新 finish_reason
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                    
                    JsonNode delta = choice.get("delta");
                    if (delta == null || delta.isNull()) {
                        continue;
                    }
                    
                    // 处理流式内容
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String content = delta.get("content").asText();
                        if (content != null && !content.isEmpty()) {
                            fullContent.append(content);
                            if (callback != null) {
                                callback.onChunk(content);
                            }
                        }
                    }
                    
                    // 处理工具调用（流式模式下可能分块传输）
                    if (delta.has("tool_calls")) {
                        parseStreamToolCalls(delta.get("tool_calls"), toolCalls);
                    }
                    
                } catch (Exception e) {
                    logger.error("Failed to parse stream chunk", Map.of(
                            "error", e.getMessage(),
                            "data", data.length() > 200 ? data.substring(0, 200) : data
                    ));
                }
            }
        } catch (IOException e) {
            logger.error("Stream read error", Map.of("error", e.getMessage()));
            throw e;
        }
        
        // 构建完整响应
        return buildStreamResponse(fullContent.toString(), toolCalls, finishReason, usage);
    }
    
    /**
     * 构建流式响应对象。
     * 
     * 将解析后的流式数据组装成完整的 LLMResponse 对象，
     * 并处理工具调用参数的 JSON 解析。
     * 
     * @param content 完整的文本内容
     * @param toolCalls 工具调用列表
     * @param finishReason 结束原因
     * @param usage token 使用统计
     * @return 完整的 LLM 响应对象
     */
    private LLMResponse buildStreamResponse(String content, List<ToolCall> toolCalls, 
                                           String finishReason, LLMResponse.UsageInfo usage) {
        LLMResponse response = new LLMResponse();
        response.setContent(content);
        response.setFinishReason(finishReason);
        response.setUsage(usage);
        
        if (!toolCalls.isEmpty()) {
            // 解析所有工具调用的 arguments
            for (ToolCall toolCall : toolCalls) {
                if (toolCall.getArguments() != null && toolCall.getArguments().containsKey("_raw_args")) {
                    String rawArgs = (String) toolCall.getArguments().get("_raw_args");
                    
                    // 检查 rawArgs 是否为空
                    if (rawArgs == null || rawArgs.trim().isEmpty()) {
                        toolCall.setArguments(new HashMap<>());
                        continue;
                    }
                    
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsedArgs = objectMapper.readValue(rawArgs, Map.class);
                        toolCall.setArguments(parsedArgs);
                    } catch (Exception e) {
                        // 解析失败，保留原始字符串
                        Map<String, Object> args = new HashMap<>();
                        args.put("raw", rawArgs);
                        toolCall.setArguments(args);
                        logger.warn("Failed to parse tool call arguments", Map.of(
                                "error", e.getMessage(),
                                "raw_args", rawArgs.length() > 100 ? rawArgs.substring(0, 100) : rawArgs
                        ));
                    }
                }
            }
            response.setToolCalls(toolCalls);
        }
        
        logger.debug("LLM stream response", Map.of(
                "content_length", content.length(),
                "tool_calls_count", toolCalls.size(),
                "finish_reason", finishReason
        ));
        
        return response;
    }
    
    /**
     * 解析流式工具调用（增量模式）。
     * 
     * 流式模式下，工具调用信息会分多个 chunk 增量传输，
     * 此方法负责将分散的数据片段拼接成完整的工具调用对象。
     * 
     * @param toolCallsNode 工具调用节点
     * @param toolCalls 工具调用列表（用于累积结果）
     */
    private void parseStreamToolCalls(JsonNode toolCallsNode, List<ToolCall> toolCalls) {
        for (JsonNode tcNode : toolCallsNode) {
            int index = tcNode.has("index") ? tcNode.get("index").asInt() : 0;
            
            // 确保列表有足够空间
            while (toolCalls.size() <= index) {
                ToolCall newToolCall = new ToolCall();
                newToolCall.setArguments(new HashMap<>());
                toolCalls.add(newToolCall);
            }
            
            ToolCall toolCall = toolCalls.get(index);
            
            // 确保 arguments 不为 null
            if (toolCall.getArguments() == null) {
                toolCall.setArguments(new HashMap<>());
            }
            
            // 解析 ID
            if (tcNode.has("id")) {
                toolCall.setId(tcNode.get("id").asText());
            }
            
            // 解析 Type
            if (tcNode.has("type")) {
                toolCall.setType(tcNode.get("type").asText());
            }
            
            // 解析 Function（增量拼接）
            if (tcNode.has("function")) {
                JsonNode funcNode = tcNode.get("function");
                
                // 解析函数名称
                if (funcNode.has("name") && !funcNode.get("name").isNull()) {
                    String name = funcNode.get("name").asText();
                    if (name != null && !name.isEmpty()) {
                        toolCall.setName(name);
                    }
                }
                
                // 增量拼接参数字符串
                if (funcNode.has("arguments")) {
                    String argsChunk = funcNode.get("arguments").asText();
                    Map<String, Object> args = toolCall.getArguments();
                    String existing = (String) args.get("_raw_args");
                    args.put("_raw_args", existing == null ? argsChunk : existing + argsChunk);
                }
            }
        }
    }
    
    /**
     * 解析 token 使用统计信息。
     * 
     * @param usageNode usage 节点
     * @return token 使用统计对象
     */
    private LLMResponse.UsageInfo parseUsage(JsonNode usageNode) {
        LLMResponse.UsageInfo usage = new LLMResponse.UsageInfo();
        usage.setPromptTokens(usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0);
        usage.setCompletionTokens(usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0);
        usage.setTotalTokens(usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : 0);
        return usage;
    }
    
    /**
     * 构建 HTTP 请求体。
     * 
     * 将消息、工具定义和选项转换为 OpenAI 兼容的 JSON 格式。
     * 
     * @param messages 对话消息列表
     * @param tools 工具定义列表
     * @param model 模型名称
     * @param options 额外选项（如 max_tokens、temperature）
     * @return JSON 请求体对象
     * @throws Exception 构建失败时抛出异常
     */
    private ObjectNode buildRequestBody(List<Message> messages, List<ToolDefinition> tools, 
                                       String model, Map<String, Object> options) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        
        // 添加消息
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            messagesArray.add(buildMessageNode(msg));
        }
        
        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDefinition tool : tools) {
                toolsArray.add(buildToolNode(tool));
            }
            requestBody.put("tool_choice", "auto");
        }
        
        // 添加选项参数
        addOptions(requestBody, model, options);
        
        return requestBody;
    }
    
    /**
     * 构建单个消息节点。
     * 
     * @param msg 消息对象
     * @return JSON 消息节点
     * @throws Exception 构建失败时抛出异常
     */
    private ObjectNode buildMessageNode(Message msg) throws Exception {
        ObjectNode msgNode = objectMapper.createObjectNode();
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
        
        return msgNode;
    }
    
    /**
     * 构建工具定义节点。
     * 
     * @param tool 工具定义对象
     * @return JSON 工具节点
     */
    private ObjectNode buildToolNode(ToolDefinition tool) {
        ObjectNode toolNode = objectMapper.createObjectNode();
        toolNode.put("type", tool.getType());
        ObjectNode funcNode = toolNode.putObject("function");
        funcNode.put("name", tool.getFunction().getName());
        funcNode.put("description", tool.getFunction().getDescription());
        funcNode.set("parameters", objectMapper.valueToTree(tool.getFunction().getParameters()));
        return toolNode;
    }
    
    /**
     * 添加请求选项参数。
     * 
     * 根据不同模型自动适配参数名称（如 max_tokens vs max_completion_tokens）。
     * 
     * @param requestBody 请求体对象
     * @param model 模型名称
     * @param options 选项映射
     */
    private void addOptions(ObjectNode requestBody, String model, Map<String, Object> options) {
        if (options == null) {
            return;
        }
        
        if (options.containsKey("max_tokens")) {
            // 处理不同模型的 max_tokens 参数名称
            String lowerModel = model.toLowerCase();
            String paramName = (lowerModel.contains("glm") || lowerModel.contains("o1")) 
                    ? "max_completion_tokens" 
                    : "max_tokens";
            requestBody.put(paramName, ((Number) options.get("max_tokens")).intValue());
        }
        
        if (options.containsKey("temperature")) {
            requestBody.put("temperature", ((Number) options.get("temperature")).doubleValue());
        }
    }
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, 
                           Map<String, Object> options) throws Exception {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }
        
        // 构建请求体
        ObjectNode requestBody = buildRequestBody(messages, tools, model, options);
        String requestJson = objectMapper.writeValueAsString(requestBody);
        
        logger.debug("LLM request", Map.of(
                "model", model,
                "messages_count", messages.size(),
                "tools_count", tools != null ? tools.size() : 0,
                "request_length", requestJson.length()
        ));
        
        // 构建并执行 HTTP 请求
        Request request = buildHttpRequest(requestJson);
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            validateResponse(response, responseBody);
            return parseResponse(responseBody);
        }
    }
    
    /**
     * 构建 HTTP 请求对象。
     * 
     * @param requestJson JSON 请求体字符串
     * @return HTTP 请求对象
     */
    private Request buildHttpRequest(String requestJson) {
        String url = apiBase + CHAT_COMPLETIONS_ENDPOINT;
        RequestBody body = RequestBody.create(requestJson, JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");
        
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", AUTHORIZATION_PREFIX + apiKey);
        }
        
        return requestBuilder.build();
    }
    
    /**
     * 验证 HTTP 响应状态。
     * 
     * @param response HTTP 响应对象
     * @throws IOException 响应失败时抛出异常
     */
    private void validateResponse(Response response) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        
        String errorBody = response.body() != null ? response.body().string() : "";
        String errorPreview = errorBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, errorBody.length()));
        
        logger.error("LLM API error", Map.of(
                "status_code", response.code(),
                "response", errorPreview
        ));
        
        throw new IOException("LLM API error (status " + response.code() + "): " + errorBody);
    }
    
    /**
     * 验证 HTTP 响应状态（带响应体参数）。
     * 
     * @param response HTTP 响应对象
     * @param responseBody 响应体内容
     * @throws IOException 响应失败时抛出异常
     */
    private void validateResponse(Response response, String responseBody) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        
        String errorPreview = responseBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, responseBody.length()));
        
        logger.error("LLM API error", Map.of(
                "status_code", response.code(),
                "response", errorPreview
        ));
        
        throw new IOException("LLM API error (status " + response.code() + "): " + responseBody);
    }
    
    /**
     * 解析 LLM 响应。
     * 
     * 从 JSON 响应中提取内容、工具调用和使用统计信息。
     * 
     * @param responseBody 响应体 JSON 字符串
     * @return LLM 响应对象
     * @throws IOException 解析失败时抛出异常
     */
    private LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        LLMResponse response = new LLMResponse();
        
        // 解析 token 使用统计
        if (root.has("usage")) {
            response.setUsage(parseUsage(root.get("usage")));
        }
        
        // 解析响应内容
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
            response.setToolCalls(parseToolCalls(messageNode.get("tool_calls")));
        }
        
        logger.debug("LLM response", Map.of(
                "content_length", response.getContent() != null ? response.getContent().length() : 0,
                "tool_calls_count", response.hasToolCalls() ? response.getToolCalls().size() : 0,
                "finish_reason", response.getFinishReason()
        ));
        
        return response;
    }
    
    /**
     * 解析工具调用列表。
     * 
     * @param toolCallsNode 工具调用 JSON 节点
     * @return 工具调用列表
     */
    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        for (JsonNode tcNode : toolCallsNode) {
            ToolCall toolCall = new ToolCall();
            toolCall.setId(tcNode.has("id") ? tcNode.get("id").asText() : UUID.randomUUID().toString());
            toolCall.setType(tcNode.has("type") ? tcNode.get("type").asText() : "function");
            
            if (tcNode.has("function")) {
                JsonNode funcNode = tcNode.get("function");
                String name = funcNode.has("name") ? funcNode.get("name").asText() : "";
                String argsStr = funcNode.has("arguments") ? funcNode.get("arguments").asText() : "{}";
                
                toolCall.setName(name);
                toolCall.setArguments(parseToolArguments(argsStr));
            }
            
            toolCalls.add(toolCall);
        }
        
        return toolCalls;
    }
    
    /**
     * 解析工具调用参数。
     * 
     * @param argsStr 参数 JSON 字符串
     * @return 参数映射，解析失败时返回包含原始字符串的映射
     */
    private Map<String, Object> parseToolArguments(String argsStr) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsStr, Map.class);
            return args;
        } catch (Exception e) {
            Map<String, Object> args = new HashMap<>();
            args.put("raw", argsStr);
            return args;
        }
    }
    
    @Override
    public String getDefaultModel() {
        return "";
    }
    
    /**
     * 根据配置创建 HTTPProvider 实例。
     * 
     * 路由机制：
     * 1. 从 models.definitions 中精确匹配模型
     * 2. 根据 provider 名称获取对应的 API Key 和 API Base
     * 3. 验证配置完整性
     * 
     * @param config 配置对象
     * @return HTTPProvider 实例
     * @throws IllegalStateException 配置错误时抛出异常
     */
    public static LLMProvider createProvider(Config config) {
        String modelName = config.getAgent().getModel();
        
        // 从配置中查找模型定义
        var modelDef = config.getModels().getDefinitions().get(modelName);
        if (modelDef == null) {
            throwModelNotFoundError(modelName, config);
        }
        
        String providerName = modelDef.getProvider();
        logger.info("创建 Provider", Map.of(
            "model", modelName,
            "provider", providerName,
            "max_context", modelDef.getMaxContextSize() != null ? modelDef.getMaxContextSize() : "unknown"
        ));
        
        // 获取 provider 配置
        ProviderConfig providerConfig = resolveProviderConfig(providerName, config);
        
        // 验证配置完整性
        validateProviderConfig(providerName, modelName, providerConfig);
        
        logger.info("Created HTTP provider", Map.of(
            "provider", providerName,
            "model", modelName,
            "api_base", providerConfig.apiBase
        ));
        
        return new HTTPProvider(providerConfig.apiKey, providerConfig.apiBase);
    }
    
    /**
     * Provider 配置封装类。
     */
    private static class ProviderConfig {
        String apiKey;
        String apiBase;
        
        ProviderConfig(String apiKey, String apiBase) {
            this.apiKey = apiKey;
            this.apiBase = apiBase;
        }
    }
    
    /**
     * 抛出模型未找到异常。
     * 
     * @param modelName 模型名称
     * @param config 配置对象
     * @throws IllegalStateException 始终抛出异常
     */
    private static void throwModelNotFoundError(String modelName, Config config) {
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
    
    /**
     * 解析 provider 配置。
     * 
     * @param providerName provider 名称
     * @param config 配置对象
     * @return provider 配置对象
     * @throws IllegalStateException 不支持的 provider 时抛出异常
     */
    private static ProviderConfig resolveProviderConfig(String providerName, Config config) {
        return switch (providerName) {
            case "dashscope" -> {
                var provider = config.getProviders().getDashscope();
                yield new ProviderConfig(
                    provider.getApiKey(),
                    resolveApiBase(provider.getApiBase(), "https://dashscope.aliyuncs.com/compatible-mode/v1")
                );
            }
            case "openai" -> {
                var provider = config.getProviders().getOpenai();
                yield new ProviderConfig(
                    provider.getApiKey(),
                    resolveApiBase(provider.getApiBase(), "https://api.openai.com/v1")
                );
            }
            case "anthropic" -> {
                var provider = config.getProviders().getAnthropic();
                yield new ProviderConfig(
                    provider.getApiKey(),
                    resolveApiBase(provider.getApiBase(), "https://api.anthropic.com/v1")
                );
            }
            case "zhipu" -> {
                var provider = config.getProviders().getZhipu();
                yield new ProviderConfig(
                    provider.getApiKey(),
                    resolveApiBase(provider.getApiBase(), "https://open.bigmodel.cn/api/paas/v4")
                );
            }
            case "gemini" -> {
                var provider = config.getProviders().getGemini();
                yield new ProviderConfig(
                    provider.getApiKey(),
                    resolveApiBase(provider.getApiBase(), "https://generativelanguage.googleapis.com/v1beta")
                );
            }
            case "ollama" -> {
                var provider = config.getProviders().getOllama();
                yield new ProviderConfig(
                    "",  // Ollama 不需要 API Key
                    resolveApiBase(provider.getApiBase(), "http://localhost:11434/v1")
                );
            }
            case "openrouter" -> {
                var provider = config.getProviders().getOpenrouter();
                yield new ProviderConfig(
                    provider.getApiKey(),
                    resolveApiBase(provider.getApiBase(), "https://openrouter.ai/api/v1")
                );
            }
            default -> throw new IllegalStateException(
                "不支持的 provider: " + providerName + ". 请检查配置"
            );
        };
    }
    
    /**
     * 验证 provider 配置完整性。
     * 
     * @param providerName provider 名称
     * @param modelName 模型名称
     * @param config provider 配置
     * @throws IllegalStateException 配置不完整时抛出异常
     */
    private static void validateProviderConfig(String providerName, String modelName, ProviderConfig config) {
        // 验证 API Base
        if (config.apiBase == null || config.apiBase.isEmpty()) {
            throw new IllegalStateException(
                "Provider " + providerName + " 的 apiBase 未配置 (model: " + modelName + ")"
            );
        }
        
        // 对于非本地服务，检查 API Key
        boolean isLocalService = "ollama".equals(providerName) || "vllm".equals(providerName);
        if (!isLocalService && (config.apiKey == null || config.apiKey.isEmpty())) {
            throw new IllegalStateException(
                "Provider " + providerName + " 的 apiKey 未配置 (model: " + modelName + ")"
            );
        }
    }
    
    /**
     * 解析 API Base URL。
     * 
     * @param configuredBase 配置的 Base URL
     * @param defaultBase 默认 Base URL
     * @return 最终使用的 Base URL
     */
    private static String resolveApiBase(String configuredBase, String defaultBase) {
        return (configuredBase != null && !configuredBase.isEmpty()) ? configuredBase : defaultBase;
    }
}