package io.leavesfly.tinyclaw.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.config.*;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.Session;
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
 * Web Console Server - 提供 Web 管理界面
 * 
 * 功能模块：
 * - Chat: 与 Agent 对话
 * - Control: Channels/Sessions/Cron Jobs 管理
 * - Agent: Workspace/Skills 管理
 * - Settings: Models/Environments 配置
 */
public class WebConsoleServer {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final String CONTENT_TYPE_HTML = "text/html; charset=utf-8";
    private static final int THREAD_POOL_SIZE = 8;
    
    private final String host;
    private final int port;
    private final Config config;
    private final AgentLoop agentLoop;
    private final ChannelManager channelManager;
    private final SessionManager sessionManager;
    private final CronService cronService;
    private final SkillsLoader skillsLoader;
    private HttpServer httpServer;
    
    public WebConsoleServer(String host, int port, Config config, AgentLoop agentLoop,
                            ChannelManager channelManager, SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader) {
        this.host = host;
        this.port = port;
        this.config = config;
        this.agentLoop = agentLoop;
        this.channelManager = channelManager;
        this.sessionManager = sessionManager;
        this.cronService = cronService;
        this.skillsLoader = skillsLoader;
    }
    
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));
        
        // API 端点
        httpServer.createContext("/api/chat", this::handleChat);
        httpServer.createContext("/api/channels", this::handleChannels);
        httpServer.createContext("/api/sessions", this::handleSessions);
        httpServer.createContext("/api/cron", this::handleCron);
        httpServer.createContext("/api/workspace", this::handleWorkspace);
        httpServer.createContext("/api/skills", this::handleSkills);
        httpServer.createContext("/api/providers", this::handleProviders);
        httpServer.createContext("/api/models", this::handleModels);
        httpServer.createContext("/api/config", this::handleConfig);
        
        // 静态文件服务
        httpServer.createContext("/", this::handleStatic);
        
        httpServer.start();
        logger.info("Web Console Server started", Map.of("host", host, "port", port));
    }
    
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(2);
            // 关闭线程池
            if (httpServer.getExecutor() != null) {
                ((java.util.concurrent.ExecutorService) httpServer.getExecutor()).shutdown();
            }
            logger.info("Web Console Server stopped");
        }
    }
    
    // ==================== Chat API ====================
    
    private void handleChat(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/chat".equals(path) && "POST".equals(method)) {
                // 发送消息给 Agent
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                String message = json.path("message").asText();
                String sessionId = json.path("sessionId").asText("web:default");
                
                String response = agentLoop.processDirect(message, sessionId);
                
                ObjectNode result = objectMapper.createObjectNode();
                result.put("response", response);
                result.put("sessionId", sessionId);
                sendJson(exchange, 200, result);
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Chat API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    // ==================== Channels API ====================
    
    private void handleChannels(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/channels".equals(path) && "GET".equals(method)) {
                // 获取所有通道状态
                ArrayNode channels = objectMapper.createArrayNode();
                ChannelsConfig channelsConfig = config.getChannels();
                
                addChannelInfo(channels, "telegram", channelsConfig.getTelegram().isEnabled());
                addChannelInfo(channels, "discord", channelsConfig.getDiscord().isEnabled());
                addChannelInfo(channels, "whatsapp", channelsConfig.getWhatsapp().isEnabled());
                addChannelInfo(channels, "feishu", channelsConfig.getFeishu().isEnabled());
                addChannelInfo(channels, "dingtalk", channelsConfig.getDingtalk().isEnabled());
                addChannelInfo(channels, "qq", channelsConfig.getQq().isEnabled());
                addChannelInfo(channels, "maixcam", channelsConfig.getMaixcam().isEnabled());
                
                sendJson(exchange, 200, channels);
            } else if (path.startsWith("/api/channels/") && "GET".equals(method)) {
                String channelName = path.substring("/api/channels/".length());
                ObjectNode detail = getChannelDetail(channelName);
                if (detail != null) {
                    sendJson(exchange, 200, detail);
                } else {
                    sendJson(exchange, 404, errorJson("Channel not found"));
                }
            } else if (path.startsWith("/api/channels/") && "PUT".equals(method)) {
                String channelName = path.substring("/api/channels/".length());
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                boolean success = updateChannelConfig(channelName, json);
                if (success) {
                    saveConfig();
                    sendJson(exchange, 200, successJson("Channel updated"));
                } else {
                    sendJson(exchange, 400, errorJson("Update failed"));
                }
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Channels API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }
    
    private void addChannelInfo(ArrayNode channels, String name, boolean enabled) {
        ObjectNode channel = objectMapper.createObjectNode();
        channel.put("name", name);
        channel.put("enabled", enabled);
        channels.add(channel);
    }
    
    private ObjectNode getChannelDetail(String name) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("name", name);
        
        ChannelsConfig channelsConfig = config.getChannels();
        switch (name) {
            case "telegram":
                detail.put("enabled", channelsConfig.getTelegram().isEnabled());
                detail.put("token", maskSecret(channelsConfig.getTelegram().getToken()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getTelegram().getAllowFrom()));
                break;
            case "discord":
                detail.put("enabled", channelsConfig.getDiscord().isEnabled());
                detail.put("token", maskSecret(channelsConfig.getDiscord().getToken()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getDiscord().getAllowFrom()));
                break;
            case "feishu":
                detail.put("enabled", channelsConfig.getFeishu().isEnabled());
                detail.put("appId", channelsConfig.getFeishu().getAppId());
                detail.put("appSecret", maskSecret(channelsConfig.getFeishu().getAppSecret()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getFeishu().getAllowFrom()));
                break;
            case "dingtalk":
                detail.put("enabled", channelsConfig.getDingtalk().isEnabled());
                detail.put("clientId", channelsConfig.getDingtalk().getClientId());
                detail.put("clientSecret", maskSecret(channelsConfig.getDingtalk().getClientSecret()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getDingtalk().getAllowFrom()));
                break;
            case "qq":
                detail.put("enabled", channelsConfig.getQq().isEnabled());
                detail.put("appId", channelsConfig.getQq().getAppId());
                detail.put("appSecret", maskSecret(channelsConfig.getQq().getAppSecret()));
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getQq().getAllowFrom()));
                break;
            case "whatsapp":
                detail.put("enabled", channelsConfig.getWhatsapp().isEnabled());
                detail.put("bridgeUrl", channelsConfig.getWhatsapp().getBridgeUrl());
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getWhatsapp().getAllowFrom()));
                break;
            case "maixcam":
                detail.put("enabled", channelsConfig.getMaixcam().isEnabled());
                detail.put("host", channelsConfig.getMaixcam().getHost());
                detail.put("port", channelsConfig.getMaixcam().getPort());
                detail.set("allowFrom", objectMapper.valueToTree(channelsConfig.getMaixcam().getAllowFrom()));
                break;
            default:
                return null;
        }
        return detail;
    }
    
    private boolean updateChannelConfig(String name, JsonNode json) {
        ChannelsConfig channelsConfig = config.getChannels();
        switch (name) {
            case "telegram":
                if (json.has("enabled")) channelsConfig.getTelegram().setEnabled(json.get("enabled").asBoolean());
                if (json.has("token") && !json.get("token").asText().contains("*")) 
                    channelsConfig.getTelegram().setToken(json.get("token").asText());
                return true;
            case "discord":
                if (json.has("enabled")) channelsConfig.getDiscord().setEnabled(json.get("enabled").asBoolean());
                if (json.has("token") && !json.get("token").asText().contains("*"))
                    channelsConfig.getDiscord().setToken(json.get("token").asText());
                return true;
            case "feishu":
                if (json.has("enabled")) channelsConfig.getFeishu().setEnabled(json.get("enabled").asBoolean());
                if (json.has("appId")) channelsConfig.getFeishu().setAppId(json.get("appId").asText());
                if (json.has("appSecret") && !json.get("appSecret").asText().contains("*"))
                    channelsConfig.getFeishu().setAppSecret(json.get("appSecret").asText());
                return true;
            case "dingtalk":
                if (json.has("enabled")) channelsConfig.getDingtalk().setEnabled(json.get("enabled").asBoolean());
                if (json.has("clientId")) channelsConfig.getDingtalk().setClientId(json.get("clientId").asText());
                if (json.has("clientSecret") && !json.get("clientSecret").asText().contains("*"))
                    channelsConfig.getDingtalk().setClientSecret(json.get("clientSecret").asText());
                return true;
            case "qq":
                if (json.has("enabled")) channelsConfig.getQq().setEnabled(json.get("enabled").asBoolean());
                if (json.has("appId")) channelsConfig.getQq().setAppId(json.get("appId").asText());
                if (json.has("appSecret") && !json.get("appSecret").asText().contains("*"))
                    channelsConfig.getQq().setAppSecret(json.get("appSecret").asText());
                return true;
            default:
                return false;
        }
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
    
    // ==================== Workspace API ====================
    
    private void handleWorkspace(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String workspace = config.getWorkspacePath();
        
        try {
            if ("/api/workspace/files".equals(path) && "GET".equals(method)) {
                ArrayNode files = objectMapper.createArrayNode();
                String[] workspaceFiles = {"AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md", "PROFILE.md", "HEARTBEAT.md"};
                for (String fileName : workspaceFiles) {
                    Path filePath = Paths.get(workspace, fileName);
                    if (Files.exists(filePath)) {
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
                        files.add(file);
                    }
                }
                // 添加 memory 目录下的 MEMORY.md
                Path memoryFile = Paths.get(workspace, "memory", "MEMORY.md");
                if (Files.exists(memoryFile)) {
                    ObjectNode memoryNode = objectMapper.createObjectNode();
                    memoryNode.put("name", "memory/MEMORY.md");
                    memoryNode.put("exists", true);
                    try {
                        memoryNode.put("size", Files.size(memoryFile));
                        memoryNode.put("lastModified", Files.getLastModifiedTime(memoryFile).toMillis());
                    } catch (IOException e) {
                        memoryNode.put("size", 0);
                        memoryNode.put("lastModified", 0);
                    }
                    files.add(memoryNode);
                }
                
                sendJson(exchange, 200, files);
            } else if (path.startsWith("/api/workspace/files/") && "GET".equals(method)) {
                String fileName = URLDecoder.decode(path.substring("/api/workspace/files/".length()), StandardCharsets.UTF_8);
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
            } else if (path.startsWith("/api/workspace/files/") && "PUT".equals(method)) {
                String fileName = URLDecoder.decode(path.substring("/api/workspace/files/".length()), StandardCharsets.UTF_8);
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                String content = json.path("content").asText();
                Path filePath = Paths.get(workspace, fileName);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                sendJson(exchange, 200, successJson("File saved"));
            } else {
                sendJson(exchange, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            logger.error("Workspace API error", Map.of("error", e.getMessage()));
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
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
    
    private void addProviderInfo(ArrayNode providers, String name, ProvidersConfig.ProviderConfig pc) {
        ObjectNode provider = objectMapper.createObjectNode();
        provider.put("name", name);
        provider.put("apiBase", pc.getApiBase() != null ? pc.getApiBase() : ProvidersConfig.getDefaultApiBase(name));
        provider.put("apiKey", maskSecret(pc.getApiKey()));
        provider.put("authorized", pc.isValid());
        providers.add(provider);
    }
    
    private boolean updateProviderConfig(String name, JsonNode json) {
        ProvidersConfig pc = config.getProviders();
        ProvidersConfig.ProviderConfig provider = getProviderByName(name);
        if (provider == null) return false;
        
        if (json.has("apiKey") && !json.get("apiKey").asText().contains("*")) {
            provider.setApiKey(json.get("apiKey").asText());
        }
        if (json.has("apiBase")) {
            provider.setApiBase(json.get("apiBase").asText());
        }
        return true;
    }
    
    private ProvidersConfig.ProviderConfig getProviderByName(String name) {
        ProvidersConfig pc = config.getProviders();
        switch (name) {
            case "openrouter": return pc.getOpenrouter();
            case "openai": return pc.getOpenai();
            case "anthropic": return pc.getAnthropic();
            case "zhipu": return pc.getZhipu();
            case "dashscope": return pc.getDashscope();
            case "gemini": return pc.getGemini();
            case "ollama": return pc.getOllama();
            default: return null;
        }
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
                result.put("model", config.getAgents().getDefaults().getModel());
                // 获取当前配置的 provider
                String currentProvider = getCurrentProvider();
                result.put("provider", currentProvider);
                sendJson(exchange, 200, result);
            } else if ("/api/config/model".equals(path) && "PUT".equals(method)) {
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                if (json.has("model")) {
                    String model = json.path("model").asText();
                    config.getAgents().getDefaults().setModel(model);
                }
                if (json.has("provider")) {
                    String provider = json.path("provider").asText();
                    // 将 provider 信息存储到配置中（可选：我们可以通过模型名称推断，或存储明确的 provider）
                    // 这里我们暂时不存储 provider，而是通过 API key 来推断
                }
                saveConfig();
                sendJson(exchange, 200, successJson("Model updated"));
            } else if ("/api/config/agent".equals(path) && "GET".equals(method)) {
                AgentsConfig.AgentDefaults defaults = config.getAgents().getDefaults();
                ObjectNode result = objectMapper.createObjectNode();
                result.put("workspace", defaults.getWorkspace());
                result.put("model", defaults.getModel());
                result.put("maxTokens", defaults.getMaxTokens());
                result.put("temperature", defaults.getTemperature());
                result.put("maxToolIterations", defaults.getMaxToolIterations());
                result.put("heartbeatEnabled", defaults.isHeartbeatEnabled());
                result.put("restrictToWorkspace", defaults.isRestrictToWorkspace());
                sendJson(exchange, 200, result);
            } else if ("/api/config/agent".equals(path) && "PUT".equals(method)) {
                String body = readRequestBody(exchange);
                JsonNode json = objectMapper.readTree(body);
                AgentsConfig.AgentDefaults defaults = config.getAgents().getDefaults();
                if (json.has("model")) defaults.setModel(json.get("model").asText());
                if (json.has("maxTokens")) defaults.setMaxTokens(json.get("maxTokens").asInt());
                if (json.has("temperature")) defaults.setTemperature(json.get("temperature").asDouble());
                if (json.has("maxToolIterations")) defaults.setMaxToolIterations(json.get("maxToolIterations").asInt());
                if (json.has("heartbeatEnabled")) defaults.setHeartbeatEnabled(json.get("heartbeatEnabled").asBoolean());
                if (json.has("restrictToWorkspace")) defaults.setRestrictToWorkspace(json.get("restrictToWorkspace").asBoolean());
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
    
    // ==================== Static Files ====================
    
    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        
        // 安全检查：防止目录遍历
        if (path.contains("..")) {
            sendError(exchange, 403, "Forbidden");
            return;
        }
        
        String resourcePath = "web" + path;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                sendError(exchange, 404, "Not Found");
                return;
            }
            
            byte[] content = is.readAllBytes();
            String contentType = getContentType(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }
    
    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
    
    // ==================== Helper Methods ====================
    
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private ObjectNode errorJson(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return node;
    }
    
    private ObjectNode successJson(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("success", true);
        node.put("message", message);
        return node;
    }
    
    private String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        if (secret.length() <= 8) return "****";
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }
    
    /**
     * 获取当前配置的第一个有效 Provider 名称
     */
    private String getCurrentProvider() {
        ProvidersConfig pc = config.getProviders();
        if (pc.getOpenrouter() != null && pc.getOpenrouter().isValid()) return "openrouter";
        if (pc.getDashscope() != null && pc.getDashscope().isValid()) return "dashscope";
        if (pc.getZhipu() != null && pc.getZhipu().isValid()) return "zhipu";
        if (pc.getOpenai() != null && pc.getOpenai().isValid()) return "openai";
        if (pc.getAnthropic() != null && pc.getAnthropic().isValid()) return "anthropic";
        if (pc.getGemini() != null && pc.getGemini().isValid()) return "gemini";
        if (pc.getOllama() != null && pc.getOllama().isValid()) return "ollama";
        return "";
    }
    
    private void saveConfig() {
        try {
            ConfigLoader.save(ConfigLoader.getConfigPath(), config);
        } catch (IOException e) {
            logger.error("Failed to save config", Map.of("error", e.getMessage()));
        }
    }
}
