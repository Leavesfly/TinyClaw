package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.Map;
import java.util.Scanner;

/**
 * Agent 命令 - 直接与 Agent 交互
 *
 */
public class AgentCommand extends CliCommand {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cli");
    
    @Override
    public String name() {
        return "agent";
    }
    
    @Override
    public String description() {
        return "直接与 Agent 交互";
    }
    
    @Override
    public int execute(String[] args) throws Exception {
        String message = "";
        String sessionKey = "cli:default";
        boolean debug = false;
        
        // 解析参数
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--debug") || arg.equals("-d")) {
                debug = true;
                System.out.println("🔍 Debug mode enabled");
            } else if (arg.equals("-m") || arg.equals("--message")) {
                if (i + 1 < args.length) {
                    message = args[++i];
                }
            } else if (arg.equals("-s") || arg.equals("--session")) {
                if (i + 1 < args.length) {
                    sessionKey = args[++i];
                }
            }
        }
        
        // 加载配置
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }
        
        // 创建服务提供者
        LLMProvider provider = createProviderOrNull(config);
        if (provider == null) {
            return 1;
        }
        
        // 创建消息总线和 Agent 循环
        MessageBus bus = new MessageBus();
        AgentLoop agentLoop = new AgentLoop(config, bus, provider);
        
        // 注册工具
        registerTools(agentLoop, config, bus, provider);
        
        // 打印启动信息
        Map<String, Object> startupInfo = agentLoop.getStartupInfo();
        @SuppressWarnings("unchecked")
        Map<String, Object> toolsInfo = (Map<String, Object>) startupInfo.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillsInfo = (Map<String, Object>) startupInfo.get("skills");
        
        logger.info("Agent initialized", Map.of(
                "tools_count", toolsInfo.get("count"),
                "skills_total", skillsInfo.get("total"),
                "skills_available", skillsInfo.get("available")
        ));
        
        if (!message.isEmpty()) {
            // 单条消息模式
            String response = agentLoop.processDirect(message, sessionKey);
            System.out.println();
            System.out.println(LOGO + " " + response);
        } else {
            // 交互模式
            System.out.println(LOGO + " 交互模式 (Ctrl+C to exit)");
            System.out.println();
            interactiveMode(agentLoop, sessionKey);
        }
        
        return 0;
    }
    
    private void interactiveMode(AgentLoop agentLoop, String sessionKey) {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print(LOGO + " 你: ");
            String input;
            try {
                input = scanner.nextLine().trim();
            } catch (Exception e) {
                System.out.println("\n再见！");
                break;
            }
            
            if (input.isEmpty()) {
                continue;
            }
            
            if (input.equals("exit") || input.equals("quit")) {
                System.out.println("再见！");
                break;
            }
            
            try {
                String response = agentLoop.processDirect(input, sessionKey);
                System.out.println();
                System.out.println(LOGO + " " + response);
                System.out.println();
            } catch (Exception e) {
                System.err.println("错误: " + e.getMessage());
            }
        }
    }
    
    @Override
    public void printHelp() {
        System.out.println(LOGO + " tinyclaw agent - 直接与 Agent 交互");
        System.out.println();
        System.out.println("Usage: tinyclaw agent [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -m, --message <text>    发送单条消息并退出");
        System.out.println("  -s, --session <key>     会话键（默认：cli:default）");
        System.out.println("  -d, --debug             启用调试模式");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  tinyclaw agent                         # 交互模式");
        System.out.println("  tinyclaw agent -m \"Hello!\"            # 单条消息");
        System.out.println("  tinyclaw agent -s my-session -m \"Hi\"  # 自定义会话");
    }
}