package io.leavesfly.tinyclaw.subagent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 子代理定义加载器 - 加载和管理动态定义的专职子代理。
 * <p>
 * 与 SkillsLoader 对称：从 {@code workspace/agents/<name>/AGENT.md} 加载定义，
 * 文件采用 Markdown 格式，YAML 前置元数据声明元信息，正文为 system prompt。
 * <p>
 * 加载优先级：workspace &gt; global &gt; builtin，同名定义高优先级覆盖低优先级。
 * 内置定义从 classpath（src/main/resources/agents）加载；文件系统定义按需读取，
 * 新增或修改 AGENT.md 后无需重启即可生效。
 */
public class SubagentsLoader {

    /**
     * 定义文件名
     */
    private static final String AGENT_FILE = "AGENT.md";

    /**
     * 内置子代理名称列表（从 classpath 加载）
     */
    private static final List<String> BUILTIN_AGENT_NAMES = List.of(
            "code-reviewer", "researcher", "doc-writer"
    );

    /**
     * classpath 中内置子代理的基础路径
     */
    private static final String BUILTIN_AGENTS_PATH = "agents/";

    /**
     * 获取内置子代理名称列表（唯一数据源，供外部组件共享）
     *
     * @return 不可变的内置子代理名称列表
     */
    public static List<String> getBuiltinAgentNames() {
        return BUILTIN_AGENT_NAMES;
    }

    /**
     * 工作空间子代理目录路径（workspace/agents）
     */
    private final String workspaceAgents;

    /**
     * 全局子代理目录路径，可为 null
     */
    private final String globalAgents;

    /**
     * 构造 SubagentsLoader 实例
     *
     * @param workspace    工作空间根路径
     * @param globalAgents 全局子代理目录路径（可为 null）
     */
    public SubagentsLoader(String workspace, String globalAgents) {
        this.workspaceAgents = Paths.get(workspace, "agents").toString();
        this.globalAgents = globalAgents;
    }

    /**
     * 便捷构造器，仅加载工作空间子代理
     */
    public SubagentsLoader(String workspace) {
        this(workspace, null);
    }

    /**
     * 列出所有可用的子代理定义。
     * <p>
     * 每次调用都重新扫描目录，保证运行时动态生效。
     */
    public List<SubagentDefinition> listAgents() {
        List<SubagentDefinition> agents = new ArrayList<>();
        addAgentsFromDir(agents, workspaceAgents, "workspace");
        addAgentsFromDir(agents, globalAgents, "global");
        addBuiltinAgents(agents);
        return agents;
    }

    /**
     * 按名称加载子代理定义。
     *
     * @param name 子代理名称
     * @return 定义对象，不存在时返回 null
     */
    public SubagentDefinition load(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        SubagentDefinition def = loadFromDir(workspaceAgents, name, "workspace");
        if (def != null) {
            return def;
        }
        def = loadFromDir(globalAgents, name, "global");
        if (def != null) {
            return def;
        }
        return loadBuiltin(name);
    }

    /**
     * 从 classpath 添加内置子代理定义到列表（同名时被高优先级定义覆盖）
     */
    private void addBuiltinAgents(List<SubagentDefinition> agents) {
        for (String name : BUILTIN_AGENT_NAMES) {
            boolean exists = agents.stream().anyMatch(a -> a.getName().equals(name));
            if (!exists) {
                SubagentDefinition def = loadBuiltin(name);
                if (def != null) {
                    agents.add(def);
                }
            }
        }
    }

    /**
     * 从 classpath 加载内置子代理定义
     *
     * @param name 子代理名称
     * @return 定义对象，不存在时返回 null
     */
    private SubagentDefinition loadBuiltin(String name) {
        if (!BUILTIN_AGENT_NAMES.contains(name)) {
            return null;
        }
        String resourcePath = BUILTIN_AGENTS_PATH + name + "/" + AGENT_FILE;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                return parseAgentContent(content, name, "classpath:" + resourcePath, "builtin");
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 构建子代理清单摘要，用于注入 spawn 工具描述，
     * 让主 Agent 的 LLM 知道每个专职子代理擅长什么。
     *
     * @return 形如 "- name: description" 的多行文本，无定义时返回空字符串
     */
    public String buildAgentsSummary() {
        List<SubagentDefinition> agents = listAgents();
        if (agents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SubagentDefinition def : agents) {
            sb.append("- ").append(def.getName());
            if (def.getDescription() != null && !def.getDescription().isEmpty()) {
                sb.append(": ").append(def.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 保存（新建或更新）工作空间子代理定义。
     *
     * @param name    子代理名称
     * @param content AGENT.md 完整内容
     * @return true 表示成功
     */
    public boolean saveWorkspaceAgent(String name, String content) {
        if (!isValidAgentName(name)) {
            return false;
        }
        Path agentDir = Paths.get(workspaceAgents, name);
        Path agentFile = agentDir.resolve(AGENT_FILE);
        try {
            Files.createDirectories(agentDir);
            Files.writeString(agentFile, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 删除工作空间子代理定义（删除对应目录）。
     *
     * @param name 子代理名称
     * @return true 表示成功，false 表示定义不存在
     */
    public boolean deleteWorkspaceAgent(String name) {
        if (!isValidAgentName(name)) {
            return false;
        }
        Path agentDir = Paths.get(workspaceAgents, name);
        if (!Files.exists(agentDir) || !Files.isDirectory(agentDir)) {
            return false;
        }
        try {
            deleteDirectoryRecursively(agentDir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 校验子代理名称，拒绝空名、路径分隔符与 ".."，防止路径穿越。
     */
    private boolean isValidAgentName(String name) {
        return name != null && !name.trim().isEmpty()
                && !name.contains("..") && !name.contains("/") && !name.contains("\\");
    }

    /**
     * 从指定目录添加子代理定义到列表（同名跳过，保证高优先级覆盖）
     */
    private void addAgentsFromDir(List<SubagentDefinition> agents, String dirPath, String source) {
        if (dirPath == null) {
            return;
        }
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(agentDir -> {
                String name = agentDir.getFileName().toString();
                boolean exists = agents.stream().anyMatch(a -> a.getName().equals(name));
                if (!exists) {
                    SubagentDefinition def = parseAgentFile(agentDir.resolve(AGENT_FILE), name, source);
                    if (def != null) {
                        agents.add(def);
                    }
                }
            });
        } catch (IOException e) {
            // 忽略读取错误
        }
    }

    /**
     * 从指定目录加载单个子代理定义
     */
    private SubagentDefinition loadFromDir(String dirPath, String name, String source) {
        if (dirPath == null) {
            return null;
        }
        return parseAgentFile(Paths.get(dirPath, name, AGENT_FILE), name, source);
    }

    /**
     * 解析 AGENT.md 文件为定义对象
     */
    private SubagentDefinition parseAgentFile(Path agentFile, String name, String source) {
        if (!Files.exists(agentFile)) {
            return null;
        }
        try {
            String content = Files.readString(agentFile);
            return parseAgentContent(content, name, agentFile.toString(), source);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析 AGENT.md 文本内容为定义对象（文件系统与 classpath 两种来源共用）
     */
    private SubagentDefinition parseAgentContent(String content, String name, String path, String source) {
        SubagentDefinition def = new SubagentDefinition();
        def.setName(name);
        def.setPath(path);
        def.setSource(source);
        def.setSystemPrompt(stripFrontmatter(content).trim());

        String frontmatter = extractFrontmatter(content);
        if (frontmatter != null && !frontmatter.isEmpty()) {
            Map<String, String> yaml = parseSimpleYAML(frontmatter);
            def.setDescription(yaml.getOrDefault("description", ""));
            String model = yaml.get("model");
            if (model != null && !model.isEmpty()) {
                def.setModel(model);
            }
            List<String> tools = parseInlineList(yaml.get("tools"));
            if (tools != null) {
                def.setTools(tools);
            }
            String maxIterations = yaml.get("max_iterations");
            if (maxIterations != null && !maxIterations.isEmpty()) {
                try {
                    def.setMaxIterations(Integer.parseInt(maxIterations.trim()));
                } catch (NumberFormatException ignored) {
                    // 非法数值时继承主 Agent 配置
                }
            }
        }
        return def;
    }

    /**
     * 提取 YAML 前置元数据（--- 分隔符之间的内容）
     */
    private String extractFrontmatter(String content) {
        Pattern pattern = Pattern.compile("(?s)^---\n(.*?)\n---");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * 去除 YAML 前置元数据
     */
    private String stripFrontmatter(String content) {
        return content.replaceFirst("(?s)^---\n.*?\n---\n?", "");
    }

    /**
     * 解析简单的 YAML 格式（key: value）
     */
    private Map<String, String> parseSimpleYAML(String content) {
        Map<String, String> result = new HashMap<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                // 移除引号
                value = value.replaceAll("^['\"]|['\"]$", "");
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 解析内联列表：支持 {@code [a, b, c]} 或逗号分隔字符串
     */
    private List<String> parseInlineList(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim().replaceAll("^['\"]|['\"]$", "");
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }
}
