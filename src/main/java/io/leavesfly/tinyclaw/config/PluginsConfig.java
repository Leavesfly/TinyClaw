package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件系统配置。
 *
 * <p>兼容 Claude Code / OpenClaw 插件生态。控制插件的发现、启用、白/黑名单、
 * 本地加载路径、市场注册与单插件配置。详见
 * {@code docs/plugin-compatibility-design.md} §9。</p>
 *
 * <p>策略优先级：{@code deny} &gt; {@code allow} &gt; {@code entries.<id>.enabled}。</p>
 */
public class PluginsConfig {

    /** 是否启用插件系统（false 时跳过全部发现与加载）。 */
    private boolean enabled;

    /** 排他允许列表（对齐 OpenClaw）。非空时仅其中的插件 id 可加载。 */
    private List<String> allow;

    /** 拒绝列表（优先级最高）。 */
    private List<String> deny;

    /** 本地插件加载配置。 */
    private LoadConfig load;

    /** 已注册的插件市场。 */
    private List<MarketplaceRef> marketplaces;

    /** 原生 TS 运行时桥接（默认关闭）。 */
    private BridgeConfig bridge;

    /** 单插件配置（对应 Claude userConfig / OpenClaw entries）。 */
    private Map<String, PluginEntry> entries;

    public PluginsConfig() {
        this.enabled = false;
        this.allow = new ArrayList<>();
        this.deny = new ArrayList<>();
        this.load = new LoadConfig();
        this.marketplaces = new ArrayList<>();
        this.bridge = new BridgeConfig();
        this.entries = new HashMap<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow;
    }

    public List<String> getDeny() {
        return deny;
    }

    public void setDeny(List<String> deny) {
        this.deny = deny;
    }

    public LoadConfig getLoad() {
        if (load == null) {
            load = new LoadConfig();
        }
        return load;
    }

    public void setLoad(LoadConfig load) {
        this.load = load;
    }

    public List<MarketplaceRef> getMarketplaces() {
        return marketplaces;
    }

    public void setMarketplaces(List<MarketplaceRef> marketplaces) {
        this.marketplaces = marketplaces;
    }

    public BridgeConfig getBridge() {
        if (bridge == null) {
            bridge = new BridgeConfig();
        }
        return bridge;
    }

    public void setBridge(BridgeConfig bridge) {
        this.bridge = bridge;
    }

    public Map<String, PluginEntry> getEntries() {
        if (entries == null) {
            entries = new HashMap<>();
        }
        return entries;
    }

    public void setEntries(Map<String, PluginEntry> entries) {
        this.entries = entries;
    }

    /**
     * 判断某个插件 id 是否被允许加载。
     *
     * <p>规则：命中 deny 一律拒绝；allow 非空时必须在 allow 中；单插件 entries 显式禁用则拒绝。</p>
     *
     * @param pluginId 插件 id
     * @return true 表示允许加载
     */
    public boolean isPluginAllowed(String pluginId) {
        if (pluginId == null) {
            return false;
        }
        if (deny != null && deny.contains(pluginId)) {
            return false;
        }
        if (allow != null && !allow.isEmpty() && !allow.contains(pluginId)) {
            return false;
        }
        PluginEntry entry = getEntries().get(pluginId);
        if (entry != null && !entry.isEnabled()) {
            return false;
        }
        return true;
    }

    /**
     * 本地插件加载路径配置。
     */
    public static class LoadConfig {
        /** 显式本地插件目录列表。 */
        private List<String> paths;

        public LoadConfig() {
            this.paths = new ArrayList<>();
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }
    }

    /**
     * 已注册的插件市场引用。
     */
    public static class MarketplaceRef {
        /** 市场名称（唯一）。 */
        private String name;
        /** 市场来源（本地路径 / github:owner/repo / git:url）。 */
        private String source;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }

    /**
     * 原生 TS 运行时桥接配置。
     */
    public static class BridgeConfig {
        /** 是否启用 Node 边车桥接。 */
        private boolean enabled;
        /** Node 可执行文件路径。 */
        private String nodePath;

        public BridgeConfig() {
            this.enabled = false;
            this.nodePath = "node";
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNodePath() {
            return nodePath;
        }

        public void setNodePath(String nodePath) {
            this.nodePath = nodePath;
        }
    }

    /**
     * 单个插件的配置条目。
     */
    public static class PluginEntry {
        /** 是否启用（默认 true）。 */
        private boolean enabled;
        /** 用户配置值（对应 Claude userConfig / ${user_config.*}）。 */
        private Map<String, Object> config;
        /** 版本固定（指向缓存目录版本）。 */
        private String version;

        public PluginEntry() {
            this.enabled = true;
            this.config = new HashMap<>();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, Object> getConfig() {
            if (config == null) {
                config = new HashMap<>();
            }
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}
