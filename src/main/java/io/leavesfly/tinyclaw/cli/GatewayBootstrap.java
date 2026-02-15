package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.channels.DiscordChannel;
import io.leavesfly.tinyclaw.channels.TelegramChannel;
import io.leavesfly.tinyclaw.channels.WebhookServer;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.voice.AliyunTranscriber;
import io.leavesfly.tinyclaw.voice.Transcriber;
import io.leavesfly.tinyclaw.web.WebConsoleServer;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 网关服务启动器，负责编排和管理所有服务的生命周期。
 * 
 * 核心职责：
 * - 初始化和启动所有服务组件（通道、定时任务、心跳、Web 控制台等）
 * - 管理服务的生命周期（启动、运行、停止）
 * - 提供优雅的关闭机制
 * - 协调各组件之间的依赖关系
 * 
 * 服务启动顺序：
 * 1. 通道管理器和语音转写器
 * 2. 定时任务服务
 * 3. 心跳服务
 * 4. Webhook 服务器
 * 5. Web Console 服务器
 * 6. Agent 主循环
 */
public class GatewayBootstrap {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("gateway");
    
    private static final int HEARTBEAT_INTERVAL_SECONDS = 1800;  // 心跳间隔（30分钟）
    private static final String HEARTBEAT_SESSION_KEY = "heartbeat:default";
    private static final String DISPLAY_HOST_REPLACEMENT = "127.0.0.1";  // 显示地址替换

    // 配置和核心组件
    private final Config config;           // 配置对象
    private final AgentLoop agentLoop;     // Agent 主循环
    private final MessageBus bus;          // 消息总线
    private final String workspace;        // 工作空间路径

    // 服务组件
    private ChannelManager channelManager;         // 通道管理器
    private CronService cronService;               // 定时任务服务
    private HeartbeatService heartbeatService;     // 心跳服务
    private WebhookServer webhookServer;           // Webhook 服务器
    private WebConsoleServer webConsoleServer;     // Web 控制台服务器
    private SessionManager sessionManager;         // 会话管理器
    private SkillsLoader skillsLoader;             // 技能加载器
    private Thread agentThread;                    // Agent 线程

    // 生命周期管理
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);  // 关闭信号量
    private boolean started = false;  // 启动状态标识

    /**
     * 构造网关启动器。
     * 
     * @param config 配置对象
     * @param agentLoop Agent 主循环
     * @param bus 消息总线
     */
    public GatewayBootstrap(Config config, AgentLoop agentLoop, MessageBus bus) {
        this.config = config;
        this.agentLoop = agentLoop;
        this.bus = bus;
        this.workspace = config.getWorkspacePath();
    }

    /**
     * 初始化所有服务组件。
     * 
     * 按依赖顺序初始化各个服务，但不启动它们。
     * 
     * @return 当前实例（支持链式调用）
     */
    public GatewayBootstrap initialize() {
        logger.info("Initializing gateway services");

        // 1. 初始化通道管理器
        channelManager = new ChannelManager(config, bus);

        // 2. 初始化语音转写器
        initializeTranscriber();

        // 3. 初始化定时任务服务
        String cronStorePath = Paths.get(workspace, "cron", "jobs.json").toString();
        cronService = new CronService(cronStorePath);

        // 4. 初始化心跳服务
        initializeHeartbeat();

        // 5. 初始化 Session 和 Skills
        sessionManager = new SessionManager(Paths.get(workspace, "sessions").toString());
        skillsLoader = new SkillsLoader(workspace, null, null);

        // 6. 初始化 Webhook Server
        webhookServer = new WebhookServer(
                config.getGateway().getHost(),
                config.getGateway().getPort(),
                channelManager
        );

        // 7. 初始化 Web Console Server
        int webPort = calculateWebConsolePort();
        webConsoleServer = new WebConsoleServer(
                config.getGateway().getHost(),
                webPort,
                config,
                agentLoop,
                sessionManager,
                cronService,
                skillsLoader
        );

        logger.info("Gateway services initialized");
        return this;
    }

    /**
     * 启动所有服务。
     * 
     * 按正确的顺序启动所有服务组件，确保依赖关系正确。
     * 
     * @return 当前实例（支持链式调用）
     * @throws IllegalStateException 如果网关已经启动
     */
    public GatewayBootstrap start() {
        if (started) {
            throw new IllegalStateException("Gateway already started");
        }

        logger.info("Starting gateway services");

        // 1. 启动定时任务服务
        startCronService();

        // 2. 启动心跳服务
        startHeartbeatService();

        // 3. 启动所有通道
        startChannels();

        // 4. 启动 Webhook Server
        startWebhookServer();

        // 5. 启动 Web Console Server
        startWebConsoleServer();

        // 6. 启动 Agent Loop
        startAgentLoop();

        // 7. 注册关闭钩子
        registerShutdownHook();

        started = true;
        logger.info("Gateway started successfully");
        return this;
    }

    /**
     * 等待关闭信号。
     * 
     * 阻塞当前线程直到网关收到关闭信号。
     * 
     * @throws InterruptedException 如果等待被中断
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * 停止所有服务。
     * 
     * 按启动的相反顺序优雅地关闭所有服务组件。
     */
    public void stop() {
        if (!started) {
            return;
        }

        logger.info("Stopping gateway services");

        // 按相反顺序停止服务
        stopService("Web Console", () -> webConsoleServer.stop(), webConsoleServer != null);
        stopService("Webhook Server", () -> webhookServer.stop(), webhookServer != null);
        stopService("Heartbeat", () -> heartbeatService.stop(), heartbeatService != null);
        stopService("Cron", () -> cronService.stop(), cronService != null);
        stopService("Channels", () -> channelManager.stopAll(), channelManager != null);
        stopService("Agent Loop", () -> agentLoop.stop(), agentLoop != null);

        shutdownLatch.countDown();
        started = false;
        logger.info("Gateway stopped");
    }

    /**
     * 获取已启用的通道列表。
     * 
     * @return 通道名称列表
     */
    public List<String> getEnabledChannels() {
        return channelManager != null ? channelManager.getEnabledChannels() : new ArrayList<>();
    }

    /**
     * 获取 Webhook 服务地址。
     * 
     * @return 可访问的 Webhook URL
     */
    public String getWebhookUrl() {
        String host = normalizeDisplayHost(config.getGateway().getHost());
        return String.format("http://%s:%d", host, config.getGateway().getPort());
    }

    /**
     * 获取 Web Console 服务地址。
     * 
     * @return 可访问的 Web Console URL
     */
    public String getWebConsoleUrl() {
        String host = normalizeDisplayHost(config.getGateway().getHost());
        return String.format("http://%s:%d", host, calculateWebConsolePort());
    }

    // ==================== 私有辅助方法 ====================
    
    /**
     * 规范化显示主机名。
     * 
     * 将绑定地址 0.0.0.0 转换为可访问的 127.0.0.1。
     * 
     * @param host 原始主机名
     * @return 规范化后的主机名
     */
    private String normalizeDisplayHost(String host) {
        return "0.0.0.0".equals(host) ? DISPLAY_HOST_REPLACEMENT : host;
    }

    /**
     * 初始化语音转写器。
     * 
     * 优先使用阿里云 DashScope（国内）。
     */
    private void initializeTranscriber() {
        Transcriber transcriber = null;

        // 优先尝试使用阿里云 DashScope
        if (config.getProviders() != null && config.getProviders().getDashscope() != null) {
            String dashscopeApiKey = config.getProviders().getDashscope().getApiKey();
            if (dashscopeApiKey != null && !dashscopeApiKey.isEmpty()) {
                transcriber = new AliyunTranscriber(dashscopeApiKey);
                logger.info("Using Aliyun DashScope for voice transcription");
            }
        }

        // 将转写器附加到支持的通道
        if (transcriber != null) {
            attachTranscriberToChannel("telegram", TelegramChannel.class, transcriber);
            attachTranscriberToChannel("discord", DiscordChannel.class, transcriber);
        } else {
            logger.warn("Voice transcription disabled: DashScope API key not configured");
        }
    }

    /**
     * 将转写器附加到指定通道。
     * 
     * @param channelName 通道名称
     * @param channelClass 通道类型
     * @param transcriber 转写器实例
     */
    private <T> void attachTranscriberToChannel(String channelName, Class<T> channelClass, Transcriber transcriber) {
        channelManager.getChannel(channelName).ifPresent(channel -> {
            if (channelClass.isInstance(channel)) {
                if (channel instanceof TelegramChannel telegramChannel) {
                    telegramChannel.setTranscriber(transcriber);
                    logger.info("Transcriber attached to Telegram channel", Map.of("provider", transcriber.getProviderName()));
                } else if (channel instanceof DiscordChannel discordChannel) {
                    discordChannel.setTranscriber(transcriber);
                    logger.info("Transcriber attached to Discord channel", Map.of("provider", transcriber.getProviderName()));
                }
            }
        });
    }

    /**
     * 初始化心跳服务。
     * 
     * 从配置读取心跳开关，创建心跳服务实例。
     */
    private void initializeHeartbeat() {
        boolean heartbeatEnabled = config.getAgent() != null
                && config.getAgent().isHeartbeatEnabled();

        heartbeatService = new HeartbeatService(
                workspace,
                prompt -> {
                    try {
                        return agentLoop.processDirect(prompt, HEARTBEAT_SESSION_KEY);
                    } catch (Exception e) {
                        logger.error("Heartbeat processing error", Map.of("error", e.getMessage()));
                        return null;
                    }
                },
                HEARTBEAT_INTERVAL_SECONDS,
                heartbeatEnabled
        );
    }

    /**
     * 计算 Web Console 端口。
     * 
     * 默认使用网关端口 + 1，未来可从配置读取。
     * 
     * @return Web Console 端口号
     */
    private int calculateWebConsolePort() {
        return config.getGateway().getPort() + 1;
    }

    /**
     * 启动定时任务服务。
     */
    private void startCronService() {
        cronService.start();
        logger.info("Cron service started");
    }

    /**
     * 启动心跳服务。
     */
    private void startHeartbeatService() {
        if (heartbeatService != null) {
            try {
                heartbeatService.start();
                logger.info("Heartbeat service started");
            } catch (Exception e) {
                logger.warn("Heartbeat service not started: " + e.getMessage());
            }
        }
    }

    /**
     * 启动所有通道。
     */
    private void startChannels() {
        channelManager.startAll();
        logger.info("Channel services started");
    }

    /**
     * 启动 Webhook 服务器。
     * 
     * @throws RuntimeException 如果启动失败
     */
    private void startWebhookServer() {
        try {
            webhookServer.start();
            logger.info("Webhook server started at " + getWebhookUrl());
        } catch (Exception e) {
            logger.error("Failed to start webhook server: " + e.getMessage());
            throw new RuntimeException("Failed to start webhook server", e);
        }
    }

    /**
     * 启动 Web Console 服务器。
     * 
     * @throws RuntimeException 如果启动失败
     */
    private void startWebConsoleServer() {
        try {
            webConsoleServer.start();
            logger.info("Web console started at " + getWebConsoleUrl());
        } catch (Exception e) {
            logger.error("Failed to start web console: " + e.getMessage());
            throw new RuntimeException("Failed to start web console", e);
        }
    }

    /**
     * 启动 Agent Loop 线程。
     */
    private void startAgentLoop() {
        agentThread = new Thread(() -> {
            try {
                agentLoop.run();
            } catch (Exception e) {
                logger.error("Agent loop error", Map.of("error", e.getMessage()));
            }
        }, "agent-loop");
        agentThread.setDaemon(true);
        agentThread.start();
        logger.info("Agent loop started");
    }

    /**
     * 停止单个服务。
     * 
     * @param serviceName 服务名称（用于日志）
     * @param stopAction 停止操作
     * @param shouldStop 是否需要停止
     */
    private void stopService(String serviceName, Runnable stopAction, boolean shouldStop) {
        if (shouldStop) {
            try {
                stopAction.run();
            } catch (Exception e) {
                logger.warn("Failed to stop " + serviceName + ": " + e.getMessage());
            }
        }
    }

    /**
     * 注册关闭钩子。
     * 
     * 在 JVM 关闭时自动停止所有服务。
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            stop();
            System.out.println("✓ 网关已停止");
        }));
    }
}
