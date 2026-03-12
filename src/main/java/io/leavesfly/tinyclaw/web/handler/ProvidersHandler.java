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
import java.util.Map;

/**
 * 处理 LLM 提供商 API（/api/providers）。
 * 同时提供 getProviderByName、getCurrentProvider 供 ModelsHandler / ConfigHandler 复用。
 */
public class ProvidersHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;

    public ProvidersHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
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
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Providers API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void addProviderInfo(ArrayNode providers, String name, ProvidersConfig.ProviderConfig pc) {
        ObjectNode provider = WebUtils.MAPPER.createObjectNode();
        provider.put("name", name);
        provider.put("apiBase", pc.getApiBase() != null
                ? pc.getApiBase() : ProvidersConfig.getDefaultApiBase(name));
        provider.put("apiKey", WebUtils.maskSecret(pc.getApiKey()));
        provider.put("authorized", pc.isValid());
        providers.add(provider);
    }

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

    private boolean isValidProvider(ProvidersConfig.ProviderConfig provider) {
        return provider != null && provider.isValid();
    }
}
