package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.Map;

/**
 * Demo 命令 - 一键运行可复现的演示流程
 *
 * <p>当前支持的子模式：</p>
 * <ul>
 *   <li><code>agent-basic</code>：构造一个固定问题，直接通过 AgentLoop 跑完一轮 CLI 对话链路，方便现场演示。</li>
 * </ul>
 *
 * <p>学习/演示提示：
 * 结合 README 中的“5 分钟 Demo / Demo 1”，可以先执行
 * <code>tinyclaw demo agent-basic</code>，再对照 TinyClaw → DemoCommand → AgentLoop 的调用关系，
 * 向听众讲解从配置加载、LLM Provider 初始化，到一次完整推理流程的关键步骤。</p>
 */
public class DemoCommand extends CliCommand {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("cli");

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public String description() {
        return "运行内置 Demo 流程（如 agent-basic）";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 1;
        }

        String mode = args[0];
        if ("agent-basic".equals(mode)) {
            return runAgentBasicDemo();
        } else {
            System.out.println("未知 Demo 模式: " + mode);
            printHelp();
            return 1;
        }
    }

    private int runAgentBasicDemo() {
        System.out.println(LOGO + " Running demo: agent-basic");
        System.out.println("这个 Demo 会加载配置、初始化 LLMProvider 和 AgentLoop，然后用一个固定问题跑完一次 CLI 对话流程。\n");

        // 1. 加载配置
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            System.err.println("Error loading config: " + e.getMessage());
            System.err.println("请先运行 'tinyclaw onboard' 完成初始化，并配置好 API Key。");
            return 1;
        }

        // 2. 创建 LLM Provider（沿用 openrouter/openai 优先策略）
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

        // 3. 创建 MessageBus 和 AgentLoop
        MessageBus bus = new MessageBus();
        AgentLoop agentLoop = new AgentLoop(config, bus, provider);

        Map<String, Object> startupInfo = agentLoop.getStartupInfo();
        @SuppressWarnings("unchecked")
        Map<String, Object> toolsInfo = (Map<String, Object>) startupInfo.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillsInfo = (Map<String, Object>) startupInfo.get("skills");

        logger.info("Demo agent initialized", Map.of(
                "tools_count", toolsInfo.get("count"),
                "skills_total", skillsInfo.get("total"),
                "skills_available", skillsInfo.get("available")
        ));

        // 4. 构造固定问题并调用 processDirect
        String sessionKey = "demo:agent-basic";
        String question = "请用 2~3 句中文介绍一下 tinyclaw 的架构，并简要说明一次消息是如何从命令行流经 Agent 再返回给用户的。";

        System.out.println("示例问题: " + question + "\n");
        try {
            String answer = agentLoop.processDirect(question, sessionKey);
            System.out.println(LOGO + " Demo 响应:\n");
            System.out.println(answer);
            System.out.println();
            System.out.println("（可以打开 AgentLoop、MessageBus、ToolRegistry 等类，对照这次 Demo 的日志来讲解内部流程。）");
        } catch (Exception e) {
            System.err.println("运行 Demo 时出错: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " tinyclaw demo - 运行内置演示流程");
        System.out.println();
        System.out.println("Usage: tinyclaw demo <mode>");
        System.out.println();
        System.out.println("可用模式:");
        System.out.println("  agent-basic    加载配置并跑一轮固定问题的 CLI 对话演示");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  tinyclaw demo agent-basic");
    }
}
