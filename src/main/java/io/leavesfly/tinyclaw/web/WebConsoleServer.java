package io.leavesfly.tinyclaw.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.config.*;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillsLoader;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Web 控制台服务器，提供基于 HTTP 的 Web 管理界面。
 * 
 * 核心功能：
 * - Chat API：与 Agent 对话（支持流式和非流式）
 * - Channels API：管理消息通道（Telegram、Discord、飞书等）
 * - Sessions API：管理会话历史
 * - Cron API：管理定时任务
 * - Workspace API：管理工作空间文件（SOUL.md、USER.md 等）
 * - Skills API：查看和管理技能
 * - Providers API：配置 LLM 提供商（OpenRouter、OpenAI 等）
 * - Models API：查看可用模型列表
 * - Config API：修改 Agent 配置
 * - Static Files：提供前端静态资源
 * 
 * 技术实现：
 * - 基于 JDK HttpServer 实现轻量级 HTTP 服务
 * - 使用线程池处理并发请求
 * - 支持 CORS 跨域访问
 * - 支持 Server-Sent Events (SSE) 流式传输
 * - Jackson 处理 JSON 序列化和反序列化
 * 
 * 安全特性：
 * - 敏感信息掩码显示（API Key、Token 等）
 * - 静态文件路径遍历防护
 * - 工作空间文件访问限制
 * 
 * API 端点：
 * - POST /api/chat - 发送消息给 Agent
 * - POST /api/chat/stream - 流式聊天
 * - GET /api/channels - 获取通道列表
 * - GET /api/sessions - 获取会话列表
 * - GET /api/cron - 获取定时任务列表
 * - GET /api/workspace/files - 获取工作空间文件列表
 * - GET /api/skills - 获取技能列表
 * - GET /api/providers - 获取提供商列表
 * - GET /api/models - 获取模型列表
 * - GET /api/config/agent - 获取 Agent 配置
 */
public class WebConsoleServer {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");           // 日志记录器
    private static final ObjectMapper objectMapper = new ObjectMapper();                    // JSON 序列化工具
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";      // JSON 内容类型
    private static final String CONTENT_TYPE_HTML = "text/html; charset=utf-8";             // HTML 内容类型
    private static final String CONTENT_TYPE_CSS = "text/css; charset=utf-8";               // CSS 内容类型
    private static final String CONTENT_TYPE_JS = "application/javascript; charset=utf-8";  // JavaScript 内容类型
    private static final String CONTENT_TYPE_PNG = "image/png";                             // PNG 内容类型
    private static final String CONTENT_TYPE_SVG = "image/svg+xml";                         // SVG 内容类型
    private static final String CONTENT_TYPE_ICO = "image/x-icon";                          // ICO 内容类型
    private static final String CONTENT_TYPE_OCTET = "application/octet-stream";            // 二进制内容类型
    private static final String CONTENT_TYPE_PLAIN = "text/plain; charset=utf-8";           // 纯文本内容类型
    private static final String CONTENT_TYPE_SSE = "text/event-stream; charset=utf-8";      // SSE 内容类型
    
    private static final int THREAD_POOL_SIZE = 8;                          // 线程池大小
    private static final int SERVER_STOP_DELAY = 2;                         // 服务器停止延迟（秒）
    private static final int MASK_PREFIX_LENGTH = 4;                        // 密钥掩码前缀长度
    private static final int MASK_SUFFIX_LENGTH = 4;                        // 密钥掩码后缀长度
    private static final int MASK_MIN_LENGTH = 8;                           // 需要掩码的最小长度
    private static final String MASK_PLACEHOLDER = "****";                  // 掩码占位符
    private static final String SECRET_MASK_INDICATOR = "*";                // 密钥掩码标识符
    
    private static final String PATH_ROOT = "/";                            // 根路径
    private static final String PATH_SEPARATOR = "/";                       // 路径分隔符
    private static final String PATH_PARENT = "..";                         // 父目录标识
    private static final String PATH_INDEX = "/index.html";                 // 默认首页
    private static final String RESOURCE_PREFIX = "web";                    // 静态资源前缀
    
    private static final String API_CHAT = "/api/chat";                     // 聊天 API 路径
    private static final String API_CHAT_STREAM = "/api/chat/stream";       // 流式聊天 API 路径
    private static final String API_CHANNELS = "/api/channels";             // 通道 API 路径
    private static final String API_SESSIONS = "/api/sessions";             // 会话 API 路径
    private static final String API_CRON = "/api/cron";                     // 定时任务 API 路径
    private static final String API_WORKSPACE = "/api/workspace";           // 工作空间 API 路径
    private static final String API_WORKSPACE_FILES = "/api/workspace/files"; // 工作空间文件 API 路径
    private static final String API_SKILLS = "/api/skills";                 // 技能 API 路径
    private static final String API_PROVIDERS = "/api/providers";           // 提供商 API 路径
    private static final String API_MODELS = "/api/models";                 // 模型 API 路径
    private static final String API_CONFIG = "/api/config";                 // 配置 API 路径
    private static final String API_CONFIG_MODEL = "/api/config/model";     // 模型配置 API 路径
    private static final String API_CONFIG_AGENT = "/api/config/agent";     // Agent 配置 API 路径
    
    private static final String HTTP_METHOD_GET = "GET";                    // HTTP GET 方法
    private static final String HTTP_METHOD_POST = "POST";                  // HTTP POST 方法
    private static final String HTTP_METHOD_PUT = "PUT";                    // HTTP PUT 方法
    private static final String HTTP_METHOD_DELETE = "DELETE";              // HTTP DELETE 方法
    
    private static final String SSE_PREFIX = "data: ";                      // SSE 数据前缀
    private static final String SSE_SUFFIX = "\n\n";                        // SSE 数据后缀
    private static final String SSE_DONE = "data: [DONE]\n\n";              // SSE 完成信号
    private static final String SSE_ERROR_PREFIX = "data: [ERROR] ";        // SSE 错误前缀
    private static final String SSE_NEWLINE_REPLACEMENT = "\ndata: ";       // SSE 换行替换
    
    private static final String HEADER_CONTENT_TYPE = "Content-Type";       // Content-Type 头
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";     // Cache-Control 头
    private static final String HEADER_CONNECTION = "Connection";           // Connection 头
    private static final String HEADER_CORS = "Access-Control-Allow-Origin"; // CORS 头
    private static final String HEADER_CORS_VALUE = "*";                    // CORS 允许所有源
    private static final String HEADER_NO_CACHE = "no-cache";               // 禁用缓存
    private static final String HEADER_KEEP_ALIVE = "keep-alive";           // 保持连接
    
    private static final String DEFAULT_SESSION_ID = "web:default";         // 默认会话 ID
    private static final String MEMORY_SUBDIR = "memory";                   // 内存子目录
    private static final String MEMORY_FILE = "MEMORY.md";                  // 内存文件名
    
    private static final String FILE_EXT_HTML = ".html";                    // HTML 文件扩展名
    private static final String FILE_EXT_CSS = ".css";                      // CSS 文件扩展名
    private static final String FILE_EXT_JS = ".js";                        // JavaScript 文件扩展名
    private static final String FILE_EXT_JSON = ".json";                    // JSON 文件扩展名
    private static final String FILE_EXT_PNG = ".png";                      // PNG 文件扩展名
    private static final String FILE_EXT_SVG = ".svg";                      // SVG 文件扩展名
    private static final String FILE_EXT_ICO = ".ico";                      // ICO 文件扩展名
    
    private static final String CHANNEL_TELEGRAM = "telegram";              // Telegram 通道
    private static final String CHANNEL_DISCORD = "discord";                // Discord 通道
    private static final String CHANNEL_WHATSAPP = "whatsapp";              // WhatsApp 通道
    private static final String CHANNEL_FEISHU = "feishu";                  // 飞书通道
    private static final String CHANNEL_DINGTALK = "dingtalk";              // 钉钉通道
    private static final String CHANNEL_QQ = "qq";                          // QQ 通道
    private static final String CHANNEL_MAIXCAM = "maixcam";                // MaixCAM 通道
    
    private static final String PROVIDER_OPENROUTER = "openrouter";         // OpenRouter 提供商
    private static final String PROVIDER_OPENAI = "openai";                 // OpenAI 提供商
    private static final String PROVIDER_ANTHROPIC = "anthropic";           // Anthropic 提供商
    private static final String PROVIDER_ZHIPU = "zhipu";                   // 智谱 AI 提供商
    private static final String PROVIDER_DASHSCOPE = "dashscope";           // 通义千问提供商
    private static final String PROVIDER_GEMINI = "gemini";                 // Gemini 提供商
    private static final String PROVIDER_OLLAMA = "ollama";                 // Ollama 提供商
    
    private static final String[] WORKSPACE_FILES = {                       // 工作空间文件列表
        "AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md", "PROFILE.md", "HEARTBEAT.md"
    };
    
    private final String host;                  // 服务器主机地址
    private final int port;                     // 服务器端口
    private final Config config;                // 配置对象
    private final AgentLoop agentLoop;          // Agent 循环
    private final SessionManager sessionManager; // 会话管理器
    private final CronService cronService;      // 定时任务服务
    private final SkillsLoader skillsLoader;    // 技能加载器
    private HttpServer httpServer;              // HTTP 服务器实例
    
    /**
     * 构造 Web 控制台服务器。
     * 
     * @param host 服务器主机地址
     * @param port 服务器端口
     * @param config 配置对象
     * @param agentLoop Agent 循环
     * @param sessionManager 会话管理器
     * @param cronService 定时任务服务
     * @param skillsLoader 技能加载器
     */
    public WebConsoleServer(String host, int port, Config config, AgentLoop agentLoop,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader) {
        this.host = host;
        this.port = port;
        this.config = config;
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
        this.cronService = cronService;
        this.skillsLoader = skillsLoader;
    }
    
    /**
     * 启动 Web 服务器。
     * 
     * 创建 HTTP 服务器实例，注册所有 API 端点，并启动服务。
     * 
     * @throws IOException 如果启动失败
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));
        
        registerApiEndpoints();
        registerStaticHandler();
        
        httpServer.start();
        logger.info("Web Console Server started", Map.of("host", host, "port", port));
    }
    
    /**
     * 注册所有 API 端点。
     */
    private void registerApiEndpoints() {
        httpServer.createContext(API_CHAT, this::handleChat);
        httpServer.createContext(API_CHANNELS, this::handleChannels);
        httpServer.createContext(API_SESSIONS, this::handleSessions);
        httpServer.createContext(API_CRON, this::handleCron);
        httpServer.createContext(API_WORKSPACE, this::handleWorkspace);
        httpServer.createContext(API_SKILLS, this::handleSkills);
        httpServer.createContext(API_PROVIDERS, this::handleProviders);
        httpServer.createContext(API_MODELS, this::handleModels);
        httpServer.createContext(API_CONFIG, this::handleConfig);
    }
    
    /**
     * 注册静态文件处理器。
     */
    private void registerStaticHandler() {
        httpServer.createContext(PATH_ROOT, this::handleStatic);
    }
    
    /**
     * 停止 Web 服务器。
     * 
     * 关闭 HTTP 服务器和线程池资源。
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(SERVER_STOP_DELAY);
            shutdownExecutor();
            logger.info("Web Console Server stopped");
        }
    }
    
    /**
     * 关闭线程池。
     */
    private void shutdownExecutor() {
        if (httpServer.getExecutor() != null) {
            ((java.util.concurrent.ExecutorService) httpServer.getExecutor()).shutdown();
        }
    }
    
    /**
     * 处理聊天 API 请求。
     * 
     * 支持：
     * - POST /api/chat - 非流式聊天
     * - POST /api/chat/stream - 流式聊天（SSE）
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleChat(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if (API_CHAT.equals(path) && HTTP_METHOD_POST.equals(method)) {
                handleChatNormal(exchange);
            } else if (API_CHAT_STREAM.equals(path) && HTTP_METHOD_POST.equals(method)) {
                handleChatStream(exchange);
            } else {
                sendNotFound(exchange);
            }
        } catch (Exception e) {
            logger.error("Chat API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    /**
     * 处理非流式聊天请求。
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleChatNormal(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        JsonNode json = objectMapper.readTree(body);
        String message = json.path("message").asText();
        String sessionId = json.path("sessionId").asText(DEFAULT_SESSION_ID);
        
        try {
            String response = agentLoop.processDirect(message, sessionId);
            
            ObjectNode result = objectMapper.createObjectNode();
            result.put("response", response);
            result.put("sessionId", sessionId);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            logger.error("Agent processing error", Map.of("error", e.getMessage()));
            ObjectNode errorResult = objectMapper.createObjectNode();
            errorResult.put("error", e.getMessage());
            sendJson(exchange, 500, errorResult);
        }
    }
    
    /**
     * 处理流式聊天请求，使用 Server-Sent Events (SSE) 返回响应。
     * 
     * SSE 格式：data: <content>\n\n
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleChatStream(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        JsonNode json = objectMapper.readTree(body);
        String message = json.path("message").asText();
        String sessionId = json.path("sessionId").asText(DEFAULT_SESSION_ID);
        
        setupSSEHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);
        
        OutputStream os = exchange.getResponseBody();
        
        try {
            streamAgentResponse(message, sessionId, os);
            writeSSEDone(os);
        } catch (Exception e) {
            logger.error("Chat stream error", Map.of("error", e.getMessage()));
            writeSSEError(os, e.getMessage());
        } finally {
            os.close();
        }
    }
    
    /**
     * 设置 SSE 响应头。
     * 
     * @param exchange HTTP 交换对象
     */
    private void setupSSEHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, CONTENT_TYPE_SSE);
        exchange.getResponseHeaders().set(HEADER_CACHE_CONTROL, HEADER_NO_CACHE);
        exchange.getResponseHeaders().set(HEADER_CONNECTION, HEADER_KEEP_ALIVE);
        exchange.getResponseHeaders().set(HEADER_CORS, HEADER_CORS_VALUE);
    }
    
    /**
     * 流式传输 Agent 响应。
     * 
     * @param message 用户消息
     * @param sessionId 会话 ID
     * @param os 输出流
     */
    private void streamAgentResponse(String message, String sessionId, OutputStream os) {
        try {
            agentLoop.processDirectStream(message, sessionId, chunk -> {
                try {
                    writeSSEData(os, chunk);
                } catch (IOException e) {
                    logger.error("SSE write error", Map.of("error", e.getMessage()));
                }
            });
        } catch (Exception e) {
            logger.error("Agent stream processing error", Map.of("error", e.getMessage()));
            try {
                writeSSEData(os, "错误: " + e.getMessage());
            } catch (IOException ioException) {
                logger.error("Failed to write error to SSE stream", 
                    Map.of("error", ioException.getMessage()));
            }
        }
    }
    
    /**
     * 写入 SSE 数据。
     * 
     * @param os 输出流
     * @param content 内容
     * @throws IOException 如果写入失败
     */
    private void writeSSEData(OutputStream os, String content) throws IOException {
        String sseData = SSE_PREFIX + escapeSSE(content) + SSE_SUFFIX;
        os.write(sseData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
    
    /**
     * 写入 SSE 完成信号。
     * 
     * @param os 输出流
     * @throws IOException 如果写入失败
     */
    private void writeSSEDone(OutputStream os) throws IOException {
        os.write(SSE_DONE.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
    
    /**
     * 写入 SSE 错误信息。
     * 
     * @param os 输出流
     * @param errorMessage 错误消息
     * @throws IOException 如果写入失败
     */
    private void writeSSEError(OutputStream os, String errorMessage) throws IOException {
        String errorData = SSE_ERROR_PREFIX + escapeSSE(errorMessage) + SSE_SUFFIX;
        os.write(errorData.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
    
    /**
     * 转义 SSE 数据中的换行符。
     * 
     * SSE 中换行需要多个 data: 行。
     * 
     * @param content 原始内容
     * @return 转义后的内容
     */
    private String escapeSSE(String content) {
        if (content == null) return "";
        return content.replace("\n", SSE_NEWLINE_REPLACEMENT);
    }
    
    /**
     * 处理通道 API 请求。
     * 
     * 支持：
     * - GET /api/channels - 获取所有通道状态
     * - GET /api/channels/{name} - 获取指定通道详情
     * - PUT /api/channels/{name} - 更新通道配置
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleChannels(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if (API_CHANNELS.equals(path) && HTTP_METHOD_GET.equals(method)) {
                handleGetChannels(exchange);
            } else if (path.startsWith(API_CHANNELS + PATH_SEPARATOR) && HTTP_METHOD_GET.equals(method)) {
                handleGetChannelDetail(exchange, path);
            } else if (path.startsWith(API_CHANNELS + PATH_SEPARATOR) && HTTP_METHOD_PUT.equals(method)) {
                handleUpdateChannel(exchange, path);
            } else {
                sendNotFound(exchange);
            }
        } catch (Exception e) {
            logger.error("Channels API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    /**
     * 处理获取所有通道状态请求。
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleGetChannels(HttpExchange exchange) throws IOException {
        ArrayNode channels = objectMapper.createArrayNode();
        ChannelsConfig channelsConfig = config.getChannels();
        
        addChannelInfo(channels, CHANNEL_TELEGRAM, channelsConfig.getTelegram().isEnabled());
        addChannelInfo(channels, CHANNEL_DISCORD, channelsConfig.getDiscord().isEnabled());
        addChannelInfo(channels, CHANNEL_WHATSAPP, channelsConfig.getWhatsapp().isEnabled());
        addChannelInfo(channels, CHANNEL_FEISHU, channelsConfig.getFeishu().isEnabled());
        addChannelInfo(channels, CHANNEL_DINGTALK, channelsConfig.getDingtalk().isEnabled());
        addChannelInfo(channels, CHANNEL_QQ, channelsConfig.getQq().isEnabled());
        addChannelInfo(channels, CHANNEL_MAIXCAM, channelsConfig.getMaixcam().isEnabled());
        
        sendJson(exchange, 200, channels);
    }
    
    /**
     * 处理获取通道详情请求。
     * 
     * @param exchange HTTP 交换对象
     * @param path 请求路径
     * @throws IOException 如果处理失败
     */
    private void handleGetChannelDetail(HttpExchange exchange, String path) throws IOException {
        String channelName = path.substring(API_CHANNELS.length() + 1);
        ObjectNode detail = getChannelDetail(channelName);
        if (detail != null) {
            sendJson(exchange, 200, detail);
        } else {
            sendJson(exchange, 404, errorJson("Channel not found"));
        }
    }
    
    /**
     * 处理更新通道配置请求。
     * 
     * @param exchange HTTP 交换对象
     * @param path 请求路径
     * @throws IOException 如果处理失败
     */
    private void handleUpdateChannel(HttpExchange exchange, String path) throws IOException {
        String channelName = path.substring(API_CHANNELS.length() + 1);
        String body = readRequestBody(exchange);
        JsonNode json = objectMapper.readTree(body);
        boolean success = updateChannelConfig(channelName, json);
        if (success) {
            saveConfig();
            sendJson(exchange, 200, successJson("Channel updated"));
        } else {
            sendJson(exchange, 400, errorJson("Update failed"));
        }
    }
    
    /**
     * 添加通道信息到结果数组。
     * 
     * @param channels 结果数组
     * @param name 通道名称
     * @param enabled 是否启用
     */
    private void addChannelInfo(ArrayNode channels, String name, boolean enabled) {
        ObjectNode channel = objectMapper.createObjectNode();
        channel.put("name", name);
        channel.put("enabled", enabled);
        channels.add(channel);
    }
    
    /**
     * 获取指定通道的详细信息。
     * 
     * @param name 通道名称
     * @return 通道详情 JSON 对象，通道不存在返回 null
     */
    private ObjectNode getChannelDetail(String name) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("name", name);
        
        ChannelsConfig channelsConfig = config.getChannels();
        return switch (name) {
            case CHANNEL_TELEGRAM -> {
                detail.put("enabled", channelsConfig.getTelegram().isEnabled());
                detail.put("token", maskSecret(channelsConfig.getTelegram().getToken()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getTelegram().getAllowFrom()));
                yield detail;
            }
            case CHANNEL_DISCORD -> {
                detail.put("enabled", channelsConfig.getDiscord().isEnabled());
                detail.put("token", maskSecret(channelsConfig.getDiscord().getToken()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getDiscord().getAllowFrom()));
                yield detail;
            }
            case CHANNEL_FEISHU -> {
                detail.put("enabled", channelsConfig.getFeishu().isEnabled());
                detail.put("appId", channelsConfig.getFeishu().getAppId());
                detail.put("appSecret", maskSecret(channelsConfig.getFeishu().getAppSecret()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getFeishu().getAllowFrom()));
                yield detail;
            }
            case CHANNEL_DINGTALK -> {
                detail.put("enabled", channelsConfig.getDingtalk().isEnabled());
                detail.put("clientId", channelsConfig.getDingtalk().getClientId());
                detail.put("clientSecret", maskSecret(channelsConfig.getDingtalk().getClientSecret()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getDingtalk().getAllowFrom()));
                yield detail;
            }
            case CHANNEL_QQ -> {
                detail.put("enabled", channelsConfig.getQq().isEnabled());
                detail.put("appId", channelsConfig.getQq().getAppId());
                detail.put("appSecret", maskSecret(channelsConfig.getQq().getAppSecret()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getQq().getAllowFrom()));
                yield detail;
            }
            case CHANNEL_WHATSAPP -> {
                detail.put("enabled", channelsConfig.getWhatsapp().isEnabled());
                detail.put("bridgeUrl", channelsConfig.getWhatsapp().getBridgeUrl());
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getWhatsapp().getAllowFrom()));
                yield detail;
            }
            case CHANNEL_MAIXCAM -> {
                detail.put("enabled", channelsConfig.getMaixcam().isEnabled());
                detail.put("host", channelsConfig.getMaixcam().getHost());
                detail.put("port", channelsConfig.getMaixcam().getPort());
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getMaixcam().getAllowFrom()));
                yield detail;
            }
            default -> null;
        };
    }
    
    /**
     * 更新通道配置。
     * 
     * @param name 通道名称
     * @param json 配置 JSON 数据
     * @return 更新成功返回 true，否则返回 false
     */
    private boolean updateChannelConfig(String name, JsonNode json) {
        ChannelsConfig channelsConfig = config.getChannels();
        return switch (name) {
            case CHANNEL_TELEGRAM -> {
                updateTelegramConfig(channelsConfig, json);
                yield true;
            }
            case CHANNEL_DISCORD -> {
                updateDiscordConfig(channelsConfig, json);
                yield true;
            }
            case CHANNEL_FEISHU -> {
                updateFeishuConfig(channelsConfig, json);
                yield true;
            }
            case CHANNEL_DINGTALK -> {
                updateDingtalkConfig(channelsConfig, json);
                yield true;
            }
            case CHANNEL_QQ -> {
                updateQQConfig(channelsConfig, json);
                yield true;
            }
            default -> false;
        };
    }
    
    /**
     * 更新 Telegram 通道配置。
     * 
     * @param channelsConfig 通道配置
     * @param json JSON 数据
     */
    private void updateTelegramConfig(ChannelsConfig channelsConfig, JsonNode json) {
        if (json.has("enabled")) {
            channelsConfig.getTelegram().setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("token") && !isSecretMasked(json.get("token").asText())) {
            channelsConfig.getTelegram().setToken(json.get("token").asText());
        }
    }
    
    /**
     * 更新 Discord 通道配置。
     * 
     * @param channelsConfig 通道配置
     * @param json JSON 数据
     */
    private void updateDiscordConfig(ChannelsConfig channelsConfig, JsonNode json) {
        if (json.has("enabled")) {
            channelsConfig.getDiscord().setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("token") && !isSecretMasked(json.get("token").asText())) {
            channelsConfig.getDiscord().setToken(json.get("token").asText());
        }
    }
    
    /**
     * 更新飞书通道配置。
     * 
     * @param channelsConfig 通道配置
     * @param json JSON 数据
     */
    private void updateFeishuConfig(ChannelsConfig channelsConfig, JsonNode json) {
        if (json.has("enabled")) {
            channelsConfig.getFeishu().setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("appId")) {
            channelsConfig.getFeishu().setAppId(json.get("appId").asText());
        }
        if (json.has("appSecret") && !isSecretMasked(json.get("appSecret").asText())) {
            channelsConfig.getFeishu().setAppSecret(json.get("appSecret").asText());
        }
    }
    
    /**
     * 更新钉钉通道配置。
     * 
     * @param channelsConfig 通道配置
     * @param json JSON 数据
     */
    private void updateDingtalkConfig(ChannelsConfig channelsConfig, JsonNode json) {
        if (json.has("enabled")) {
            channelsConfig.getDingtalk().setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("clientId")) {
            channelsConfig.getDingtalk().setClientId(json.get("clientId").asText());
        }
        if (json.has("clientSecret") && !isSecretMasked(json.get("clientSecret").asText())) {
            channelsConfig.getDingtalk().setClientSecret(json.get("clientSecret").asText());
        }
    }
    
    /**
     * 更新 QQ 通道配置。
     * 
     * @param channelsConfig 通道配置
     * @param json JSON 数据
     */
    private void updateQQConfig(ChannelsConfig channelsConfig, JsonNode json) {
        if (json.has("enabled")) {
            channelsConfig.getQq().setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("appId")) {
            channelsConfig.getQq().setAppId(json.get("appId").asText());
        }
        if (json.has("appSecret") && !isSecretMasked(json.get("appSecret").asText())) {
            channelsConfig.getQq().setAppSecret(json.get("appSecret").asText());
        }
    }
    
    /**
     * 检查密钥是否被掩码。
     * 
     * @param secret 密钥字符串
     * @return 被掩码返回 true，否则返回 false
     */
    private boolean isSecretMasked(String secret) {
        return secret.contains(SECRET_MASK_INDICATOR);
    }
    
    // ==================== Sessions API ====================
    
    private void handleSessions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/sessions".equals(path) && "GET".equals(method)) {
                ArrayNode sessions = objectMapper.createArrayNode();
                for (String key : sessionManager.getSessionKeys()) {
                    ObjectNode session = objectMapper.createObjectNode();
                    session.put("key", key);
                    session.put("messageCount", sessionManager.getHistory(key).size());
                    sessions.add(session);
                }
                sendJson(exchange, 200, sessions);
            } else if (path.startsWith("/api/sessions/") && "GET".equals(method)) {
                String key = URLDecoder.decode(path.substring("/api/sessions/".length()), StandardCharsets.UTF_8);
                var history = sessionManager.getHistory(key);
                ArrayNode messages = objectMapper.createArrayNode();
                for (var msg : history) {
                    ObjectNode m = objectMapper.createObjectNode();
                    m.put("role", msg.getRole());
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    messages.add(m);
                }
                sendJson(exchange, 200, messages);
            } else if (path.startsWith("/api/sessions/") && "DELETE".equals(method)) {
                String key = URLDecoder.decode(path.substring("/api/sessions/".length()), StandardCharsets.UTF_8);
                sessionManager.deleteSession(key);
                sendJson(exchange, 200, successJson("Session deleted"));
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Sessions API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    // ==================== Cron API ====================
    
    private void handleCron(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/cron".equals(path) && "GET".equals(method)) {
                List<CronJob> jobs = cronService.listJobs(true);
                ArrayNode result = objectMapper.createArrayNode();
                for (CronJob job : jobs) {
                    ObjectNode jobNode = objectMapper.createObjectNode();
                    jobNode.put("id", job.getId());
                    jobNode.put("name", job.getName());
                    jobNode.put("enabled", job.isEnabled());
                    jobNode.put("message", job.getPayload().getMessage());
                    if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.CRON) {
                        jobNode.put("schedule", job.getSchedule().getExpr());
                    } else if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.EVERY) {
                        jobNode.put("schedule", "every " + (job.getSchedule().getEveryMs() / 1000) + "s");
                    }
                    if (job.getState().getNextRunAtMs() != null) {
                        jobNode.put("nextRun", job.getState().getNextRunAtMs());
                    }
                    result.add(jobNode);
                }
                sendJson(exchange, 200, result);
            } else if ("/api/cron".equals(path) && "POST".equals(method)) {
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                String name = json.path("name").asText();
                String message = json.path("message").asText();
                CronSchedule schedule;
                if (json.has("cron")) {
                    schedule = CronSchedule.cron(json.get("cron").asText());
                } else if (json.has("everySeconds")) {
                    schedule = CronSchedule.every(json.get("everySeconds").asLong() * 1000);
                } else {
                    sendJson(exchange, 400, errorJson("Missing schedule"));
                    return;
                }
                CronJob job = cronService.addJob(name, schedule, message, false, null, null);
                sendJson(exchange, 200, objectMapper.valueToTree(Map.of("id", job.getId())));
            } else if (path.matches("/api/cron/[^/]+") && "DELETE".equals(method)) {
                String id = path.substring("/api/cron/".length());
                boolean removed = cronService.removeJob(id);
                if (removed) {
                    sendJson(exchange, 200, successJson("Job removed"));
                } else {
                    sendJson(exchange, 404, errorJson("Job not found"));
                }
            } else if (path.matches("/api/cron/[^/]+/enable") && "PUT".equals(method)) {
                String id = path.substring("/api/cron/".length()).replace("/enable", "");
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                boolean enabled = json.path("enabled").asBoolean(true);
                CronJob job = cronService.enableJob(id, enabled);
                if (job != null) {
                    sendJson(exchange, 200, successJson("Job " + (enabled ? "enabled" : "disabled")));
                } else {
                    sendJson(exchange, 404, errorJson("Job not found"));
                }
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Cron API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    /**
     * 处理工作空间 API 请求。
     * 
     * 支持：
     * - GET /api/workspace/files - 获取工作空间文件列表
     * - GET /api/workspace/files/{filename} - 获取文件内容
     * - PUT /api/workspace/files/{filename} - 保存文件内容
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleWorkspace(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String workspace = config.getWorkspacePath();
        
        try {
            if (API_WORKSPACE_FILES.equals(path) && HTTP_METHOD_GET.equals(method)) {
                handleListWorkspaceFiles(exchange, workspace);
            } else if (path.startsWith(API_WORKSPACE_FILES + PATH_SEPARATOR) && HTTP_METHOD_GET.equals(method)) {
                handleGetWorkspaceFile(exchange, path, workspace);
            } else if (path.startsWith(API_WORKSPACE_FILES + PATH_SEPARATOR) && HTTP_METHOD_PUT.equals(method)) {
                handleSaveWorkspaceFile(exchange, path, workspace);
            } else {
                sendNotFound(exchange);
            }
        } catch (Exception e) {
            logger.error("Workspace API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    /**
     * 处理获取工作空间文件列表请求。
     * 
     * @param exchange HTTP 交换对象
     * @param workspace 工作空间路径
     * @throws IOException 如果处理失败
     */
    private void handleListWorkspaceFiles(HttpExchange exchange, String workspace) throws IOException {
        ArrayNode files = objectMapper.createArrayNode();
        
        for (String fileName : WORKSPACE_FILES) {
            Path filePath = Paths.get(workspace, fileName);
            if (Files.exists(filePath)) {
                files.add(createFileInfo(fileName, filePath));
            }
        }
        
        addMemoryFile(files, workspace);
        sendJson(exchange, 200, files);
    }
    
    /**
     * 创建文件信息 JSON 对象。
     * 
     * @param fileName 文件名
     * @param filePath 文件路径
     * @return 文件信息 JSON 对象
     */
    private ObjectNode createFileInfo(String fileName, Path filePath) {
        ObjectNode file = objectMapper.createObjectNode();
        file.put("name", fileName);
        file.put("exists", true);
        try {
            file.put("size", Files.size(filePath));
            file.put("lastModified", Files.getLastModifiedTime(filePath).toMillis());
        } catch (IOException e) {
            file.put("size", 0);
            file.put("lastModified", 0);
        }
        return file;
    }
    
    /**
     * 添加内存文件到文件列表。
     * 
     * @param files 文件列表
     * @param workspace 工作空间路径
     */
    private void addMemoryFile(ArrayNode files, String workspace) {
        Path memoryFile = Paths.get(workspace, MEMORY_SUBDIR, MEMORY_FILE);
        if (Files.exists(memoryFile)) {
            String memoryFileName = MEMORY_SUBDIR + PATH_SEPARATOR + MEMORY_FILE;
            files.add(createFileInfo(memoryFileName, memoryFile));
        }
    }
    
    /**
     * 处理获取工作空间文件内容请求。
     * 
     * @param exchange HTTP 交换对象
     * @param path 请求路径
     * @param workspace 工作空间路径
     * @throws IOException 如果处理失败
     */
    private void handleGetWorkspaceFile(HttpExchange exchange, String path, String workspace) throws IOException {
        String fileName = URLDecoder.decode(path.substring(API_WORKSPACE_FILES.length() + 1), StandardCharsets.UTF_8);
        Path filePath = Paths.get(workspace, fileName);
        if (Files.exists(filePath)) {
            String content = Files.readString(filePath);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("name", fileName);
            result.put("content", content);
            sendJson(exchange, 200, result);
        } else {
            sendJson(exchange, 404, errorJson("File not found"));
        }
    }
    
    /**
     * 处理保存工作空间文件内容请求。
     * 
     * @param exchange HTTP 交换对象
     * @param path 请求路径
     * @param workspace 工作空间路径
     * @throws IOException 如果处理失败
     */
    private void handleSaveWorkspaceFile(HttpExchange exchange, String path, String workspace) throws IOException {
        String fileName = URLDecoder.decode(path.substring(API_WORKSPACE_FILES.length() + 1), StandardCharsets.UTF_8);
        String body = readRequestBody(exchange);
        JsonNode json = objectMapper.readTree(body);
        String content = json.path("content").asText();
        Path filePath = Paths.get(workspace, fileName);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
        sendJson(exchange, 200, successJson("File saved"));
    }
    
    // ==================== Skills API ====================
    
    private void handleSkills(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/skills".equals(path) && "GET".equals(method)) {
                List<SkillInfo> skills = skillsLoader.listSkills();
                ArrayNode result = objectMapper.createArrayNode();
                for (SkillInfo skill : skills) {
                    ObjectNode skillNode = objectMapper.createObjectNode();
                    skillNode.put("name", skill.getName());
                    skillNode.put("description", skill.getDescription() != null ? skill.getDescription() : "");
                    skillNode.put("source", skill.getSource());
                    skillNode.put("path", skill.getPath());
                    result.add(skillNode);
                }
                sendJson(exchange, 200, result);
            } else if (path.startsWith("/api/skills/") && "GET".equals(method)) {
                String name = URLDecoder.decode(path.substring("/api/skills/".length()), StandardCharsets.UTF_8);
                String content = skillsLoader.loadSkill(name);
                if (content != null) {
                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("name", name);
                    result.put("content", content);
                    sendJson(exchange, 200, result);
                } else {
                    sendJson(exchange, 404, errorJson("Skill not found"));
                }
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Skills API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    // ==================== Providers API ====================
    
    private void handleProviders(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/providers".equals(path) && "GET".equals(method)) {
                ArrayNode providers = objectMapper.createArrayNode();
                ProvidersConfig pc = config.getProviders();
                
                addProviderInfo(providers, "openrouter", pc.getOpenrouter());
                addProviderInfo(providers, "openai", pc.getOpenai());
                addProviderInfo(providers, "anthropic", pc.getAnthropic());
                addProviderInfo(providers, "zhipu", pc.getZhipu());
                addProviderInfo(providers, "dashscope", pc.getDashscope());
                addProviderInfo(providers, "gemini", pc.getGemini());
                addProviderInfo(providers, "ollama", pc.getOllama());
                
                sendJson(exchange, 200, providers);
            } else if (path.startsWith("/api/providers/") && "PUT".equals(method)) {
                String name = path.substring("/api/providers/".length());
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                boolean success = updateProviderConfig(name, json);
                if (success) {
                    saveConfig();
                    sendJson(exchange, 200, successJson("Provider updated"));
                } else {
                    sendJson(exchange, 400, errorJson("Update failed"));
                }
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Providers API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    /**
     * 添加 Provider 信息到结果数组。
     * 
     * @param providers 结果数组
     * @param name Provider 名称
     * @param pc Provider 配置
     */
    private void addProviderInfo(ArrayNode providers, String name, ProvidersConfig.ProviderConfig pc) {
        ObjectNode provider = objectMapper.createObjectNode();
        provider.put("name", name);
        provider.put("apiBase", pc.getApiBase() != null ? pc.getApiBase() : ProvidersConfig.getDefaultApiBase(name));
        provider.put("apiKey", maskSecret(pc.getApiKey()));
        provider.put("authorized", pc.isValid());
        providers.add(provider);
    }
    
    /**
     * 更新 Provider 配置。
     * 
     * @param name Provider 名称
     * @param json 配置 JSON 数据
     * @return 更新成功返回 true，否则返回 false
     */
    private boolean updateProviderConfig(String name, JsonNode json) {
        ProvidersConfig.ProviderConfig provider = getProviderByName(name);
        if (provider == null) {
            return false;
        }
        
        if (json.has("apiKey") && !isSecretMasked(json.get("apiKey").asText())) {
            provider.setApiKey(json.get("apiKey").asText());
        }
        if (json.has("apiBase")) {
            provider.setApiBase(json.get("apiBase").asText());
        }
        return true;
    }
    
    /**
     * 根据名称获取 Provider 配置。
     * 
     * @param name Provider 名称
     * @return Provider 配置对象，不存在返回 null
     */
    private ProvidersConfig.ProviderConfig getProviderByName(String name) {
        ProvidersConfig pc = config.getProviders();
        return switch (name) {
            case PROVIDER_OPENROUTER -> pc.getOpenrouter();
            case PROVIDER_OPENAI -> pc.getOpenai();
            case PROVIDER_ANTHROPIC -> pc.getAnthropic();
            case PROVIDER_ZHIPU -> pc.getZhipu();
            case PROVIDER_DASHSCOPE -> pc.getDashscope();
            case PROVIDER_GEMINI -> pc.getGemini();
            case PROVIDER_OLLAMA -> pc.getOllama();
            default -> null;
        };
    }
    
    // ==================== Models API ====================
    
    private void handleModels(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/models".equals(path) && "GET".equals(method)) {
                // 获取所有模型定义，并标记对应 provider 是否已授权
                ArrayNode models = objectMapper.createArrayNode();
                ModelsConfig modelsConfig = config.getModels();
                ProvidersConfig providersConfig = config.getProviders();
                
                for (Map.Entry<String, ModelsConfig.ModelDefinition> entry : modelsConfig.getDefinitions().entrySet()) {
                    String modelName = entry.getKey();
                    ModelsConfig.ModelDefinition def = entry.getValue();
                    String providerName = def.getProvider();
                    
                    // 检查 provider 是否已授权
                    ProvidersConfig.ProviderConfig providerConfig = getProviderByName(providerName);
                    boolean authorized = providerConfig != null && providerConfig.isValid();
                    
                    ObjectNode modelNode = objectMapper.createObjectNode();
                    modelNode.put("name", modelName);
                    modelNode.put("provider", providerName);
                    modelNode.put("model", def.getModel());
                    modelNode.put("maxContextSize", def.getMaxContextSize() != null ? def.getMaxContextSize() : 0);
                    modelNode.put("description", def.getDescription() != null ? def.getDescription() : "");
                    modelNode.put("authorized", authorized);
                    models.add(modelNode);
                }
                sendJson(exchange, 200, models);
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Models API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    // ==================== Config API ====================
    
    private void handleConfig(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/config/model".equals(path) && "GET".equals(method)) {
                ObjectNode result = objectMapper.createObjectNode();
                result.put("model", config.getAgent().getModel());
                // 获取当前配置的 provider
                String currentProvider = getCurrentProvider();
                result.put("provider", currentProvider);
                sendJson(exchange, 200, result);
            } else if ("/api/config/model".equals(path) && "PUT".equals(method)) {
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                if (json.has("model")) {
                    String model = json.path("model").asText();
                    config.getAgent().setModel(model);
                }
                if (json.has("provider")) {
                    String provider = json.path("provider").asText();
                    // 将 provider 信息存储到配置中（可选：我们可以通过模型名称推断，或存储明确的 provider）
                    // 这里我们暂时不存储 provider，而是通过 API key 来推断
                }
                saveConfig();
                sendJson(exchange, 200, successJson("Model updated"));
            } else if ("/api/config/agent".equals(path) && "GET".equals(method)) {
                AgentConfig agentConfig = config.getAgent();
                ObjectNode result = objectMapper.createObjectNode();
                result.put("workspace", agentConfig.getWorkspace());
                result.put("model", agentConfig.getModel());
                result.put("maxTokens", agentConfig.getMaxTokens());
                result.put("temperature", agentConfig.getTemperature());
                result.put("maxToolIterations", agentConfig.getMaxToolIterations());
                result.put("heartbeatEnabled", agentConfig.isHeartbeatEnabled());
                result.put("restrictToWorkspace", agentConfig.isRestrictToWorkspace());
                sendJson(exchange, 200, result);
            } else if ("/api/config/agent".equals(path) && "PUT".equals(method)) {
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                AgentConfig agentConfig = config.getAgent();
                if (json.has("model")) agentConfig.setModel(json.get("model").asText());
                if (json.has("maxTokens")) agentConfig.setMaxTokens(json.get("maxTokens").asInt());
                if (json.has("temperature")) agentConfig.setTemperature(json.get("temperature").asDouble());
                if (json.has("maxToolIterations")) agentConfig.setMaxToolIterations(json.get("maxToolIterations").asInt());
                if (json.has("heartbeatEnabled")) agentConfig.setHeartbeatEnabled(json.get("heartbeatEnabled").asBoolean());
                if (json.has("restrictToWorkspace")) agentConfig.setRestrictToWorkspace(json.get("restrictToWorkspace").asBoolean());
                saveConfig();
                sendJson(exchange, 200, successJson("Agent config updated"));
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Config API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    /**
     * 处理静态文件请求。
     * 
     * 从 classpath 的 web 目录加载静态资源（HTML、CSS、JS、图片等）。
     * 包含安全防护：防止目录遍历攻击。
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果处理失败
     */
    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        path = normalizeStaticPath(path);
        
        if (isPathTraversalAttempt(path)) {
            sendError(exchange, 403, "Forbidden");
            return;
        }
        
        serveStaticResource(exchange, path);
    }
    
    /**
     * 规范化静态文件路径。
     * 
     * 将根路径或空路径转换为 index.html。
     * 
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private String normalizeStaticPath(String path) {
        if (PATH_ROOT.equals(path) || path.isEmpty()) {
            return PATH_INDEX;
        }
        return path;
    }
    
    /**
     * 检查是否为路径遍历攻击尝试。
     * 
     * @param path 文件路径
     * @return 是路径遍历返回 true，否则返回 false
     */
    private boolean isPathTraversalAttempt(String path) {
        return path.contains(PATH_PARENT);
    }
    
    /**
     * 提供静态资源文件。
     * 
     * @param exchange HTTP 交换对象
     * @param path 文件路径
     * @throws IOException 如果处理失败
     */
    private void serveStaticResource(HttpExchange exchange, String path) throws IOException {
        String resourcePath = RESOURCE_PREFIX + path;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                sendError(exchange, 404, "Not Found");
                return;
            }
            
            sendStaticFile(exchange, is, path);
        }
    }
    
    /**
     * 发送静态文件内容。
     * 
     * @param exchange HTTP 交换对象
     * @param is 输入流
     * @param path 文件路径
     * @throws IOException 如果发送失败
     */
    private void sendStaticFile(HttpExchange exchange, InputStream is, String path) throws IOException {
        byte[] content = is.readAllBytes();
        String contentType = getContentType(path);
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }
    
    /**
     * 根据文件扩展名获取内容类型。
     * 
     * @param path 文件路径
     * @return 内容类型字符串
     */
    private String getContentType(String path) {
        if (path.endsWith(FILE_EXT_HTML)) return CONTENT_TYPE_HTML;
        if (path.endsWith(FILE_EXT_CSS)) return CONTENT_TYPE_CSS;
        if (path.endsWith(FILE_EXT_JS)) return CONTENT_TYPE_JS;
        if (path.endsWith(FILE_EXT_JSON)) return CONTENT_TYPE_JSON;
        if (path.endsWith(FILE_EXT_PNG)) return CONTENT_TYPE_PNG;
        if (path.endsWith(FILE_EXT_SVG)) return CONTENT_TYPE_SVG;
        if (path.endsWith(FILE_EXT_ICO)) return CONTENT_TYPE_ICO;
        return CONTENT_TYPE_OCTET;
    }
    
    /**
     * 读取请求体内容。
     * 
     * @param exchange HTTP 交换对象
     * @return 请求体字符串
     * @throws IOException 如果读取失败
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    /**
     * 发送 JSON 响应。
     * 
     * @param exchange HTTP 交换对象
     * @param statusCode HTTP 状态码
     * @param data 数据对象
     * @throws IOException 如果发送失败
     */
    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        exchange.getResponseHeaders().set(HEADER_CORS, HEADER_CORS_VALUE);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * 发送错误响应（纯文本）。
     * 
     * @param exchange HTTP 交换对象
     * @param statusCode HTTP 状态码
     * @param message 错误消息
     * @throws IOException 如果发送失败
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, CONTENT_TYPE_PLAIN);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * 发送 404 Not Found 响应。
     * 
     * @param exchange HTTP 交换对象
     * @throws IOException 如果发送失败
     */
    private void sendNotFound(HttpExchange exchange) throws IOException {
        sendJson(exchange, 404, errorJson("Not found"));
    }
    
    /**
     * 创建错误 JSON 对象。
     * 
     * @param message 错误消息
     * @return JSON 对象
     */
    private ObjectNode errorJson(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return node;
    }
    
    /**
     * 创建成功 JSON 对象。
     * 
     * @param message 成功消息
     * @return JSON 对象
     */
    private ObjectNode successJson(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("success", true);
        node.put("message", message);
        return node;
    }
    
    /**
     * 掩码显示敏感信息（API Key、Token 等）。
     * 
     * 规则：
     * - 空字符串返回空
     * - 长度 <= 8 返回 "****"
     * - 长度 > 8 显示前 4 位和后 4 位，中间用 "****" 替换
     * 
     * @param secret 敏感信息
     * @return 掩码后的字符串
     */
    private String maskSecret(String secret) {
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
     * 获取当前配置的第一个有效 Provider 名称。
     * 
     * 按优先级顺序检查：OpenRouter > DashScope > Zhipu > OpenAI > Anthropic > Gemini > Ollama
     * 
     * @return Provider 名称，无有效 Provider 返回空字符串
     */
    private String getCurrentProvider() {
        ProvidersConfig pc = config.getProviders();
        if (isValidProvider(pc.getOpenrouter())) return PROVIDER_OPENROUTER;
        if (isValidProvider(pc.getDashscope())) return PROVIDER_DASHSCOPE;
        if (isValidProvider(pc.getZhipu())) return PROVIDER_ZHIPU;
        if (isValidProvider(pc.getOpenai())) return PROVIDER_OPENAI;
        if (isValidProvider(pc.getAnthropic())) return PROVIDER_ANTHROPIC;
        if (isValidProvider(pc.getGemini())) return PROVIDER_GEMINI;
        if (isValidProvider(pc.getOllama())) return PROVIDER_OLLAMA;
        return "";
    }
    
    /**
     * 检查 Provider 是否有效。
     * 
     * @param provider Provider 配置
     * @return 有效返回 true，否则返回 false
     */
    private boolean isValidProvider(ProvidersConfig.ProviderConfig provider) {
        return provider != null && provider.isValid();
    }
    
    /**
     * 保存配置到文件。
     */
    private void saveConfig() {
        try {
            ConfigLoader.save(ConfigLoader.getConfigPath(), config);
        } catch (IOException e) {
            logger.error("Failed to save config", Map.of("error", e.getMessage()));
        }
    }
}
