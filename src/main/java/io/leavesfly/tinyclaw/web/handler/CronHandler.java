package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 处理定时任务 API（/api/cron）。
 */
public class CronHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final CronService cronService;
    private final SecurityMiddleware security;

    /**
     * 构造 CronHandler，注入全局配置、定时任务服务与安全中间件。
     */
    public CronHandler(Config config, CronService cronService, SecurityMiddleware security) {
        this.config = config;
        this.cronService = cronService;
        this.security = security;
    }

    /**
     * 入口路由：预检通过后，按路径分发列表、创建、删除或启停操作。
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_CRON.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleListCron(exchange, corsOrigin);

            } else if (WebUtils.API_CRON.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleCreateCron(exchange, corsOrigin);

            } else if (path.matches(WebUtils.API_CRON + "/[^/]+")
                    && WebUtils.HTTP_METHOD_DELETE.equals(method)) {
                String id = path.substring(WebUtils.API_CRON.length() + 1);
                boolean removed = cronService.removeJob(id);
                if (removed) {
                    WebUtils.sendJson(exchange, 200, WebUtils.successJson("Job removed"), corsOrigin);
                } else {
                    WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Job not found"), corsOrigin);
                }

            } else if (path.matches(WebUtils.API_CRON + "/[^/]+/enable")
                    && WebUtils.HTTP_METHOD_PUT.equals(method)) {
                String id = path.substring(WebUtils.API_CRON.length() + 1).replace("/enable", "");
                String body = WebUtils.readRequestBodyLimited(exchange);
                JsonNode json = WebUtils.MAPPER.readTree(body);
                boolean enabled = json.path("enabled").asBoolean(true);
                CronJob job = cronService.enableJob(id, enabled);
                if (job != null) {
                    WebUtils.sendJson(exchange, 200,
                            WebUtils.successJson("Job " + (enabled ? "enabled" : "disabled")), corsOrigin);
                } else {
                    WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Job not found"), corsOrigin);
                }

            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Cron API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    /**
     * 返回所有定时任务列表，包含 id、name、启用状态、计划表达式及下次运行时间。
     */
    private void handleListCron(HttpExchange exchange, String corsOrigin) throws IOException {
        List<CronJob> jobs = cronService.listJobs(true);
        ArrayNode result = WebUtils.MAPPER.createArrayNode();
        for (CronJob job : jobs) {
            ObjectNode jobNode = WebUtils.MAPPER.createObjectNode();
            jobNode.put("id", job.getId());
            jobNode.put("name", job.getName());
            jobNode.put("enabled", job.isEnabled());
            jobNode.put("message", job.getPayload().getMessage());
            if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.CRON) {
                jobNode.put("schedule", job.getSchedule().getExpr());
            } else if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.EVERY) {
                jobNode.put("schedule", "every " + (job.getSchedule().getEveryMs() / 1000) + "s");
            }
            if (job.getState().getNextRunAtMs() != null) {
                jobNode.put("nextRun", job.getState().getNextRunAtMs());
            }
            result.add(jobNode);
        }
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 解析请求体并创建新定时任务，支持 cron 表达式与固定间隔两种方式。
     * 缺少 schedule 字段时返回 400。
     */
    private void handleCreateCron(HttpExchange exchange, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String name = json.path("name").asText();
        String message = json.path("message").asText();
        CronSchedule schedule;
        if (json.has("cron")) {
            schedule = CronSchedule.cron(json.get("cron").asText());
        } else if (json.has("everySeconds")) {
            schedule = CronSchedule.every(json.get("everySeconds").asLong() * 1000);
        } else {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("Missing schedule"), corsOrigin);
            return;
        }
        String channel = json.has("channel") ? json.get("channel").asText() : null;
        String to = json.has("to") ? json.get("to").asText() : null;
        CronJob job = cronService.addJob(name, schedule, message, channel, to);
        WebUtils.sendJson(exchange, 200,
                WebUtils.MAPPER.valueToTree(Map.of("id", job.getId())), corsOrigin);
    }
}
