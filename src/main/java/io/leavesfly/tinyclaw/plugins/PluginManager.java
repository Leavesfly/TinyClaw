package io.leavesfly.tinyclaw.plugins;

import io.leavesfly.tinyclaw.collaboration.AgentRole;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.config.PluginsConfig;
import io.leavesfly.tinyclaw.hooks.HookRegistry;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.skills.SkillsLoader;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件装配总线。
 *
 * <p>串联插件的发现、白/黑名单校验、注册，并把各组件接入 TinyClaw 运行时：</p>
 * <ul>
 *   <li>技能组件 → 注入 {@link SkillsLoader} 的额外技能根</li>
 *   <li>MCP 组件 → 收集为 {@link MCPServersConfig.MCPServerConfig}，合并进现有 MCP 装配</li>
 *   <li>hooks 组件 → 适配为 {@link HookRegistry}，合并进现有 HookDispatcher</li>
 * </ul>
 *
 * <p>发现顺序：workspace/plugins → ~/.tinyclaw/plugins → 配置 {@code plugins.load.paths}。
 * 同 id 插件先发现者优先。全部操作不执行插件代码。</p>
 */
public class PluginManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    private final Config config;
    private final String workspace;
    private final PluginRegistry registry = new PluginRegistry();
    private final PluginDiscovery discovery = new PluginDiscovery(new ManifestParser());
    private final McpComponentAdapter mcpAdapter = new McpComponentAdapter();
    private final HookComponentAdapter hookAdapter = new HookComponentAdapter();
    private final AgentComponentAdapter agentAdapter = new AgentComponentAdapter();

    /** 从插件收集到的 MCP server 配置，待合并进全局 MCP 装配。 */
    private final List<MCPServersConfig.MCPServerConfig> collectedMcpServers = new ArrayList<>();

    /** 从插件收集并合并后的 hooks 注册表，待叠加进全局 HookDispatcher。 */
    private HookRegistry pluginHookRegistry = HookRegistry.EMPTY;

    /** 从插件收集到的 agent 角色列表（保持发现顺序）。 */
    private final List<AgentRole> pluginAgentRoles = new ArrayList<>();

    /** 插件 agent 命名索引（roleName -&gt; 角色，先注册者优先，供 CollaborateTool 按名引用）。 */
    private final Map<String, AgentRole> pluginAgentsByName = new LinkedHashMap<>();

    public PluginManager(Config config) {
        this.config = config;
        this.workspace = config.getWorkspacePath();
    }

    /**
     * 初始化插件系统：发现、过滤、注册，并注入技能组件、收集 MCP 组件。
     *
     * @param skillsLoader 技能加载器，用于注入插件自带 skills 目录（可为 null）
     */
    public void initialize(SkillsLoader skillsLoader) {
        PluginsConfig pc = config.getPlugins();
        if (pc == null || !pc.isEnabled()) {
            return;
        }

        List<PluginManifest> discovered = discovery.discover(resolveLoadRoots(pc));
        int registered = 0;
        for (PluginManifest manifest : discovered) {
            String id = manifest.getId();
            if (!pc.isPluginAllowed(id)) {
                logger.info("插件未通过 allow/deny 校验，已跳过", Map.of("plugin", id));
                continue;
            }
            if (registry.has(id)) {
                // 同 id 先发现者优先
                continue;
            }
            registry.register(manifest);
            registered++;

            injectSkills(manifest, skillsLoader);
            collectMcpServers(manifest, pc);
            collectHooks(manifest, pc);
            collectAgents(manifest, pc);
        }

        logger.info("插件系统初始化完成", Map.of(
                "discovered", discovered.size(),
                "registered", registered,
                "mcp_servers", collectedMcpServers.size(),
                "hooks", pluginHookRegistry.isEmpty() ? "none" : "loaded",
                "agents", pluginAgentRoles.size()));
    }

    /**
     * 把从插件收集到的 MCP server 合并进目标 MCP 配置。
     *
     * <p>由 AgentRuntime 在 {@code initializeMCPServers()} 之前调用，从而复用现有
     * MCPManager 的连接与工具注册逻辑，无需新增协议。</p>
     *
     * @param target 全局 MCP 配置（通常为 {@code config.getMcpServers()}）
     */
    public void mergeMcpServersInto(MCPServersConfig target) {
        if (collectedMcpServers.isEmpty() || target == null) {
            return;
        }
        if (target.getServers() == null) {
            target.setServers(new ArrayList<>());
        }
        target.getServers().addAll(collectedMcpServers);
        target.setEnabled(true);
        logger.info("已合并插件 MCP server", Map.of("count", collectedMcpServers.size()));
    }

    /**
     * 注入插件技能到 SkillsLoader。
     */
    private void injectSkills(PluginManifest manifest, SkillsLoader skillsLoader) {
        if (skillsLoader == null || !manifest.hasSkills()) {
            return;
        }
        for (String root : manifest.getSkillRoots()) {
            skillsLoader.addSkillRoot(root, "plugin:" + manifest.getId(), manifest.getId());
        }
    }

    /**
     * 从插件收集 MCP server 配置。
     */
    private void collectMcpServers(PluginManifest manifest, PluginsConfig pc) {
        if (!manifest.hasMcpServers()) {
            return;
        }
        VariableResolver resolver = buildResolver(manifest, pc);
        collectedMcpServers.addAll(mcpAdapter.adapt(manifest, resolver));
    }

    /**
     * 从插件收集 hooks 并合并进插件 hooks 注册表。
     */
    private void collectHooks(PluginManifest manifest, PluginsConfig pc) {
        if (!manifest.hasHooks()) {
            return;
        }
        VariableResolver resolver = buildResolver(manifest, pc);
        HookRegistry reg = hookAdapter.adapt(manifest, resolver);
        if (reg != null && !reg.isEmpty()) {
            pluginHookRegistry = pluginHookRegistry.mergedWith(reg);
        }
    }

    /**
     * 从插件收集 agent 角色并建立命名索引（同名先注册者优先）。
     */
    private void collectAgents(PluginManifest manifest, PluginsConfig pc) {
        if (!manifest.hasAgents()) {
            return;
        }
        VariableResolver resolver = buildResolver(manifest, pc);
        for (AgentRole role : agentAdapter.adapt(manifest, resolver)) {
            String name = role.getRoleName();
            if (pluginAgentsByName.containsKey(name)) {
                logger.info("插件 agent 同名已存在，已跳过", Map.of(
                        "plugin", String.valueOf(manifest.getId()), "agent", name));
                continue;
            }
            pluginAgentsByName.put(name, role);
            pluginAgentRoles.add(role);
        }
    }

    /**
     * 构建变量替换器：填充 CLAUDE_PLUGIN_ROOT / DATA / PROJECT_DIR / user_config。
     */
    private VariableResolver buildResolver(PluginManifest manifest, PluginsConfig pc) {
        String pluginRoot = manifest.getRootDir() != null
                ? manifest.getRootDir().toAbsolutePath().toString() : "";
        String pluginData = ConfigLoader.expandHome(
                Paths.get("~/.tinyclaw/plugins/data", sanitizeId(manifest.getId())).toString());
        Map<String, Object> userConfig = null;
        PluginsConfig.PluginEntry entry = pc.getEntries().get(manifest.getId());
        if (entry != null) {
            userConfig = entry.getConfig();
        }
        return new VariableResolver(pluginRoot, pluginData, workspace, userConfig);
    }

    /**
     * 解析插件加载根：默认目录 + 配置 load.paths。
     */
    private List<String> resolveLoadRoots(PluginsConfig pc) {
        List<String> roots = new ArrayList<>();
        roots.add(Paths.get(workspace, "plugins").toString());
        roots.add(ConfigLoader.expandHome("~/.tinyclaw/plugins"));
        if (pc.getLoad() != null && pc.getLoad().getPaths() != null) {
            for (String p : pc.getLoad().getPaths()) {
                roots.add(ConfigLoader.expandHome(p));
            }
        }
        return roots;
    }

    /**
     * 规整插件 id 为文件系统安全的目录名（对齐 Claude CLAUDE_PLUGIN_DATA 规则）。
     */
    private String sanitizeId(String id) {
        return id == null ? "unknown" : id.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    /** 获取插件注册表，供 CLI / Web 控制台查询。 */
    public PluginRegistry getRegistry() {
        return registry;
    }

    /** 获取从插件收集合并后的 hooks 注册表，供 AgentRuntime 叠加到 HookDispatcher。 */
    public HookRegistry getPluginHookRegistry() {
        return pluginHookRegistry;
    }

    /** 获取从插件收集的 agent 角色列表（供展示与批量注册）。 */
    public List<AgentRole> getPluginAgentRoles() {
        return pluginAgentRoles;
    }

    /** 获取插件 agent 命名索引（roleName -&gt; 角色），供 CollaborateTool 按名引用。 */
    public Map<String, AgentRole> getPluginAgentsByName() {
        return pluginAgentsByName;
    }
}
