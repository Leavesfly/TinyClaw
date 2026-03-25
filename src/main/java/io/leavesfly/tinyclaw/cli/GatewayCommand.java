package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.List;

/**
 * 网关命令，启动 TinyClaw 网关服务器。
 * 
 * 核心功能：
 * - 启动完整的网关服务（通道、定时任务、心跳、Webhook、Web Console）
 * - 支持无 LLM Provider 启动，可通过 Web Console 后续配置
 * - 提供多通道消息接入（钉钉、飞书、QQ、Telegram、Discord 等）
 * - 内置 Web Console 管理界面
 * 
 * 服务架构：
 * - MessageBus：消息总线，协调各组件通信
 * - AgentRuntime：Agent 主循环，处理用户消息
 * - ChannelManager：管理所有通道的生命周期
 * - WebhookServer：处理外部 Webhook 回调
 * - WebConsoleServer：提供 Web 管理界面
 * - CronService：定时任务调度
 * - HeartbeatService：心跳检测
 * 
 * 使用场景：
 * - 生产环境部署，提供 24/7 服务
 * - 多通道接入，统一管理多个 IM 平台
 * - 团队协作，共享 Agent 服务
 */
public class GatewayCommand extends CliCommand {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cli");
    
    private static final String WARNING_NO_PROVIDER = "⚠️  LLM Provider 未配置，但仍可启动 Web Console 进行配置";
    private static final String GUIDE_WEB_CONSOLE = "👉 请访问 Web Console 配置 LLM Provider:";
    private static final String SHUTDOWN_TIP = "按 Ctrl+C 停止";
    
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
        // 解析命令行参数
        boolean debug = parseDebugFlag(args);
        
        // 加载配置并创建 Agent
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }
        
        AgentContext agentContext = createAgentContext(config);
        
        // 创建并启动网关
        GatewayBootstrap gateway = createAndStartGateway(config, agentContext);
        
        // 打印启动信息
        printStartupInfo(gateway, config, agentContext.providerConfigured);
        
        // 等待关闭
        gateway.awaitShutdown();
        
        return 0;
    }
    
    /**
     * 解析调试标志。
     * 
     * @param args 命令行参数
     * @return 是否启用调试模式
     */
    private boolean parseDebugFlag(String[] args) {
        for (String arg : args) {
            if ("--debug".equals(arg) || "-d".equals(arg)) {
                System.out.println("🔍 调试模式已启用");
                return true;
            }
        }
        return false;
    }
    
    /**
     * 创建 Agent 上下文。
     * 
     * @param config 配置对象
     * @return Agent 上下文
     */
    private AgentContext createAgentContext(Config config) {
        // 创建服务提供者（允许为 null）
        LLMProvider provider = createProviderOrNull(config);
        boolean providerConfigured = (provider != null);
        
        if (!providerConfigured) {
            System.out.println();
            System.out.println(WARNING_NO_PROVIDER);
            System.out.println();
        }
        
        // 创建消息总线和 Agent 循环
        MessageBus bus = new MessageBus();
        AgentRuntime agentRuntime = new AgentRuntime(config, bus, provider);
        
        // 注册工具，再打印 Agent 状态
        if (providerConfigured) {
            registerTools(agentRuntime, config, bus, provider);
            printAgentStatus(agentRuntime);
        }
        
        return new AgentContext(agentRuntime, bus, providerConfigured);
    }
    
    /**
     * 创建并启动网关。
     * 
     * @param config 配置对象
     * @param agentContext Agent 上下文
     * @return 网关实例
     */
    private GatewayBootstrap createAndStartGateway(Config config, AgentContext agentContext) {
        return new GatewayBootstrap(config, agentContext.agentRuntime, agentContext.bus)
                .initialize()
                .start();
    }
    
    /**
     * 打印网关启动信息。
     * 
     * @param gateway 网关实例
     * @param config 配置对象
     * @param providerConfigured Provider 是否已配置
     */
    private void printStartupInfo(GatewayBootstrap gateway, Config config, boolean providerConfigured) {
        // 打印通道信息
        printChannelInfo(gateway);
        
        // 打印网关基本信息
        printGatewayBasicInfo(config);
        
        // 打印服务状态
        printServiceStatus();
        
        // 打印 Webhook 信息
        printWebhookInfo(gateway);
        
        // 打印 Web Console 信息
        printWebConsoleInfo(gateway, providerConfigured);
    }
    
    /**
     * 打印通道信息。
     * 
     * @param gateway 网关实例
     */
    private void printChannelInfo(GatewayBootstrap gateway) {
        List<String> enabledChannels = gateway.getEnabledChannels();
        if (!enabledChannels.isEmpty()) {
            System.out.println("✓ 已启用通道: " + String.join(", ", enabledChannels));
        } else {
            System.out.println("⚠ 警告: 没有启用任何通道");
        }
    }
    
    /**
     * 打印网关基本信息。
     * 
     * @param config 配置对象
     */
    private void printGatewayBasicInfo(Config config) {
        System.out.println("✓ 网关已启动于 " + config.getGateway().getHost() + ":" + config.getGateway().getPort());
        System.out.println(SHUTDOWN_TIP);
    }
    
    /**
     * 打印服务状态。
     */
    private void printServiceStatus() {
        System.out.println("✓ 定时任务服务已启动");
        System.out.println("✓ 心跳服务已启动");
        System.out.println("✓ 通道服务已启动");
    }
    
    /**
     * 打印 Webhook 信息。
     * 
     * @param gateway 网关实例
     */
    private void printWebhookInfo(GatewayBootstrap gateway) {
        System.out.println("✓ Webhook Server 已启动（" + gateway.getWebhookUrl() + "）");
        System.out.println("  • POST /webhook/dingtalk  → 钉钉回调");
        System.out.println("  • POST /webhook/feishu    → 飞书回调");
        System.out.println("  • POST /webhook/qq        → QQ 回调");
        System.out.println("  • GET  /health            → 健康检查");
    }
    
    /**
     * 打印 Web Console 信息。
     * 
     * @param gateway 网关实例
     * @param providerConfigured Provider 是否已配置
     */
    private void printWebConsoleInfo(GatewayBootstrap gateway, boolean providerConfigured) {
        System.out.println("✓ Web Console 已启动");
        System.out.println("  • 访问地址: " + gateway.getWebConsoleUrl());
        
        // 如果 Provider 未配置，提示用户通过 Web Console 配置
        if (!providerConfigured) {
            System.out.println();
            System.out.println(GUIDE_WEB_CONSOLE);
            System.out.println("   " + gateway.getWebConsoleUrl() + " -> Settings -> Models");
        }
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
    
    /**
     * Agent 上下文封装类。
     * 
     * 封装 Agent 相关的组件和状态。
     */
    private record AgentContext(AgentRuntime agentRuntime, MessageBus bus, boolean providerConfigured) {
    }
}