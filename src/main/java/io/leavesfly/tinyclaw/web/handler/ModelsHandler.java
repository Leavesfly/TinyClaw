package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ModelsConfig;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理模型列表 API（/api/models）。
 */
public class ModelsHandler extends BaseHandler {

    private final ProvidersHandler providersHandler;

    /**
     * 构造 ModelsHandler，注入全局配置、安全中间件及 ProvidersHandler（用于校验 Provider 是否已授权）。
     */
    public ModelsHandler(Config config, SecurityMiddleware security, ProvidersHandler providersHandler) {
        super(config, security);
        this.providersHandler = providersHandler;
    }

    /**
     * 返回所有模型定义列表。
     * 每个模型节点会一并附带 authorized 字段，表明对应 Provider 是否已配置 API Key。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!WebUtils.API_MODELS.equals(path) || !WebUtils.HTTP_METHOD_GET.equals(method)) {
            return false;
        }
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
        return true;
    }
}
