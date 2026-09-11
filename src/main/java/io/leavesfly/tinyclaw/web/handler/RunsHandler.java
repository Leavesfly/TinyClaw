package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.session.RunRecord;
import io.leavesfly.tinyclaw.session.RunRegistry;
import io.leavesfly.tinyclaw.tools.InteractionBroker;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 处理执行记录 API（P1：/api/runs）。
 *
 * <p>三个端点支撑刷新恢复闭环：</p>
 * <ul>
 *   <li>{@code GET /api/runs?sessionId=&clientRequestId=}：提交结果查询与执行列表；
 *       断线后状态未知时先查这里，禁止直接重复提交；</li>
 *   <li>{@code GET /api/runs/{id}}：单次执行状态（含状态机各时间戳与失败摘要）；</li>
 *   <li>{@code GET /api/runs/{id}/interactions}：该执行所属会话的待确认交互，
 *       刷新后据此重建审批卡。</li>
 * </ul>
 *
 * <p>鉴权由 {@link BaseHandler} 外壳无条件执行；本 Handler 只做只读查询。</p>
 */
public class RunsHandler extends BaseHandler {

    private static final String API_RUNS = "/api/runs";
    private static final int LIST_LIMIT = 20;

    private final RunRegistry runRegistry;
    private final AgentRuntime agentRuntime;

    /**
     * 构造 RunsHandler。
     *
     * @param runRegistry  执行登记处（可为 null：能力未启用时端点返回明确说明）
     * @param agentRuntime Agent 运行时（用于取 InteractionBroker 查询待确认交互）
     */
    public RunsHandler(Config config, SecurityMiddleware security,
                       RunRegistry runRegistry, AgentRuntime agentRuntime) {
        super(config, security);
        this.runRegistry = runRegistry;
        this.agentRuntime = agentRuntime;
    }

    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!path.startsWith(API_RUNS) || !WebUtils.HTTP_METHOD_GET.equals(method)) {
            return false;
        }
        String sub = path.substring(API_RUNS.length());
        if (sub.isEmpty() || sub.equals("/")) {
            handleList(exchange, corsOrigin);
        } else if (sub.equals("/pending") && querySessionId(exchange) != null) {
            // 便捷端点：按会话直接查待确认交互（前端恢复横幅用，无需先查 run）
            handlePendingForSession(exchange, corsOrigin);
        } else if (sub.startsWith("/")) {
            String rest = sub.substring(1);
            int slash = rest.indexOf('/');
            String runId = slash < 0 ? rest : rest.substring(0, slash);
            if (slash < 0) {
                handleGet(exchange, corsOrigin, runId);
            } else if (rest.substring(slash).equals("/interactions")) {
                handleRunInteractions(exchange, corsOrigin, runId);
            } else {
                return false;
            }
        } else {
            return false;
        }
        return true;
    }

    /** GET /api/runs?sessionId=&clientRequestId= */
    private void handleList(HttpExchange exchange, String corsOrigin) throws IOException {
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        if (runRegistry == null) {
            result.put("enabled", false);
            result.put("message", "Run registry is not available");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }
        String sessionId = querySessionId(exchange);
        String clientRequestId = queryParam(exchange, "clientRequestId");

        // 幂等键优先：断线重连场景查提交结果
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            RunRecord record = runRegistry.findByClientRequestId(clientRequestId);
            result.put("enabled", true);
            if (record != null) {
                result.set("run", toJson(record));
            } else {
                result.putNull("run");
            }
            if (sessionId != null) {
                result.set("runs", toArray(runRegistry.listBySession(sessionId, LIST_LIMIT)));
            }
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }

        if (sessionId == null || sessionId.isBlank()) {
            WebUtils.sendJson(exchange, 400,
                    WebUtils.errorJson("sessionId or clientRequestId is required"), corsOrigin);
            return;
        }
        result.put("enabled", true);
        result.set("runs", toArray(runRegistry.listBySession(sessionId, LIST_LIMIT)));
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /** GET /api/runs/{id} */
    private void handleGet(HttpExchange exchange, String corsOrigin, String runId) throws IOException {
        if (runRegistry == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Run registry is not available"), corsOrigin);
            return;
        }
        RunRecord record = runRegistry.get(runId);
        if (record == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Run not found: " + runId), corsOrigin);
            return;
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("enabled", true);
        result.set("run", toJson(record));
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /** GET /api/runs/{id}/interactions：该执行所属会话的待确认交互。 */
    private void handleRunInteractions(HttpExchange exchange, String corsOrigin, String runId) throws IOException {
        InteractionBroker broker = broker();
        if (runRegistry == null || broker == null) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("interactions", WebUtils.MAPPER.createArrayNode());
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }
        RunRecord record = runRegistry.get(runId);
        if (record == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Run not found: " + runId), corsOrigin);
            return;
        }
        sendPending(exchange, corsOrigin, broker.pendingForSession(record.sessionKey));
    }

    /** GET /api/runs/pending?sessionId=：按会话直接查待确认交互。 */
    private void handlePendingForSession(HttpExchange exchange, String corsOrigin) throws IOException {
        InteractionBroker broker = broker();
        if (broker == null) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("interactions", WebUtils.MAPPER.createArrayNode());
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }
        sendPending(exchange, corsOrigin, broker.pendingForSession(querySessionId(exchange)));
    }

    private void sendPending(HttpExchange exchange, String corsOrigin,
                             List<InteractionBroker.PendingInfo> infos) throws IOException {
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        ArrayNode arr = WebUtils.MAPPER.createArrayNode();
        long now = System.currentTimeMillis();
        for (InteractionBroker.PendingInfo info : infos) {
            // 过期项不再下发：工具线程按超时处理在即，避免前端渲染必然失效的卡片
            if (info.expiresAt <= now) {
                continue;
            }
            ObjectNode node = WebUtils.MAPPER.createObjectNode();
            node.put("requestId", info.requestId);
            node.put("type", info.type);
            node.put("prompt", info.prompt != null ? info.prompt : "");
            node.put("reason", info.reason != null ? info.reason : "");
            node.set("options", WebUtils.MAPPER.valueToTree(info.options));
            node.put("createdAt", info.createdAt);
            node.put("expiresAt", info.expiresAt);
            arr.add(node);
        }
        result.set("interactions", arr);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    private InteractionBroker broker() {
        return agentRuntime != null ? agentRuntime.getInteractionBroker() : null;
    }

    /** RunRecord → JSON（只读视图）。 */
    private ObjectNode toJson(RunRecord r) {
        ObjectNode node = WebUtils.MAPPER.createObjectNode();
        node.put("id", r.id);
        node.put("clientRequestId", r.clientRequestId != null ? r.clientRequestId : "");
        node.put("sessionKey", r.sessionKey);
        node.put("status", r.status);
        node.put("createdAt", r.createdAt);
        node.put("updatedAt", r.updatedAt);
        if (r.endedAt != null) {
            node.put("endedAt", r.endedAt);
        }
        node.put("messagePreview", r.messagePreview != null ? r.messagePreview : "");
        node.put("errorSummary", r.errorSummary != null ? r.errorSummary : "");
        node.put("running", !r.isTerminal());
        return node;
    }

    private ArrayNode toArray(List<RunRecord> records) {
        ArrayNode arr = WebUtils.MAPPER.createArrayNode();
        for (RunRecord r : records) {
            arr.add(toJson(r));
        }
        return arr;
    }

    /** 从查询串解析首个命名参数（URL 解码），缺失返回 null。 */
    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith(name + "=")) {
                String raw = param.substring(name.length() + 1);
                if (raw.isEmpty()) {
                    return null;
                }
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String querySessionId(HttpExchange exchange) {
        return queryParam(exchange, "sessionId");
    }
}
