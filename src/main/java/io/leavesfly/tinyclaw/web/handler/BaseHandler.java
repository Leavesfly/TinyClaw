package io.leavesfly.tinyclaw.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.Map;

/**
 * API Handler 模板基类。
 *
 * <p>此前每个 Handler 的 {@code handle} 都手写同一段外壳：调用 {@code security.preCheck}、
 * 取 path/method/corsOrigin、包一层 try/catch、未命中回 404、异常回 500。本类把这段外壳
 * 收敛为模板方法，子类只实现 {@link #route} 里真正的分发逻辑。</p>
 *
 * <h2>鉴权从纪律变默认</h2>
 * <p>{@link #handle} 是 {@code final} 的，鉴权由基类无条件执行：新增 Handler 时
 * <b>不存在“忘记调 preCheck”这条失败路径</b>。需要放宽的端点必须显式覆盖
 * {@link #authorize} 并说明理由，把例外变成可见的、需要交代的决定。</p>
 *
 * <h2>与原实现的一处差异</h2>
 * <p>鉴权被移入 try 内：原先 {@code preCheck} 抛出的 IOException 会穿透到 HttpServer，
 * 客户端只会看到连接被重置；现在统一转成 500 响应。</p>
 */
public abstract class BaseHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /** 类名后缀，用于从类名推导日志中的 API 名称。 */
    private static final String HANDLER_SUFFIX = "Handler";

    protected final Config config;
    protected final SecurityMiddleware security;

    protected BaseHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    /**
     * 请求入口：鉴权 → 路由分发 → 未命中回 404 → 异常回 500。
     *
     * <p>故意声明为 {@code final}：外壳的执行顺序（尤其是鉴权先于业务）不允许子类改写。</p>
     */
    public final void handle(HttpExchange exchange) throws IOException {
        String corsOrigin = corsOrigin();
        // 在 try 外取 path：鉴权环节报错时日志也能带上是哪个路径出的问题
        String path = exchange.getRequestURI().getPath();
        try {
            if (!authorize(exchange)) {
                return;
            }
            if (!route(exchange, path, exchange.getRequestMethod(), corsOrigin)) {
                WebUtils.sendNotFound(exchange, corsOrigin);
            }
        } catch (Exception e) {
            logger.error(apiName() + " API error",
                    Map.of("error", String.valueOf(e.getMessage()), "path", path));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(errorMessage(e)), corsOrigin);
        }
    }

    /**
     * 500 响应体里对外暴露的错误文案，默认直接给异常消息。
     *
     * <p>敏感端点（如认证）应覆盖为固定文案，避免把内部细节泄露给未登录调用方。
     * 完整异常信息无论如何都会进日志。</p>
     */
    protected String errorMessage(Exception e) {
        return e.getMessage();
    }

    /**
     * 路由分发，由子类实现。
     *
     * @return {@code true} 表示请求已被处理；{@code false} 表示路径/方法未命中，
     *         由基类统一回 404 —— 子类不必自己写兜底分支
     */
    protected abstract boolean route(HttpExchange exchange, String path, String method,
                                     String corsOrigin) throws Exception;

    /**
     * 鉴权：默认执行完整预检（CORS 预检 + 认证 + 限流）。
     *
     * <p>覆盖此方法即等于声明“这个端点不需要标准鉴权”，覆盖时请在 Javadoc 里写明原因。</p>
     *
     * @return {@code false} 表示请求已被鉴权环节终结（响应已写出），不再进入路由
     */
    protected boolean authorize(HttpExchange exchange) throws IOException {
        return security.preCheck(exchange);
    }

    /**
     * 日志中使用的 API 名称，默认由类名去掉 {@code Handler} 后缀得到。
     */
    protected String apiName() {
        String simpleName = getClass().getSimpleName();
        return simpleName.endsWith(HANDLER_SUFFIX)
                ? simpleName.substring(0, simpleName.length() - HANDLER_SUFFIX.length())
                : simpleName;
    }

    /**
     * 当前配置的 CORS 允许源。
     */
    protected String corsOrigin() {
        return config.getGateway().getCorsOrigin();
    }
}
