package io.leavesfly.tinyclaw.web;

import com.sun.net.httpserver.HttpServer;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatRunner;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.RunRegistry;
import io.leavesfly.tinyclaw.session.SessionFlagsStore;
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
    /** P1：执行登记处（可为 null，表示幂等/恢复能力未启用）。 */
    private final RunRegistry runRegistry;
    /** P2：附件存储（可为 null，表示附件能力未启用）。 */
    private final io.leavesfly.tinyclaw.web.attachment.AttachmentStore attachmentStore;
    /** P3：成果存储（可为 null，表示成果能力未启用）。 */
    private final io.leavesfly.tinyclaw.web.artifact.ArtifactStore artifactStore;
    /** P4：会话整理标记存储（可为 null，表示未启用）。 */
    private final SessionFlagsStore flagsStore;
    /** P4：项目存储（可为 null，表示项目能力未启用）。 */
    private final io.leavesfly.tinyclaw.web.project.ProjectStore projectStore;
    private HttpServer httpServer;

    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner) {
        this(host, port, config, agentRuntime, sessionManager, cronService,
                skillsLoader, heartbeatRunner, null);
    }

    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner,
                            RunRegistry runRegistry) {
        this(host, port, config, agentRuntime, sessionManager, cronService,
                skillsLoader, heartbeatRunner, runRegistry, null);
    }

    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner,
                            RunRegistry runRegistry,
                            io.leavesfly.tinyclaw.web.attachment.AttachmentStore attachmentStore) {
        this(host, port, config, agentRuntime, sessionManager, cronService,
                skillsLoader, heartbeatRunner, runRegistry, attachmentStore, null);
    }

    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner,
                            RunRegistry runRegistry,
                            io.leavesfly.tinyclaw.web.attachment.AttachmentStore attachmentStore,
                            io.leavesfly.tinyclaw.web.artifact.ArtifactStore artifactStore) {
        this(host, port, config, agentRuntime, sessionManager, cronService,
                skillsLoader, heartbeatRunner, runRegistry, attachmentStore, artifactStore, null);
    }

    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner,
                            RunRegistry runRegistry,
                            io.leavesfly.tinyclaw.web.attachment.AttachmentStore attachmentStore,
                            io.leavesfly.tinyclaw.web.artifact.ArtifactStore artifactStore,
                            SessionFlagsStore flagsStore) {
        this(host, port, config, agentRuntime, sessionManager, cronService,
                skillsLoader, heartbeatRunner, runRegistry, attachmentStore, artifactStore,
                flagsStore, null);
    }

    /**
     * 完整构造（P4：附带项目存储）。
     */
    public WebConsoleServer(String host, int port, Config config, AgentRuntime agentRuntime,
                            SessionManager sessionManager,
                            CronService cronService, SkillsLoader skillsLoader,
                            HeartbeatRunner heartbeatRunner,
                            RunRegistry runRegistry,
                            io.leavesfly.tinyclaw.web.attachment.AttachmentStore attachmentStore,
                            io.leavesfly.tinyclaw.web.artifact.ArtifactStore artifactStore,
                            SessionFlagsStore flagsStore,
                            io.leavesfly.tinyclaw.web.project.ProjectStore projectStore) {
        this.host = host;
        this.port = port;
        this.config = config;
        this.agentRuntime = agentRuntime;
        this.sessionManager = sessionManager;
        this.cronService = cronService;
        this.skillsLoader = skillsLoader;
        this.heartbeatRunner = heartbeatRunner;
        this.runRegistry = runRegistry;
        this.attachmentStore = attachmentStore;
        this.artifactStore = artifactStore;
        this.flagsStore = flagsStore;
        this.projectStore = projectStore;
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
        // ChatHandler 单实例复用：四个 context 共享同一状态（P1 执行登记注入点）
        ChatHandler chatHandler = new ChatHandler(config, agentRuntime, security);
        chatHandler.setRunRegistry(runRegistry);
        chatHandler.setAttachmentStore(attachmentStore);
        httpServer.createContext(WebUtils.API_CHAT,      chatHandler::handle);
        httpServer.createContext(WebUtils.API_CHAT_ABORT, chatHandler::handle);
        httpServer.createContext(WebUtils.API_CHAT_STATUS, chatHandler::handle);
        httpServer.createContext(WebUtils.API_CHAT_INTERACTION, chatHandler::handle);
        // P1：执行记录查询（刷新恢复 / 幂等结果查询 / 待确认交互重建）
        httpServer.createContext("/api/runs",          new RunsHandler(config, security, runRegistry, agentRuntime)::handle);
        // P2：通用附件（二进制上传 / 元信息轮询 / 解析文本 / 原始下载）
        if (attachmentStore != null) {
            httpServer.createContext("/api/attachments", new AttachmentsHandler(config, security, attachmentStore)::handle);
        }
        // P3：持久化成果（服务端登记的写文件记录 + 版本快照 + 预览/下载）
        if (artifactStore != null) {
            httpServer.createContext("/api/artifacts", new ArtifactsHandler(config, security, artifactStore)::handle);
        }
        httpServer.createContext(WebUtils.API_CHANNELS,  new ChannelsHandler(config, security,
                agentRuntime != null ? agentRuntime.getChannelManager() : null)::handle);
        httpServer.createContext(WebUtils.API_SESSIONS,  registerSessionsHandler(security)::handle);
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

        // P4：项目空间（仅在项目存储可用时注册）
        if (projectStore != null) {
            httpServer.createContext(WebUtils.API_PROJECTS,
                    new ProjectsHandler(config, security, projectStore, attachmentStore)::handle);
        }
    }

    /**
     * 构造 SessionsHandler 并注入项目存储（P4：projectId 存在性校验）。
     */
    private SessionsHandler registerSessionsHandler(SecurityMiddleware security) {
        SessionsHandler handler = new SessionsHandler(config, sessionManager, security,
                config.getWorkspacePath(), flagsStore);
        if (projectStore != null) {
            handler.setProjectStore(projectStore);
        }
        return handler;
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
