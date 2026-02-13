package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import io.leavesfly.tinyclaw.tools.*;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Scanner;

/**
 * Agent 命令 - 直接与 Agent 交互
 *
 * <p>学习提示：这是从 CLI 到 AgentLoop 的桥梁类，配合 README 中的“5 分钟 Demo”里 Demo 1 使用，
 * 可以很清楚地看到从命令行参数解析，到创建 MessageBus/HTTPProvider/AgentLoop，再到调用 processDirect 的完整链路。</p>
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
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            System.err.println("Error loading config: " + e.getMessage());
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
                throw new IllegalStateException("No API key configured. Please set OpenRouter or OpenAI API key.");
            }
            provider = new HTTPProvider(apiKey, apiBase != null ? apiBase : "https://openrouter.ai/api/v1");
        } catch (Exception e) {
            System.err.println("Error creating provider: " + e.getMessage());
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
    
    private void registerTools(AgentLoop agentLoop, Config config, MessageBus bus, LLMProvider provider) {
        String workspace = config.getWorkspacePath();
        
        // 文件工具（无需工作区 - 工具自己处理相对路径）
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
        
        // 定时任务工具（CLI 模式简化版）
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
        
        // 技能管理工具（赋予 AI 自主学习和管理技能的能力）
        agentLoop.registerTool(new SkillsTool(workspace));
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