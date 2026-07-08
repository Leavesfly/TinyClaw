package io.leavesfly.tinyclaw.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件清单解析器。
 *
 * <p>按优先级探测并解析插件清单，兼容以下布局：</p>
 * <ol>
 *   <li>{@code .claude-plugin/plugin.json}（Claude Code，首选）</li>
 *   <li>{@code openclaw.plugin.json}（OpenClaw 原生）</li>
 *   <li>{@code .codex-plugin/plugin.json}、{@code .cursor-plugin/plugin.json}</li>
 *   <li>无清单但存在根 {@code SKILL.md} → 单技能插件</li>
 * </ol>
 *
 * <p>解析过程不执行任何插件代码，仅读取 JSON 与目录结构（对齐安全模型）。
 * 未知顶层字段一律忽略（对齐 Claude Code 宽容策略）。</p>
 */
public class ManifestParser {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认技能子目录。 */
    private static final String DEFAULT_SKILLS_DIR = "skills";

    /** 默认 MCP 配置文件。 */
    private static final String DEFAULT_MCP_FILE = ".mcp.json";

    /** 默认 agent（子代理）子目录。 */
    private static final String DEFAULT_AGENTS_DIR = "agents";

    /** YAML frontmatter 提取正则（{@code ---} 分隔符之间的内容）。 */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("(?s)^---\\r?\\n(.*?)\\r?\\n---");

    /**
     * 解析给定插件目录，返回统一清单模型。
     *
     * @param pluginDir 插件根目录
     * @return 解析出的 {@link PluginManifest}，无法识别为插件时返回 null
     */
    public PluginManifest parse(Path pluginDir) {
        if (pluginDir == null || !Files.isDirectory(pluginDir)) {
            return null;
        }

        // 1. 按优先级探测清单文件
        Path manifestFile = null;
        String layout = null;
        Path claude = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        Path openclaw = pluginDir.resolve("openclaw.plugin.json");
        Path codex = pluginDir.resolve(".codex-plugin").resolve("plugin.json");
        Path cursor = pluginDir.resolve(".cursor-plugin").resolve("plugin.json");

        if (Files.isRegularFile(claude)) {
            manifestFile = claude;
            layout = "claude";
        } else if (Files.isRegularFile(openclaw)) {
            manifestFile = openclaw;
            layout = "openclaw";
        } else if (Files.isRegularFile(codex)) {
            manifestFile = codex;
            layout = "codex";
        } else if (Files.isRegularFile(cursor)) {
            manifestFile = cursor;
            layout = "cursor";
        }

        if (manifestFile != null) {
            return parseManifestFile(pluginDir, manifestFile, layout);
        }

        // 2. 无清单：根目录存在 SKILL.md → 单技能插件
        if (Files.isRegularFile(pluginDir.resolve("SKILL.md"))) {
            return buildSingleSkillManifest(pluginDir);
        }

        // 3. 无清单但存在 skills/ 目录 → 视为技能集合插件
        if (Files.isDirectory(pluginDir.resolve(DEFAULT_SKILLS_DIR))) {
            PluginManifest m = new PluginManifest();
            m.setId(pluginDir.getFileName().toString());
            m.setRootDir(pluginDir);
            m.setLayout("skills-dir");
            m.addSkillRoot(pluginDir.resolve(DEFAULT_SKILLS_DIR).toAbsolutePath().toString());
            m.addDeclaredComponent("skills");
            resolveMcpServers(pluginDir, null, m);
            resolveHooks(pluginDir, null, m);
            resolveAgents(pluginDir, null, m);
            return m;
        }

        return null;
    }

    /**
     * 解析清单文件内容。
     */
    private PluginManifest parseManifestFile(Path pluginDir, Path manifestFile, String layout) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(manifestFile));

            PluginManifest m = new PluginManifest();
            m.setRootDir(pluginDir);
            m.setLayout(layout);

            // id：清单 name 必填；缺省回退目录名
            String name = textOrNull(root, "name");
            m.setId(name != null && !name.isEmpty() ? name : pluginDir.getFileName().toString());
            m.setDisplayName(textOrNull(root, "displayName"));
            m.setVersion(textOrNull(root, "version"));
            m.setDescription(textOrNull(root, "description"));
            if (root.has("defaultEnabled") && root.get("defaultEnabled").isBoolean()) {
                m.setDefaultEnabled(root.get("defaultEnabled").asBoolean());
            }

            // 技能：默认 skills/ 始终扫描（若存在）；再叠加清单 skills 字段
            Path defaultSkills = pluginDir.resolve(DEFAULT_SKILLS_DIR);
            if (Files.isDirectory(defaultSkills)) {
                m.addSkillRoot(defaultSkills.toAbsolutePath().toString());
                m.addDeclaredComponent("skills");
            }
            for (String rel : collectPaths(root.get("skills"))) {
                Path resolved = safeResolve(pluginDir, rel);
                if (resolved != null && Files.isDirectory(resolved)) {
                    m.addSkillRoot(resolved.toAbsolutePath().toString());
                    m.addDeclaredComponent("skills");
                }
            }

            // MCP servers：默认 .mcp.json + 清单 mcpServers（string|array|object）
            resolveMcpServers(pluginDir, root.get("mcpServers"), m);

            // hooks：默认 hooks/hooks.json + 清单 hooks（string|array|object）
            resolveHooks(pluginDir, root.get("hooks"), m);

            // agents：默认 agents/*.md + 清单 agents（路径引用，指向 .md 文件或目录）
            resolveAgents(pluginDir, root.get("agents"), m);

            // 其它组件仅登记（首期不执行）
            for (String comp : new String[]{"commands", "lspServers"}) {
                if (root.has(comp)) {
                    m.addDeclaredComponent(comp);
                }
            }

            return m;
        } catch (IOException e) {
            logger.error("解析插件清单失败", java.util.Map.of(
                    "file", manifestFile.toString(), "error", e.getMessage()));
            return null;
        }
    }

    /**
     * 构建单技能插件清单（根目录直接含 SKILL.md）。
     *
     * <p>注意：单技能布局的技能注入依赖 {@code skills/} 子目录结构，此处仅登记元数据，
     * 技能内容通过父目录扫描处理留待后续增强（见设计文档 §12）。</p>
     */
    private PluginManifest buildSingleSkillManifest(Path pluginDir) {
        PluginManifest m = new PluginManifest();
        m.setRootDir(pluginDir);
        m.setLayout("single-skill");
        m.setId(pluginDir.getFileName().toString());
        m.addDeclaredComponent("skill");
        return m;
    }

    /**
     * 解析并合并 MCP server 配置：默认 {@code .mcp.json} 文件 + 清单 mcpServers 字段。
     *
     * @param pluginDir     插件根
     * @param mcpNode       清单中的 mcpServers 节点（string|array|object），可为 null
     * @param manifest      待填充的清单模型
     */
    private void resolveMcpServers(Path pluginDir, JsonNode mcpNode, PluginManifest manifest) {
        ObjectNode merged = MAPPER.createObjectNode();

        // 默认 .mcp.json
        Path defaultMcp = pluginDir.resolve(DEFAULT_MCP_FILE);
        if (Files.isRegularFile(defaultMcp)) {
            mergeMcpFile(defaultMcp, merged);
        }

        // 清单 mcpServers 字段
        if (mcpNode != null) {
            if (mcpNode.isTextual()) {
                Path p = safeResolve(pluginDir, mcpNode.asText());
                if (p != null) {
                    mergeMcpFile(p, merged);
                }
            } else if (mcpNode.isArray()) {
                for (JsonNode item : mcpNode) {
                    if (item.isTextual()) {
                        Path p = safeResolve(pluginDir, item.asText());
                        if (p != null) {
                            mergeMcpFile(p, merged);
                        }
                    }
                }
            } else if (mcpNode.isObject()) {
                mergeMcpNode(mcpNode, merged);
            }
        }

        if (merged.size() > 0) {
            manifest.setMcpServers(merged);
            manifest.addDeclaredComponent("mcpServers");
        }
    }

    /**
     * 从 .mcp.json 文件读取并合并 server 配置。
     * 兼容顶层 {@code mcpServers} 包裹或直接的 serverName→config 映射。
     */
    private void mergeMcpFile(Path file, ObjectNode target) {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(file));
            JsonNode servers = node.has("mcpServers") ? node.get("mcpServers") : node;
            mergeMcpNode(servers, target);
        } catch (IOException e) {
            logger.warn("读取插件 .mcp.json 失败: " + file + " - " + e.getMessage());
        }
    }

    /**
     * 将一个 serverName→config 的对象节点合并到目标。
     */
    private void mergeMcpNode(JsonNode servers, ObjectNode target) {
        if (servers == null || !servers.isObject()) {
            return;
        }
        servers.fieldNames().forEachRemaining(name ->
                target.set(name, servers.get(name)));
    }

    /**
     * 解析并合并 hooks 配置：默认 {@code hooks/hooks.json} 文件 + 清单 {@code hooks} 字段。
     *
     * <p>归一化结果为 <b>事件名 -&gt; 条目数组</b> 的映射（即 Claude Code {@code hooks} 字段的内层内容），
     * 命令中的变量留待 {@link HookComponentAdapter} 在装配阶段替换。</p>
     *
     * @param pluginDir  插件根
     * @param hooksNode  清单中的 hooks 节点（string|array|object），可为 null
     * @param manifest   待填充的清单模型
     */
    private void resolveHooks(Path pluginDir, JsonNode hooksNode, PluginManifest manifest) {
        JsonNode merged = null;

        // 默认 hooks/hooks.json
        Path defaultHooks = pluginDir.resolve("hooks").resolve("hooks.json");
        if (Files.isRegularFile(defaultHooks)) {
            merged = mergeHooks(merged, readHooksFile(defaultHooks));
        }

        // 清单 hooks 字段
        if (hooksNode != null) {
            if (hooksNode.isObject()) {
                merged = mergeHooks(merged, unwrapHooks(hooksNode));
            } else {
                for (String rel : collectPaths(hooksNode)) {
                    Path p = safeResolve(pluginDir, rel);
                    if (p != null && Files.isRegularFile(p)) {
                        merged = mergeHooks(merged, readHooksFile(p));
                    }
                }
            }
        }

        if (merged != null && merged.isObject() && merged.size() > 0) {
            manifest.setHooks(merged);
            manifest.addDeclaredComponent("hooks");
        }
    }

    /**
     * 读取 hooks JSON 文件，返回内层的 <b>事件名 -&gt; 条目数组</b> 映射。
     * 兼容顶层 {@code hooks} 包裹或直接的映射。
     */
    private JsonNode readHooksFile(Path file) {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(file));
            return unwrapHooks(node);
        } catch (IOException e) {
            logger.warn("读取插件 hooks 文件失败: " + file + " - " + e.getMessage());
            return null;
        }
    }

    /** 若节点带有顶层 {@code hooks} 对象包裹则拆包，否则原样返回。 */
    private JsonNode unwrapHooks(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject() && node.has("hooks") && node.get("hooks").isObject()) {
            return node.get("hooks");
        }
        return node;
    }

    /**
     * 合并两个 hooks 映射：同事件的条目数组按序拼接。
     */
    private JsonNode mergeHooks(JsonNode a, JsonNode b) {
        ObjectNode out = MAPPER.createObjectNode();
        absorbHooks(out, a);
        absorbHooks(out, b);
        return out;
    }

    private void absorbHooks(ObjectNode out, JsonNode src) {
        if (src == null || !src.isObject()) {
            return;
        }
        src.fields().forEachRemaining(e -> {
            JsonNode existing = out.get(e.getKey());
            JsonNode incoming = e.getValue();
            if (existing != null && existing.isArray() && incoming.isArray()) {
                ((ArrayNode) existing).addAll((ArrayNode) incoming);
            } else if (incoming.isArray()) {
                ArrayNode arr = MAPPER.createArrayNode();
                arr.addAll((ArrayNode) incoming);
                out.set(e.getKey(), arr);
            } else {
                out.set(e.getKey(), incoming);
            }
        });
    }

    /**
     * 收集 string|array 字段的相对路径列表。
     */
    private List<String> collectPaths(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        if (node.isTextual()) {
            result.add(node.asText());
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    /**
     * 安全解析相对路径：必须落在插件根目录内，拒绝 {@code ../} 越界。
     */
    private Path safeResolve(Path pluginDir, String relative) {
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        Path base = pluginDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            logger.warn("拒绝越界插件路径: " + relative);
            return null;
        }
        return resolved;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node != null && node.has(field) && node.get(field).isTextual()) {
            return node.get(field).asText();
        }
        return null;
    }

    /**
     * 解析并登记 agent（子代理）：默认 {@code agents/*.md} + 清单 {@code agents} 路径引用。
     *
     * <p>每个 markdown 文件视为一个 Claude Code subagent：YAML frontmatter 提供
     * name/description/model/tools，正文作为系统提示词。变量留待装配阶段替换。</p>
     *
     * @param pluginDir  插件根
     * @param agentsNode 清单中的 agents 节点（string|array 路径），可为 null
     * @param manifest   待填充的清单模型
     */
    private void resolveAgents(Path pluginDir, JsonNode agentsNode, PluginManifest manifest) {
        int before = manifest.getAgents().size();

        // 默认 agents/ 目录下所有 *.md
        Path defaultDir = pluginDir.resolve(DEFAULT_AGENTS_DIR);
        if (Files.isDirectory(defaultDir)) {
            for (Path md : listMarkdown(defaultDir)) {
                addAgentFromFile(md, manifest);
            }
        }

        // 清单 agents 字段：路径引用（.md 文件或目录）
        for (String rel : collectPaths(agentsNode)) {
            Path p = safeResolve(pluginDir, rel);
            if (p == null) {
                continue;
            }
            if (Files.isDirectory(p)) {
                for (Path md : listMarkdown(p)) {
                    addAgentFromFile(md, manifest);
                }
            } else if (Files.isRegularFile(p)) {
                addAgentFromFile(p, manifest);
            }
        }

        if (manifest.getAgents().size() > before) {
            manifest.addDeclaredComponent("agents");
        }
    }

    /** 列出目录下的 *.md 文件（按文件名稳定排序，保证确定性）。 */
    private List<Path> listMarkdown(Path dir) {
        List<Path> result = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(result::add);
        } catch (IOException e) {
            logger.warn("读取插件 agents 目录失败: " + dir + " - " + e.getMessage());
        }
        return result;
    }

    /** 解析单个 agent markdown 文件并登记到清单。 */
    private void addAgentFromFile(Path mdFile, PluginManifest manifest) {
        try {
            String content = Files.readString(mdFile);
            Map<String, String> yaml = parseSimpleYaml(extractFrontmatter(content));
            String body = stripFrontmatter(content).trim();

            PluginManifest.AgentDefinition def = new PluginManifest.AgentDefinition();
            String name = yaml.get("name");
            if (name == null || name.isEmpty()) {
                name = mdFile.getFileName().toString().replaceFirst("(?i)\\.md$", "");
            }
            def.setName(name);
            def.setDescription(yaml.get("description"));
            def.setModel(yaml.get("model"));
            for (String tool : parseInlineList(yaml.get("tools"))) {
                def.addTool(tool);
            }
            def.setSystemPrompt(body);

            if (!body.isEmpty()) {
                manifest.addAgent(def);
            } else {
                logger.warn("插件 agent 缺少系统提示词正文，已跳过: " + mdFile);
            }
        } catch (IOException e) {
            logger.warn("读取插件 agent 文件失败: " + mdFile + " - " + e.getMessage());
        }
    }

    /** 提取 YAML frontmatter（{@code ---} 分隔符之间的内容），无则返回空串。 */
    private String extractFrontmatter(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 去除 YAML frontmatter，返回正文。 */
    private String stripFrontmatter(String content) {
        return content.replaceFirst("(?s)^---\\r?\\n.*?\\r?\\n---\\r?\\n?", "");
    }

    /** 解析简单 {@code key: value} 形式的 YAML。 */
    private Map<String, String> parseSimpleYaml(String content) {
        Map<String, String> result = new HashMap<>();
        if (content == null) {
            return result;
        }
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim().replaceAll("^['\"]|['\"]$", "");
                result.put(key, value);
            }
        }
        return result;
    }

    /** 解析内联列表：支持 {@code [a, b, c]} 或逗号分隔字符串。 */
    private List<String> parseInlineList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        for (String part : value.split(",")) {
            String item = part.trim().replaceAll("^['\"]|['\"]$", "");
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }
}
