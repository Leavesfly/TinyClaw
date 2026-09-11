package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.agent.context.SectionContext;
import io.leavesfly.tinyclaw.bootstrap.RuntimeAssembly;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.channels.DiscordChannel;
import io.leavesfly.tinyclaw.channels.TelegramChannel;
import io.leavesfly.tinyclaw.channels.WebhookServer;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronPayload;
import io.leavesfly.tinyclaw.cron.CronRunRecord;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatRunner;
import io.leavesfly.tinyclaw.tools.CronTool;
import io.leavesfly.tinyclaw.tools.EditFileTool;
import io.leavesfly.tinyclaw.tools.WriteFileTool;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.session.RunRegistry;
import io.leavesfly.tinyclaw.session.SessionFlagsStore;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.voice.AliyunTranscriber;
import io.leavesfly.tinyclaw.voice.Transcriber;
import io.leavesfly.tinyclaw.web.WebConsoleServer;
import io.leavesfly.tinyclaw.web.artifact.ArtifactRecord;
import io.leavesfly.tinyclaw.web.artifact.ArtifactStore;
import io.leavesfly.tinyclaw.web.attachment.AttachmentStore;
import io.leavesfly.tinyclaw.web.project.ProjectStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * 2. 定时任务服务（心跳与记忆进化作为内置 cron job 由其调度）
 * 3. Webhook 服务器
 * 4. Web Console 服务器
 * 5. Agent 主循环
 */
public class GatewayBootstrap {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("gateway");

    private static final String DISPLAY_HOST_REPLACEMENT = "127.0.0.1";  // 显示地址替换

    // 配置和核心组件
    private final Config config;           // 配置对象
    private final AgentRuntime agentRuntime;     // Agent 主循环
    private final MessageBus bus;          // 消息总线
    private final String workspace;        // 工作空间路径

    // 服务组件
    private ChannelManager channelManager;         // 通道管理器
    private final CronService cronService;         // 定时任务服务（由装配根注入，全局单实例）
    private final CronTool cronTool;               // 定时任务工具（与 cronService 绑定，用于执行用户 job）
    private HeartbeatRunner heartbeatRunner;       // 心跳运行器（由 cron job 调度）
    private WebhookServer webhookServer;           // Webhook 服务器
    private WebConsoleServer webConsoleServer;     // Web 控制台服务器
    private SessionManager sessionManager;         // 会话管理器
    private SkillsLoader skillsLoader;             // 技能加载器
    private Thread agentThread;                    // Agent 线程

    // P6：cron 编排依赖（initialize 中赋值，供 executeUserCronJob 使用）
    private volatile AttachmentStore cronAttachmentStore;
    private volatile ArtifactStore cronArtifactStore;
    private volatile SessionFlagsStore cronFlagsStore;
    private volatile ProjectStore cronProjectStore;

    /**
     * P6：用户 cron job 单次执行的侧表详情（JobHandler 返回 String，
     * runId/sessionKey/artifactIds 经 RunDetailCollector 回传 CronService）。
     * key = jobId；执行前 put，执行后补成果；collect 时拉取并移除。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CronRunEnvelope> cronRunEnvelopes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 侧表条目：startedAtMs 用于与 CronService 的执行实例比对，防止串轮。 */
    private static final class CronRunEnvelope {
        final long startedAtMs;
        final String runId;
        final String sessionKey;
        volatile java.util.List<String> artifactIds;
        volatile String deliveryStatus;

        CronRunEnvelope(long startedAtMs, String runId, String sessionKey, String deliveryStatus) {
            this.startedAtMs = startedAtMs;
            this.runId = runId;
            this.sessionKey = sessionKey;
            this.deliveryStatus = deliveryStatus;
            this.artifactIds = java.util.List.of();
        }
    }

    /** 记忆进化在途去重：上一轮未完成则跳过新的触发 */
    private final AtomicBoolean evolutionInFlight = new AtomicBoolean(false);

    // 生命周期管理
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);  // 关闭信号量
    private boolean started = false;  // 启动状态标识

    /**
     * 构造网关启动器。
     *
     * <p>有状态组件（AgentRuntime / MessageBus / CronService）一律从装配根取用，
     * 不在本类内重建——此前本类另建一份 CronService，与 {@code registerTools} 里那份
     * 共用同一个 jobs.json 且各自全量覆盖写，导致用户创建的定时任务静默丢失。</p>
     *
     * @param assembly 运行时装配结果
     */
    public GatewayBootstrap(RuntimeAssembly assembly) {
        this.config = assembly.config();
        this.agentRuntime = assembly.agentRuntime();
        this.bus = assembly.bus();
        this.workspace = assembly.config().getWorkspacePath();
        this.cronService = assembly.cronService();
        this.cronTool = assembly.cronTool();
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

        // 将通道管理器注入 AgentRuntime，使其能感知各通道的能力（如流式输出）
        agentRuntime.setChannelManager(channelManager);

        // 2. 初始化语音转写器
        initializeTranscriber();

        // 3. 定时任务服务已由装配根创建，此处只负责接上任务处理器与内置 job

        // 4. 初始化心跳运行器（tick 由 cron 调度，不再有独立心跳线程）
        heartbeatRunner = new HeartbeatRunner(
                config,
                agentRuntime,
                agentRuntime.getSessionManager(),
                workspace,
                agentRuntime::isTaskRunning,
                (channel, chatId, content) -> bus.publishOutbound(new OutboundMessage(channel, chatId, content))
        );

        // 设置任务处理器：系统内置 job（心跳/记忆进化）按名称分发，
        // 其余用户 job 走 P6 编排（新会话模式/资料注入/成果收集/忙碌跳过）
        cronService.setOnJob(job -> {
            String name = job.getName();
            if (name != null && name.startsWith(HeartbeatRunner.HEARTBEAT_JOB_PREFIX)) {
                heartbeatRunner.runOnceForJob(name);
                return "ok";
            }
            if (HeartbeatRunner.MEMORY_EVOLUTION_JOB_NAME.equals(name)) {
                triggerMemoryEvolution();
                return "ok";
            }
            return executeUserCronJob(job);
        });
        // P6：执行详情收集器——把侧表中的 runId/sessionKey/artifactIds 合入执行历史。
        // 同一 job 的执行严格串行（单 worker + 同步 future.get），侧表中的条目
        // 必然属于当前这轮：执行前 put、collect 时拉取并移除
        cronService.setRunDetailCollector((jobId, startedAtMs) -> {
            CronRunEnvelope env = cronRunEnvelopes.remove(jobId);
            if (env == null) {
                return null;
            }
            return new CronService.RunDetail(
                    env.runId, env.sessionKey, env.artifactIds, env.deliveryStatus);
        });

        // 注册/更新系统内置 job（幂等，按 name 查重）
        heartbeatRunner.registerSystemJobs(cronService);

        // 5. 初始化 Session 和 Skills
        // 复用 AgentRuntime 内部的 SessionManager，确保 Web Console 与 Agent 共享同一内存状态，
        // 避免 AgentRuntime 写入新会话后 WebConsoleServer 因持有独立实例而看不到新会话的问题。
        sessionManager = agentRuntime.getSessionManager();
        skillsLoader = new SkillsLoader(workspace, null, null);

        // 6. 初始化 Webhook Server（传入通道配置用于签名校验）
        webhookServer = new WebhookServer(
                config.getGateway().getHost(),
                config.getGateway().getPort(),
                channelManager,
                config.getChannels()
        );

        // 7. 初始化 Web Console Server
        // P1：执行登记处（workspace/runs，重启时非终态标记中断）；
        // 与 HITL broker 联动，交互开始/结束时标记 WAITING_USER / RUNNING
        RunRegistry runRegistry =
                new RunRegistry(
                        java.nio.file.Paths.get(workspace, "runs").toString());
        if (agentRuntime.getInteractionBroker() != null) {
            agentRuntime.getInteractionBroker().setRunRegistry(runRegistry);
        }
        // P2：通用附件存储（workspace/attachments：原始字节 + 元信息 + 解析文本）
        AttachmentStore attachmentStore =
                new AttachmentStore(
                        java.nio.file.Paths.get(workspace, "attachments").toString());
        this.cronAttachmentStore = attachmentStore;
        // P3：成果存储（workspace/artifacts：登记记录 + 版本快照）
        ArtifactStore artifactStore =
                new ArtifactStore(
                        java.nio.file.Paths.get(workspace, "artifacts").toString());
        this.cronArtifactStore = artifactStore;
        // P3：把成果登记回调注入 write_file / edit_file（实际写盘成功后登记）
        injectArtifactRecorder(agentRuntime, artifactStore);
        // P4：会话整理标记（标题/置顶/归档，workspace/session-flags.json）
        SessionFlagsStore flagsStore =
                new SessionFlagsStore(
                        java.nio.file.Paths.get(workspace, "session-flags.json").toString());
        this.cronFlagsStore = flagsStore;
        // P5：会话记忆模式门（memoryMode=OFF 的会话关闭长期记忆自动检索与自动提取）。
        // 门引用 flagsStore 实时读取，切换立即生效；读路径（ContextBuilder 记忆注入）
        // 与写路径（SessionSummarizer 摘要后提取）共用同一门。
        // isMemoryOff 的语义是「是否关闭」，而门语义是「是否启用」，因此取反。
        agentRuntime.getContextBuilder().setMemoryGate(
                sessionKey -> !flagsStore.isMemoryOff(sessionKey));
        // P4：项目存储（workspace/projects.json：名称/指令/资料引用，revision 单调递增）
        ProjectStore projectStore =
                new ProjectStore(
                        java.nio.file.Paths.get(workspace, "projects.json").toString());
        this.cronProjectStore = projectStore;
        // P4：项目解析器——会话归属项目时注入项目指令与项目记忆域（p:<projectId>）。
        // 归属标记指向已删除项目时 resolveProject 返回 null（指令不再注入）；
        // 实时读，项目修改后下一轮立即生效。
        agentRuntime.getContextBuilder().setProjectResolver(sessionKey -> {
            String pid = flagsStore.get(sessionKey).projectId;
            if (pid == null || !projectStore.exists(pid)) {
                return null;
            }
            ProjectStore.Project p = projectStore.get(pid);
            return new SectionContext.ProjectInfo(
                    p.id, p.name, p.instructions);
        });
        int webPort = calculateWebConsolePort();
        webConsoleServer = new WebConsoleServer(
                config.getGateway().getHost(),
                webPort,
                config,
                agentRuntime,
                sessionManager,
                cronService,
                skillsLoader,
                heartbeatRunner,
                runRegistry,
                attachmentStore,
                artifactStore,
                flagsStore,
                projectStore
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

        // 1. 启动定时任务服务（心跳与记忆进化作为内置 job 随之调度）
        startCronService();

        // 2. 启动所有通道
        startChannels();

        // 3. 启动 Webhook Server
        startWebhookServer();

        // 4. 启动 Web Console Server
        startWebConsoleServer();

        // 5. 启动 Agent Loop
        startAgentLoop();

        // 6. 注册关闭钩子
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

        // 1. 先停止入口层，不再接收新消息
        stopService("Web Console", () -> webConsoleServer.stop(), webConsoleServer != null);
        stopService("Webhook Server", () -> webhookServer.stop(), webhookServer != null);
        stopService("Cron", () -> cronService.stop(), cronService != null);
        stopService("Heartbeat", () -> heartbeatRunner.stop(), heartbeatRunner != null);

        // 2. 停止 Agent，不再产生新的出站消息
        stopService("Agent Loop", () -> agentRuntime.stop(), agentRuntime != null);

        // 3. 等待出站队列排空后关闭总线（最多等待 5 秒），确保已生成的回复都能发出去
        if (bus != null) {
            try {
                bus.drainAndClose(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                bus.close();
            }
        }

        // 4. 最后停止通道（此时出站队列已排空，通道可以安全关闭）
        stopService("Channels", () -> channelManager.stopAll(), channelManager != null);

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

    // ==================== P6：用户 cron job 编排 ====================

    /**
     * 执行用户 cron job（P6 编排入口，取代直接调 cronTool.executeJob）：
     *
     * <ul>
     *   <li><b>新会话模式（默认）</b>：每次运行新建会话 key（cron-&lt;jobId&gt;-&lt;ts&gt;），
     *       只复用用户确认的资料（attachmentIds 注入任务消息）与任务指令，
     *       不复制源会话完整历史；</li>
     *   <li><b>继续同会话模式</b>（用户明确选择）：复用专用会话 key（cron-&lt;jobId&gt;），
     *       会话忙碌时跳过本轮并返回 [SKIPPED]（与执行失败区分，不触发误告警）；</li>
     *   <li><b>项目归属</b>：新会话标记 projectId，项目指令与项目记忆域随会话注入；
     *   <li><b>成果收集</b>：执行后取该会话新增的成果登记 id 合入执行历史。</li>
     * </ul>
     *
     * <p>后台运行不自动批准危险操作：HITL 审批仍需人工 resolve，
     * 超时由 CronService 的 JOB_TIMEOUT 兜底并记为 timeout 失败原因。</p>
     */
    private String executeUserCronJob(CronJob job) {
        CronPayload payload = job.getPayload();
        long now = System.currentTimeMillis();
        String runId = newCronRunId();

        // 会话键：NEW_SESSION 每次新建；CONTINUE_SESSION 复用专用会话
        String sessionKey;
        if (CronPayload.RUN_MODE_CONTINUE_SESSION
                .equals(payload.effectiveRunMode())) {
            sessionKey = "cron-" + job.getId();
            // 同一 Job 不允许重叠执行：会话忙碌（上一轮未结束或用户正在该会话交互）时跳过本轮
            if (agentRuntime.isTaskRunning(sessionKey)) {
                cronRunEnvelopes.put(job.getId(),
                        new CronRunEnvelope(now, runId, sessionKey,
                                CronRunRecord.DELIVERY_SKIPPED));
                logger.info("Cron job skipped: session busy", Map.of(
                        "job_id", job.getId(), "session_key", sessionKey));
                return "[SKIPPED] session busy: previous run still active or user interacting, "
                        + "will retry at next schedule";
            }
        } else {
            sessionKey = "cron-" + job.getId() + "-" + Long.toHexString(now);
        }

        // 项目归属：新会话标记 projectId（项目不存在时忽略，指令不注入）
        SessionFlagsStore flags = this.cronFlagsStore;
        String projectId = payload.getProjectId();
        if (flags != null && projectId != null
                && cronProjectStore != null && cronProjectStore.exists(projectId)) {
            flags.setProjectId(sessionKey, projectId);
        }

        // 任务消息：原始指令 + 资料块 + 输出要求（仅复用用户确认的内容，不带源会话历史）
        String message = payload.getMessage() != null ? payload.getMessage() : "";
        java.util.List<String> attachmentIds = payload.getAttachmentIds();
        if (attachmentIds != null && !attachmentIds.isEmpty() && cronAttachmentStore != null) {
            String block = cronAttachmentStore.buildContextBlock(attachmentIds);
            if (block != null) {
                message = message + "\n" + block;
            }
        }
        if (payload.getOutputTarget() != null && !payload.getOutputTarget().isBlank()) {
            message = message + "\n\n[输出要求]\n" + payload.getOutputTarget().trim();
        }

        // 执行前成果快照：执行后取差集作为本次新增
        java.util.Set<String> artifactsBefore = sessionArtifactIds(sessionKey);
        cronRunEnvelopes.put(job.getId(), new CronRunEnvelope(now, runId, sessionKey,
                CronRunRecord.DELIVERY_GENERATED));

        String result = cronTool.executeJobInSession(job, sessionKey, message);

        // 成果差集：写入侧表供收集器合入历史（生成成功≠消息已送达，投递由通道异步承担）
        java.util.List<String> newArtifacts = new java.util.ArrayList<>();
        for (String id : sessionArtifactIds(sessionKey)) {
            if (!artifactsBefore.contains(id)) {
                newArtifacts.add(id);
            }
        }
        CronRunEnvelope env = cronRunEnvelopes.get(job.getId());
        if (env != null && env.runId.equals(runId)) {
            env.artifactIds = java.util.List.copyOf(newArtifacts);
        }
        return result;
    }

    /** 当前会话的成果登记 id 集合（存储未就绪时为空集）。 */
    private java.util.Set<String> sessionArtifactIds(String sessionKey) {
        ArtifactStore store = this.cronArtifactStore;
        if (store == null || sessionKey == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ArtifactRecord r : store.listBySession(sessionKey)) {
            if (r.id != null) {
                ids.add(r.id);
            }
        }
        return ids;
    }

    /** 生成 12 位十六进制执行实例 id（手动重试自然产生新 runId）。 */
    private static String newCronRunId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 触发进化周期。
     *
     * 由 __memory_evolution__ 系统 cron job 触发，异步执行不阻塞调度线程。
     * 上一轮进化未完成时跳过（AtomicBoolean 去重）。
     * 进化过程包括：
     * 1. 基于反馈的智能记忆进化
     * 2. 常规记忆进化（提炼 → 整合 → 衰减归档）
     * 3. Prompt 优化（如果启用）
     * 4. 清理已结束会话的跟踪数据
     */
    private void triggerMemoryEvolution() {
        if (!agentRuntime.isProviderConfigured()) {
            return;
        }
        if (!evolutionInFlight.compareAndSet(false, true)) {
            logger.debug("Memory evolution already in flight, skipping");
            return;
        }

        Thread evolutionThread = new Thread(() -> {
            try {
                // 调用完整的进化周期（包含反馈驱动的进化 + Prompt 优化）
                agentRuntime.runEvolutionCycle();
            } catch (Exception e) {
                logger.error("Evolution cycle failed", Map.of("error", e.getMessage()));
            } finally {
                evolutionInFlight.set(false);
            }
        }, "evolution-cycle");
        evolutionThread.setDaemon(true);
        evolutionThread.start();
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
     * P3：向 write_file / edit_file 注入成果登记回调。
     *
     * <p>工具未注册（provider 未就绪）或类型不符时静默跳过，不影响启动。</p>
     */
    private void injectArtifactRecorder(AgentRuntime runtime,
                                         ArtifactStore artifactStore) {
        if (runtime == null || artifactStore == null || runtime.getToolRegistry() == null) {
            return;
        }
        runtime.getToolRegistry().get("write_file").ifPresent(tool -> {
            if (tool instanceof WriteFileTool writeFileTool) {
                writeFileTool.setArtifactRecorder(artifactStore);
            }
        });
        runtime.getToolRegistry().get("edit_file").ifPresent(tool -> {
            if (tool instanceof EditFileTool editFileTool) {
                editFileTool.setArtifactRecorder(artifactStore);
            }
        });
    }

    /**
     * 启动定时任务服务。
     */
    private void startCronService() {
        cronService.start();
        logger.info("Cron service started");
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
                agentRuntime.run();
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
