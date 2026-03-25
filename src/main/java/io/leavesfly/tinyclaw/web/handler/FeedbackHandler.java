package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.agent.evolution.FeedbackManager;
import io.leavesfly.tinyclaw.agent.evolution.FeedbackType;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理用户反馈 API（/api/feedback）。
 * 
 * 提供显式反馈收集接口，用于进化模块的评估反馈机制。
 * 
 * 支持的反馈类型：
 * - THUMBS_UP：点赞
 * - THUMBS_DOWN：踩
 * - STAR_RATING：评分（1-5）
 * - TEXT_COMMENT：文字评论
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
     * 入口路由：预检通过后，处理反馈提交。
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleSubmitFeedback(exchange);
            } else if (WebUtils.HTTP_METHOD_GET.equals(method)) {
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
     * 处理反馈提交请求。
     * 
     * 请求格式：
     * {
     *   "sessionId": "会话ID",
     *   "messageId": "消息ID（可选）",
     *   "type": "THUMBS_UP | THUMBS_DOWN | STAR_RATING | TEXT_COMMENT",
     *   "value": 评分值（STAR_RATING 类型时使用，1-5）,
     *   "comment": "文字评论（可选）"
     * }
     */
    private void handleSubmitFeedback(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        
        // 检查进化功能是否启用
        FeedbackManager feedbackManager = agentRuntime.getFeedbackManager();
        if (feedbackManager == null) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", false);
            result.put("message", "反馈功能未启用，请在配置中开启 evolution.feedbackEnabled");
            WebUtils.sendJson(exchange, 400, result, corsOrigin);
            return;
        }

        // 解析请求体
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        
        String sessionId = json.path("sessionId").asText(null);
        String messageId = json.path("messageId").asText(null);
        String typeStr = json.path("type").asText(null);
        int value = json.path("value").asInt(0);
        String comment = json.path("comment").asText(null);

        // 参数校验
        if (sessionId == null || sessionId.isBlank()) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", false);
            result.put("message", "缺少 sessionId 参数");
            WebUtils.sendJson(exchange, 400, result, corsOrigin);
            return;
        }

        if (typeStr == null || typeStr.isBlank()) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", false);
            result.put("message", "缺少 type 参数");
            WebUtils.sendJson(exchange, 400, result, corsOrigin);
            return;
        }

        // 解析反馈类型
        FeedbackType feedbackType;
        try {
            feedbackType = FeedbackType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", false);
            result.put("message", "无效的 type 参数: " + typeStr + "，支持: THUMBS_UP, THUMBS_DOWN, STAR_RATING, TEXT_COMMENT");
            WebUtils.sendJson(exchange, 400, result, corsOrigin);
            return;
        }

        // STAR_RATING 类型需要 value 参数
        if (feedbackType == FeedbackType.STAR_RATING && (value < 1 || value > 5)) {
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", false);
            result.put("message", "STAR_RATING 类型需要 value 参数（1-5）");
            WebUtils.sendJson(exchange, 400, result, corsOrigin);
            return;
        }

        // 记录反馈
        try {
            // 如果是 STAR_RATING 类型，将 value 转换为评论格式
            String effectiveComment = comment;
            if (feedbackType == FeedbackType.STAR_RATING && value > 0) {
                effectiveComment = (comment != null ? comment + " " : "") + "[评分:" + value + "/5]";
            }
            
            feedbackManager.recordExplicitFeedback(sessionId, messageId, feedbackType, effectiveComment);
            
            logger.info("User feedback recorded", Map.of(
                    "sessionId", sessionId,
                    "type", feedbackType.name(),
                    "value", value));

            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", true);
            result.put("message", "反馈已记录，感谢您的评价！");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (Exception e) {
            logger.error("Failed to record feedback", Map.of("error", e.getMessage()));
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("success", false);
            result.put("message", "记录反馈失败: " + e.getMessage());
            WebUtils.sendJson(exchange, 500, result, corsOrigin);
        }
    }

    /**
     * 获取反馈功能状态。
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
