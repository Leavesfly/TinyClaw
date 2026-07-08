package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.plugins.ManifestParser;
import io.leavesfly.tinyclaw.plugins.MarketplaceManager;
import io.leavesfly.tinyclaw.plugins.MarketplaceManifest;
import io.leavesfly.tinyclaw.plugins.PluginDiscovery;
import io.leavesfly.tinyclaw.plugins.PluginInstaller;
import io.leavesfly.tinyclaw.plugins.PluginManifest;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件命令，管理兼容 Claude Code / OpenClaw 的插件。
 *
 * <p>支持的子命令：</p>
 * <ul>
 *   <li>{@code list}：列出已发现的插件</li>
 *   <li>{@code inspect <id>}：查看某个插件的清单与组件</li>
 *   <li>{@code install <source>}：从本地路径 / GitHub / git 安装插件；或 {@code <plugin>@<marketplace>} 从市场选装</li>
 *   <li>{@code marketplace add|list|remove}：管理已注册的插件市场</li>
 * </ul>
 */
public class PluginsCommand extends CliCommand {

    private static final String CHECK = "✓";
    private static final String CROSS = "✗";
    private static final String BULLET = "•";

    @Override
    public String name() {
        return "plugins";
    }

    @Override
    public String description() {
        return "管理插件（兼容 Claude Code / OpenClaw）";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length < 1) {
            printHelp();
            return 1;
        }
        return switch (args[0]) {
            case "list" -> listPlugins();
            case "inspect" -> inspectPlugin(args);
            case "install" -> installPlugin(args);
            case "marketplace" -> marketplace(args);
            default -> {
                System.out.println("未知子命令: " + args[0]);
                printHelp();
                yield 1;
            }
        };
    }

    @Override
    public void printHelp() {
        System.out.println("plugins - " + description());
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  tinyclaw plugins list                列出已发现的插件");
        System.out.println("  tinyclaw plugins inspect <id>        查看插件详情");
        System.out.println("  tinyclaw plugins install <source>    安装插件");
        System.out.println("  tinyclaw plugins install <p>@<market> 从市场按名选装");
        System.out.println("  tinyclaw plugins marketplace add <source>    注册市场");
        System.out.println("  tinyclaw plugins marketplace list            列出市场");
        System.out.println("  tinyclaw plugins marketplace remove <name>   移除市场");
        System.out.println();
        System.out.println("安装来源示例:");
        System.out.println("  ./my-plugin                          本地路径");
        System.out.println("  owner/repo                           GitHub 简短");
        System.out.println("  git:https://host/x/y.git@v1.0.0      指定 ref");
        System.out.println("  quality-review-plugin@my-plugins     市场选装");
    }

    /**
     * 列出所有加载根中发现的插件。
     */
    private int listPlugins() {
        List<PluginManifest> plugins = discoverAll();
        if (plugins.isEmpty()) {
            System.out.println("未发现插件。可通过 tinyclaw plugins install <source> 安装。");
            return 0;
        }
        System.out.println("📦 已发现插件 (" + plugins.size() + "):");
        System.out.println();
        Config config = loadConfigQuiet();
        for (PluginManifest m : plugins) {
            boolean allowed = config == null || config.getPlugins().isPluginAllowed(m.getId());
            String mark = allowed ? CHECK : CROSS;
            System.out.println("  " + mark + " " + m.getId()
                    + (m.getVersion() != null ? " @" + m.getVersion() : "")
                    + "  [" + m.getLayout() + "]");
            if (m.getDescription() != null && !m.getDescription().isEmpty()) {
                System.out.println("      " + m.getDescription());
            }
            System.out.println("      组件: " + String.join(", ", m.getDeclaredComponents()));
        }
        System.out.println();
        System.out.println(CROSS + " 表示未在 plugins.allow 中，加载时会被跳过。");
        return 0;
    }

    /**
     * 查看单个插件详情。
     */
    private int inspectPlugin(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: tinyclaw plugins inspect <id>");
            return 1;
        }
        String id = args[1];
        for (PluginManifest m : discoverAll()) {
            if (id.equals(m.getId())) {
                System.out.println("📦 插件: " + m.getId());
                System.out.println("  展示名  : " + m.getDisplayName());
                System.out.println("  版本    : " + (m.getVersion() != null ? m.getVersion() : "(未声明，将回退 git SHA)"));
                System.out.println("  布局    : " + m.getLayout());
                System.out.println("  根目录  : " + m.getRootDir());
                System.out.println("  默认启用: " + m.isDefaultEnabled());
                System.out.println("  描述    : " + (m.getDescription() != null ? m.getDescription() : "(无)"));
                System.out.println("  组件    : " + String.join(", ", m.getDeclaredComponents()));
                if (m.hasSkills()) {
                    System.out.println("  技能根  :");
                    for (String root : m.getSkillRoots()) {
                        System.out.println("    " + BULLET + " " + root);
                    }
                }
                if (m.hasMcpServers()) {
                    System.out.println("  MCP servers: " + m.getMcpServers().size() + " 个");
                }
                if (m.hasHooks()) {
                    List<String> events = new ArrayList<>();
                    m.getHooks().fieldNames().forEachRemaining(events::add);
                    System.out.println("  hooks   : " + String.join(", ", events));
                }
                if (m.hasAgents()) {
                    List<String> names = new ArrayList<>();
                    for (PluginManifest.AgentDefinition a : m.getAgents()) {
                        names.add(a.getName());
                    }
                    System.out.println("  agents  : " + String.join(", ", names));
                }
                return 0;
            }
        }
        System.out.println(CROSS + " 未找到插件: " + id);
        return 1;
    }

    /**
     * 安装插件。
     *
     * <p>误路由：若说明符形如 {@code name@market}（无 / 与 :）或为裸插件名（无 / : 且非路径），
     * 则走市场选装；否则作为直接来源（本地/GitHub/git）安装。</p>
     */
    private int installPlugin(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: tinyclaw plugins install <source> | <plugin>@<marketplace>");
            return 1;
        }
        String spec = args[1].trim();
        try {
            if (isMarketplaceNamedInstall(spec)) {
                MarketplaceManager mgr = new MarketplaceManager();
                int at = spec.indexOf('@');
                String result = (at > 0)
                        ? mgr.installPlugin(spec.substring(0, at), spec.substring(at + 1))
                        : mgr.installPlugin(spec);
                System.out.println(result);
                return 0;
            }
            PluginInstaller installer = new PluginInstaller();
            String result = installer.install(spec);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println(CROSS + " 安装失败: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 判断是否为市场按名安装：{@code name@market} 或裸插件名（均不含 / 与 :，且非本地路径）。
     * 直接来源（owner/repo、./path、git:URL）都含 / 或 : 或以 . / ~ 开头，不会误判。
     */
    private boolean isMarketplaceNamedInstall(String spec) {
        if (spec.contains("/") || spec.contains(":")
                || spec.startsWith(".") || spec.startsWith("~")) {
            return false;
        }
        // name@market 或 裸名
        return spec.matches("[a-zA-Z0-9._-]+(@[a-zA-Z0-9._-]+)?");
    }

    /**
     * marketplace 子命令：add / list / remove。
     */
    private int marketplace(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: tinyclaw plugins marketplace <add|list|remove> [args]");
            return 1;
        }
        MarketplaceManager mgr = new MarketplaceManager();
        try {
            switch (args[1]) {
                case "add" -> {
                    if (args.length < 3) {
                        System.out.println("Usage: tinyclaw plugins marketplace add <source>");
                        return 1;
                    }
                    MarketplaceManifest mf = mgr.add(args[2]);
                    System.out.println(CHECK + " 市场已注册: " + mf.getName()
                            + " (" + mf.getPlugins().size() + " 个插件)");
                    printMarketplacePlugins(mf);
                    System.out.println();
                    System.out.println("可用 tinyclaw plugins install <plugin>@" + mf.getName() + " 选装。");
                    return 0;
                }
                case "list" -> {
                    List<MarketplaceManifest> markets = mgr.list();
                    if (markets.isEmpty()) {
                        System.out.println("未注册任何市场。可用 tinyclaw plugins marketplace add <source> 注册。");
                        return 0;
                    }
                    System.out.println("🏪 已注册市场 (" + markets.size() + "):");
                    for (MarketplaceManifest mf : markets) {
                        System.out.println();
                        System.out.println("  " + mf.getName()
                                + (mf.getVersion() != null ? " @" + mf.getVersion() : "")
                                + "  (" + mf.getPlugins().size() + " 个插件)");
                        if (mf.getDescription() != null && !mf.getDescription().isEmpty()) {
                            System.out.println("      " + mf.getDescription());
                        }
                        printMarketplacePlugins(mf);
                    }
                    return 0;
                }
                case "remove" -> {
                    if (args.length < 3) {
                        System.out.println("Usage: tinyclaw plugins marketplace remove <name>");
                        return 1;
                    }
                    boolean removed = mgr.remove(args[2]);
                    System.out.println(removed ? (CHECK + " 已移除市场: " + args[2])
                            : (CROSS + " 未找到市场: " + args[2]));
                    return removed ? 0 : 1;
                }
                default -> {
                    System.out.println("未知 marketplace 子命令: " + args[1]);
                    return 1;
                }
            }
        } catch (Exception e) {
            System.err.println(CROSS + " 市场操作失败: " + e.getMessage());
            return 1;
        }
    }

    private void printMarketplacePlugins(MarketplaceManifest mf) {
        for (MarketplaceManifest.MarketplaceEntry e : mf.getPlugins()) {
            System.out.println("      " + BULLET + " " + e.getName()
                    + (e.getDescription() != null ? "  — " + e.getDescription() : ""));
        }
    }

    /**
     * 在默认加载根中发现全部插件。
     */
    private List<PluginManifest> discoverAll() {
        List<String> roots = new ArrayList<>();
        Config config = loadConfigQuiet();
        String workspace = config != null ? config.getWorkspacePath()
                : ConfigLoader.expandHome("~/.tinyclaw/workspace");
        roots.add(Paths.get(workspace, "plugins").toString());
        roots.add(ConfigLoader.expandHome("~/.tinyclaw/plugins"));
        if (config != null && config.getPlugins().getLoad() != null
                && config.getPlugins().getLoad().getPaths() != null) {
            for (String p : config.getPlugins().getLoad().getPaths()) {
                roots.add(ConfigLoader.expandHome(p));
            }
        }
        return new PluginDiscovery(new ManifestParser()).discover(roots);
    }

    /**
     * 静默加载配置（失败返回 null，不打印引导信息）。
     */
    private Config loadConfigQuiet() {
        try {
            return ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            return null;
        }
    }
}
