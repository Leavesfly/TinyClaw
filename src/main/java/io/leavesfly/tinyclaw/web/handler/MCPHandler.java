package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPClient;
import io.leavesfly.tinyclaw.mcp.MCPMessage;
import io.leavesfly.tinyclaw.mcp.SSEMCPClient;
import io.leavesfly.tinyclaw.mcp.StreamableHttpMCPClient;
import io.leavesfly.tinyclaw.mcp.StdioMCPClient;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 处理 MCP 服务器配置管理 API（/api/mcp）。
 *
 * 支持的操作：
 * - GET  /api/mcp          获取 MCP 配置（enabled 状态 + 服务器列表）
 * - PUT  /api/mcp          更新 MCP 全局开关（enabled）
 * - POST /api/mcp          添加新的 MCP 服务器配置
 * - PUT  /api/mcp/{name}   更新指定 MCP 服务器配置
 * - DELETE /api/mcp/{name} 删除指定 MCP 服务器配置
 * - POST /api/mcp/{name}/test 测试连接并获取工具列表
 */
public class MCPHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;

    public MCPHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_MCP.equals(path)) {
                switch (method) {
                    case WebUtils.HTTP_METHOD_GET -> handleGetConfig(exchange, corsOrigin);
                    case WebUtils.HTTP_METHOD_PUT -> handleUpdateEnabled(exchange, corsOrigin);
                    case WebUtils.HTTP_METHOD_POST -> handleAddServer(exchange, corsOrigin);
                    default -> WebUtils.sendJson(exchange, 405, WebUtils.errorJson("Method not allowed"), corsOrigin);
                }
            } else if (path.startsWith(WebUtils.API_MCP + WebUtils.PATH_SEPARATOR)) {
                String subPath = path.substring(WebUtils.API_MCP.length() + 1);
                // 检查是否为 /api/mcp/{name}/test
                if (subPath.endsWith("/test")) {
                    String serverName = URLDecoder.decode(
                            subPath.substring(0, subPath.length() - "/test".length()), StandardCharsets.UTF_8);
                    if (WebUtils.HTTP_METHOD_POST.equals(method)) {
                        handleTestConnection(exchange, serverName, corsOrigin);
                    } else {
                        WebUtils.sendJson(exchange, 405, WebUtils.errorJson("Method not allowed"), corsOrigin);
                    }
                } else {
                    String serverName = URLDecoder.decode(subPath, StandardCharsets.UTF_8);
                    switch (method) {
                        case WebUtils.HTTP_METHOD_PUT -> handleUpdateServer(exchange, serverName, corsOrigin);
                        case WebUtils.HTTP_METHOD_DELETE -> handleDeleteServer(exchange, serverName, corsOrigin);
                        default -> WebUtils.sendJson(exchange, 405, WebUtils.errorJson("Method not allowed"), corsOrigin);
                    }
                }
            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("MCP API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    /**
     * GET /api/mcp — 获取 MCP 配置
     */
    private void handleGetConfig(HttpExchange exchange, String corsOrigin) throws IOException {
        MCPServersConfig mcpConfig = getOrCreateMcpConfig();

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("enabled", mcpConfig.isEnabled());

        ArrayNode serversArray = WebUtils.MAPPER.createArrayNode();
        for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
            ObjectNode serverNode = WebUtils.MAPPER.createObjectNode();
            serverNode.put("name", server.getName());
            serverNode.put("type", server.getType() != null ? server.getType() : "sse");
            serverNode.put("description", server.getDescription() != null ? server.getDescription() : "");
            serverNode.put("endpoint", server.getEndpoint() != null ? server.getEndpoint() : "");
            serverNode.put("apiKey", server.getApiKey() != null ? WebUtils.maskSecret(server.getApiKey()) : "");
            serverNode.put("command", server.getCommand() != null ? server.getCommand() : "");
            if (server.getArgs() != null) {
                ArrayNode argsArray = WebUtils.MAPPER.createArrayNode();
                server.getArgs().forEach(argsArray::add);
                serverNode.set("args", argsArray);
            }
            if (server.getEnv() != null) {
                ObjectNode envNode = WebUtils.MAPPER.createObjectNode();
                server.getEnv().forEach(envNode::put);
                serverNode.set("env", envNode);
            }
            serverNode.put("enabled", server.isEnabled());
            serverNode.put("timeout", server.getTimeout());
            serversArray.add(serverNode);
        }
        result.set("servers", serversArray);

        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * PUT /api/mcp — 更新 MCP 全局开关
     */
    private void handleUpdateEnabled(HttpExchange exchange, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);

        MCPServersConfig mcpConfig = getOrCreateMcpConfig();

        if (json.has("enabled")) {
            mcpConfig.setEnabled(json.get("enabled").asBoolean());
        }

        WebUtils.saveConfig(config, logger);
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("MCP config updated"), corsOrigin);
    }

    /**
     * POST /api/mcp — 添加新的 MCP 服务器
     */
    private void handleAddServer(HttpExchange exchange, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);

        String name = getRequiredField(json, "name");
        if (name == null || name.isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Server name is required"), corsOrigin);
            return;
        }

        MCPServersConfig mcpConfig = getOrCreateMcpConfig();

        // 检查名称是否已存在
        boolean exists = mcpConfig.getServers().stream()
                .anyMatch(s -> s.getName().equals(name));
        if (exists) {
            WebUtils.sendJson(exchange, 409, WebUtils.errorJson("Server '" + name + "' already exists"), corsOrigin);
            return;
        }

        MCPServersConfig.MCPServerConfig serverConfig = new MCPServersConfig.MCPServerConfig();
        serverConfig.setName(name);
        serverConfig.setType(getFieldOrDefault(json, "type", "sse"));
        serverConfig.setDescription(getFieldOrDefault(json, "description", ""));
        serverConfig.setEndpoint(getFieldOrDefault(json, "endpoint", ""));
        serverConfig.setApiKey(getFieldOrDefault(json, "apiKey", ""));
        serverConfig.setCommand(getFieldOrDefault(json, "command", ""));
        if (json.has("args") && json.get("args").isArray()) {
            List<String> argsList = new ArrayList<>();
            json.get("args").forEach(node -> argsList.add(node.asText()));
            serverConfig.setArgs(argsList);
        }
        if (json.has("env") && json.get("env").isObject()) {
            java.util.Map<String, String> envMap = new java.util.LinkedHashMap<>();
            json.get("env").fields().forEachRemaining(entry -> envMap.put(entry.getKey(), entry.getValue().asText()));
            serverConfig.setEnv(envMap);
        }
        serverConfig.setEnabled(json.has("enabled") ? json.get("enabled").asBoolean() : true);
        serverConfig.setTimeout(json.has("timeout") ? json.get("timeout").asInt() : 30000);

        mcpConfig.getServers().add(serverConfig);
        WebUtils.saveConfig(config, logger);

        WebUtils.sendJson(exchange, 201, WebUtils.successJson("Server '" + name + "' added"), corsOrigin);
    }

    /**
     * PUT /api/mcp/{name} — 更新指定服务器配置
     */
    private void handleUpdateServer(HttpExchange exchange, String serverName, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);

        MCPServersConfig mcpConfig = getOrCreateMcpConfig();
        MCPServersConfig.MCPServerConfig serverConfig = findServer(mcpConfig, serverName);

        if (serverConfig == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Server '" + serverName + "' not found"), corsOrigin);
            return;
        }

        if (json.has("type")) {
            serverConfig.setType(json.get("type").asText());
        }
        if (json.has("description")) {
            serverConfig.setDescription(json.get("description").asText());
        }
        if (json.has("endpoint")) {
            serverConfig.setEndpoint(json.get("endpoint").asText());
        }
        if (json.has("apiKey")) {
            String apiKey = json.get("apiKey").asText();
            if (!WebUtils.isSecretMasked(apiKey)) {
                serverConfig.setApiKey(apiKey);
            }
        }
        if (json.has("command")) {
            serverConfig.setCommand(json.get("command").asText());
        }
        if (json.has("args") && json.get("args").isArray()) {
            List<String> argsList = new ArrayList<>();
            json.get("args").forEach(node -> argsList.add(node.asText()));
            serverConfig.setArgs(argsList);
        }
        if (json.has("env") && json.get("env").isObject()) {
            java.util.Map<String, String> envMap = new java.util.LinkedHashMap<>();
            json.get("env").fields().forEachRemaining(entry -> envMap.put(entry.getKey(), entry.getValue().asText()));
            serverConfig.setEnv(envMap);
        }
        if (json.has("enabled")) {
            serverConfig.setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("timeout")) {
            serverConfig.setTimeout(json.get("timeout").asInt());
        }

        WebUtils.saveConfig(config, logger);
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Server '" + serverName + "' updated"), corsOrigin);
    }

    /**
     * DELETE /api/mcp/{name} — 删除指定服务器配置
     */
    private void handleDeleteServer(HttpExchange exchange, String serverName, String corsOrigin) throws IOException {
        MCPServersConfig mcpConfig = getOrCreateMcpConfig();

        boolean removed = mcpConfig.getServers().removeIf(s -> s.getName().equals(serverName));
        if (!removed) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Server '" + serverName + "' not found"), corsOrigin);
            return;
        }

        WebUtils.saveConfig(config, logger);
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Server '" + serverName + "' deleted"), corsOrigin);
    }

    /**
     * POST /api/mcp/{name}/test — 测试连接并获取工具列表
     */
    @SuppressWarnings("unchecked")
    private void handleTestConnection(HttpExchange exchange, String serverName, String corsOrigin) throws IOException {
        MCPServersConfig mcpConfig = getOrCreateMcpConfig();
        MCPServersConfig.MCPServerConfig serverConfig = findServer(mcpConfig, serverName);

        if (serverConfig == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Server '" + serverName + "' not found"), corsOrigin);
            return;
        }

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("serverName", serverName);

        MCPClient client = null;
        try {
            // 根据类型创建临时客户端
            if (serverConfig.isStdio()) {
                client = new StdioMCPClient(
                        serverConfig.getCommand(),
                        serverConfig.getArgs(),
                        serverConfig.getEnv(),
                        serverConfig.getTimeout()
                );
            } else if (serverConfig.isStreamableHttp()) {
                client = new StreamableHttpMCPClient(
                        serverConfig.getEndpoint(),
                        serverConfig.getApiKey(),
                        serverConfig.getTimeout()
                );
            } else {
                client = new SSEMCPClient(
                        serverConfig.getEndpoint(),
                        serverConfig.getApiKey(),
                        serverConfig.getTimeout()
                );
            }

            // 连接
            client.connect();
            result.put("connected", true);

            // 初始化握手
            Map<String, Object> initParams = new HashMap<>();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.put("capabilities", Collections.emptyMap());
            initParams.put("clientInfo", Map.of("name", "TinyClaw", "version", "0.1.0"));

            MCPMessage initResponse = client.sendRequest("initialize", initParams);
            result.put("initialized", true);

            // 解析服务器信息
            if (initResponse.getResult() != null) {
                Map<String, Object> initResult = initResponse.getResult();
                ObjectNode serverInfoNode = WebUtils.MAPPER.createObjectNode();

                if (initResult.get("protocolVersion") != null) {
                    serverInfoNode.put("protocolVersion", (String) initResult.get("protocolVersion"));
                }
                Map<String, Object> serverInfo = (Map<String, Object>) initResult.get("serverInfo");
                if (serverInfo != null) {
                    if (serverInfo.get("name") != null) {
                        serverInfoNode.put("name", (String) serverInfo.get("name"));
                    }
                    if (serverInfo.get("version") != null) {
                        serverInfoNode.put("version", (String) serverInfo.get("version"));
                    }
                }
                result.set("serverInfo", serverInfoNode);
            }

            // 发送 initialized 通知
            client.sendNotification("notifications/initialized", Collections.emptyMap());

            // 获取工具列表
            MCPMessage toolsResponse = client.sendRequest("tools/list", Collections.emptyMap());

            ArrayNode toolsArray = WebUtils.MAPPER.createArrayNode();
            if (toolsResponse.getResult() != null && toolsResponse.getResult().containsKey("tools")) {
                List<Map<String, Object>> toolsList =
                        (List<Map<String, Object>>) toolsResponse.getResult().get("tools");
                if (toolsList != null) {
                    for (Map<String, Object> tool : toolsList) {
                        ObjectNode toolNode = WebUtils.MAPPER.createObjectNode();
                        toolNode.put("name", (String) tool.get("name"));
                        toolNode.put("description", tool.get("description") != null ? (String) tool.get("description") : "");

                        // 解析参数信息
                        Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
                        if (inputSchema != null) {
                            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
                            if (properties != null && !properties.isEmpty()) {
                                ArrayNode paramsArray = WebUtils.MAPPER.createArrayNode();
                                for (String paramName : properties.keySet()) {
                                    ObjectNode paramNode = WebUtils.MAPPER.createObjectNode();
                                    paramNode.put("name", paramName);
                                    Map<String, Object> paramDef = (Map<String, Object>) properties.get(paramName);
                                    if (paramDef != null) {
                                        if (paramDef.get("type") != null) {
                                            paramNode.put("type", (String) paramDef.get("type"));
                                        }
                                        if (paramDef.get("description") != null) {
                                            paramNode.put("description", (String) paramDef.get("description"));
                                        }
                                    }
                                    paramsArray.add(paramNode);
                                }
                                toolNode.set("parameters", paramsArray);
                            }

                            // 解析 required 字段
                            List<String> required = (List<String>) inputSchema.get("required");
                            if (required != null) {
                                ArrayNode requiredArray = WebUtils.MAPPER.createArrayNode();
                                required.forEach(requiredArray::add);
                                toolNode.set("required", requiredArray);
                            }
                        }

                        toolsArray.add(toolNode);
                    }
                }
            }

            result.set("tools", toolsArray);
            result.put("toolCount", toolsArray.size());
            result.put("success", true);

            WebUtils.sendJson(exchange, 200, result, corsOrigin);

        } catch (Exception e) {
            logger.error("MCP test connection failed", Map.of(
                    "server", serverName, "error", e.getMessage()));
            result.put("connected", false);
            result.put("success", false);
            result.put("error", e.getMessage());
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 获取或创建 MCP 配置（确保不为 null）
     */
    private MCPServersConfig getOrCreateMcpConfig() {
        MCPServersConfig mcpConfig = config.getMcpServers();
        if (mcpConfig == null) {
            mcpConfig = new MCPServersConfig();
            config.setMcpServers(mcpConfig);
        }
        if (mcpConfig.getServers() == null) {
            mcpConfig.setServers(new ArrayList<>());
        }
        return mcpConfig;
    }

    private MCPServersConfig.MCPServerConfig findServer(MCPServersConfig mcpConfig, String name) {
        return mcpConfig.getServers().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private String getRequiredField(JsonNode json, String field) {
        return json.has(field) ? json.get(field).asText() : null;
    }

    private String getFieldOrDefault(JsonNode json, String field, String defaultValue) {
        return json.has(field) ? json.get(field).asText() : defaultValue;
    }
}
