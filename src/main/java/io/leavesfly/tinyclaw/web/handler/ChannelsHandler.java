package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理通道管理 API（/api/channels）。
 */
public class ChannelsHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;

    public ChannelsHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_CHANNELS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleGetChannels(exchange, corsOrigin);
            } else if (path.startsWith(WebUtils.API_CHANNELS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleGetChannelDetail(exchange, path, corsOrigin);
            } else if (path.startsWith(WebUtils.API_CHANNELS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_PUT.equals(method)) {
                handleUpdateChannel(exchange, path, corsOrigin);
            } else {
                WebUtils.sendNotFound(exchange, corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Channels API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void handleGetChannels(HttpExchange exchange, String corsOrigin) throws IOException {
        ArrayNode channels = WebUtils.MAPPER.createArrayNode();
        ChannelsConfig cc = config.getChannels();
        addChannelInfo(channels, WebUtils.CHANNEL_TELEGRAM, cc.getTelegram().isEnabled());
        addChannelInfo(channels, WebUtils.CHANNEL_DISCORD,  cc.getDiscord().isEnabled());
        addChannelInfo(channels, WebUtils.CHANNEL_WHATSAPP, cc.getWhatsapp().isEnabled());
        addChannelInfo(channels, WebUtils.CHANNEL_FEISHU,   cc.getFeishu().isEnabled());
        addChannelInfo(channels, WebUtils.CHANNEL_DINGTALK, cc.getDingtalk().isEnabled());
        addChannelInfo(channels, WebUtils.CHANNEL_QQ,       cc.getQq().isEnabled());
        addChannelInfo(channels, WebUtils.CHANNEL_MAIXCAM,  cc.getMaixcam().isEnabled());
        WebUtils.sendJson(exchange, 200, channels, corsOrigin);
    }

    private void handleGetChannelDetail(HttpExchange exchange, String path, String corsOrigin) throws IOException {
        String channelName = path.substring(WebUtils.API_CHANNELS.length() + 1);
        ObjectNode detail = getChannelDetail(channelName);
        if (detail != null) {
            WebUtils.sendJson(exchange, 200, detail, corsOrigin);
        } else {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Channel not found"), corsOrigin);
        }
    }

    private void handleUpdateChannel(HttpExchange exchange, String path, String corsOrigin) throws IOException {
        String channelName = path.substring(WebUtils.API_CHANNELS.length() + 1);
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        boolean success = updateChannelConfig(channelName, json);
        if (success) {
            WebUtils.saveConfig(config, logger);
            WebUtils.sendJson(exchange, 200, WebUtils.successJson("Channel updated"), corsOrigin);
        } else {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Update failed"), corsOrigin);
        }
    }

    private void addChannelInfo(ArrayNode channels, String name, boolean enabled) {
        ObjectNode channel = WebUtils.MAPPER.createObjectNode();
        channel.put("name", name);
        channel.put("enabled", enabled);
        channels.add(channel);
    }

    private ObjectNode getChannelDetail(String name) {
        ObjectNode detail = WebUtils.MAPPER.createObjectNode();
        detail.put("name", name);
        ChannelsConfig cc = config.getChannels();
        return switch (name) {
            case WebUtils.CHANNEL_TELEGRAM -> {
                detail.put("enabled", cc.getTelegram().isEnabled());
                detail.put("token", WebUtils.maskSecret(cc.getTelegram().getToken()));
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getTelegram().getAllowFrom()));
                yield detail;
            }
            case WebUtils.CHANNEL_DISCORD -> {
                detail.put("enabled", cc.getDiscord().isEnabled());
                detail.put("token", WebUtils.maskSecret(cc.getDiscord().getToken()));
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getDiscord().getAllowFrom()));
                yield detail;
            }
            case WebUtils.CHANNEL_FEISHU -> {
                detail.put("enabled", cc.getFeishu().isEnabled());
                detail.put("appId", cc.getFeishu().getAppId());
                detail.put("appSecret", WebUtils.maskSecret(cc.getFeishu().getAppSecret()));
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getFeishu().getAllowFrom()));
                yield detail;
            }
            case WebUtils.CHANNEL_DINGTALK -> {
                detail.put("enabled", cc.getDingtalk().isEnabled());
                detail.put("clientId", cc.getDingtalk().getClientId());
                detail.put("clientSecret", WebUtils.maskSecret(cc.getDingtalk().getClientSecret()));
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getDingtalk().getAllowFrom()));
                yield detail;
            }
            case WebUtils.CHANNEL_QQ -> {
                detail.put("enabled", cc.getQq().isEnabled());
                detail.put("appId", cc.getQq().getAppId());
                detail.put("appSecret", WebUtils.maskSecret(cc.getQq().getAppSecret()));
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getQq().getAllowFrom()));
                yield detail;
            }
            case WebUtils.CHANNEL_WHATSAPP -> {
                detail.put("enabled", cc.getWhatsapp().isEnabled());
                detail.put("bridgeUrl", cc.getWhatsapp().getBridgeUrl());
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getWhatsapp().getAllowFrom()));
                yield detail;
            }
            case WebUtils.CHANNEL_MAIXCAM -> {
                detail.put("enabled", cc.getMaixcam().isEnabled());
                detail.put("host", cc.getMaixcam().getHost());
                detail.put("port", cc.getMaixcam().getPort());
                detail.set("allowFrom", WebUtils.MAPPER.valueToTree(cc.getMaixcam().getAllowFrom()));
                yield detail;
            }
            default -> null;
        };
    }

    private boolean updateChannelConfig(String name, JsonNode json) {
        ChannelsConfig cc = config.getChannels();
        return switch (name) {
            case WebUtils.CHANNEL_TELEGRAM -> { updateTelegramConfig(cc, json);  yield true; }
            case WebUtils.CHANNEL_DISCORD  -> { updateDiscordConfig(cc, json);   yield true; }
            case WebUtils.CHANNEL_FEISHU   -> { updateFeishuConfig(cc, json);    yield true; }
            case WebUtils.CHANNEL_DINGTALK -> { updateDingtalkConfig(cc, json);  yield true; }
            case WebUtils.CHANNEL_QQ       -> { updateQQConfig(cc, json);        yield true; }
            default -> false;
        };
    }

    private void updateTelegramConfig(ChannelsConfig cc, JsonNode json) {
        if (json.has("enabled")) cc.getTelegram().setEnabled(json.get("enabled").asBoolean());
        if (json.has("token") && !WebUtils.isSecretMasked(json.get("token").asText()))
            cc.getTelegram().setToken(json.get("token").asText());
    }

    private void updateDiscordConfig(ChannelsConfig cc, JsonNode json) {
        if (json.has("enabled")) cc.getDiscord().setEnabled(json.get("enabled").asBoolean());
        if (json.has("token") && !WebUtils.isSecretMasked(json.get("token").asText()))
            cc.getDiscord().setToken(json.get("token").asText());
    }

    private void updateFeishuConfig(ChannelsConfig cc, JsonNode json) {
        if (json.has("enabled")) cc.getFeishu().setEnabled(json.get("enabled").asBoolean());
        if (json.has("appId"))   cc.getFeishu().setAppId(json.get("appId").asText());
        if (json.has("appSecret") && !WebUtils.isSecretMasked(json.get("appSecret").asText()))
            cc.getFeishu().setAppSecret(json.get("appSecret").asText());
    }

    private void updateDingtalkConfig(ChannelsConfig cc, JsonNode json) {
        if (json.has("enabled"))    cc.getDingtalk().setEnabled(json.get("enabled").asBoolean());
        if (json.has("clientId"))   cc.getDingtalk().setClientId(json.get("clientId").asText());
        if (json.has("clientSecret") && !WebUtils.isSecretMasked(json.get("clientSecret").asText()))
            cc.getDingtalk().setClientSecret(json.get("clientSecret").asText());
    }

    private void updateQQConfig(ChannelsConfig cc, JsonNode json) {
        if (json.has("enabled"))   cc.getQq().setEnabled(json.get("enabled").asBoolean());
        if (json.has("appId"))     cc.getQq().setAppId(json.get("appId").asText());
        if (json.has("appSecret") && !WebUtils.isSecretMasked(json.get("appSecret").asText()))
            cc.getQq().setAppSecret(json.get("appSecret").asText());
    }
}
