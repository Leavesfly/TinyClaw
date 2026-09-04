package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.evolution.FeedbackManager;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理进化反馈 API（/api/feedback）。
 *
 * <ul>
 *   <li><b>GET /api/feedback</b> — 返回进化功能启用状态与 prompt 优化统计；</li>
 *   <li><b>POST /api/feedback</b> — 提交一条用户显式评价（👍/👎）。</li>
 * </ul>
 */
public class FeedbackHandler extends BaseHandler {

    private final AgentRuntime agentRuntime;

    /**
     * 构造 FeedbackHandler。
     *
     * @param config    全局配置
     * @param agentRuntime Agent 循环执行器
     * @param security  安全中间件
     */
    public FeedbackHandler(Config config, AgentRuntime agentRuntime, SecurityMiddleware security) {
        super(config, security);
        this.agentRuntime = agentRuntime;
    }

    /**
     * GET 返回进化状态；POST 提交用户显式评价。其余方法/路径未命中，由基类回 404。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (WebUtils.HTTP_METHOD_GET.equals(method)) {
            handleGetStatus(exchange);
            return true;
        }
        if (WebUtils.API_FEEDBACK.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleSubmit(exchange);
            return true;
        }
        return false;
    }

    /**
     * 获取进化功能状态。
     */
    private void handleGetStatus(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        
        boolean feedbackEnabled = agentRuntime.getFeedbackManager() != null;
        boolean promptOptEnabled = agentRuntime.getPromptOptimizer() != null;
        
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("feedbackEnabled", feedbackEnabled);
        result.put("promptOptimizationEnabled", promptOptEnabled);
        
        if (feedbackEnabled && agentRuntime.getPromptOptimizer() != null) {
            Map<String, Object> stats = agentRuntime.getPromptOptimizer().getStats();
            ObjectNode statsNode = WebUtils.MAPPER.createObjectNode();
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                statsNode.putPOJO(entry.getKey(), entry.getValue());
            }
            result.set("optimizationStats", statsNode);
        }
        
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 提交一条用户显式评价（👍/👎）。
     *
     * <p>请求体：{@code {"sessionId": "web:default", "rating": "up"|"down", "note": "可选"}}。
     * 进化未启用（FeedbackManager 为 null）时返回 501；rating 非法时返回 400。</p>
     */
    private void handleSubmit(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        FeedbackManager feedbackManager = agentRuntime != null ? agentRuntime.getFeedbackManager() : null;
        if (feedbackManager == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Feedback is not enabled"), corsOrigin);
            return;
        }

        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String sessionId = json.path("sessionId").asText(WebUtils.DEFAULT_SESSION_ID);
        String rating = json.path("rating").asText("");
        String note = json.path("note").asText("");

        if (!"up".equals(rating) && !"down".equals(rating)) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("rating must be 'up' or 'down'"), corsOrigin);
            return;
        }

        feedbackManager.recordExplicitRating(sessionId, "up".equals(rating), note);
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Feedback recorded"), corsOrigin);
    }
}
