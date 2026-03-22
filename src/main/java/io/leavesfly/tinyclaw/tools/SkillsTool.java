package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.config.ToolsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillRegistry;
import io.leavesfly.tinyclaw.skills.SkillsInstaller;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.skills.SkillsSearcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能管理工具，赋予 Agent 自主学习和管理技能的能力。
 * 
 * 这是实现 AI 自主学习 Skill 的核心工具，让 Agent 不再依赖人工安装技能，
 * 而是能够自主发现、安装、创建和编辑技能。
 * 
 * 支持的操作：
 * - list: 列出所有已安装的技能
 * - invoke: 调用技能，返回基础路径和完整指令（用于执行带脚本的技能）
 * - install: 从 GitHub 仓库安装技能
 * - create: 创建新技能（AI 自主学习的核心能力）
 * - edit: 编辑已有技能的内容
 * - remove: 删除指定技能
 * 
 * 设计理念：传统的 Skill 是人工预定义的静态指令模板；而通过此工具，AI 可以：
 * 1. 在交互中识别重复模式，主动创建新技能固化经验
 * 2. 从社区（GitHub）按需安装技能来解决新问题
 * 3. 迭代优化已有技能，使其越来越好
 */
public class SkillsTool implements Tool {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("skills");

    private final SkillsLoader skillsLoader;
    private final SkillsInstaller skillsInstaller;
    private final SkillsSearcher skillsSearcher;
    private final String workspace;                // 工作空间路径

    /**
     * 创建技能管理工具（使用默认配置）。
     *
     * @param workspace 工作空间路径
     */
    public SkillsTool(String workspace) {
        this(workspace, new SkillsLoader(workspace, null, null), null);
    }

    /**
     * 创建带完整配置的技能管理工具。
     *
     * @param workspace     工作空间路径
     * @param globalSkills  全局技能目录路径
     * @param builtinSkills 内置技能目录路径
     */
    public SkillsTool(String workspace, String globalSkills, String builtinSkills) {
        this(workspace, new SkillsLoader(workspace, globalSkills, builtinSkills), null);
    }

    /**
     * 创建共享 SkillsLoader 实例的技能管理工具。
     * 
     * 通过共享 SkillsLoader 实例，确保 SkillsTool 和 ContextBuilder 
     * 对技能列表的视图保持一致，避免 create/edit 后不同步的问题。
     *
     * @param workspace    工作空间路径
     * @param skillsLoader 共享的技能加载器实例
     */
    public SkillsTool(String workspace, SkillsLoader skillsLoader) {
        this(workspace, skillsLoader, null);
    }

    /**
     * 创建带完整配置的技能管理工具（主构造函数）。
     * 
     * @param workspace    工作空间路径
     * @param skillsLoader 技能加载器实例
     * @param skillsConfig 技能工具配置（为 null 时使用默认配置）
     */
    public SkillsTool(String workspace, SkillsLoader skillsLoader, ToolsConfig.SkillsToolConfig skillsConfig) {
        this.workspace = workspace;
        this.skillsLoader = skillsLoader;
        this.skillsInstaller = new SkillsInstaller(workspace);
        this.skillsSearcher = (skillsConfig != null)
                ? SkillsSearcher.fromConfig(skillsConfig)
                : new SkillsSearcher();
    }

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "管理和执行技能：列出、调用（invoke）、搜索 GitHub 上的技能、安装（支持 GitHub 仓库或压缩包 URL）、创建新技能、编辑现有技能或删除技能。"
                + "使用 search 操作搜索 GitHub 上的社区技能。"
                + "使用 invoke 操作调用技能，会返回技能完整内容和目录路径（base-path），可用于执行技能目录下的脚本。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("action", buildParam("string",
                "要执行的操作："
                        + "'list' - 列出所有已安装的技能; "
                        + "'invoke' - 调用技能，返回完整内容和基础路径（base-path），可用于执行技能目录下的脚本; "
                        + "'search' - 在 GitHub 上搜索可用的技能仓库（需要 query 参数）; "
                        + "'install' - 安装技能，支持 GitHub 仓库（'owner/repo' 或 'owner/repo/skill-name'）或压缩包 URL（.zip/.tar.gz）; "
                        + "'create' - 创建新技能，指定名称和内容; "
                        + "'edit' - 更新现有技能的内容; "
                        + "'remove' - 按名称删除技能",
                new String[]{"list", "invoke", "search", "install", "create", "edit", "remove"}));
        properties.put("name", buildParam("string", "技能名称（invoke、create、edit、remove 操作必需）"));
        properties.put("query", buildParam("string", "搜索关键词，用于 search 和 search_install 操作（描述你需要的技能功能，例如 'pptx generation' 或 'weather forecast'）"));
        properties.put("repo", buildParam("string",
                "install 操作的安装来源，支持以下格式：\n"
                        + "- GitHub 仓库：'owner/repo' 或 'owner/repo/skill-name'\n"
                        + "- GitHub 完整 URL：'https://github.com/owner/repo'\n"
                        + "- 压缩包 URL（.zip/.tar.gz）：'https://example.com/skill.zip|skill-name'（| 后为技能名）\n"
                        + "- 压缩包 URL（文件名即技能名）：'https://example.com/my-skill.zip'"));
        properties.put("content", buildParam("string",
                "用于 create/edit 操作的 Markdown 格式技能内容。"
                        + "应包含 YAML frontmatter（---\\nname: ...\\ndescription: ...\\n---）后跟技能指令。"));
        properties.put("skill_description", buildParam("string", "技能的简短描述（创建新技能时使用）"));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"action"}
        );
    }

    /**
     * 构建参数描述 Map（无枚举值）。
     */
    private Map<String, Object> buildParam(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    /**
     * 构建参数描述 Map（含枚举值）。
     */
    private Map<String, Object> buildParam(String type, String description, String[] enumValues) {
        Map<String, Object> param = new HashMap<>();
        param.put("type", type);
        param.put("description", description);
        param.put("enum", enumValues);
        return param;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String action = requireParam(args, "action", "execute");

        try {
            return switch (action) {
                case "list"    -> executeList();
                case "invoke"  -> executeInvoke(args);
                case "search"  -> executeSearch(args);
                case "install" -> executeInstall(args);
                case "create"  -> executeCreate(args);
                case "edit"    -> executeEdit(args);
                case "remove"  -> executeRemove(args);
                default -> throw new IllegalArgumentException(
                        "未知操作: " + action + "。有效操作：list、invoke、search、install、create、edit、remove");
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("执行操作 '" + action + "' 失败", e);
        }
    }

    /**
     * 从参数 Map 中获取必填字符串参数，为空时抛出 {@link IllegalArgumentException}。
     *
     * @param args      参数映射
     * @param key       参数键名
     * @param operation 当前操作名称，用于错误提示
     * @return 非空的参数值
     */
    private String requireParam(Map<String, Object> args, String key, String operation) {
        String value = (String) args.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("对于 '" + operation + "' 操作，" + key + " 参数是必需的");
        }
        return value;
    }

    /**
     * 列出所有已安装的技能。
     *
     * @return 技能列表的格式化字符串
     */
    private String executeList() {
        List<SkillInfo> skills = skillsLoader.listSkills();
        if (skills.isEmpty()) {
            return "没有安装技能。您可以：\n"
                    + "- 从 GitHub 安装：使用操作 'install' 和 repo='owner/repo'\n"
                    + "- 创建新技能：使用操作 'create' 并指定 name 和 content";
        }

        String skillLines = skills.stream()
                .map(skill -> {
                    String header = "- **" + skill.getName() + "**"
                            + (isNotEmpty(skill.getDescription()) ? " — " + skill.getDescription() : "");
                    return header + "\n  来源: " + skill.getSource() + " | 路径: " + skill.getPath();
                })
                .collect(Collectors.joining("\n"));

        return "已安装技能 (" + skills.size() + "):\n\n" + skillLines + "\n";
    }

    private static boolean isNotEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private record SkillContent(String name, String content) {}

    /**
     * 从参数中提取技能名称，并加载其内容。
     *
     * @param args      参数映射，必须包含 name 字段
     * @param operation 当前操作名称，用于错误提示
     * @return 技能名称和内容的记录
     * @throws ToolException 如果技能未找到
     */
    private SkillContent resolveSkillContent(Map<String, Object> args, String operation) throws ToolException {
        String skillName = requireParam(args, "name", operation);

        String content = skillsLoader.loadSkill(skillName);
        if (content == null) {
            throw new ToolException("技能 '" + skillName + "' 未找到。使用 'list' 操作查看可用技能。");
        }

        return new SkillContent(skillName, content);
    }

    /**
     * 调用技能，返回基础路径和完整指令。
     * 
     * 这是执行带脚本技能的核心方法，符合 Claude Code Skills 行业标准。
     * 返回内容包含：Base Path（技能目录绝对路径）和技能的完整 Markdown 指令。
     * Agent 收到响应后，可根据指令使用 exec 工具执行技能目录下的脚本。
     * 
     * @param args 参数映射，必须包含 name 字段
     * @return 包含 Base Path 和技能内容的字符串
     */
    private String executeInvoke(Map<String, Object> args) throws ToolException {
        SkillContent skill = resolveSkillContent(args, "invoke");
        String skillName = skill.name();
        String content = skill.content();

        // 查找技能并获取其路径
        SkillLocation location = findSkillLocation(skillName);
        if (location == null) {
            return "技能 '" + skillName + "' 位置解析失败，无法获取 base-path。";
        }

        logger.info("技能调用", Map.of(
                "skill", skillName,
                "base_path", location.basePath,
                "source", location.source
        ));

        // 构建符合 Claude Code Skills 标准的响应格式
        StringBuilder result = new StringBuilder();
        result.append("<skill-invocation>\n");
        result.append("<name>").append(skillName).append("</name>\n");
        result.append("<source>").append(location.source).append("</source>\n");
        result.append("<base-path>").append(location.basePath).append("</base-path>\n");
        result.append("</skill-invocation>\n\n");
        result.append("# Skill: ").append(skillName).append("\n\n");
        result.append(content);
        result.append("\n\n---\n\n");
        result.append("**提示**: 如果技能指令中包含脚本执行，请使用上述 base-path 作为脚本的工作目录。\n");
        result.append("例如: `exec(command='python3 ").append(location.basePath).append("/script.py')`");

        return result.toString();
    }

    /**
     * 查找技能所在位置。
     * 
     * 按优先级顺序查找：workspace > global > builtin。
     * 对于 builtin 技能，会自动将其从 classpath 解压到文件系统缓存目录，
     * 确保返回的 base-path 在 JAR 环境下也是有效的文件系统路径。
     * 
     * @param skillName 技能名称
     * @return 技能位置信息，未找到返回 null
     */
    private SkillLocation findSkillLocation(String skillName) {
        // 优先查找工作空间技能
        Path workspacePath = Paths.get(workspace, "skills", skillName, "SKILL.md");
        if (Files.exists(workspacePath)) {
            return new SkillLocation(
                    Paths.get(workspace, "skills", skillName).toAbsolutePath().toString(),
                    "workspace"
            );
        }

        // 遍历已加载的技能列表查找
        for (SkillInfo skill : skillsLoader.listSkills()) {
            if (skill.getName().equals(skillName)) {
                // builtin 技能的路径是 classpath: 前缀，JAR 环境下不是有效的文件系统路径
                // 需要解压到文件系统缓存目录
                if ("builtin".equals(skill.getSource())) {
                    String extractedPath = skillsLoader.extractBuiltinSkillToFileSystem(skillName);
                    if (extractedPath != null) {
                        return new SkillLocation(extractedPath, "builtin");
                    }
                    // 解压失败时回退，返回提示信息
                    logger.warn("无法解压 builtin 技能到文件系统", Map.of("skill", skillName));
                    return new SkillLocation(skill.getPath(), skill.getSource());
                }
                
                Path skillPath = Paths.get(skill.getPath()).getParent();
                return new SkillLocation(
                        skillPath.toAbsolutePath().toString(),
                        skill.getSource()
                );
            }
        }

        return null;
    }

    /**
     * 技能位置信息：目录绝对路径 + 来源（workspace/global/builtin）。
     */
    private record SkillLocation(String basePath, String source) {}

    /**
     * 在 GitHub 上搜索可用的技能仓库。
     *
     * 通过 GitHub Search API 搜索包含 SKILL.md 的仓库，
     * 支持按关键词搜索和按 topic 过滤。返回搜索结果列表，
     * 包含仓库名称、描述、星标数和安装命令。
     *
     * @param args 参数映射，必须包含 query 字段
     * @return 格式化的搜索结果
     */
    private String executeSearch(Map<String, Object> args) {
        String query = requireParam(args, "query", "search");

        logger.info("Searching skill registries", Map.of("query", query));

        List<SkillsSearcher.SkillSearchResult> results = skillsSearcher.search(query, 5);

        String enabledRegistryNames = skillsSearcher.getRegistries().stream()
                .filter(SkillRegistry::isEnabled)
                .map(SkillRegistry::getName)
                .collect(Collectors.joining(", "));

        String globalSearchSuffix = skillsSearcher.isAllowGlobalSearch() ? " + GitHub 全网" : "";
        String searchSourceLine = "🔍 搜索源: " + enabledRegistryNames + globalSearchSuffix + "\n\n";

        return searchSourceLine + skillsSearcher.formatResults(results, query);
    }

    /**
     * 安装技能。
     *
     * @param args 参数映射，必须包含 repo 字段
     * @return 安装结果信息
     * @throws Exception 安装失败时抛出异常
     */
    private String executeInstall(Map<String, Object> args) throws Exception {
        String repo = requireParam(args, "repo", "install");

        logger.info("AI-initiated skill install", Map.of("repo", repo));
        String result = skillsInstaller.install(repo);
        return result + "\n技能现已可用，将在下次上下文构建时加载。";
    }

    /**
     * 创建新技能，AI 自主学习的核心能力。
     * 
     * @param args 参数映射，必须包含 name 字段，可选 content 或 skill_description 字段
     * @return 创建结果信息
     * @throws Exception 创建失败时抛出异常
     */
    private String executeCreate(Map<String, Object> args) throws Exception {
        String skillName = requireParam(args, "name", "create");

        String content = (String) args.get("content");
        String skillDescription = (String) args.get("skill_description");

        if (content == null || content.isEmpty()) {
            if (skillDescription == null || skillDescription.isEmpty()) {
                throw new IllegalArgumentException("对于 'create' 操作，content 或 skill_description 参数是必需的");
            }
            content = buildSkillTemplate(skillName, skillDescription);
        }

        // 确保内容包含 frontmatter
        content = ensureFrontmatter(content, skillName, skillDescription);

        Path skillDir = Paths.get(workspace, "skills", skillName);
        Path skillFile = skillDir.resolve("SKILL.md");

        if (Files.exists(skillFile)) {
            throw new IllegalArgumentException("技能 '" + skillName + "' 已存在。请使用 'edit' 操作修改它，或先使用 'remove' 删除。");
        }

        Files.createDirectories(skillDir);
        Files.writeString(skillFile, content);

        logger.info("AI created new skill", Map.of(
                "skill", skillName,
                "path", skillFile.toString(),
                "content_length", content.length()
        ));

        return "✓ 技能 '" + skillName + "' 已成功创建于 " + skillFile
                + "\n技能将在下次上下文构建时自动加载。";
    }

    /**
     * 编辑已有技能的内容。
     * 
     * @param args 参数映射，必须包含 name 和 content 字段
     * @return 编辑结果信息
     * @throws Exception 编辑失败时抛出异常
     */
    private String executeEdit(Map<String, Object> args) throws Exception {
        String skillName = requireParam(args, "name", "edit");
        String content = requireParam(args, "content", "edit");

        // 查找技能文件
        Path workspaceSkillFile = Paths.get(workspace, "skills", skillName, "SKILL.md");

        if (!Files.exists(workspaceSkillFile)) {
            // 检查技能是否存在于其他位置（global/builtin）
            String existingContent = skillsLoader.loadSkill(skillName);
            if (existingContent != null) {
                // 复制到工作空间以进行编辑（工作空间具有最高优先级）
                Files.createDirectories(workspaceSkillFile.getParent());
                Files.writeString(workspaceSkillFile, content);

                logger.info("AI copied and edited skill to workspace", Map.of(
                        "skill", skillName,
                        "path", workspaceSkillFile.toString()
                ));

                return "✓ 技能 '" + skillName + "' 已复制到工作空间并更新于 " + workspaceSkillFile
                        + "\n工作空间版本将覆盖原始版本。";
            }
            throw new IllegalArgumentException("技能 '" + skillName + "' 未找到。请使用 'create' 操作创建新技能。");
        }

        // 确保内容包含 frontmatter
        content = ensureFrontmatter(content, skillName, (String) args.get("skill_description"));

        Files.writeString(workspaceSkillFile, content);

        logger.info("AI edited skill", Map.of(
                "skill", skillName,
                "path", workspaceSkillFile.toString(),
                "content_length", content.length()
        ));

        return "✓ 技能 '" + skillName + "' 已成功更新于 " + workspaceSkillFile;
    }

    /**
     * 删除指定技能。
     * 
     * @param args 参数映射，必须包含 name 字段
     * @return 删除结果信息
     * @throws Exception 删除失败时抛出异常
     */
    private String executeRemove(Map<String, Object> args) throws Exception {
        String skillName = requireParam(args, "name", "remove");

        Path skillDir = Paths.get(workspace, "skills", skillName);
        if (!Files.exists(skillDir)) {
            return "技能 '" + skillName + "' 在工作空间技能目录中未找到。";
        }

        deleteDirectory(skillDir);

        logger.info("AI removed skill", Map.of("skill", skillName));

        return "✓ 技能 '" + skillName + "' 已成功删除。";
    }

    /**
     * 确保内容包含 frontmatter。
     * 
     * @param content 原始内容
     * @param skillName 技能名称
     * @param skillDescription 技能描述
     * @return 包含 frontmatter 的内容
     */
    private String ensureFrontmatter(String content, String skillName, String skillDescription) {
        if (content.trim().startsWith("---")) {
            return content;
        }
        
        String description = skillDescription != null ? skillDescription : "A skill for " + skillName;
        return "---\nname: \"" + skillName + "\"\ndescription: \"" + description + "\"\n---\n\n" + content;
    }

    /**
     * 构建技能模板。
     * 
     * @param skillName 技能名称
     * @param description 技能描述
     * @return 技能模板内容
     */
    private String buildSkillTemplate(String skillName, String description) {
        return "---\n"
                + "name: \"" + skillName + "\"\n"
                + "description: \"" + description + "\"\n"
                + "---\n\n"
                + "# " + skillName + "\n\n"
                + description + "\n\n"
                + "## Instructions\n\n"
                + "当用户要求执行与此技能相关的任务时，请遵循以下步骤:\n\n"
                + "1. 理解用户的请求\n"
                + "2. 执行适当的操作\n"
                + "3. 报告结果\n";
    }

    /**
     * 递归删除目录。
     * 
     * @param directory 要删除的目录路径
     * @throws IOException 删除失败时抛出异常
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            try (var stream = Files.list(directory)) {
                stream.forEach(path -> {
                    try {
                        deleteDirectory(path);
                    } catch (IOException e) {
                        throw new RuntimeException("删除失败: " + path, e);
                    }
                });
            }
        }
        Files.deleteIfExists(directory);
    }
}
