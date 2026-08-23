package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
    private static final int READ_TIMEOUT_SECONDS = 120;       // 非流式读取超时
    private static final int WRITE_TIMEOUT_SECONDS = 30;       // 写入超时
    
    /**
     * 流式读取超时（秒）。
     * 
     * <p>OkHttp 的 readTimeout 在流式下是「两个字节之间的最大间隔」。推理型模型（如
     * 本地 Ollama 上的大参数量模型）在吐出首个 token 前可能静默数分钟，用 120s
     * 会把正常的长时思考误判为超时并中断请求。流式场景因此单独放宽。</p>
     */
    private static final int STREAM_READ_TIMEOUT_SECONDS = 600;
    
    // 其他常量
    private static final int MAX_ERROR_RESPONSE_LENGTH = 500;  // 错误响应最大长度
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private static final String AUTHORIZATION_PREFIX = "Bearer ";
    
    private final String apiKey;              // API 密鑰
    private final String apiBase;             // API 基础 URL
    private final String name;               // Provider 名称
    private final OkHttpClient httpClient;    // HTTP 客户端（非流式）
    private final OkHttpClient streamHttpClient;  // HTTP 客户端（流式，读超时更宽）
    private final LLMRequestBuilder requestBuilder;       // 请求构建器
    private final StreamResponseParser responseParser;    // 响应解析器
    
    /** 思考模式开关（默认开启）：关闭时请求中注入对应 provider 的关闭参数 */
    private volatile boolean thinkingEnabled = true;
    
    /**
     * 设置思考模式开关，保存配置后可热更新，无需重建 Provider。
     *
     * @param thinkingEnabled 是否启用思考模式
     */
    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }
        
    public HTTPProvider(String apiKey, String apiBase) {
        this(apiKey, apiBase, "unknown");
    }
    
    public HTTPProvider(String apiKey, String apiBase, String name) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        this.name = name != null ? name : "unknown";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
        // 复用连接池与调度器，仅放宽读超时，避免多开一套线程与连接资源
        this.streamHttpClient = this.httpClient.newBuilder()
                .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.requestBuilder = new LLMRequestBuilder(objectMapper);
        this.responseParser = new StreamResponseParser(objectMapper);
    }
    
    @Override
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, 
                                  Map<String, Object> options, StreamCallback callback) {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }
        
        // 构建请求体并启用流式输出
        ObjectNode requestBody;
        try {
            requestBody = requestBuilder.buildRequestBody(messages, tools, model, options);
        } catch (Exception e) {
            throw new LLMException("构建请求体失败", e);
        }
        applyThinkingDefaults(requestBody);
        requestBody.put("stream", true);
        // 显式要求在流式响应的最后一个 chunk 中返回 usage 信息（OpenAI 兼容 API 标准）
        ObjectNode streamOptions = requestBody.putObject("stream_options");
        streamOptions.put("include_usage", true);
        
        String requestJson;
        try {
            requestJson = requestBuilder.toJson(requestBody);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败", e);
        }
        logger.debug("LLM stream request", Map.of(
                "model", model,
                "messages_count", messages.size(),
                "tools_count", tools != null ? tools.size() : 0
        ));
        
        // 构建并执行 HTTP 请求
        Request request = buildHttpRequest(requestJson);
        try (Response response = streamHttpClient.newCall(request).execute()) {
            validateResponse(response, model);
            if (response.body() == null) {
                throw new LLMException("流式响应体为空 (model=" + model + ")");
            }
            return responseParser.parseStreamResponse(response.body().source(), callback);
        } catch (IOException e) {
            throw wrapRequestFailure("stream", model, e);
        }
    }
    
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, 
                           Map<String, Object> options) {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }
        
        // 构建请求体
        ObjectNode requestBody;
        try {
            requestBody = requestBuilder.buildRequestBody(messages, tools, model, options);
        } catch (Exception e) {
            throw new LLMException("构建请求体失败", e);
        }
        applyThinkingDefaults(requestBody);
        String requestJson;
        try {
            requestJson = requestBuilder.toJson(requestBody);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败", e);
        }
        
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
            validateResponse(response, responseBody, model);
            return responseParser.parseResponse(responseBody);
        } catch (IOException e) {
            throw wrapRequestFailure("non-stream", model, e);
        }
    }
    
    /**
     * 包装网络层请求失败异常，记录定位所需的完整上下文。
     *
     * <p>原始实现仅包装为 {@code LLMException("执行请求失败")}，上层只打 getMessage()
     * 时会丢失底层网络根因（如 SocketTimeoutException: timeout），无法定位是哪个
     * provider/模型/地址出了问题。此处日志携带全部定位字段，异常消息附带根因。</p>
     *
     * @param mode  请求模式（stream / non-stream）
     * @param model 本次请求的模型名称
     * @param cause 网络层异常
     * @return 携带根因的 LLMException
     */
    private LLMException wrapRequestFailure(String mode, String model, IOException cause) {
        String rootCause = LLMException.rootCauseMessage(cause);
        logger.error("LLM request IO failure", Map.of(
                "mode", mode,
                "provider", name,
                "model", model,
                "api_base", apiBase,
                "error_type", cause.getClass().getName(),
                "root_cause", rootCause
        ));
        return new LLMException("执行请求失败: " + rootCause, cause);
    }
    
    /**
     * 按思考模式开关注入对应参数。
     * 
     * 开启（默认）：不注入任何参数，由模型自行决定是否思考。
     * 关闭：按 provider 差异注入关闭参数：
     * - ollama 使用 {@code reasoning_effort: "none"}
     *   （注意：顶层 think 参数仅在原生 /api/chat 生效，
     *   本项目使用的 OpenAI 兼容 /v1/chat/completions 端点会忽略它）
     * - 其他 OpenAI 兼容服务（如 dashscope）使用 {@code enable_thinking: false}
     * 
     * 若请求体中已显式携带思考参数，则以调用方设置为准，不覆盖。
     * 
     * @param requestBody 请求体对象
     */
    private void applyThinkingDefaults(ObjectNode requestBody) {
        if (thinkingEnabled) {
            return;
        }
        if ("ollama".equals(name)) {
            if (!requestBody.has("reasoning_effort")) {
                requestBody.put("reasoning_effort", "none");
            }
        } else if (!requestBody.has("enable_thinking")) {
            requestBody.put("enable_thinking", false);
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
     * 验证 HTTP 响应状态（流式请求）。
     *
     * @param response HTTP 响应对象
     * @param model    请求使用的模型名称
     * @throws IOException 响应失败时抛出异常
     */
    private void validateResponse(Response response, String model) throws IOException {
        if (response.isSuccessful()) {
            return;
        }

        String errorBody = response.body() != null ? response.body().string() : "";
        String errorPreview = errorBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, errorBody.length()));

        logger.error("LLM API error", Map.of(
                "model", model,
                "api_base", apiBase,
                "status_code", response.code(),
                "response", errorPreview
        ));

        throw new IOException("LLM API error (model=" + model + ", status=" + response.code() + "): " + errorPreview);
    }

    /**
     * 验证 HTTP 响应状态（非流式请求，带响应体参数）。
     *
     * @param response     HTTP 响应对象
     * @param responseBody 响应体内容
     * @param model        请求使用的模型名称
     * @throws IOException 响应失败时抛出异常
     */
    private void validateResponse(Response response, String responseBody, String model) throws IOException {
        if (response.isSuccessful()) {
            return;
        }

        String errorPreview = responseBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, responseBody.length()));

        logger.error("LLM API error", Map.of(
                "model", model,
                "api_base", apiBase,
                "status_code", response.code(),
                "response", errorPreview
        ));

        // 仅带截断后的预览：异常消息会经 SSE 途径回显给前端，
        // 完整错误体可能包含被回显的请求内容且体积不可控
        throw new IOException("LLM API error (model=" + model + ", status=" + response.code() + "): " + errorPreview);
    }
    
    @Override
    public String getDefaultModel() {
        return "";
    }

    @Override
    public String getName() {
        return name;
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
        
        HTTPProvider provider = new HTTPProvider(providerConfig.apiKey, providerConfig.apiBase, providerName);
        provider.setThinkingEnabled(config.getAgent().isThinkingEnabled());
        return provider;
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
