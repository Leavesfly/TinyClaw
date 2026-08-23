package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Token 消耗统计 API（/api/token-stats）。
 *
 * <p>支持按日期范围查询 token 消耗，返回总量、按模型分组、按日期分组三个维度的数据。</p>
 *
 * <p>请求示例：GET /api/token-stats?startDate=2026-02-17&endDate=2026-03-19</p>
 */
public class TokenStatsHandler extends BaseHandler {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TokenUsageStore tokenUsageStore;

    public TokenStatsHandler(Config config, TokenUsageStore tokenUsageStore, SecurityMiddleware security) {
        super(config, security);
        this.tokenUsageStore = tokenUsageStore;
    }

    /**
     * 处理 GET /api/token-stats 请求。
     * 查询参数：startDate（yyyy-MM-dd）、endDate（yyyy-MM-dd），均可选，默认最近 30 天。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!WebUtils.HTTP_METHOD_GET.equals(method)) {
            WebUtils.sendJson(exchange, 405, WebUtils.errorJson("Method not allowed"), corsOrigin);
            return true;
        }

        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQueryParams(query);

        String endDate = params.getOrDefault("endDate", LocalDate.now().format(DATE_FORMATTER));
        String startDate = params.getOrDefault("startDate",
                LocalDate.now().minusDays(30).format(DATE_FORMATTER));

        TokenUsageStore.TokenStats stats = tokenUsageStore.query(startDate, endDate);

        ObjectNode result = buildResponse(stats, startDate, endDate);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
        return true;
    }

    // ==================== 内部工具方法 ====================

    private ObjectNode buildResponse(TokenUsageStore.TokenStats stats,
                                     String startDate, String endDate) {
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("totalPromptTokens", stats.totalPromptTokens);
        result.put("totalCompletionTokens", stats.totalCompletionTokens);
        result.put("totalTokens", stats.totalPromptTokens + stats.totalCompletionTokens);
        result.put("totalCalls", stats.totalCalls);

        // 按模型分组
        ArrayNode byModelArray = WebUtils.MAPPER.createArrayNode();
        for (Map.Entry<String, long[]> entry : stats.byModel.entrySet()) {
            String[] parts = entry.getKey().split("::", 2);
            long[] values = entry.getValue();
            ObjectNode modelNode = WebUtils.MAPPER.createObjectNode();
            modelNode.put("provider", parts.length > 0 ? parts[0] : "unknown");
            modelNode.put("model", parts.length > 1 ? parts[1] : "unknown");
            modelNode.put("promptTokens", values[0]);
            modelNode.put("completionTokens", values[1]);
            modelNode.put("totalTokens", values[0] + values[1]);
            modelNode.put("callCount", values[2]);
            byModelArray.add(modelNode);
        }
        result.set("byModel", byModelArray);

        // 按日期分组（按日期升序排列）
        ArrayNode byDateArray = WebUtils.MAPPER.createArrayNode();
        stats.byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    long[] values = entry.getValue();
                    ObjectNode dateNode = WebUtils.MAPPER.createObjectNode();
                    dateNode.put("date", entry.getKey());
                    dateNode.put("promptTokens", values[0]);
                    dateNode.put("completionTokens", values[1]);
                    dateNode.put("totalTokens", values[0] + values[1]);
                    dateNode.put("callCount", values[2]);
                    byDateArray.add(dateNode);
                });
        result.set("byDate", byDateArray);

        return result;
    }

    /**
     * 解析 URL 查询字符串为键值对 Map。
     */
    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new java.util.HashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }
}
