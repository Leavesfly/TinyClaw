package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ModelsConfig;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理模型列表 API（/api/models）。
 */
public class ModelsHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;
    private final ProvidersHandler providersHandler;

    public ModelsHandler(Config config, SecurityMiddleware security, ProvidersHandler providersHandler) {
        this.config = config;
        this.security = security;
        this.providersHandler = providersHandler;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_MODELS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                ArrayNode models = WebUtils.MAPPER.createArrayNode();
                ModelsConfig modelsConfig = config.getModels();

                for (Map.Entry<String, ModelsConfig.ModelDefinition> entry
                        : modelsConfig.getDefinitions().entrySet()) {
                    String modelName = entry.getKey();
                    ModelsConfig.ModelDefinition def = entry.getValue();
                    String providerName = def.getProvider();

                    ProvidersConfig.ProviderConfig providerConfig =
                            providersHandler.getProviderByName(providerName);
                    boolean authorized = providerConfig != null && providerConfig.isValid();

                    ObjectNode modelNode = WebUtils.MAPPER.createObjectNode();
                    modelNode.put("name", modelName);
                    modelNode.put("provider", providerName);
                    modelNode.put("model", def.getModel());
                    modelNode.put("maxContextSize",
                            def.getMaxContextSize() != null ? def.getMaxContextSize() : 0);
                    modelNode.put("description",
                            def.getDescription() != null ? def.getDescription() : "");
                    modelNode.put("authorized", authorized);
                    models.add(modelNode);
                }
                WebUtils.sendJson(exchange, 200, models, corsOrigin);
            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Models API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }
}
