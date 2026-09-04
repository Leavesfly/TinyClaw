package io.leavesfly.tinyclaw.web;

import com.sun.net.httpserver.HttpServer;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatRunner;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;
import io.leavesfly.tinyclaw.web.handler.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Web 控制台服务器，提供基于 HTTP 的 Web 管理界面。
 *
 * 职责：HTTP 服务生命周期管理 + API 路由注册。
 * 各业务逻辑已拆分至 handler/ 子包及 SecurityMiddleware / WebUtils 中。
 */
public class WebConsoleServer {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    private static final int THREAD_POOL_SIZE  = 8;
    private static final int SERVER_STOP_DELAY = 2;

    private final String host;
    private final int port;
    private final Config config;
    private final AgentRuntime agentRuntime;
    private final SessionManager sessionManager;
    private final CronService cronService;
    private final SkillsLoader skillsLoader;
    private final HeartbeatRunner heartbeatRunner;
    private HttpServer httpServer;

    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner) {
        this.host = host;
        this.port = port;
        this.config = config;
        this.agentRuntime = agentRuntime;
        this.sessionManager = sessionManager;
        this.cronService = cronService;
        this.skillsLoader = skillsLoader;
        this.heartbeatRunner = heartbeatRunner;
    }

    /**
     * 启动 Web 服务器。
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));

        SecurityMiddleware security = new SecurityMiddleware(config);
        ProvidersHandler providersHandler = new ProvidersHandler(config, security);

        registerApiEndpoints(security, providersHandler);
        registerStaticHandler();

        httpServer.start();
        logger.info("Web Console Server started", Map.of("host", host, "port", port));
    }

    /**
     * 停止 Web 服务器。
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(SERVER_STOP_DELAY);
            shutdownExecutor();
            logger.info("Web Console Server stopped");
        }
    }

    private void registerApiEndpoints(SecurityMiddleware security, ProvidersHandler providersHandler) {
        httpServer.createContext("/api/auth",          new AuthHandler(config, security)::handle);
        httpServer.createContext(WebUtils.API_CHAT,      new ChatHandler(config, agentRuntime, security)::handle);
        httpServer.createContext(WebUtils.API_CHAT_ABORT, new ChatHandler(config, agentRuntime, security)::handle);
        httpServer.createContext(WebUtils.API_CHAT_STATUS, new ChatHandler(config, agentRuntime, security)::handle);
        httpServer.createContext(WebUtils.API_CHAT_INTERACTION, new ChatHandler(config, agentRuntime, security)::handle);
        httpServer.createContext(WebUtils.API_CHANNELS,  new ChannelsHandler(config, security,
                agentRuntime != null ? agentRuntime.getChannelManager() : null)::handle);
        httpServer.createContext(WebUtils.API_SESSIONS,  new SessionsHandler(config, sessionManager, security, config.getWorkspacePath())::handle);
        httpServer.createContext(WebUtils.API_CRON,      new CronHandler(config, cronService, security)::handle);
        httpServer.createContext(WebUtils.API_WORKSPACE, new WorkspaceHandler(config, security)::handle);
        httpServer.createContext(WebUtils.API_SKILLS,    new SkillsHandler(config, skillsLoader, security)::handle);
        httpServer.createContext(WebUtils.API_PROVIDERS, providersHandler::handle);
        httpServer.createContext(WebUtils.API_MODELS,    new ModelsHandler(config, security, providersHandler)::handle);
        httpServer.createContext(WebUtils.API_CONFIG,    new ConfigHandler(config, security, providersHandler, agentRuntime)::handle);
        httpServer.createContext(WebUtils.API_FEEDBACK,  new FeedbackHandler(config, agentRuntime, security)::handle);
        httpServer.createContext(WebUtils.API_MCP,       new MCPHandler(config, security)::handle);
        // 多模态支持：文件上传和静态文件服务
        httpServer.createContext(WebUtils.API_UPLOAD,    new UploadHandler(config, security)::handle);
        httpServer.createContext(WebUtils.API_FILES,     new FilesHandler(config, security)::handle);
        // Token 消耗统计
        TokenUsageStore tokenUsageStore = new TokenUsageStore(config.getWorkspacePath());
        httpServer.createContext(WebUtils.API_TOKEN_STATS, new TokenStatsHandler(config, tokenUsageStore, security)::handle);

        // Reflection 2.0：工具健康面板 + HITL 审批（仅在组件可用时注册）
        ReflectionHandler reflectionHandler = new ReflectionHandler(config, security);
        if (agentRuntime != null && agentRuntime.getToolHealthAggregator() != null) {
            reflectionHandler.setComponents(
                    agentRuntime.getToolHealthAggregator(),
                    agentRuntime.getReflectionEngine(),
                    agentRuntime.getRepairApplier());
        }
        httpServer.createContext(WebUtils.API_REFLECTION, reflectionHandler::handle);

        // 心跳状态与手动触发（仅在 gateway 启用心跳时可用）
        httpServer.createContext(WebUtils.API_HEARTBEAT,
                new HeartbeatHandler(config, security, heartbeatRunner)::handle);

        // 长期记忆管理（provider 就绪后可用，否则返回 501）
        httpServer.createContext(WebUtils.API_MEMORY,
                new MemoryHandler(config, security, agentRuntime)::handle);
    }

    private void registerStaticHandler() {
        httpServer.createContext(WebUtils.PATH_ROOT, new StaticHandler()::handle);
    }

    private void shutdownExecutor() {
        if (httpServer.getExecutor() != null) {
            ((java.util.concurrent.ExecutorService) httpServer.getExecutor()).shutdown();
        }
    }
}
