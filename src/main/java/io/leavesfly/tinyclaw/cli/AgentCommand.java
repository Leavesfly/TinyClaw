package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        // 每次启动生成新的会话 ID，避免历史污染
        String sessionKey = generateSessionKey();
        boolean debug = false;
        boolean stream = true;  // 默认启用流式输出
        
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
            } else if (arg.equals("--no-stream")) {
                stream = false;
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
            System.out.println();
            System.out.print(LOGO + ": ");
            
            if (stream) {
                // 流式输出
                agentLoop.processDirectStream(message, sessionKey, chunk -> {
                    System.out.print(chunk);
                    System.out.flush();
                });
                System.out.println();
            } else {
                // 非流式输出
                String response = agentLoop.processDirect(message, sessionKey);
                System.out.println(response);
            }
        } else {
            // 交互模式
            System.out.println(LOGO + " 交互模式 (Ctrl+C to exit)");
            if (stream) {
                System.out.println("🚀 流式输出已启用 (使用 --no-stream 关闭)");
            }
            System.out.println();
            interactiveMode(agentLoop, sessionKey, stream);
        }
        
        return 0;
    }
    
    private void interactiveMode(AgentLoop agentLoop, String sessionKey, boolean stream) {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("你: ");
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
                System.out.println();
                System.out.print(LOGO + ": ");
                
                if (stream) {
                    // 流式输出
                    agentLoop.processDirectStream(input, sessionKey, chunk -> {
                        System.out.print(chunk);
                        System.out.flush();
                    });
                    System.out.println();
                } else {
                    // 非流式输出
                    String response = agentLoop.processDirect(input, sessionKey);
                    System.out.println(response);
                }
                
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
        System.out.println("  -s, --session <key>     指定会话键（默认每次启动创建新会话）");
        System.out.println("  -d, --debug             启用调试模式");
        System.out.println("  --no-stream             禁用流式输出（默认启用）");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  tinyclaw agent                         # 交互模式（流式）");
        System.out.println("  tinyclaw agent --no-stream             # 交互模式（非流式）");
        System.out.println("  tinyclaw agent -m \"Hello!\"            # 单条消息");
        System.out.println("  tinyclaw agent -s my-session -m \"Hi\"  # 指定会话（用于恢复历史对话）");
    }
    
    /**
     * 生成唯一的会话 ID，格式: cli_yyyyMMdd_HHmmss
     */
    private String generateSessionKey() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return "cli_" + LocalDateTime.now().format(formatter);
    }
}