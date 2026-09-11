package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronPayload;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 处理定时任务 API（/api/cron）。
 *
 * <p>P6 扩展：</p>
 * <ul>
 *   <li>调度完整暴露 at / every / cron 与时区（tz），创建与更新均支持；</li>
 *   <li>{@code POST /api/cron/preview}：复用服务端调度计算逻辑返回未来 5 次执行时间，
 *       前端不另写 Cron 解释器；</li>
 *   <li>创建接受 P6 payload 扩展字段（runMode/projectId/sourceSessionKey/attachmentIds/outputTarget）；</li>
 *   <li>列表输出补 runMode/projectId/outputTarget 与历史中的 runId/sessionKey/artifactIds/deliveryStatus。</li>
 * </ul>
 */
public class CronHandler extends BaseHandler {

    /** 安全的资料引用 id（附件 id 为 12 位十六进制）。 */
    private static final Pattern SAFE_ATTACHMENT_ID = Pattern.compile("[0-9a-f]{12}");

    /** 单任务资料引用上限（与 ProjectStore 一致）。 */
    private static final int MAX_ATTACHMENT_IDS = 20;

    private final CronService cronService;

    /**
     * 构造 CronHandler，注入全局配置、定时任务服务与安全中间件。
     */
    public CronHandler(Config config, CronService cronService, SecurityMiddleware security) {
        super(config, security);
        this.cronService = cronService;
    }

    /**
     * 按路径分发列表、创建、删除、启停、手动触发或执行预览。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if ((WebUtils.API_CRON + "/preview").equals(path)
                && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handlePreview(exchange, corsOrigin);
        } else if (WebUtils.API_CRON.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
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

        } else if (path.matches(WebUtils.API_CRON + "/[^/]+")
                && WebUtils.HTTP_METHOD_PUT.equals(method)) {
            handleUpdateCron(exchange, path, corsOrigin);

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

        } else if (path.matches(WebUtils.API_CRON + "/[^/]+/run")
                && WebUtils.HTTP_METHOD_POST.equals(method)) {
            String id = path.substring(WebUtils.API_CRON.length() + 1).replace("/run", "");
            boolean triggered = cronService.runJobNow(id);
            if (triggered) {
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Job triggered"), corsOrigin);
            } else {
                WebUtils.sendJson(exchange, 404,
                        WebUtils.errorJson("Job not found or service not running"), corsOrigin);
            }

        } else {
            return false;
        }
        return true;
    }

    /**
     * P6：执行预览——按请求的调度配置（kind + atMs/everyMs/expr + tz）返回未来 5 次执行时间。
     * 复用 CronService 与实际调度同一套计算逻辑；非法 cron 表达式返回 400。
     * 请求体：{kind: "at"|"every"|"cron", atMs?, everySeconds?|everyMs?, cron?, tz?}。
     */
    private void handlePreview(HttpExchange exchange, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        CronSchedule schedule = parseSchedule(json);
        if (schedule == null) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(
                    "schedule is required: {kind: at|every|cron, atMs|everySeconds|cron, tz?}"), corsOrigin);
            return;
        }
        // cron 表达式提前校验：解析失败给 400 而不是空列表（用户能立即发现写错）
        if (CronSchedule.ScheduleKind.CRON == schedule.getKind()
                && cronService.previewNextRuns(schedule, 1).isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(
                    "invalid cron expr: " + schedule.getExpr()), corsOrigin);
            return;
        }
        List<Long> runs = cronService.previewNextRuns(schedule, 5);
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("count", runs.size());
        ArrayNode times = WebUtils.MAPPER.createArrayNode();
        runs.forEach(times::add);
        result.set("nextRuns", times);
        if (schedule.getTz() != null) {
            result.put("tz", schedule.getTz());
        }
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 返回所有定时任务列表，包含 id、name、启用状态、计划表达式、下次运行时间、
     * 上次运行状态及执行历史（含 P6 的 runId/sessionKey/artifactIds/deliveryStatus）。
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
            if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.AT) {
                jobNode.put("schedule", "at " + job.getSchedule().getAtMs());
            } else if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.CRON) {
                jobNode.put("schedule", job.getSchedule().getExpr());
            } else if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.EVERY) {
                jobNode.put("schedule", "every " + (job.getSchedule().getEveryMs() / 1000) + "s");
            }
            // 原始调度字段，供编辑弹窗回填
            jobNode.put("kind", job.getSchedule().getKind().getValue());
            if (job.getSchedule().getExpr() != null) {
                jobNode.put("expr", job.getSchedule().getExpr());
            }
            if (job.getSchedule().getEveryMs() != null) {
                jobNode.put("everyMs", job.getSchedule().getEveryMs());
            }
            if (job.getSchedule().getAtMs() != null) {
                jobNode.put("atMs", job.getSchedule().getAtMs());
            }
            if (job.getSchedule().getTz() != null) {
                jobNode.put("tz", job.getSchedule().getTz());
            }
            if (job.getPayload().getChannel() != null) {
                jobNode.put("channel", job.getPayload().getChannel());
            }
            if (job.getPayload().getTo() != null) {
                jobNode.put("to", job.getPayload().getTo());
            }
            // P6：执行模式与扩展字段
            jobNode.put("runMode", job.getPayload().effectiveRunMode());
            if (job.getPayload().getProjectId() != null) {
                jobNode.put("projectId", job.getPayload().getProjectId());
            }
            if (job.getPayload().getSourceSessionKey() != null) {
                jobNode.put("sourceSessionKey", job.getPayload().getSourceSessionKey());
            }
            if (job.getPayload().getAttachmentIds() != null) {
                jobNode.set("attachmentIds",
                        WebUtils.MAPPER.valueToTree(job.getPayload().getAttachmentIds()));
            }
            if (job.getPayload().getOutputTarget() != null) {
                jobNode.put("outputTarget", job.getPayload().getOutputTarget());
            }
            if (job.getState().getNextRunAtMs() != null) {
                jobNode.put("nextRun", job.getState().getNextRunAtMs());
            }
            if (job.getState().getLastRunAtMs() != null) {
                jobNode.put("lastRun", job.getState().getLastRunAtMs());
            }
            if (job.getState().getLastStatus() != null) {
                jobNode.put("lastStatus", job.getState().getLastStatus());
            }
            if (job.getState().getLastError() != null) {
                jobNode.put("lastError", job.getState().getLastError());
            }
            if (job.getState().getHistory() != null) {
                jobNode.set("history", WebUtils.MAPPER.valueToTree(job.getState().getHistory()));
            }
            result.add(jobNode);
        }
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 解析请求体并更新任务配置（name/message/channel/to/cron/everySeconds/atMs/tz 均为可选）。
     */
    private void handleUpdateCron(HttpExchange exchange, String path, String corsOrigin) throws IOException {
        String id = path.substring(WebUtils.API_CRON.length() + 1);
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);

        CronSchedule schedule = parseSchedule(json);

        CronJob job = cronService.updateJob(id,
                json.has("name") ? json.get("name").asText() : null,
                schedule,
                json.has("message") ? json.get("message").asText() : null,
                json.has("channel") ? json.get("channel").asText() : null,
                json.has("to") ? json.get("to").asText() : null);
        if (job != null) {
            // P6：更新扩展字段（仅更新请求中出现的字段），合入后落盘
            applyP6Fields(job.getPayload(), json);
            cronService.persistJob(job);
            WebUtils.sendJson(exchange, 200, WebUtils.successJson("Job updated"), corsOrigin);
        } else {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Job not found"), corsOrigin);
        }
    }

    /**
     * 解析请求体并创建新定时任务，支持 at / every / cron 三种调度与时区（P6）。
     * 缺少调度字段时返回 400。P6 扩展字段随 payload 一并保存。
     */
    private void handleCreateCron(HttpExchange exchange, String corsOrigin) throws IOException {
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String name = json.path("name").asText();
        String message = json.path("message").asText();
        CronSchedule schedule = parseSchedule(json);
        if (schedule == null) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(
                    "Missing schedule: {kind: at|every|cron, atMs|everySeconds|cron, tz?}"), corsOrigin);
            return;
        }
        if (CronSchedule.ScheduleKind.CRON == schedule.getKind()
                && cronService.previewNextRuns(schedule, 1).isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(
                    "invalid cron expr: " + schedule.getExpr()), corsOrigin);
            return;
        }
        CronPayload payload = new CronPayload(message,
                json.has("channel") ? json.get("channel").asText() : null,
                json.has("to") ? json.get("to").asText() : null);
        applyP6Fields(payload, json);
        CronJob job = cronService.addJob(name, schedule, payload);
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("id", job.getId());
        result.put("nextRun", job.getState().getNextRunAtMs());
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    // ==================== P6 辅助 ====================

    /**
     * 从请求体解析调度：优先显式 kind，否则按 cron / everySeconds / atMs 字段推断
     * （与旧版兼容：旧客户端只传 cron 或 everySeconds）。tz 可选，任意 kind 均可携带。
     */
    private CronSchedule parseSchedule(JsonNode json) {
        String kind = json.path("kind").asText("");
        CronSchedule schedule = null;
        if ("at".equals(kind) || (!kind.isEmpty() && json.has("atMs"))) {
            long atMs = json.path("atMs").asLong(0);
            if (atMs > 0) {
                schedule = CronSchedule.at(atMs);
            }
        } else if ("every".equals(kind) || json.has("everySeconds")) {
            long seconds = json.path("everySeconds").asLong(0);
            if (seconds > 0) {
                schedule = CronSchedule.every(seconds * 1000);
            } else if (json.has("everyMs") && json.path("everyMs").asLong(0) > 0) {
                schedule = CronSchedule.every(json.path("everyMs").asLong());
            }
        } else if ("cron".equals(kind) || json.has("cron")) {
            String expr = json.path("cron").asText("");
            if (!expr.isEmpty()) {
                schedule = CronSchedule.cron(expr);
            }
        }
        if (schedule != null && json.has("tz")) {
            schedule.setTz(json.path("tz").asText(null));
        }
        return schedule;
    }

    /**
     * 合入 P6 扩展字段（仅更新请求中出现的字段）。
     * attachmentIds 逐个校验 12 位十六进制并截断至上限；runMode 仅接受两个合法值。
     */
    private void applyP6Fields(CronPayload payload, JsonNode json) {
        if (json.has("runMode")) {
            String mode = json.path("runMode").asText("");
            if (CronPayload.RUN_MODE_NEW_SESSION.equals(mode)
                    || CronPayload.RUN_MODE_CONTINUE_SESSION.equals(mode)) {
                payload.setRunMode(mode);
            }
        }
        if (json.has("projectId")) {
            String pid = json.path("projectId").asText("");
            payload.setProjectId(pid.isBlank() ? null : pid.trim());
        }
        if (json.has("sourceSessionKey")) {
            String key = json.path("sourceSessionKey").asText("");
            payload.setSourceSessionKey(key.isBlank() ? null : key.trim());
        }
        if (json.has("attachmentIds") && json.path("attachmentIds").isArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonNode n : json.path("attachmentIds")) {
                String id = n.asText("");
                if (SAFE_ATTACHMENT_ID.matcher(id).matches() && !ids.contains(id)) {
                    ids.add(id);
                }
                if (ids.size() >= MAX_ATTACHMENT_IDS) {
                    break;
                }
            }
            payload.setAttachmentIds(ids.isEmpty() ? null : ids);
        }
        if (json.has("outputTarget")) {
            String target = json.path("outputTarget").asText("");
            payload.setOutputTarget(target.isBlank() ? null
                    : (target.length() > 2000 ? target.substring(0, 2000) : target));
        }
    }
}
