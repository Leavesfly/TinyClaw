package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.evolution.reflection.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reflection 2.0 Web API Handler（/api/reflection）。
 *
 * <p>提供以下端点：
 * <ul>
 *   <li><b>GET /api/reflection</b> — 总览：健康面板 + 引擎统计 + 修复统计</li>
 *   <li><b>GET /api/reflection/health</b> — 所有工具的健康度列表</li>
 *   <li><b>GET /api/reflection/proposals</b> — 所有修复提案列表</li>
 *   <li><b>POST /api/reflection/proposals/{id}/approve</b> — 审批通过</li>
 *   <li><b>POST /api/reflection/proposals/{id}/reject</b> — 拒绝</li>
 *   <li><b>POST /api/reflection/proposals/{id}/apply</b> — 应用已审批的提案</li>
 *   <li><b>GET /api/reflection/repairs</b> — 已应用修复的概览</li>
 * </ul>
 */
public class ReflectionHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web.reflection");

    private final Config config;
    private final SecurityMiddleware security;

    /**
     * 持有全部 Reflection 组件的不可变快照，保证原子性设置和读取。
     */
    private static final class Components {
        final ToolHealthAggregator aggregator;
        final ReflectionEngine engine;
        final RepairApplier applier;

        Components(ToolHealthAggregator aggregator, ReflectionEngine engine, RepairApplier applier) {
            this.aggregator = aggregator;
            this.engine = engine;
            this.applier = applier;
        }
    }

    /** 原子性地持有所有组件引用（null 表示 Reflection 未启用） */
    private volatile Components components;

    public ReflectionHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    /** 原子性地注入全部 Reflection 组件（在 Provider 初始化后调用）。 */
    public void setComponents(ToolHealthAggregator aggregator, ReflectionEngine engine, RepairApplier applier) {
        this.components = new Components(aggregator, engine, applier);
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            // 路由
            if (path.equals(WebUtils.API_REFLECTION) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleOverview(exchange);
            } else if (path.equals(WebUtils.API_REFLECTION + "/health") && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleHealth(exchange);
            } else if (path.equals(WebUtils.API_REFLECTION + "/proposals") && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleListProposals(exchange);
            } else if (path.startsWith(WebUtils.API_REFLECTION + "/proposals/") && WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleProposalAction(exchange, path);
            } else if (path.equals(WebUtils.API_REFLECTION + "/repairs") && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleRepairs(exchange);
            } else {
                WebUtils.sendNotFound(exchange, corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Reflection API error", Map.of("error", e.getMessage(), "path", path));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    // ==================== GET /api/reflection ====================

    private void handleOverview(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        ObjectNode result = WebUtils.MAPPER.createObjectNode();

        boolean reflectionEnabled = false;
        try {
            reflectionEnabled = config.getAgent().getEvolution().getReflection().isEnabled();
        } catch (NullPointerException ignored) {
            // 配置链路缺失，视为未启用
        }
        result.put("reflectionEnabled", reflectionEnabled);

        Components comps = this.components;
        if (!reflectionEnabled || comps == null || comps.aggregator == null) {
            result.put("message", "Reflection 2.0 is not enabled. Set evolution.reflection.enabled=true in config.");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }

        // 引擎统计
        if (comps.engine != null) {
            ObjectNode engineStats = WebUtils.MAPPER.createObjectNode();
            for (Map.Entry<String, Object> entry : comps.engine.getStats().entrySet()) {
                engineStats.putPOJO(entry.getKey(), entry.getValue());
            }
            result.set("engineStats", engineStats);
        }

        // 修复统计
        if (comps.applier != null) {
            ObjectNode repairStats = WebUtils.MAPPER.createObjectNode();
            for (Map.Entry<String, Object> entry : comps.applier.getStats().entrySet()) {
                repairStats.putPOJO(entry.getKey(), entry.getValue());
            }
            result.set("repairStats", repairStats);
        }

        // 健康度摘要（按成功率排序，只取前 10 个）
        int windowMinutes = 60;
        try {
            windowMinutes = config.getAgent().getEvolution().getReflection().getDetectionWindowMinutes();
        } catch (NullPointerException ignored) {
            // 使用默认值
        }
        List<ToolHealthStat> healthStats = comps.aggregator.queryAll(windowMinutes);
        ArrayNode healthSummary = WebUtils.MAPPER.createArrayNode();
        int limit = Math.min(10, healthStats.size());
        for (int i = 0; i < limit; i++) {
            healthSummary.addPOJO(healthStats.get(i).toMap());
        }
        result.set("healthSummary", healthSummary);

        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    // ==================== GET /api/reflection/health ====================

    private void handleHealth(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        Components comps = this.components;

        if (comps == null || comps.aggregator == null) {
            WebUtils.sendJson(exchange, 200, WebUtils.errorJson("Reflection not enabled"), corsOrigin);
            return;
        }

        int windowMinutes = 60;
        try {
            windowMinutes = config.getAgent().getEvolution().getReflection().getDetectionWindowMinutes();
        } catch (NullPointerException ignored) {
            // 使用默认值
        }
        List<ToolHealthStat> stats = comps.aggregator.queryAll(windowMinutes);

        ArrayNode array = WebUtils.MAPPER.createArrayNode();
        for (ToolHealthStat stat : stats) {
            array.addPOJO(stat.toMap());
        }

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("windowMinutes", windowMinutes);
        result.put("toolCount", stats.size());
        result.set("tools", array);

        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    // ==================== GET /api/reflection/proposals ====================

    private void handleListProposals(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        Components comps = this.components;

        if (comps == null || comps.engine == null) {
            WebUtils.sendJson(exchange, 200, WebUtils.errorJson("Reflection not enabled"), corsOrigin);
            return;
        }

        List<RepairProposal> proposals = comps.engine.getProposals();
        ArrayNode array = WebUtils.MAPPER.createArrayNode();
        for (RepairProposal proposal : proposals) {
            array.addPOJO(proposal.toMap());
        }

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("totalCount", proposals.size());
        result.set("proposals", array);

        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    // ==================== POST /api/reflection/proposals/{id}/{action} ====================

    private void handleProposalAction(HttpExchange exchange, String path) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        Components comps = this.components;

        if (comps == null || comps.engine == null || comps.applier == null) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Reflection not enabled"), corsOrigin);
            return;
        }

        // 解析 path: /api/reflection/proposals/{id}/{action}
        String prefix = WebUtils.API_REFLECTION + "/proposals/";
        if (path.length() <= prefix.length()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Invalid path. Expected: /proposals/{id}/{action}"), corsOrigin);
            return;
        }
        String suffix = path.substring(prefix.length());
        String[] parts = suffix.split("/", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Invalid path. Expected: /proposals/{id}/{action}"), corsOrigin);
            return;
        }

        String proposalId = parts[0];
        String action = parts[1];

        // 读取请求体中的 note（可选字段）
        String note = "";
        try {
            String body = WebUtils.readRequestBodyLimited(exchange);
            if (body != null && !body.isBlank()) {
                JsonNode bodyNode = WebUtils.MAPPER.readTree(body);
                if (bodyNode.has("note")) {
                    note = bodyNode.get("note").asText("");
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse note from request body", Map.of("error", e.getMessage()));
        }

        switch (action) {
            case "approve":
                if (comps.engine.approveProposal(proposalId, note)) {
                    WebUtils.sendJson(exchange, 200, WebUtils.successJson("Proposal approved: " + proposalId), corsOrigin);
                } else {
                    WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Proposal not found or not pending: " + proposalId), corsOrigin);
                }
                break;

            case "reject":
                if (comps.engine.rejectProposal(proposalId, note)) {
                    WebUtils.sendJson(exchange, 200, WebUtils.successJson("Proposal rejected: " + proposalId), corsOrigin);
                } else {
                    WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Proposal not found or not pending: " + proposalId), corsOrigin);
                }
                break;

            case "apply":
                var found = comps.engine.findProposal(proposalId);
                if (found.isEmpty()) {
                    WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Proposal not found: " + proposalId), corsOrigin);
                } else if (found.get().getStatus() != RepairProposal.Status.APPROVED) {
                    WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Proposal must be approved before applying"), corsOrigin);
                } else {
                    boolean applied = comps.applier.apply(found.get());
                    if (applied) {
                        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Proposal applied: " + proposalId), corsOrigin);
                    } else {
                        WebUtils.sendJson(exchange, 500, WebUtils.errorJson("Failed to apply proposal"), corsOrigin);
                    }
                }
                break;

            default:
                WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Unknown action: " + action + ". Expected: approve/reject/apply"), corsOrigin);
        }
    }

    // ==================== GET /api/reflection/repairs ====================

    private void handleRepairs(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        Components comps = this.components;

        if (comps == null || comps.applier == null) {
            WebUtils.sendJson(exchange, 200, WebUtils.errorJson("Reflection not enabled"), corsOrigin);
            return;
        }

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        for (Map.Entry<String, Object> entry : comps.applier.getStats().entrySet()) {
            result.putPOJO(entry.getKey(), entry.getValue());
        }

        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }
}
