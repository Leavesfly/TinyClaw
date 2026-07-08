package io.leavesfly.tinyclaw.plugins;

import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 插件市场管理器。
 *
 * <p>兼容 Claude Code 的 marketplace 心智：注册（add）指向含
 * {@code .claude-plugin/marketplace.json} 的仓库/目录后，即可用 {@code <plugin>@<marketplace>}
 * 从市场按名选装插件。</p>
 *
 * <p>已注册市场以本地目录形式缓存在 {@code ~/.tinyclaw/marketplaces/<name>/}；
 * 选装时解析对应插件的 {@code source} 并委托 {@link PluginInstaller} 完成安装
 * （安装结果落到 {@code ~/.tinyclaw/plugins/<id>}，随后被正常发现）。</p>
 *
 * <p>支持的插件来源：相对路径、github、url(git)、git-subdir(owner/repo 简写)。
 * npm 与完整 URL 的 git-subdir 首期不支持，会给出明确提示。</p>
 */
public class MarketplaceManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    private final Path marketplacesRoot;
    private final PluginInstaller installer;
    private final MarketplaceParser parser = new MarketplaceParser();

    public MarketplaceManager() {
        this(Paths.get(ConfigLoader.expandHome("~/.tinyclaw/marketplaces")), new PluginInstaller());
    }

    public MarketplaceManager(Path marketplacesRoot, PluginInstaller installer) {
        this.marketplacesRoot = marketplacesRoot;
        this.installer = installer;
    }

    /**
     * 注册（添加）一个市场：拉取来源仓库/目录，解析 marketplace.json，缓存到本地。
     *
     * @param source 市场来源（本地路径 / owner/repo / git:URL@ref）
     * @return 已安装市场的清单
     * @throws Exception 拉取或解析失败
     */
    public MarketplaceManifest add(String source) throws Exception {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("市场来源不能为空");
        }
        source = source.trim();
        Files.createDirectories(marketplacesRoot);
        Path temp = Files.createTempDirectory("tinyclaw-market-");
        try {
            Path src = installer.resolveSource(source, temp);
            MarketplaceManifest mf = parser.parse(src);
            if (mf == null) {
                throw new IOException("未在来源中找到 .claude-plugin/marketplace.json: " + source);
            }
            if (mf.getName() == null || mf.getName().isEmpty()) {
                throw new IOException("marketplace.json 缺少 name 字段");
            }
            Path target = marketplacesRoot.resolve(sanitize(mf.getName()));
            if (Files.exists(target)) {
                installer.deleteRecursively(target);
            }
            installer.copyRecursively(src, target);
            MarketplaceManifest installed = parser.parse(target);
            logger.info("市场已注册", Map.of(
                    "marketplace", mf.getName(),
                    "plugins", installed != null ? installed.getPlugins().size() : 0));
            return installed;
        } finally {
            installer.deleteRecursively(temp);
        }
    }

    /**
     * 列出所有已注册市场。
     */
    public List<MarketplaceManifest> list() {
        List<MarketplaceManifest> result = new ArrayList<>();
        if (!Files.isDirectory(marketplacesRoot)) {
            return result;
        }
        try (var stream = Files.list(marketplacesRoot)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(dir)) {
                    MarketplaceManifest mf = parser.parse(dir);
                    if (mf != null) {
                        result.add(mf);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("列出市场失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 加载单个已注册市场，未注册返回 null。
     */
    public MarketplaceManifest getMarketplace(String name) {
        if (name == null) {
            return null;
        }
        return parser.parse(marketplacesRoot.resolve(sanitize(name)));
    }

    /**
     * 移除已注册市场。
     *
     * @return true 表示确实删除了一个市场
     */
    public boolean remove(String name) throws IOException {
        Path dir = marketplacesRoot.resolve(sanitize(name));
        if (!Files.isDirectory(dir)) {
            return false;
        }
        installer.deleteRecursively(dir);
        return true;
    }

    /**
     * 从指定市场按名安装插件。
     */
    public String installPlugin(String pluginName, String marketplaceName) throws Exception {
        MarketplaceManifest mf = getMarketplace(marketplaceName);
        if (mf == null) {
            throw new IOException("未注册的市场: " + marketplaceName
                    + "（请先 tinyclaw plugins marketplace add <source>）");
        }
        MarketplaceManifest.MarketplaceEntry entry = mf.findPlugin(pluginName);
        if (entry == null) {
            throw new IOException("市场 " + marketplaceName + " 中未找到插件: " + pluginName);
        }
        String specifier = resolveSpecifier(mf, entry);
        return installer.install(specifier);
    }

    /**
     * 在所有已注册市场中搜索并安装同名插件（唯一命中才安装）。
     */
    public String installPlugin(String pluginName) throws Exception {
        List<MarketplaceManifest> matches = new ArrayList<>();
        for (MarketplaceManifest mf : list()) {
            if (mf.findPlugin(pluginName) != null) {
                matches.add(mf);
            }
        }
        if (matches.isEmpty()) {
            throw new IOException("已注册市场中未找到插件: " + pluginName);
        }
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (MarketplaceManifest mf : matches) {
                names.append(mf.getName()).append(" ");
            }
            throw new IOException("插件 " + pluginName + " 存在于多个市场，请用 <plugin>@<marketplace> 指定: "
                    + names.toString().trim());
        }
        return installPlugin(pluginName, matches.get(0).getName());
    }

    /**
     * 将市场插件条目的 {@code source} 归一化为 {@link PluginInstaller#install(String)} 可识别的说明符。
     */
    String resolveSpecifier(MarketplaceManifest mf, MarketplaceManifest.MarketplaceEntry entry) throws IOException {
        MarketplaceManifest.MarketplaceSource s = entry.getSource();
        if (s == null || s.getType() == MarketplaceManifest.MarketplaceSource.Type.UNKNOWN) {
            throw new IOException("插件来源未声明或无法识别: " + entry.getName());
        }
        switch (s.getType()) {
            case RELATIVE -> {
                return resolveRelative(mf, s.getPath(), entry.getName());
            }
            case GITHUB -> {
                if (s.getRepo() == null || s.getRepo().isEmpty()) {
                    throw new IOException("github 来源缺少 repo: " + entry.getName());
                }
                return withRef(s.getRepo(), s.getRef());
            }
            case URL -> {
                if (s.getUrl() == null || s.getUrl().isEmpty()) {
                    throw new IOException("url 来源缺少 url: " + entry.getName());
                }
                return withRef("git:" + s.getUrl(), s.getRef());
            }
            case GIT_SUBDIR -> {
                String url = s.getUrl();
                if (url == null || s.getSubdir() == null) {
                    throw new IOException("git-subdir 来源缺少 url 或 path: " + entry.getName());
                }
                if (url.contains("://") || url.startsWith("git@")) {
                    throw new IOException("git-subdir 首期仅支持 owner/repo 简写 URL: " + url);
                }
                return withRef(url + "/" + s.getSubdir(), s.getRef());
            }
            case NPM -> throw new IOException("暂不支持 npm 来源插件: " + entry.getName());
            default -> throw new IOException("插件来源无法识别: " + entry.getName());
        }
    }

    /**
     * 解析相对路径来源为市场根内的绝对本地路径（拒绝 {@code ../} 越界）。
     */
    private String resolveRelative(MarketplaceManifest mf, String rel, String pluginName) throws IOException {
        if (rel == null || rel.isEmpty()) {
            throw new IOException("相对路径来源为空: " + pluginName);
        }
        Path base = mf.getRootDir().toAbsolutePath().normalize();
        Path resolved;
        if (rel.startsWith("./") || rel.startsWith("/")) {
            String clean = rel.startsWith("./") ? rel.substring(2) : rel.substring(1);
            resolved = base.resolve(clean).normalize();
        } else {
            // 裸名：前置 metadata.pluginRoot
            Path b = base;
            String pr = mf.getPluginRoot();
            if (pr != null && !pr.isEmpty()) {
                pr = pr.startsWith("./") ? pr.substring(2) : pr;
                b = base.resolve(pr).normalize();
            }
            resolved = b.resolve(rel).normalize();
        }
        if (!resolved.startsWith(base)) {
            throw new IOException("插件路径越界（不在市场根内）: " + rel);
        }
        if (!Files.isDirectory(resolved)) {
            throw new IOException("插件目录不存在: " + resolved);
        }
        return resolved.toString();
    }

    private String withRef(String base, String ref) {
        return (ref != null && !ref.isEmpty()) ? base + "@" + ref : base;
    }

    private String sanitize(String name) {
        return name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    public Path getMarketplacesRoot() {
        return marketplacesRoot;
    }
}
