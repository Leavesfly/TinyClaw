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
 * 网关服务启动器 - 负责编排和管理所有服务的生命周期
 */
public class GatewayBootstrap {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("gateway");

    // 配置和核心组件
    private final Config config;
    private final AgentLoop agentLoop;
    private final MessageBus bus;
    private final String workspace;

    // 服务组件
    private ChannelManager channelManager;
    private CronService cronService;
    private HeartbeatService heartbeatService;
    private WebhookServer webhookServer;
    private WebConsoleServer webConsoleServer;
    private SessionManager sessionManager;
    private SkillsLoader skillsLoader;
    private Thread agentThread;

    // 生命周期管理
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private boolean started = false;

    public GatewayBootstrap(Config config, AgentLoop agentLoop, MessageBus bus) {
        this.config = config;
        this.agentLoop = agentLoop;
        this.bus = bus;
        this.workspace = config.getWorkspacePath();
    }

    /**
     * 初始化所有服务组件
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
     * 启动所有服务
     */
    public GatewayBootstrap start() {
        if (started) {
            throw new IllegalStateException("Gateway already started");
        }

        logger.info("Starting gateway services");

        // 1. 启动定时任务服务
        cronService.start();
        logger.info("Cron service started");

        // 2. 启动心跳服务
        if (heartbeatService != null) {
            try {
                heartbeatService.start();
                logger.info("Heartbeat service started");
            } catch (Exception e) {
                logger.warn("Heartbeat service not started: " + e.getMessage());
            }
        }

        // 3. 启动所有通道
        channelManager.startAll();
        logger.info("Channel services started");

        // 4. 启动 Webhook Server
        try {
            webhookServer.start();
            logger.info("Webhook server started at " + getWebhookUrl());
        } catch (Exception e) {
            logger.error("Failed to start webhook server: " + e.getMessage());
            throw new RuntimeException("Failed to start webhook server", e);
        }

        // 5. 启动 Web Console Server
        try {
            webConsoleServer.start();
            logger.info("Web console started at " + getWebConsoleUrl());
        } catch (Exception e) {
            logger.error("Failed to start web console: " + e.getMessage());
            throw new RuntimeException("Failed to start web console", e);
        }

        // 6. 启动 Agent Loop
        startAgentLoop();

        // 7. 注册关闭钩子
        registerShutdownHook();

        started = true;
        logger.info("Gateway started successfully");
        return this;
    }

    /**
     * 等待关闭信号
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * 停止所有服务
     */
    public void stop() {
        if (!started) {
            return;
        }

        logger.info("Stopping gateway services");

        // 按相反顺序停止服务
        if (webConsoleServer != null) {
            webConsoleServer.stop();
        }
        if (webhookServer != null) {
            webhookServer.stop();
        }
        if (heartbeatService != null) {
            heartbeatService.stop();
        }
        if (cronService != null) {
            cronService.stop();
        }
        if (channelManager != null) {
            channelManager.stopAll();
        }
        if (agentLoop != null) {
            agentLoop.stop();
        }

        shutdownLatch.countDown();
        started = false;
        logger.info("Gateway stopped");
    }

    /**
     * 获取已启用的通道列表
     */
    public List<String> getEnabledChannels() {
        return channelManager != null ? channelManager.getEnabledChannels() : new ArrayList<>();
    }

    /**
     * 获取 Webhook 服务地址
     */
    public String getWebhookUrl() {
        String host = config.getGateway().getHost();
        // 0.0.0.0 是绑定地址，显示时用 127.0.0.1 更友好
        if ("0.0.0.0".equals(host)) {
            host = "127.0.0.1";
        }
        return String.format("http://%s:%d", host, config.getGateway().getPort());
    }

    /**
     * 获取 Web Console 服务地址
     */
    public String getWebConsoleUrl() {
        String host = config.getGateway().getHost();
        // 0.0.0.0 是绑定地址，显示时用 127.0.0.1 更友好
        if ("0.0.0.0".equals(host)) {
            host = "127.0.0.1";
        }
        return String.format("http://%s:%d", host, calculateWebConsolePort());
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 初始化语音转写器
     * <p>
     * 优先使用阿里云 DashScope（国内）
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
     * 将转写器附加到指定通道
     */
    private <T> void attachTranscriberToChannel(String channelName, Class<T> channelClass, Transcriber transcriber) {
        channelManager.getChannel(channelName).ifPresent(channel -> {
            if (channelClass.isInstance(channel)) {
                if (channel instanceof TelegramChannel) {
                    ((TelegramChannel) channel).setTranscriber(transcriber);
                    logger.info("Transcriber attached to Telegram channel", Map.of("provider", transcriber.getProviderName()));
                } else if (channel instanceof DiscordChannel) {
                    ((DiscordChannel) channel).setTranscriber(transcriber);
                    logger.info("Transcriber attached to Discord channel", Map.of("provider", transcriber.getProviderName()));
                }
            }
        });
    }

    /**
     * 初始化心跳服务
     */
    private void initializeHeartbeat() {
        boolean heartbeatEnabled = config.getAgent() != null
                && config.getAgent().isHeartbeatEnabled();

        heartbeatService = new HeartbeatService(
                workspace,
                prompt -> {
                    try {
                        return agentLoop.processDirect(prompt, "heartbeat:default");
                    } catch (Exception e) {
                        logger.error("Heartbeat processing error", Map.of("error", e.getMessage()));
                        return null;
                    }
                },
                1800, // 30分钟间隔
                heartbeatEnabled
        );
    }

    /**
     * 计算 Web Console 端口
     */
    private int calculateWebConsolePort() {
        // 默认使用网关端口 + 1
        // 未来可以从配置读取
        return config.getGateway().getPort() + 1;
    }

    /**
     * 启动 Agent Loop 线程
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
     * 注册关闭钩子
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            stop();
            System.out.println("✓ 网关已停止");
        }));
    }
}
