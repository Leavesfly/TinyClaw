package io.leavesfly.tinyclaw.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Web 层共享工具类，提供 HTTP 响应、JSON 构建、密钥掩码等通用能力。
 */
public class WebUtils {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== Content-Type ====================
    public static final String CONTENT_TYPE_JSON  = "application/json; charset=utf-8";
    public static final String CONTENT_TYPE_HTML  = "text/html; charset=utf-8";
    public static final String CONTENT_TYPE_CSS   = "text/css; charset=utf-8";
    public static final String CONTENT_TYPE_JS    = "application/javascript; charset=utf-8";
    public static final String CONTENT_TYPE_PNG   = "image/png";
    public static final String CONTENT_TYPE_SVG   = "image/svg+xml";
    public static final String CONTENT_TYPE_ICO   = "image/x-icon";
    public static final String CONTENT_TYPE_OCTET = "application/octet-stream";
    public static final String CONTENT_TYPE_PLAIN = "text/plain; charset=utf-8";
    public static final String CONTENT_TYPE_SSE   = "text/event-stream; charset=utf-8";

    // ==================== HTTP Headers ====================
    public static final String HEADER_CONTENT_TYPE    = "Content-Type";
    public static final String HEADER_CACHE_CONTROL   = "Cache-Control";
    public static final String HEADER_CONNECTION      = "Connection";
    public static final String HEADER_CORS            = "Access-Control-Allow-Origin";
    public static final String HEADER_CORS_HEADERS    = "Access-Control-Allow-Headers";
    public static final String HEADER_CORS_METHODS    = "Access-Control-Allow-Methods";
    public static final String HEADER_CORS_HEADERS_VALUE = "Content-Type, Authorization";
    public static final String HEADER_CORS_METHODS_VALUE = "GET, POST, PUT, DELETE, OPTIONS";
    public static final String HEADER_AUTHORIZATION   = "Authorization";
    public static final String HEADER_NO_CACHE        = "no-cache";
    public static final String HEADER_KEEP_ALIVE      = "keep-alive";

    // ==================== SSE ====================
    public static final String SSE_PREFIX             = "data: ";
    public static final String SSE_SUFFIX             = "\n\n";
    public static final String SSE_DONE               = "data: [DONE]\n\n";
    public static final String SSE_ERROR_PREFIX       = "data: [ERROR] ";
    public static final String SSE_NEWLINE_REPLACEMENT = "\ndata: ";

    // ==================== Secret Mask ====================
    public static final int    MASK_PREFIX_LENGTH     = 4;
    public static final int    MASK_SUFFIX_LENGTH     = 4;
    public static final int    MASK_MIN_LENGTH        = 8;
    public static final String MASK_PLACEHOLDER       = "****";
    public static final String SECRET_MASK_INDICATOR  = "*";

    // ==================== Static Files ====================
    public static final String PATH_ROOT              = "/";
    public static final String PATH_SEPARATOR         = "/";
    public static final String PATH_PARENT            = "..";
    public static final String PATH_INDEX             = "/index.html";
    public static final String RESOURCE_PREFIX        = "web";

    // ==================== File Extensions ====================
    public static final String FILE_EXT_HTML = ".html";
    public static final String FILE_EXT_CSS  = ".css";
    public static final String FILE_EXT_JS   = ".js";
    public static final String FILE_EXT_JSON = ".json";
    public static final String FILE_EXT_PNG  = ".png";
    public static final String FILE_EXT_SVG  = ".svg";
    public static final String FILE_EXT_ICO  = ".ico";

    // ==================== Request Limits ====================
    public static final int MAX_REQUEST_BODY_SIZE = 1024 * 1024;

    // ==================== API Paths ====================
    public static final String API_CHAT            = "/api/chat";
    public static final String API_CHAT_STREAM     = "/api/chat/stream";
    public static final String API_CHAT_ABORT      = "/api/chat/abort";
    public static final String API_CHAT_STATUS     = "/api/chat/status";
    public static final String API_CHANNELS        = "/api/channels";
    public static final String API_SESSIONS        = "/api/sessions";
    public static final String API_CRON            = "/api/cron";
    public static final String API_WORKSPACE       = "/api/workspace";
    public static final String API_WORKSPACE_FILES = "/api/workspace/files";
    public static final String API_SKILLS          = "/api/skills";
    public static final String API_PROVIDERS       = "/api/providers";
    public static final String API_MODELS          = "/api/models";
    public static final String API_CONFIG          = "/api/config";
    public static final String API_CONFIG_MODEL    = "/api/config/model";
    public static final String API_CONFIG_AGENT    = "/api/config/agent";
    public static final String API_FEEDBACK        = "/api/feedback";
    public static final String API_MCP             = "/api/mcp";
    public static final String API_UPLOAD          = "/api/upload";
    public static final String API_FILES           = "/api/files";
    public static final String API_TOKEN_STATS     = "/api/token-stats";

    // ==================== HTTP Methods ====================
    public static final String HTTP_METHOD_GET     = "GET";
    public static final String HTTP_METHOD_POST    = "POST";
    public static final String HTTP_METHOD_PUT     = "PUT";
    public static final String HTTP_METHOD_DELETE  = "DELETE";
    public static final String HTTP_METHOD_OPTIONS = "OPTIONS";

    // ==================== Session ====================
    public static final String DEFAULT_SESSION_ID  = "web:default";

    // ==================== Workspace ====================
    public static final String   MEMORY_SUBDIR    = "memory";
    public static final String   MEMORY_FILE      = "MEMORY.md";
    public static final String[] WORKSPACE_FILES  = {
        "AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md", "PROFILE.md", "HEARTBEAT.md"
    };

    // ==================== Channel Names ====================
    public static final String CHANNEL_TELEGRAM = "telegram";
    public static final String CHANNEL_DISCORD  = "discord";
    public static final String CHANNEL_WHATSAPP = "whatsapp";
    public static final String CHANNEL_FEISHU   = "feishu";
    public static final String CHANNEL_DINGTALK = "dingtalk";
    public static final String CHANNEL_QQ       = "qq";
    public static final String CHANNEL_MAIXCAM  = "maixcam";

    // ==================== Provider Names ====================
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_OPENAI     = "openai";
    public static final String PROVIDER_ANTHROPIC  = "anthropic";
    public static final String PROVIDER_ZHIPU      = "zhipu";
    public static final String PROVIDER_DASHSCOPE  = "dashscope";
    public static final String PROVIDER_GEMINI     = "gemini";
    public static final String PROVIDER_OLLAMA     = "ollama";

    private WebUtils() {}

    // ==================== HTTP Response Helpers ====================

    /**
     * 发送 JSON 响应，并附带 CORS 头。
     */
    public static void sendJson(HttpExchange exchange, int statusCode, Object data, String corsOrigin) throws IOException {
        String json = MAPPER.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        exchange.getResponseHeaders().set(HEADER_CORS, corsOrigin);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 发送纯文本错误响应。
     */
    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, CONTENT_TYPE_PLAIN);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 发送 404 JSON 响应。
     */
    public static void sendNotFound(HttpExchange exchange, String corsOrigin) throws IOException {
        sendJson(exchange, 404, errorJson("Not found"), corsOrigin);
    }

    // ==================== JSON Builders ====================

    public static ObjectNode errorJson(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message);
        return node;
    }

    public static ObjectNode successJson(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("success", true);
        node.put("message", message);
        return node;
    }

    // ==================== Request Body ====================

    /**
     * 读取请求体（无大小限制）。
     */
    public static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 读取请求体（最大 1MB）。
     */
    public static String readRequestBodyLimited(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = is.read(chunk)) != -1) {
                totalRead += bytesRead;
                if (totalRead > MAX_REQUEST_BODY_SIZE) {
                    throw new IOException("Request body too large (max " + MAX_REQUEST_BODY_SIZE + " bytes)");
                }
                buffer.write(chunk, 0, bytesRead);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    // ==================== Secret Mask ====================

    /**
     * 掩码显示敏感信息（API Key、Token 等）。
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= MASK_MIN_LENGTH) {
            return MASK_PLACEHOLDER;
        }
        return secret.substring(0, MASK_PREFIX_LENGTH)
               + MASK_PLACEHOLDER
               + secret.substring(secret.length() - MASK_SUFFIX_LENGTH);
    }

    /**
     * 判断密钥是否已被掩码。
     */
    public static boolean isSecretMasked(String secret) {
        return secret.contains(SECRET_MASK_INDICATOR);
    }

    // ==================== Content Type ====================

    /**
     * 根据文件扩展名返回 Content-Type。
     */
    public static String getContentType(String path) {
        if (path.endsWith(FILE_EXT_HTML)) return CONTENT_TYPE_HTML;
        if (path.endsWith(FILE_EXT_CSS))  return CONTENT_TYPE_CSS;
        if (path.endsWith(FILE_EXT_JS))   return CONTENT_TYPE_JS;
        if (path.endsWith(FILE_EXT_JSON)) return CONTENT_TYPE_JSON;
        if (path.endsWith(FILE_EXT_PNG))  return CONTENT_TYPE_PNG;
        if (path.endsWith(FILE_EXT_SVG))  return CONTENT_TYPE_SVG;
        if (path.endsWith(FILE_EXT_ICO))  return CONTENT_TYPE_ICO;
        return CONTENT_TYPE_OCTET;
    }

    // ==================== Config Persistence ====================

    /**
     * 将配置保存到文件。
     */
    public static void saveConfig(Config config, TinyClawLogger logger) {
        try {
            ConfigLoader.save(ConfigLoader.getConfigPath(), config);
        } catch (IOException e) {
            logger.error("Failed to save config", Map.of("error", e.getMessage()));
        }
    }
}
