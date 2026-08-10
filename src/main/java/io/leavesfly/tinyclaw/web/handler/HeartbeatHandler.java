package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatRunner;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * 处理心跳 API（/api/heartbeat）。
 *
 * <p>GET 返回心跳启用状态与各 agent 最近一次运行信息；
 * POST /api/heartbeat/now 手动触发一次心跳（异步执行）。</p>
 */
public class HeartbeatHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;
    private final HeartbeatRunner heartbeatRunner;

    /**
     * 构造 HeartbeatHandler。
     *
     * @param config 全局配置
     * @param security 安全中间件
     * @param heartbeatRunner 心跳运行器（可为 null，此时 API 返回禁用状态）
     */
    public HeartbeatHandler(Config config, SecurityMiddleware security, HeartbeatRunner heartbeatRunner) {
        this.config = config;
        this.security = security;
        this.heartbeatRunner = heartbeatRunner;
    }

    /**
     * 入口路由：GET 查询状态，POST /now 手动触发。
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            boolean enabled = config.getAgent() != null && config.getAgent().isHeartbeatEnabled();

            if (WebUtils.API_HEARTBEAT.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                ObjectNode result = WebUtils.MAPPER.createObjectNode();
                result.put("enabled", enabled);
                if (heartbeatRunner != null) {
                    result.set("lastRuns", WebUtils.MAPPER.valueToTree(heartbeatRunner.getLastRuns()));
                }
                WebUtils.sendJson(exchange, 200, result, corsOrigin);

            } else if ((WebUtils.API_HEARTBEAT + "/now").equals(path)
                    && WebUtils.HTTP_METHOD_POST.equals(method)) {
                if (!enabled || heartbeatRunner == null) {
                    WebUtils.sendJson(exchange, 409, WebUtils.errorJson("Heartbeat is disabled"), corsOrigin);
                    return;
                }
                heartbeatRunner.runNow();
                WebUtils.sendJson(exchange, 200, WebUtils.successJson("Heartbeat triggered"), corsOrigin);

            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Heartbeat API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }
}
