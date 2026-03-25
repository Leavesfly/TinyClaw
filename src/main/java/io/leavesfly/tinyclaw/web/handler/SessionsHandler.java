package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.Session;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.session.ToolCallRecord;
import io.leavesfly.tinyclaw.agent.collaboration.AgentMessage;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationRecord;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理会话管理 API（/api/sessions）。
 */
public class SessionsHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SessionManager sessionManager;
    private final SecurityMiddleware security;
    private final String collaborationDir;

    /**
     * 构造 SessionsHandler，注入全局配置、会话管理器与安全中间件。
     *
     * @param config         全局配置
     * @param sessionManager 会话管理器
     * @param security       安全中间件
     * @param workspacePath  工作区路径，用于加载协同记录（可为 null）
     */
    public SessionsHandler(Config config, SessionManager sessionManager,
                           SecurityMiddleware security, String workspacePath) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.security = security;
        this.collaborationDir = workspacePath != null
                ? Paths.get(workspacePath, "collaboration").toString() : null;
    }

    /**
     * 入口路由：预检通过后，按路径分发列表查询、历史记录获取或删除操作。
     * 路径中的 sessionKey 使用 URL 解码处理。
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_SESSIONS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                ArrayNode sessions = WebUtils.MAPPER.createArrayNode();
                for (String key : sessionManager.getSessionKeys()) {
                    var history = sessionManager.getHistory(key);
                    ObjectNode session = WebUtils.MAPPER.createObjectNode();
                    session.put("key", key);
                    session.put("messageCount", history.size());
                    // 取第一条 user 消息作为会话预览标题
                    String firstMessage = history.stream()
                            .filter(m -> "user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank())
                            .findFirst()
                            .map(m -> m.getContent().length() > 15 ? m.getContent().substring(0, 15) + "…" : m.getContent())
                            .orElse("");
                    session.put("firstMessage", firstMessage);
                    sessions.add(session);
                }
                WebUtils.sendJson(exchange, 200, sessions, corsOrigin);

            } else if (WebUtils.API_SESSIONS.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
                String body = WebUtils.readRequestBodyLimited(exchange);
                JsonNode json = WebUtils.MAPPER.readTree(body);
                String sessionKey = json.path("sessionKey").asText();
                if (sessionKey == null || sessionKey.isBlank()) {
                    WebUtils.sendJson(exchange, 400, WebUtils.errorJson("sessionKey is required"), corsOrigin);
                    return;
                }
                Session session = sessionManager.getOrCreate(sessionKey);
                sessionManager.save(session);
                ObjectNode result = WebUtils.MAPPER.createObjectNode();
                result.put("key", sessionKey);
                result.put("messageCount", 0);
                WebUtils.sendJson(exchange, 200, result, corsOrigin);

            } else if (path.startsWith(WebUtils.API_SESSIONS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_GET.equals(method)) {
                String key = URLDecoder.decode(
                        path.substring(WebUtils.API_SESSIONS.length() + 1), StandardCharsets.UTF_8);
                var history = sessionManager.getHistory(key);
                var toolCallRecords = sessionManager.getToolCallRecords(key);
                var summary = sessionManager.getSummary(key);

                // 按 messageIndex（assistant 消息在 history 中的绝对位置索引）分组工具调用记录
                Map<Integer, List<ToolCallRecord>> recordsByIndex = new HashMap<>();
                for (ToolCallRecord record : toolCallRecords) {
                    recordsByIndex
                            .computeIfAbsent(record.getMessageIndex(), idx -> new ArrayList<>())
                            .add(record);
                }

                ArrayNode messages = WebUtils.MAPPER.createArrayNode();
                // 如果会话有摘要（说明历史已被压缩），在消息列表最前面插入一条虚拟摘要消息，
                // 前端检测到 role=summary 时渲染为摘要提示卡片，告知用户前面有内容已被压缩
                if (summary != null && !summary.isBlank()) {
                    ObjectNode summaryMsg = WebUtils.MAPPER.createObjectNode();
                    summaryMsg.put("role", "summary");
                    summaryMsg.put("content", summary);
                    messages.add(summaryMsg);
                }
                int msgIdx = 0;
                for (var msg : history) {
                    ObjectNode m = WebUtils.MAPPER.createObjectNode();
                    m.put("role", msg.getRole());
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    // 添加图片字段（多模态支持）
                    if (msg.hasImages()) {
                        ArrayNode imagesArray = m.putArray("images");
                        for (String imgPath : msg.getImages()) {
                            imagesArray.add(imgPath);
                        }
                    }
                    // assistant 消息：按绝对位置索引附带其触发的工具调用记录
                    if ("assistant".equals(msg.getRole())) {
                        var records = recordsByIndex.get(msgIdx);
                        if (records != null && !records.isEmpty()) {
                            ArrayNode toolCallsArray = m.putArray("toolCallRecords");
                            for (ToolCallRecord record : records) {
                                ObjectNode r = WebUtils.MAPPER.createObjectNode();
                                r.put("toolName", record.getToolName());
                                r.put("argsSummary", record.getArgsSummary());
                                r.put("resultSummary", record.getResultSummary());
                                r.put("success", record.isSuccess());
                                // collaborate 工具调用：附带协同过程详情
                                if ("collaborate".equals(record.getToolName())) {
                                    appendCollaborationDetailToToolRecord(r, record.getArgsSummary());
                                }
                                toolCallsArray.add(r);
                            }
                        }
                    }
                    messages.add(m);
                    msgIdx++;
                }
                WebUtils.sendJson(exchange, 200, messages, corsOrigin);

            } else if (path.startsWith(WebUtils.API_SESSIONS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_DELETE.equals(method)) {
                String key = URLDecoder.decode(
                        path.substring(WebUtils.API_SESSIONS.length() + 1), StandardCharsets.UTF_8);
                sessionManager.deleteSession(key);
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Session deleted"), corsOrigin);

            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Sessions API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    /**
     * 为 collaborate 工具调用记录附加协同过程详情。
     * 从 collaboration 目录加载匹配的协同记录，将多 Agent 对话历史、
     * 参与者、统计指标等嵌入到工具调用记录的 collaborationDetail 字段中。
     *
     * <p>匹配策略：从 collaborate 工具的 argsSummary 中提取 topic/goal，
     * 与协同记录的 goal 进行匹配。
     *
     * @param toolRecordNode collaborate 工具调用记录的 JSON 节点
     * @param argsSummary    工具调用参数摘要
     */
    private void appendCollaborationDetailToToolRecord(ObjectNode toolRecordNode, String argsSummary) {
        if (collaborationDir == null) {
            logger.info("collaborationDir is null, skip", Map.of());
            return;
        }

        logger.info("Loading collaboration records", Map.of("dir", collaborationDir));
        List<CollaborationRecord> allRecords = CollaborationRecord.loadAll(collaborationDir);
        logger.info("Loaded collaboration records", Map.of("count", allRecords.size(), "dir", collaborationDir));
        if (allRecords.isEmpty()) {
            return;
        }

        // 从 argsSummary 中提取 topic 关键词进行匹配
        // argsSummary 格式示例: {mode=debate, topic=AI 会毁灭人类, roles=[...]}
        CollaborationRecord matchedRecord = null;
        for (CollaborationRecord record : allRecords) {
            if (record.getGoal() != null && argsSummary != null
                    && argsSummary.contains(record.getGoal())) {
                matchedRecord = record;
                break;
            }
        }

        // 降级：取最新的协同记录（按 endTime 排序）
        if (matchedRecord == null && !allRecords.isEmpty()) {
            matchedRecord = allRecords.stream()
                    .max(java.util.Comparator.comparingLong(CollaborationRecord::getEndTime))
                    .orElse(null);
        }

        if (matchedRecord == null) {
            return;
        }

        ObjectNode detail = buildCollaborationDetailNode(matchedRecord);
        toolRecordNode.set("collaborationDetail", detail);
    }

    /**
     * 将 CollaborationRecord 构建为 JSON 节点，包含模式、目标、参与者、
     * 多 Agent 对话历史和统计指标。
     */
    private ObjectNode buildCollaborationDetailNode(CollaborationRecord record) {
        ObjectNode detail = WebUtils.MAPPER.createObjectNode();
        detail.put("mode", record.getMode());
        detail.put("goal", record.getGoal() != null ? record.getGoal() : "");
        detail.put("conclusion", record.getConclusion() != null ? record.getConclusion() : "");
        detail.put("totalRounds", record.getTotalRounds());
        detail.put("status", record.getStatus() != null ? record.getStatus() : "");
        detail.put("startTime", record.getStartTime());
        detail.put("endTime", record.getEndTime());

        // 参与者列表
        if (record.getParticipants() != null && !record.getParticipants().isEmpty()) {
            ArrayNode participantsArray = detail.putArray("participants");
            for (String participant : record.getParticipants()) {
                participantsArray.add(participant);
            }
        }

        // 多 Agent 对话历史
        if (record.getMessages() != null && !record.getMessages().isEmpty()) {
            ArrayNode agentMessages = detail.putArray("agentMessages");
            for (AgentMessage agentMsg : record.getMessages()) {
                ObjectNode agentMsgNode = WebUtils.MAPPER.createObjectNode();
                agentMsgNode.put("agentId", agentMsg.getAgentId() != null ? agentMsg.getAgentId() : "");
                agentMsgNode.put("agentRole", agentMsg.getAgentRole() != null ? agentMsg.getAgentRole() : "");
                agentMsgNode.put("content", agentMsg.getContent() != null ? agentMsg.getContent() : "");
                agentMsgNode.put("timestamp", agentMsg.getTimestamp());
                if (agentMsg.getMessageType() != null) {
                    agentMsgNode.put("messageType", agentMsg.getMessageType().name());
                }
                if (agentMsg.getTargetRole() != null) {
                    agentMsgNode.put("targetRole", agentMsg.getTargetRole());
                }
                agentMessages.add(agentMsgNode);
            }
        }

        // 统计指标
        if (record.getMetrics() != null && !record.getMetrics().isEmpty()) {
            ObjectNode metricsNode = WebUtils.MAPPER.valueToTree(record.getMetrics());
            detail.set("metrics", metricsNode);
        }

        return detail;
    }
}
