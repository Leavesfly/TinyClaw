package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.agent.AgentLoop;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.config.ModelsConfig;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.security.SecurityGuard;

import io.leavesfly.tinyclaw.tools.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 命令的基类
 */
public abstract class CliCommand {
    
    protected static final String LOGO = "🦞";
    protected static final String VERSION = "0.1.0";
    protected static final TinyClawLogger logger = TinyClawLogger.getLogger("cli");
    
    /**
     * 获取命令名称
     */
    public abstract String name();
    
    /**
     * 获取命令描述
     */
    public abstract String description();
    
    /**
     * 执行命令
     * @return 退出码（0 表示成功）
     */
    public abstract int execute(String[] args) throws Exception;
    
    /**
     * 打印此命令的帮助信息
     */
    public void printHelp() {
        System.out.println(name() + " - " + description());
    }
    
    /**
     * 将命令行参数解析为键值对
     */
    protected Map<String, String> parseArgs(String[] args, int startIndex) {
        Map<String, String> result = new HashMap<>();
        
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    result.put(key, args[++i]);
                } else {
                    result.put(key, "true");
                }
            } else if (arg.startsWith("-")) {
                String key = arg.substring(1);
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    result.put(key, args[++i]);
                } else {
                    result.put(key, "true");
                }
            }
        }
        
        return result;
    }
    
    /**
     * 获取配置文件路径
     */
    protected String getConfigPath() {
        String home = System.getProperty("user.home");
        return home + "/.tinyclaw/config.json";
    }
    
    /**
     * 加载配置文件，失败时打印友好提示
     * @return Config 对象，失败返回 null
     */
    protected Config loadConfig() {
        String configPath = getConfigPath();
        File configFile = new File(configPath);
        
        if (!configFile.exists()) {
            printConfigNotFoundError(configPath);
            return null;
        }
        
        try {
            return ConfigLoader.load(configPath);
        } catch (Exception e) {
            System.err.println();
            System.err.println(LOGO + " 配置文件加载失败");
            System.err.println();
            System.err.println("  原因: " + e.getMessage());
            System.err.println("  路径: " + configPath);
            System.err.println();
            System.err.println("请检查配置文件格式是否正确，或重新运行:");
            System.err.println("  tinyclaw onboard");
            System.err.println();
            return null;
        }
    }
    
    /**
     * 打印配置文件不存在的友好错误提示
     */
    private void printConfigNotFoundError(String configPath) {
        System.err.println();
        System.err.println(LOGO + " 欢迎使用 TinyClaw!");
        System.err.println();
        System.err.println("  看起来这是你第一次运行，需要先初始化配置。");
        System.err.println();
        System.err.println("  请运行以下命令开始:");
        System.err.println("    tinyclaw onboard");
        System.err.println();
        System.err.println("  这将会:");
        System.err.println("    • 创建配置文件 " + configPath);
        System.err.println("    • 初始化工作空间目录");
        System.err.println("    • 生成模板文件");
        System.err.println();
    }
    
    /**
     * 创建 LLM Provider，失败时打印友好提示
     * @return LLMProvider 对象，失败返回 null
     */
    protected LLMProvider createProviderOrNull(Config config) {
        try {
            return createProvider(config);
        } catch (Exception e) {
            printProviderError(e.getMessage());
            return null;
        }
    }
    
    /**
     * 创建 LLM Provider，优先按当前 model 定义选择对应的 provider。
     *
     * 解析顺序：
     * 1. 从 ModelsConfig 中查找当前 model 对应的 provider（保证 api_base 与 model 一致）
     * 2. 若 model 未在 ModelsConfig 中定义，则 fallback 到第一个有效的 provider
     */
    protected LLMProvider createProvider(Config config) {
        ProvidersConfig providers = config.getProviders();
        String modelName = config.getAgent().getModel();

        // 优先从 ModelsConfig 中通过 model 反查 provider，保证 api_base 与 model 绑定一致
        ModelsConfig.ModelDefinition modelDef = config.getModels().getDefinitions().get(modelName);
        if (modelDef != null) {
            String providerName = modelDef.getProvider();
            ProvidersConfig.ProviderConfig providerConfig = resolveProviderConfig(providers, providerName);
            if (providerConfig != null && (providerConfig.isValid() || providerConfig.isValidForLocal())) {
                String apiBase = providerConfig.getApiBase();
                if (apiBase == null || apiBase.isEmpty()) {
                    apiBase = ProvidersConfig.getDefaultApiBase(providerName);
                }
                return new HTTPProvider(providerConfig.getApiKey(), apiBase);
            }
            // model 对应的 provider 未配置 apiKey，抛出明确的错误提示
            throw new IllegalStateException(
                "模型 \"" + modelName + "\" 对应的 Provider \"" + providerName + "\" 未配置 API Key，" +
                "请通过 Web Console -> Settings -> Models 配置后重试。"
            );
        }

        // model 未在 ModelsConfig 中定义时，fallback 到第一个有效的 provider
        logger.warn("Model not found in ModelsConfig, falling back to first valid provider",
                Map.of("model", modelName));
        ProvidersConfig.ProviderConfig providerConfig = providers.getFirstValidProvider()
            .orElseThrow(() -> new IllegalStateException("未配置 API 密钥"));

        String providerName = providers.getProviderName(providerConfig);
        String apiBase = providerConfig.getApiBase();
        if (apiBase == null || apiBase.isEmpty()) {
            apiBase = ProvidersConfig.getDefaultApiBase(providerName);
        }
        return new HTTPProvider(providerConfig.getApiKey(), apiBase);
    }

    /**
     * 根据 provider 名称从 ProvidersConfig 中查找对应的 ProviderConfig。
     */
    private ProvidersConfig.ProviderConfig resolveProviderConfig(ProvidersConfig providers, String providerName) {
        return switch (providerName) {
            case "openrouter" -> providers.getOpenrouter();
            case "openai"     -> providers.getOpenai();
            case "anthropic"  -> providers.getAnthropic();
            case "zhipu"      -> providers.getZhipu();
            case "dashscope"  -> providers.getDashscope();
            case "gemini"     -> providers.getGemini();
            case "ollama"     -> providers.getOllama();
            default -> null;
        };
    }
    
    /**
     * 打印 Provider 创建失败的友好错误提示
     */
    private void printProviderError(String message) {
        System.err.println();
        System.err.println(LOGO + " LLM 服务初始化失败");
        System.err.println();
        System.err.println("  原因: " + message);
        System.err.println();
        System.err.println("  请在配置文件中设置至少一个 Provider 的 API Key:");
        System.err.println("    " + getConfigPath());
        System.err.println();
        System.err.println("  支持的 Provider:");
        System.err.println("    • openrouter  - https://openrouter.ai/keys");
        System.err.println("    • openai      - https://platform.openai.com/api-keys");
        System.err.println("    • anthropic   - https://console.anthropic.com/");
        System.err.println("    • zhipu       - https://open.bigmodel.cn/");
        System.err.println("    • dashscope   - https://dashscope.console.aliyun.com/");
        System.err.println("    • ollama      - 本地部署，无需 API Key");
        System.err.println();
    }
    
    /**
     * 注册常用工具到 AgentLoop
     */
    protected void registerTools(AgentLoop agentLoop, Config config, MessageBus bus, LLMProvider provider) {
        String workspace = config.getWorkspacePath();
        
        // 初始化 SecurityGuard
        SecurityGuard securityGuard = null;
        if (config.getAgent().isRestrictToWorkspace()) {
            List<String> customBlacklist = config.getAgent().getCommandBlacklist();
            if (customBlacklist != null && !customBlacklist.isEmpty()) {
                securityGuard = new SecurityGuard(workspace, true, customBlacklist);
            } else {
                securityGuard = new SecurityGuard(workspace, true);
            }
        }
        
        // 文件工具
        agentLoop.registerTool(securityGuard != null ? new ReadFileTool(securityGuard) : new ReadFileTool());
        agentLoop.registerTool(securityGuard != null ? new WriteFileTool(securityGuard) : new WriteFileTool());
        agentLoop.registerTool(securityGuard != null ? new AppendFileTool(securityGuard) : new AppendFileTool());
        agentLoop.registerTool(securityGuard != null ? new ListDirTool(securityGuard) : new ListDirTool());
        
        // 文件编辑工具
        agentLoop.registerTool(securityGuard != null ? new EditFileTool(securityGuard) : new EditFileTool(workspace));
        
        // 执行工具
        agentLoop.registerTool(new ExecTool(workspace, securityGuard));
        
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
        
        // 子代理工具（传入 ToolRegistry 以支持工具调用和 Agent Loop）
        SubagentManager subagentManager = new SubagentManager(provider, workspace, bus, agentLoop.getToolRegistry());
        agentLoop.registerTool(new SpawnTool(subagentManager));
        
        // 技能管理工具（共享 SkillsLoader 实例，确保与 ContextBuilder 的技能视图一致）
        agentLoop.registerTool(new SkillsTool(workspace, agentLoop.getSkillsLoader(),
                config.getTools() != null ? config.getTools().getSkills() : null));
        
        // 社交网络工具
        if (config.getSocialNetwork() != null && config.getSocialNetwork().isEnabled()) {
            agentLoop.registerTool(new SocialNetworkTool(
                config.getSocialNetwork().getEndpoint(),
                config.getSocialNetwork().getAgentId(),
                config.getSocialNetwork().getApiKey()
            ));
        }
    }
    
    /**
     * 打印 Agent 启动状态信息
     */
    protected void printAgentStatus(AgentLoop agentLoop) {
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
    }
}