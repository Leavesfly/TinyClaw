package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;

/**
 * 处理 LLM 提供商 API（/api/providers）。
 * 同时提供 getProviderByName、getCurrentProvider 供 ModelsHandler / ConfigHandler 复用。
 */
public class ProvidersHandler extends BaseHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /**
     * 构造 ProvidersHandler，注入全局配置与安全中间件。
     */
    public ProvidersHandler(Config config, SecurityMiddleware security) {
        super(config, security);
    }

    /**
     * 入口路由：预检通过后，按路径分发列表查询或更新操作。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (WebUtils.API_PROVIDERS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
            ArrayNode providers = WebUtils.MAPPER.createArrayNode();
            ProvidersConfig pc = config.getProviders();
            addProviderInfo(providers, WebUtils.PROVIDER_OPENROUTER, pc.getOpenrouter());
            addProviderInfo(providers, WebUtils.PROVIDER_OPENAI,     pc.getOpenai());
            addProviderInfo(providers, WebUtils.PROVIDER_ANTHROPIC,  pc.getAnthropic());
            addProviderInfo(providers, WebUtils.PROVIDER_ZHIPU,      pc.getZhipu());
            addProviderInfo(providers, WebUtils.PROVIDER_DASHSCOPE,  pc.getDashscope());
            addProviderInfo(providers, WebUtils.PROVIDER_GEMINI,     pc.getGemini());
            addProviderInfo(providers, WebUtils.PROVIDER_OLLAMA,     pc.getOllama());
            WebUtils.sendJson(exchange, 200, providers, corsOrigin);

        } else if (path.startsWith(WebUtils.API_PROVIDERS + WebUtils.PATH_SEPARATOR)
                && WebUtils.HTTP_METHOD_PUT.equals(method)) {
            String name = path.substring(WebUtils.API_PROVIDERS.length() + 1);
            String body = WebUtils.readRequestBodyLimited(exchange);
            JsonNode json = WebUtils.MAPPER.readTree(body);
            boolean success = updateProviderConfig(name, json);
            if (success) {
                WebUtils.saveConfig(config, logger);
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Provider updated"), corsOrigin);
            } else {
                WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Update failed"), corsOrigin);
            }
        } else {
            return false;
        }
        return true;
    }

    /**
     * 向 providers 数组追加一个描述单个 Provider 的节点，包含 name、apiBase、脲码的 apiKey 以及 authorized 字段。
     */
    private void addProviderInfo(ArrayNode providers, String name, ProvidersConfig.ProviderConfig pc) {
        ObjectNode provider = WebUtils.MAPPER.createObjectNode();
        provider.put("name", name);
        provider.put("apiBase", pc.getApiBase() != null
                ? pc.getApiBase() : ProvidersConfig.getDefaultApiBase(name));
        provider.put("apiKey", WebUtils.maskSecret(pc.getApiKey()));
        provider.put("authorized", pc.isValid());
        providers.add(provider);
    }

    /**
     * 将请求中的 apiKey/apiBase 写入对应 Provider 配置。
     * 已脲码的 apiKey 不会覆盖原有值。
     * Provider 不存在时返回 false。
     */
    private boolean updateProviderConfig(String name, JsonNode json) {
        ProvidersConfig.ProviderConfig provider = getProviderByName(name);
        if (provider == null) return false;
        if (json.has("apiKey") && !WebUtils.isSecretMasked(json.get("apiKey").asText()))
            provider.setApiKey(json.get("apiKey").asText());
        if (json.has("apiBase"))
            provider.setApiBase(json.get("apiBase").asText());
        return true;
    }

    /**
     * 根据名称获取 Provider 配置（供 ModelsHandler / ConfigHandler 使用）。
     */
    public ProvidersConfig.ProviderConfig getProviderByName(String name) {
        ProvidersConfig pc = config.getProviders();
        return switch (name) {
            case WebUtils.PROVIDER_OPENROUTER -> pc.getOpenrouter();
            case WebUtils.PROVIDER_OPENAI     -> pc.getOpenai();
            case WebUtils.PROVIDER_ANTHROPIC  -> pc.getAnthropic();
            case WebUtils.PROVIDER_ZHIPU      -> pc.getZhipu();
            case WebUtils.PROVIDER_DASHSCOPE  -> pc.getDashscope();
            case WebUtils.PROVIDER_GEMINI     -> pc.getGemini();
            case WebUtils.PROVIDER_OLLAMA     -> pc.getOllama();
            default -> null;
        };
    }

    /**
     * 获取当前第一个有效 Provider 名称（按优先级：OpenRouter > DashScope > Zhipu > OpenAI > Anthropic > Gemini > Ollama）。
     */
    public String getCurrentProvider() {
        ProvidersConfig pc = config.getProviders();
        if (isValidProvider(pc.getOpenrouter())) return WebUtils.PROVIDER_OPENROUTER;
        if (isValidProvider(pc.getDashscope()))  return WebUtils.PROVIDER_DASHSCOPE;
        if (isValidProvider(pc.getZhipu()))      return WebUtils.PROVIDER_ZHIPU;
        if (isValidProvider(pc.getOpenai()))     return WebUtils.PROVIDER_OPENAI;
        if (isValidProvider(pc.getAnthropic()))  return WebUtils.PROVIDER_ANTHROPIC;
        if (isValidProvider(pc.getGemini()))     return WebUtils.PROVIDER_GEMINI;
        if (isValidProvider(pc.getOllama()))     return WebUtils.PROVIDER_OLLAMA;
        return "";
    }

    /**
     * 判断 Provider 配置是否有效（非 null 且通过 isValid 校验）。
     */
    private boolean isValidProvider(ProvidersConfig.ProviderConfig provider) {
        return provider != null && provider.isValid();
    }
}
