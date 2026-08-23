package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理进化功能状态查询 API（/api/feedback）。
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
     * 返回进化功能状态。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!WebUtils.HTTP_METHOD_GET.equals(method)) {
            return false;
        }
        handleGetStatus(exchange);
        return true;
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
