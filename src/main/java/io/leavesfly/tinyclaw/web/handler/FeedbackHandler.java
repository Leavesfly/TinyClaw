package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理进化功能状态查询 API（/api/feedback）。
 */
public class FeedbackHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web.feedback");

    private final Config config;
    private final AgentRuntime agentRuntime;
    private final SecurityMiddleware security;

    /**
     * 构造 FeedbackHandler。
     *
     * @param config    全局配置
     * @param agentRuntime Agent 循环执行器
     * @param security  安全中间件
     */
    public FeedbackHandler(Config config, AgentRuntime agentRuntime, SecurityMiddleware security) {
        this.config = config;
        this.agentRuntime = agentRuntime;
        this.security = security;
    }

    /**
     * 入口路由：预检通过后，返回进化功能状态。
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleGetStatus(exchange);
            } else {
                WebUtils.sendNotFound(exchange, corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Feedback API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
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
}
