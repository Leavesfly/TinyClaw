package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.List;

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
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }
        
        // 创建服务提供者（允许为 null）
        LLMProvider provider = createProviderOrNull(config);
        boolean providerConfigured = (provider != null);
        
        if (!providerConfigured) {
            System.out.println();
            System.out.println("⚠️  LLM Provider 未配置，但仍可启动 Web Console 进行配置");
            System.out.println();
        }
        
        // 创建消息总线和 Agent 循环
        MessageBus bus = new MessageBus();
        AgentLoop agentLoop = new AgentLoop(config, bus, provider);
        
        // 打印启动信息
        if (providerConfigured) {
            printAgentStatus(agentLoop);
        }
        
        // 注册工具（如果 provider 存在）
        if (providerConfigured) {
            registerTools(agentLoop, config, bus, provider);
        }
        
        // 创建并启动网关
        GatewayBootstrap gateway = new GatewayBootstrap(config, agentLoop, bus)
                .initialize()
                .start();
        
        // 打印启动信息
        printStartupInfo(gateway, config, providerConfigured);
        
        // 等待关闭
        gateway.awaitShutdown();
        
        return 0;
    }
    
    /**
     * 打印网关启动信息
     */
    private void printStartupInfo(GatewayBootstrap gateway, Config config, boolean providerConfigured) {
        // 获取启用的通道列表
        List<String> enabledChannels = gateway.getEnabledChannels();
        if (!enabledChannels.isEmpty()) {
            System.out.println("✓ 已启用通道: " + String.join(", ", enabledChannels));
        } else {
            System.out.println("⚠ 警告: 没有启用任何通道");
        }
        
        System.out.println("✓ 网关已启动于 " + config.getGateway().getHost() + ":" + config.getGateway().getPort());
        System.out.println("按 Ctrl+C 停止");
        
        System.out.println("✓ 定时任务服务已启动");
        System.out.println("✓ 心跳服务已启动");
        System.out.println("✓ 通道服务已启动");
        
        System.out.println("✓ Webhook Server 已启动（" + gateway.getWebhookUrl() + "）");
        System.out.println("  • POST /webhook/dingtalk  → 钉钉回调");
        System.out.println("  • POST /webhook/feishu    → 飞书回调");
        System.out.println("  • POST /webhook/qq        → QQ 回调");
        System.out.println("  • GET  /health            → 健康检查");
        
        System.out.println("✓ Web Console 已启动");
        System.out.println("  • 访问地址: " + gateway.getWebConsoleUrl());
        
        // 如果 Provider 未配置，提示用户通过 Web Console 配置
        if (!providerConfigured) {
            System.out.println();
            System.out.println("👉 请访问 Web Console 配置 LLM Provider:");
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
}