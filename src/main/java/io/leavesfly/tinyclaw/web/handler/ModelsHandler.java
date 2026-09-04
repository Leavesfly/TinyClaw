package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ModelsConfig;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
import io.leavesfly.tinyclaw.providers.LLMException;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.List;
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
     * 路由：GET /api/models 返回模型列表；POST /api/models/test 验证 provider/模型连接。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (WebUtils.API_MODELS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
            return handleListModels(exchange, corsOrigin);
        }
        if ((WebUtils.API_MODELS + "/test").equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleTestConnection(exchange, corsOrigin);
            return true;
        }
        return false;
    }

    /**
     * 返回所有模型定义列表。
     * 每个模型节点会一并附带 authorized 字段，表明对应 Provider 是否已配置 API Key。
     */
    private boolean handleListModels(HttpExchange exchange, String corsOrigin) throws IOException {
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

    /**
     * POST /api/models/test — 保存前验证 provider/模型连接。
     *
     * <p>body：{provider, model?}；model 缺省时取该 provider 下第一个已定义模型。
     * 以极小 max_tokens 发起真实请求，证明“所选即所答”，
     * 返回 {success, latencyMs, model} 或 {success:false, error}。</p>
     */
    private void handleTestConnection(HttpExchange exchange, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String providerName = json.path("provider").asText("");
        String modelName = json.path("model").asText("");

        if (providerName.isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("provider is required"), corsOrigin);
            return;
        }

        if (modelName.isEmpty()) {
            modelName = config.getModels().getDefinitions().entrySet().stream()
                    .filter(e -> providerName.equals(e.getValue().getProvider()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("");
        }
        if (modelName.isEmpty()) {
            WebUtils.sendJson(exchange, 400,
                    WebUtils.errorJson("no model defined for provider: " + providerName), corsOrigin);
            return;
        }

        ProvidersConfig.ProviderConfig providerConfig = providersHandler.getProviderByName(providerName);
        if (providerConfig == null || !providerConfig.isValid()) {
            WebUtils.sendJson(exchange, 400,
                    WebUtils.errorJson("provider not authorized: " + providerName), corsOrigin);
            return;
        }

        String apiBase = providerConfig.getApiBaseOrDefault(
                ProvidersConfig.getDefaultApiBase(providerName));
        HTTPProvider probe = new HTTPProvider(providerConfig.getApiKey(), apiBase, providerName);
        probe.setThinkingEnabled(config.getAgent().isThinkingEnabled());

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("model", modelName);
        long start = System.currentTimeMillis();
        try {
            probe.chat(List.of(Message.user("ping")), null, modelName, Map.of("max_tokens", 8));
            result.put("success", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", LLMException.rootCauseMessage(e));
        }
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }
}
