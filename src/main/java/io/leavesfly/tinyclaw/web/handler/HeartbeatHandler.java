package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatRunner;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;

/**
 * 处理心跳 API（/api/heartbeat）。
 *
 * <p>GET 返回心跳启用状态与各 agent 最近一次运行信息；
 * POST /api/heartbeat/now 手动触发一次心跳（异步执行）。</p>
 */
public class HeartbeatHandler extends BaseHandler {

    private final HeartbeatRunner heartbeatRunner;

    /**
     * 构造 HeartbeatHandler。
     *
     * @param config 全局配置
     * @param security 安全中间件
     * @param heartbeatRunner 心跳运行器（可为 null，此时 API 返回禁用状态）
     */
    public HeartbeatHandler(Config config, SecurityMiddleware security, HeartbeatRunner heartbeatRunner) {
        super(config, security);
        this.heartbeatRunner = heartbeatRunner;
    }

    /**
     * GET 查询状态，POST /now 手动触发。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
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
                return true;
            }
            heartbeatRunner.runNow();
            WebUtils.sendJson(exchange, 200, WebUtils.successJson("Heartbeat triggered"), corsOrigin);

        } else {
            return false;
        }
        return true;
    }
}
