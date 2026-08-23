package io.leavesfly.tinyclaw.bootstrap;

import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.collaboration.AgentOrchestrator;
import io.leavesfly.tinyclaw.collaboration.CollaborateTool;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.plugins.PluginManager;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.security.SecurityGuard;
import io.leavesfly.tinyclaw.subagent.SpawnTool;
import io.leavesfly.tinyclaw.subagent.SubagentManager;
import io.leavesfly.tinyclaw.subagent.SubagentsLoader;
import io.leavesfly.tinyclaw.tools.CronTool;
import io.leavesfly.tinyclaw.tools.EditFileTool;
import io.leavesfly.tinyclaw.tools.ExecTool;
import io.leavesfly.tinyclaw.tools.ListDirTool;
import io.leavesfly.tinyclaw.tools.MessageTool;
import io.leavesfly.tinyclaw.tools.ReadFileTool;
import io.leavesfly.tinyclaw.tools.SkillsTool;
import io.leavesfly.tinyclaw.tools.SocialNetworkTool;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;
import io.leavesfly.tinyclaw.tools.TokenUsageTool;
import io.leavesfly.tinyclaw.tools.WebFetchTool;
import io.leavesfly.tinyclaw.tools.WebSearchTool;
import io.leavesfly.tinyclaw.tools.WriteFileTool;

import java.util.List;
import java.util.Map;

/**
 * 运行时装配（组合根）——集中构建 TinyClaw 的对象图。
 *
 * <p>此前装配逻辑位于 {@code cli.CliCommand#registerTools}，而网关又在 {@code GatewayBootstrap}
 * 里第二次构建有状态组件，导致同一份 {@code workspace/cron/jobs.json} 上出现两个
 * {@link CronService} 实例互相全量覆盖、用户创建的定时任务静默丢失。把装配收敛到这里之后，
 * 有状态单例（CronService / SecurityGuard / SubagentManager）在整个进程内只有一份，
 * CLI 与网关都从同一个 assembly 取用。</p>
 *
 * <p>装配与展示解耦：本类不属于 {@code cli} 包，因此嵌入式使用和集成测试可以直接装配运行时，
 * 无需继承某个 CLI 命令。</p>
 */
public final class RuntimeAssembly {

    /** web_fetch 单次抓取的最大字符数。 */
    private static final int WEB_FETCH_MAX_CHARS = 50000;

    /** web_search 单次返回的结果条数。 */
    private static final int WEB_SEARCH_RESULT_COUNT = 5;

    private final Config config;
    private final MessageBus bus;
    private final AgentRuntime agentRuntime;

    /**
     * 全局唯一的定时任务服务。无论 provider 是否配置都会创建，
     * 因为心跳与记忆进化是以内置 cron job 的形式调度的。
     */
    private final CronService cronService;

    /**
     * 与 {@link #cronService} 绑定的定时任务工具。既作为 LLM 工具（provider 就绪时注册），
     * 也作为网关执行用户 job 的入口，两种用途共用同一实例。
     */
    private final CronTool cronTool;

    private final boolean providerConfigured;

    private RuntimeAssembly(Config config, MessageBus bus, AgentRuntime agentRuntime,
                            CronService cronService, CronTool cronTool, boolean providerConfigured) {
        this.config = config;
        this.bus = bus;
        this.agentRuntime = agentRuntime;
        this.cronService = cronService;
        this.cronTool = cronTool;
        this.providerConfigured = providerConfigured;
    }

    /**
     * 装配运行时。
     *
     * <p>provider 为 null 时仍完成装配（网关可先起 Web Console 让用户配置 API Key），
     * 此时跳过内置工具注册，但 CronService 与 CronTool 照常创建。</p>
     *
     * @param config   已加载的配置
     * @param provider LLM Provider，可为 null 表示尚未配置
     * @return 装配结果
     */
    public static RuntimeAssembly assemble(Config config, LLMProvider provider) {
        MessageBus bus = new MessageBus();
        AgentRuntime agentRuntime = new AgentRuntime(config, bus, provider);

        CronService cronService = new CronService(
                CronService.defaultStorePath(config.getWorkspacePath()));
        CronTool cronTool = new CronTool(cronService, agentRuntime::processDirectWithChannel, bus);

        RuntimeAssembly assembly = new RuntimeAssembly(
                config, bus, agentRuntime, cronService, cronTool, provider != null);

        if (provider != null) {
            assembly.registerBuiltinTools(provider);
        }
        return assembly;
    }

    /**
     * 注册内置工具。仅在 provider 就绪时调用——子代理与协同工具都需要 provider 才能工作。
     *
     * @param provider 已就绪的 LLM Provider
     */
    private void registerBuiltinTools(LLMProvider provider) {
        String workspace = config.getWorkspacePath();
        SecurityGuard securityGuard = createSecurityGuard(workspace);

        // 文件工具（SecurityGuard 强制注入）
        agentRuntime.registerTool(new ReadFileTool(securityGuard));
        agentRuntime.registerTool(new WriteFileTool(securityGuard));
        agentRuntime.registerTool(new ListDirTool(securityGuard));
        agentRuntime.registerTool(new EditFileTool(securityGuard));

        // 执行工具
        agentRuntime.registerTool(new ExecTool(workspace, securityGuard));

        // 网络工具
        String braveApiKey = config.getTools() != null ? config.getTools().getBraveApi() : null;
        if (braveApiKey != null && !braveApiKey.isEmpty()) {
            agentRuntime.registerTool(new WebSearchTool(braveApiKey, WEB_SEARCH_RESULT_COUNT));
        }
        agentRuntime.registerTool(new WebFetchTool(WEB_FETCH_MAX_CHARS));

        // 消息工具
        MessageTool messageTool = new MessageTool();
        messageTool.setSendCallback((channel, chatId, content) ->
                bus.publishOutbound(new OutboundMessage(channel, chatId, content)));
        agentRuntime.registerTool(messageTool);

        // 定时任务工具（复用装配期创建的单实例，不再另建 CronService）
        agentRuntime.registerTool(cronTool);

        // 子代理工具（传入 ToolRegistry 以支持工具调用和 Agent Loop）
        SubagentManager subagentManager = new SubagentManager(
                provider, workspace, bus, agentRuntime.getToolRegistry(),
                config.getAgent().getModel(), config.getAgent().getMaxToolIterations());
        // 注入动态子代理定义加载器（workspace/agents/<name>/AGENT.md，支持运行时热更新）
        subagentManager.setAgentsLoader(new SubagentsLoader(workspace));
        agentRuntime.registerTool(new SpawnTool(subagentManager));
        agentRuntime.registerShutdownHook(subagentManager::shutdown);

        // 技能管理工具（共享 SkillsLoader 实例，确保与 ContextBuilder 的技能视图一致）
        agentRuntime.registerTool(new SkillsTool(workspace, agentRuntime.getSkillsLoader(),
                config.getTools() != null ? config.getTools().getSkills() : null));

        // 社交网络工具
        if (config.getSocialNetwork() != null && config.getSocialNetwork().isEnabled()) {
            agentRuntime.registerTool(new SocialNetworkTool(
                    config.getSocialNetwork().getEndpoint(),
                    config.getSocialNetwork().getAgentId(),
                    config.getSocialNetwork().getApiKey()));
        }

        // Token 消耗查询工具
        TokenUsageStore tokenUsageStore = agentRuntime.getTokenUsageStore();
        if (tokenUsageStore != null) {
            agentRuntime.registerTool(new TokenUsageTool(tokenUsageStore));
        }

        registerCollaborateTool(provider);
    }

    /**
     * 构造 SecurityGuard。自定义黑名单为空时走默认黑名单构造器。
     *
     * @param workspace 工作空间根目录
     * @return 安全守卫实例
     */
    private SecurityGuard createSecurityGuard(String workspace) {
        boolean restrictToWorkspace = config.getAgent().isRestrictToWorkspace();
        List<String> customBlacklist = config.getAgent().getCommandBlacklist();
        if (customBlacklist != null && !customBlacklist.isEmpty()) {
            return new SecurityGuard(workspace, restrictToWorkspace, customBlacklist);
        }
        return new SecurityGuard(workspace, restrictToWorkspace);
    }

    /**
     * 注册多 Agent 协同工具，仅在协同编排器就绪时生效。
     *
     * @param provider 已就绪的 LLM Provider
     */
    private void registerCollaborateTool(LLMProvider provider) {
        AgentOrchestrator orchestrator = agentRuntime.getOrchestrator();
        if (orchestrator == null) {
            return;
        }
        CollaborateTool collaborateTool = new CollaborateTool(orchestrator);
        collaborateTool.setLLMContext(provider, config.getAgent().getModel());

        // 注入插件注册的 agent 角色库，使主 Agent 可在 collaborate 中按名复用插件 agent
        PluginManager pluginManager = agentRuntime.getPluginManager();
        if (pluginManager != null && !pluginManager.getPluginAgentsByName().isEmpty()) {
            collaborateTool.setPluginAgents(pluginManager.getPluginAgentsByName());
        }
        agentRuntime.registerTool(collaborateTool);
    }

    public Config config() {
        return config;
    }

    public MessageBus bus() {
        return bus;
    }

    public AgentRuntime agentRuntime() {
        return agentRuntime;
    }

    public CronService cronService() {
        return cronService;
    }

    public CronTool cronTool() {
        return cronTool;
    }

    /** provider 是否已配置。false 表示内置工具未注册，Agent 只能用于配置态。 */
    public boolean isProviderConfigured() {
        return providerConfigured;
    }

    /**
     * 获取启动摘要（工具数、技能数），供 CLI 打印。
     *
     * @return 启动信息
     */
    public Map<String, Object> startupInfo() {
        return agentRuntime.getStartupInfo();
    }
}
