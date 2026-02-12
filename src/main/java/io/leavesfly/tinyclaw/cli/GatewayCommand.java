package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.channels.ChannelManager;
import io.leavesfly.tinyclaw.channels.DiscordChannel;
import io.leavesfly.tinyclaw.channels.TelegramChannel;
import io.leavesfly.tinyclaw.channels.WebhookServer;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.heartbeat.HeartbeatService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.voice.GroqTranscriber;

import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.tools.*;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 网关命令 - 启动 TinyClaw 网关服务器
 */
public class GatewayCommand extends CliCommand {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cli");
    
    @Override
    public String name() {
        return "gateway";
    }
    
    @Override
    public String description() {
        return "启动 tinyclaw 网关";
    }
    
    @Override
    public int execute(String[] args) throws Exception {
        boolean debug = false;
        
        // 解析参数
        for (String arg : args) {
            if (arg.equals("--debug") || arg.equals("-d")) {
                debug = true;
                System.out.println("🔍 调试模式已启用");
                break;
            }
        }
        
        // 加载配置
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            System.err.println("加载配置错误: " + e.getMessage());
            System.err.println("运行 'tinyclaw onboard' first to initialize.");
            return 1;
        }
        
        // 创建服务提供者
        LLMProvider provider;
        try {
            String apiKey = config.getProviders().getOpenrouter().getApiKey();
            String apiBase = config.getProviders().getOpenrouter().getApiBase();
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = config.getProviders().getOpenai().getApiKey();
                apiBase = "https://api.openai.com/v1";
            }
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("未配置 API 密钥。请设置 OpenRouter 或 OpenAI API 密钥。");
            }
            provider = new HTTPProvider(apiKey, apiBase != null ? apiBase : "https://openrouter.ai/api/v1");
        } catch (Exception e) {
            System.err.println("创建服务提供者错误: " + e.getMessage());
            return 1;
        }
        
        // 创建消息总线和 Agent 循环
        MessageBus bus = new MessageBus();
        AgentLoop agentLoop = new AgentLoop(config, bus, provider);
        
        // 打印启动信息
        System.out.println();
        System.out.println("📦 Agent 状态:");
        Map<String, Object> startupInfo = agentLoop.getStartupInfo();
        @SuppressWarnings("unchecked")
        Map<String, Object> toolsInfo = (Map<String, Object>) startupInfo.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillsInfo = (Map<String, Object>) startupInfo.get("skills");
        System.out.println("  • 工具: " + toolsInfo.get("count") + " 已加载");
        System.out.println("  • 技能: " + skillsInfo.get("available") + "/" + skillsInfo.get("total") + " 可用");
        
        logger.info("Agent initialized", Map.of(
                "tools_count", toolsInfo.get("count"),
                "skills_total", skillsInfo.get("total"),
                "skills_available", skillsInfo.get("available")
        ));
        
        // 设置工具和服务
        String workspace = config.getWorkspacePath();
        
        // 注册工具
        registerTools(agentLoop, config, bus, provider);
        
        // 设置定时任务服务
        String cronStorePath = Paths.get(workspace, "cron", "jobs.json").toString();
        CronService cronService = new CronService(cronStorePath);
        
        // 设置定时任务工具处理器
        CronTool cronTool = findCronTool(agentLoop);
        if (cronTool != null) {
            cronService.setOnJob(job -> cronTool.executeJob(job));
        }
        
        // 初始化通道管理器
        ChannelManager channelManager = new ChannelManager(config, bus);
        
        // 初始化语音转写器（如果配置了 Groq API Key）
        String groqApiKey = config.getProviders() != null && config.getProviders().getGroq() != null 
                ? config.getProviders().getGroq().getApiKey() : null;
        if (groqApiKey != null && !groqApiKey.isEmpty()) {
            final GroqTranscriber transcriber = new GroqTranscriber(groqApiKey);
            logger.info("Groq 语音转写服务已启用");
            
            // 将转写器设置到 Telegram 通道
            channelManager.getChannel("telegram").ifPresent(ch -> {
                if (ch instanceof TelegramChannel) {
                    ((TelegramChannel) ch).setTranscriber(transcriber);
                    logger.info("Groq 转写器已连接到 Telegram 通道");
                }
            });
            
            // 将转写器设置到 Discord 通道
            channelManager.getChannel("discord").ifPresent(ch -> {
                if (ch instanceof DiscordChannel) {
                    ((DiscordChannel) ch).setTranscriber(transcriber);
                    logger.info("Groq 转写器已连接到 Discord 通道");
                }
            });
        }
        
        // 初始化心跳服务
        boolean heartbeatEnabled = config.getAgents() != null && config.getAgents().getDefaults() != null 
                && config.getAgents().getDefaults().isHeartbeatEnabled();
        HeartbeatService heartbeatService = new HeartbeatService(
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
        
        // 获取启用的通道列表
        List<String> enabledChannels = channelManager.getEnabledChannels();
        if (!enabledChannels.isEmpty()) {
            System.out.println("✓ 已启用通道: " + String.join(", ", enabledChannels));
        } else {
            System.out.println("⚠ 警告: 没有启用任何通道");
        }
        
        System.out.println("✓ 网关已启动于 " + config.getGateway().getHost() + ":" + config.getGateway().getPort());
        System.out.println("按 Ctrl+C 停止");
        
        // 启动服务
        cronService.start();
        System.out.println("✓ 定时任务服务已启动");
        
        // 启动心跳服务
        try {
            heartbeatService.start();
            System.out.println("✓ 心跳服务已启动");
        } catch (Exception e) {
            logger.warn("心跳服务未启动: " + e.getMessage());
        }
        
        // 启动所有通道
        channelManager.startAll();
        System.out.println("✓ 通道服务已启动");
        
        // 启动 Webhook Server（接收钉钉、飞书、QQ 等平台的回调）
        WebhookServer webhookServer = new WebhookServer(
                config.getGateway().getHost(),
                config.getGateway().getPort(),
                channelManager
        );
        webhookServer.start();
        System.out.println("✓ Webhook Server 已启动（" + config.getGateway().getHost() + ":" + config.getGateway().getPort() + "）");
        System.out.println("  • POST /webhook/dingtalk  → 钉钉回调");
        System.out.println("  • POST /webhook/feishu    → 飞书回调");
        System.out.println("  • POST /webhook/qq        → QQ 回调");
        System.out.println("  • GET  /health            → 健康检查");
        
        // 在后台启动 agent 循环
        Thread agentThread = new Thread(() -> {
            try {
                agentLoop.run();
            } catch (Exception e) {
                logger.error("Agent loop error", Map.of("error", e.getMessage()));
            }
        }, "agent-loop");
        agentThread.setDaemon(true);
        agentThread.start();
        
        // 关闭钩子
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            webhookServer.stop();
            heartbeatService.stop();
            cronService.stop();
            channelManager.stopAll();
            agentLoop.stop();
            shutdownLatch.countDown();
            System.out.println("✓ 网关已停止");
        }));
        
        // 等待关闭
        shutdownLatch.await();
        
        return 0;
    }
    
    private void registerTools(AgentLoop agentLoop, Config config, MessageBus bus, LLMProvider provider) {
        String workspace = config.getWorkspacePath();
        
        // 文件工具
        agentLoop.registerTool(new ReadFileTool());
        agentLoop.registerTool(new WriteFileTool());
        agentLoop.registerTool(new AppendFileTool());
        agentLoop.registerTool(new ListDirTool());
        
        // 文件编辑工具
        agentLoop.registerTool(new EditFileTool(workspace));
        
        // 执行工具
        agentLoop.registerTool(new ExecTool(workspace));
        
        // 网络工具
        String braveApiKey = config.getTools() != null ? config.getTools().getBraveApi() : null;
        if (braveApiKey != null && !braveApiKey.isEmpty()) {
            agentLoop.registerTool(new WebSearchTool(braveApiKey, 5));
        }
        agentLoop.registerTool(new WebFetchTool(50000));
        
        // 消息工具
        MessageTool messageTool = new MessageTool();
        messageTool.setSendCallback((channel, chatId, content) -> {
            bus.publishOutbound(new OutboundMessage(channel, chatId, content));
        });
        agentLoop.registerTool(messageTool);
        
        // 定时任务工具
        String cronStorePath = Paths.get(workspace, "cron", "jobs.json").toString();
        CronService cronService = new CronService(cronStorePath);
        
        CronTool cronTool = new CronTool(cronService, new CronTool.JobExecutor() {
            @Override
            public String processDirectWithChannel(String content, String sessionKey, String channel, String chatId) throws Exception {
                return agentLoop.processDirectWithChannel(content, sessionKey, channel, chatId);
            }
        }, bus);
        agentLoop.registerTool(cronTool);
        
        // 子代理工具
        SubagentManager subagentManager = new SubagentManager(provider, workspace, bus);
        agentLoop.registerTool(new SpawnTool(subagentManager));
    }
    
    private CronTool findCronTool(AgentLoop agentLoop) {
        // 这是简化方法 - 在实际实现中你应该有获取已注册工具的方法
        return null; // 将通过工具注册设置
    }
    
    @Override
    public void printHelp() {
        System.out.println(LOGO + " tinyclaw gateway - 启动网关服务器");
        System.out.println();
        System.out.println("Usage: tinyclaw gateway [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -d, --debug    启用调试模式");
    }
}