package io.leavesfly.tinyclaw.plugins;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件清单的统一内存模型。
 *
 * <p>兼容 Claude Code（{@code .claude-plugin/plugin.json}）、OpenClaw
 * （{@code openclaw.plugin.json}）、Codex、Cursor 布局，以及无清单的单技能插件。
 * 由 {@link ManifestParser} 填充，字段为归一化后的结果，而非直接 JSON 绑定。</p>
 *
 * <p>设计原则：宽容未知字段（对齐 Claude Code），字段缺省即降级。</p>
 */
public class PluginManifest {

    /** 插件唯一 id（kebab-case，来自清单 name 或目录名）。 */
    private String id;

    /** 展示名（displayName，缺省回退 id）。 */
    private String displayName;

    /** 语义化版本（缺省时由安装流程回退 git SHA）。 */
    private String version;

    /** 简介。 */
    private String description;

    /** 插件根目录绝对路径。 */
    private Path rootDir;

    /** 清单来源布局：claude / openclaw / codex / cursor / single-skill。 */
    private String layout;

    /** 安装后是否默认启用（对应 Claude defaultEnabled，默认 true）。 */
    private boolean defaultEnabled = true;

    /** 归一化后的技能根目录（绝对路径），供 SkillsLoader 注入。 */
    private final List<String> skillRoots = new ArrayList<>();

    /**
     * 归一化后的 mcpServers 节点（对象：serverName -&gt; 配置）。
     * 由内联 mcpServers 与引用的 {@code .mcp.json} 合并而来，可能为 null。
     */
    private JsonNode mcpServers;

    /**
     * 归一化后的 hooks 节点（对象：事件名 -&gt; 条目数组），对齐 Claude Code hooks 结构。
     * 由默认 {@code hooks/hooks.json} 与清单 {@code hooks} 字段合并而来，可能为 null。
     * 命令中的 {@code ${CLAUDE_PLUGIN_ROOT}} 等变量在装配阶段由 HookComponentAdapter 替换。
     */
    private JsonNode hooks;

    /** 声明的组件摘要（供 inspect 展示，如 "skills"、"mcpServers"）。 */
    private final List<String> declaredComponents = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName != null && !displayName.isEmpty() ? displayName : id;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Path getRootDir() {
        return rootDir;
    }

    public void setRootDir(Path rootDir) {
        this.rootDir = rootDir;
    }

    public String getLayout() {
        return layout;
    }

    public void setLayout(String layout) {
        this.layout = layout;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public void setDefaultEnabled(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public List<String> getSkillRoots() {
        return skillRoots;
    }

    public void addSkillRoot(String root) {
        if (root != null && !root.isEmpty() && !skillRoots.contains(root)) {
            skillRoots.add(root);
        }
    }

    public JsonNode getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(JsonNode mcpServers) {
        this.mcpServers = mcpServers;
    }

    public List<String> getDeclaredComponents() {
        return declaredComponents;
    }

    public void addDeclaredComponent(String component) {
        if (component != null && !declaredComponents.contains(component)) {
            declaredComponents.add(component);
        }
    }

    /** 是否包含可注入的技能组件。 */
    public boolean hasSkills() {
        return !skillRoots.isEmpty();
    }

    /** 是否包含 MCP server 组件。 */
    public boolean hasMcpServers() {
        return mcpServers != null && mcpServers.size() > 0;
    }

    public JsonNode getHooks() {
        return hooks;
    }

    public void setHooks(JsonNode hooks) {
        this.hooks = hooks;
    }

    /** 是否包含可注册的 hooks 组件。 */
    public boolean hasHooks() {
        return hooks != null && hooks.size() > 0;
    }
}
