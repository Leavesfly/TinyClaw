package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.config.AgentConfig;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理 Agent 及模型配置 API（/api/config）。
 */
public class ConfigHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;
    private final ProvidersHandler providersHandler;
    private final AgentLoop agentLoop;

    /**
     * 构造 ConfigHandler，注入全局配置、安全中间件、ProvidersHandler 以及 AgentLoop（用于模型热重载）。
     */
    public ConfigHandler(Config config, SecurityMiddleware security, ProvidersHandler providersHandler,
                         AgentLoop agentLoop) {
        this.config = config;
        this.security = security;
        this.providersHandler = providersHandler;
        this.agentLoop = agentLoop;
    }

    /**
     * 入口路由：预检通过后，按路径分发到以下四种操作：
     * <ul>
     *   <li>GET  /api/config/model   —— 读取当前模型与 Provider</li>
     *   <li>PUT  /api/config/model   —— 更新模型与 Provider 并持久化</li>
     *   <li>GET  /api/config/agent   —— 读取 Agent 全量配置项</li>
     *   <li>PUT  /api/config/agent   —— 更新 Agent 配置并持久化</li>
     * </ul>
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_CONFIG_MODEL.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                ObjectNode result = WebUtils.MAPPER.createObjectNode();
                result.put("model", config.getAgent().getModel());
                String savedProvider = config.getAgent().getProvider();
                String currentProvider = (savedProvider != null && !savedProvider.isEmpty())
                        ? savedProvider : providersHandler.getCurrentProvider();
                result.put("provider", currentProvider);
                WebUtils.sendJson(exchange, 200, result, corsOrigin);

            } else if (WebUtils.API_CONFIG_MODEL.equals(path) && WebUtils.HTTP_METHOD_PUT.equals(method)) {
                String body = WebUtils.readRequestBodyLimited(exchange);
                JsonNode json = WebUtils.MAPPER.readTree(body);
                if (json.has("model"))    config.getAgent().setModel(json.path("model").asText());
                if (json.has("provider")) config.getAgent().setProvider(json.path("provider").asText());
                WebUtils.saveConfig(config, logger);
                // 配置保存后立即热重载，使模型选择无需重启即可生效
                if (agentLoop != null) {
                    agentLoop.reloadModel();
                }
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Model updated"), corsOrigin);

            } else if (WebUtils.API_CONFIG_AGENT.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                AgentConfig agentConfig = config.getAgent();
                ObjectNode result = WebUtils.MAPPER.createObjectNode();
                result.put("workspace",           agentConfig.getWorkspace());
                result.put("model",               agentConfig.getModel());
                result.put("maxTokens",           agentConfig.getMaxTokens());
                result.put("temperature",         agentConfig.getTemperature());
                result.put("maxToolIterations",   agentConfig.getMaxToolIterations());
                result.put("heartbeatEnabled",    agentConfig.isHeartbeatEnabled());
                result.put("restrictToWorkspace", agentConfig.isRestrictToWorkspace());
                WebUtils.sendJson(exchange, 200, result, corsOrigin);

            } else if (WebUtils.API_CONFIG_AGENT.equals(path) && WebUtils.HTTP_METHOD_PUT.equals(method)) {
                String body = WebUtils.readRequestBodyLimited(exchange);
                JsonNode json = WebUtils.MAPPER.readTree(body);
                AgentConfig agentConfig = config.getAgent();
                if (json.has("model"))               agentConfig.setModel(json.get("model").asText());
                if (json.has("maxTokens"))           agentConfig.setMaxTokens(json.get("maxTokens").asInt());
                if (json.has("temperature"))         agentConfig.setTemperature(json.get("temperature").asDouble());
                if (json.has("maxToolIterations"))   agentConfig.setMaxToolIterations(json.get("maxToolIterations").asInt());
                if (json.has("heartbeatEnabled"))    agentConfig.setHeartbeatEnabled(json.get("heartbeatEnabled").asBoolean());
                if (json.has("restrictToWorkspace")) agentConfig.setRestrictToWorkspace(json.get("restrictToWorkspace").asBoolean());
                WebUtils.saveConfig(config, logger);
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Agent config updated"), corsOrigin);

            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Config API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }
}
