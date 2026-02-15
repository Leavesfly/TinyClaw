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
 * Agent 命令，直接与 Agent 交互。
 * 
 * 提供两种交互模式：
 * - 单条消息模式：发送一条消息后退出
 * - 交互模式：持续对话直到用户退出
 * 
 * 支持的功能：
 * - 流式输出：实时显示 Agent 响应（默认启用）
 * - 会话管理：自动生成或手动指定会话 ID
 * - 调试模式：显示详细的运行信息
 * 
 * 使用场景：
 * - 快速测试 Agent 功能
 * - 命令行中进行对话
 * - 调试 Agent 行为
 */
public class AgentCommand extends CliCommand {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cli");
    
    private static final String EXIT_COMMAND = "exit";       // 退出命令
    private static final String QUIT_COMMAND = "quit";       // 退出命令（别名）
    private static final String SESSION_PREFIX = "cli_";     // 会话 ID 前缀
    private static final String PROMPT_USER = "你: ";         // 用户输入提示符
    private static final String PROMPT_SEPARATOR = ": ";     // Agent 响应提示符分隔符
    
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
        // 解析命令行参数
        CommandArgs cmdArgs = parseArguments(args);
        
        // 加载配置并创建 Agent
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }
        
        AgentLoop agentLoop = createAndInitializeAgent(config);
        if (agentLoop == null) {
            return 1;
        }
        
        // 执行相应模式
        if (cmdArgs.hasMessage()) {
            executeSingleMessageMode(agentLoop, cmdArgs);
        } else {
            executeInteractiveMode(agentLoop, cmdArgs);
        }
        
        return 0;
    }
    
    /**
     * 解析命令行参数。
     * 
     * @param args 命令行参数数组
     * @return 解析后的参数对象
     */
    private CommandArgs parseArguments(String[] args) {
        String message = "";
        String sessionKey = generateSessionKey();
        boolean debug = false;
        boolean stream = true;
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--debug", "-d" -> {
                    debug = true;
                    System.out.println("🔍 Debug mode enabled");
                }
                case "-m", "--message" -> {
                    if (i + 1 < args.length) {
                        message = args[++i];
                    }
                }
                case "-s", "--session" -> {
                    if (i + 1 < args.length) {
                        sessionKey = args[++i];
                    }
                }
                case "--no-stream" -> stream = false;
            }
        }
        
        return new CommandArgs(message, sessionKey, debug, stream);
    }
    
    /**
     * 创建并初始化 Agent。
     * 
     * @param config 配置对象
     * @return Agent 实例，失败时返回 null
     */
    private AgentLoop createAndInitializeAgent(Config config) {
        // 创建服务提供者
        LLMProvider provider = createProviderOrNull(config);
        if (provider == null) {
            return null;
        }
        
        // 创建消息总线和 Agent 循环
        MessageBus bus = new MessageBus();
        AgentLoop agentLoop = new AgentLoop(config, bus, provider);
        
        // 注册工具
        registerTools(agentLoop, config, bus, provider);
        
        // 打印启动信息
        logStartupInfo(agentLoop);
        
        return agentLoop;
    }
    
    /**
     * 记录启动信息。
     * 
     * @param agentLoop Agent 实例
     */
    private void logStartupInfo(AgentLoop agentLoop) {
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
    }
    
    /**
     * 执行单条消息模式。
     * 
     * @param agentLoop Agent 实例
     * @param cmdArgs 命令参数
     */
    private void executeSingleMessageMode(AgentLoop agentLoop, CommandArgs cmdArgs) throws Exception {
        System.out.println();
        System.out.print(LOGO + PROMPT_SEPARATOR);
        
        if (cmdArgs.stream) {
            processStreamResponse(agentLoop, cmdArgs.message, cmdArgs.sessionKey);
        } else {
            processNonStreamResponse(agentLoop, cmdArgs.message, cmdArgs.sessionKey);
        }
    }
    
    /**
     * 执行交互模式。
     * 
     * @param agentLoop Agent 实例
     * @param cmdArgs 命令参数
     */
    private void executeInteractiveMode(AgentLoop agentLoop, CommandArgs cmdArgs) {
        System.out.println(LOGO + " 交互模式 (Ctrl+C to exit)");
        if (cmdArgs.stream) {
            System.out.println("🚀 流式输出已启用 (使用 --no-stream 关闭)");
        }
        System.out.println();
        interactiveMode(agentLoop, cmdArgs.sessionKey, cmdArgs.stream);
    }
    
    /**
     * 处理流式响应。
     * 
     * @param agentLoop Agent 实例
     * @param message 用户消息
     * @param sessionKey 会话键
     */
    private void processStreamResponse(AgentLoop agentLoop, String message, String sessionKey) throws Exception {
        agentLoop.processDirectStream(message, sessionKey, chunk -> {
            System.out.print(chunk);
            System.out.flush();
        });
        System.out.println();
    }
    
    /**
     * 处理非流式响应。
     * 
     * @param agentLoop Agent 实例
     * @param message 用户消息
     * @param sessionKey 会话键
     */
    private void processNonStreamResponse(AgentLoop agentLoop, String message, String sessionKey) throws Exception {
        String response = agentLoop.processDirect(message, sessionKey);
        System.out.println(response);
    }
    
    /**
     * 交互模式主循环。
     * 
     * @param agentLoop Agent 实例
     * @param sessionKey 会话键
     * @param stream 是否启用流式输出
     */
    private void interactiveMode(AgentLoop agentLoop, String sessionKey, boolean stream) {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print(PROMPT_USER);
            
            String input = readUserInput(scanner);
            if (input == null) {
                break;
            }
            
            if (input.isEmpty()) {
                continue;
            }
            
            if (isExitCommand(input)) {
                System.out.println("再见！");
                break;
            }
            
            processUserInput(agentLoop, input, sessionKey, stream);
        }
    }
    
    /**
     * 读取用户输入。
     * 
     * @param scanner 输入扫描器
     * @return 用户输入字符串，异常时返回 null
     */
    private String readUserInput(Scanner scanner) {
        try {
            return scanner.nextLine().trim();
        } catch (Exception e) {
            System.out.println("\n再见！");
            return null;
        }
    }
    
    /**
     * 判断是否为退出命令。
     * 
     * @param input 用户输入
     * @return 是否为退出命令
     */
    private boolean isExitCommand(String input) {
        return EXIT_COMMAND.equals(input) || QUIT_COMMAND.equals(input);
    }
    
    /**
     * 处理用户输入并显示响应。
     * 
     * @param agentLoop Agent 实例
     * @param input 用户输入
     * @param sessionKey 会话键
     * @param stream 是否启用流式输出
     */
    private void processUserInput(AgentLoop agentLoop, String input, String sessionKey, boolean stream) {
        try {
            System.out.println();
            System.out.print(LOGO + PROMPT_SEPARATOR);
            
            if (stream) {
                processStreamResponse(agentLoop, input, sessionKey);
            } else {
                processNonStreamResponse(agentLoop, input, sessionKey);
            }
            
            System.out.println();
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
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
     * 生成唯一的会话 ID。
     * 
     * 格式：cli_yyyyMMdd_HHmmss
     * 
     * @return 会话 ID
     */
    private String generateSessionKey() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return SESSION_PREFIX + LocalDateTime.now().format(formatter);
    }
    
    /**
     * 命令行参数封装类。
     */
    private record CommandArgs(String message, String sessionKey, boolean debug, boolean stream) {
        boolean hasMessage() {
            return !message.isEmpty();
        }
    }
}