package io.leavesfly.tinyclaw.plugins;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件市场（marketplace.json）的统一内存模型。
 *
 * <p>对齐 Claude Code {@code .claude-plugin/marketplace.json} 目录格式：一个市场是列出多个插件
 * 及其来源的目录清单。由 {@link MarketplaceParser} 解析填充。</p>
 *
 * <p>设计原则：宽容未知字段；纯静态解析，不执行任何插件代码。</p>
 */
public class MarketplaceManifest {

    /** 市场名称（kebab-case，用于 {@code <plugin>@<marketplace>} 安装）。 */
    private String name;

    /** 市场简介。 */
    private String description;

    /** 市场清单版本。 */
    private String version;

    /** 前置到裸相对插件来源路径的基目录（对应 metadata.pluginRoot），可为 null。 */
    private String pluginRoot;

    /** 市场本地根目录（含 {@code .claude-plugin/}）绝对路径。 */
    private Path rootDir;

    /** 市场列出的插件条目。 */
    private final List<MarketplaceEntry> plugins = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPluginRoot() {
        return pluginRoot;
    }

    public void setPluginRoot(String pluginRoot) {
        this.pluginRoot = pluginRoot;
    }

    public Path getRootDir() {
        return rootDir;
    }

    public void setRootDir(Path rootDir) {
        this.rootDir = rootDir;
    }

    public List<MarketplaceEntry> getPlugins() {
        return plugins;
    }

    public void addPlugin(MarketplaceEntry entry) {
        if (entry != null) {
            plugins.add(entry);
        }
    }

    /** 按名查找插件条目，未命中返回 null。 */
    public MarketplaceEntry findPlugin(String pluginName) {
        if (pluginName == null) {
            return null;
        }
        for (MarketplaceEntry e : plugins) {
            if (pluginName.equals(e.getName())) {
                return e;
            }
        }
        return null;
    }

    /**
     * 市场中的单个插件条目。
     */
    public static class MarketplaceEntry {
        private String name;
        private String description;
        private String version;
        private MarketplaceSource source;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public MarketplaceSource getSource() {
            return source;
        }

        public void setSource(MarketplaceSource source) {
            this.source = source;
        }
    }

    /**
     * 插件来源，归一化 Claude Code 的多种 {@code source} 形态。
     */
    public static class MarketplaceSource {

        /** 来源类型。 */
        public enum Type {
            RELATIVE, GITHUB, URL, GIT_SUBDIR, NPM, UNKNOWN
        }

        private Type type = Type.UNKNOWN;

        /** 相对路径（RELATIVE，形如 {@code ./plugins/foo} 或裸名 {@code foo}）。 */
        private String path;
        /** GitHub 简写 {@code owner/repo}（GITHUB）。 */
        private String repo;
        /** 完整 git URL（URL / GIT_SUBDIR）。 */
        private String url;
        /** 仓库内子目录（GIT_SUBDIR）。 */
        private String subdir;
        /** git 分支或 tag。 */
        private String ref;
        /** git 提交 SHA。 */
        private String sha;
        /** npm 包名（NPM）。 */
        private String pkg;

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getRepo() {
            return repo;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSubdir() {
            return subdir;
        }

        public void setSubdir(String subdir) {
            this.subdir = subdir;
        }

        public String getRef() {
            return ref;
        }

        public void setRef(String ref) {
            this.ref = ref;
        }

        public String getSha() {
            return sha;
        }

        public void setSha(String sha) {
            this.sha = sha;
        }

        public String getPkg() {
            return pkg;
        }

        public void setPkg(String pkg) {
            this.pkg = pkg;
        }
    }
}
