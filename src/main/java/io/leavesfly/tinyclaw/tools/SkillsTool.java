package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.config.ToolsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.skills.SkillInfo;
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

/**
 * 技能管理工具，赋予 Agent 自主学习和管理技能的能力。
 * 
 * 这是实现 AI 自主学习 Skill 的核心工具，让 Agent 不再依赖人工安装技能，
 * 而是能够自主发现、安装、创建和编辑技能。
 * 
 * 支持的操作：
 * - list: 列出所有已安装的技能
 * - show: 查看指定技能的完整内容
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
        return "管理和执行技能：列出、查看、调用（invoke）、搜索 GitHub 上的技能、搜索并自动安装、从 GitHub 安装、创建新技能、编辑现有技能或删除技能。"
                + "使用 search 操作搜索 GitHub 上的社区技能，使用 search_install 一键搜索并安装最匹配的技能。"
                + "使用 invoke 操作来调用带脚本的技能，会返回技能目录路径以便执行脚本。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> actionParam = new HashMap<>();
        actionParam.put("type", "string");
        actionParam.put("description",
                "要执行的操作："
                        + "'list' - 列出所有已安装的技能; "
                        + "'show' - 显示技能的完整内容; "
                        + "'invoke' - 调用技能并返回基础路径（用于执行带脚本的技能）; "
                        + "'search' - 在 GitHub 上搜索可用的技能仓库（需要 query 参数）; "
                        + "'search_install' - 搜索 GitHub 并自动安装最匹配的技能（需要 query 参数）; "
                        + "'install' - 从 GitHub 安装技能（例如 'owner/repo' 或 'owner/repo/skill-name'）; "
                        + "'create' - 创建新技能，指定名称和内容; "
                        + "'edit' - 更新现有技能的内容; "
                        + "'remove' - 按名称删除技能");
        actionParam.put("enum", new String[]{"list", "show", "invoke", "search", "search_install", "install", "create", "edit", "remove"});
        properties.put("action", actionParam);

        Map<String, Object> nameParam = new HashMap<>();
        nameParam.put("type", "string");
        nameParam.put("description", "技能名称（show、create、edit、remove 操作必需）");
        properties.put("name", nameParam);

        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "搜索关键词，用于 search 和 search_install 操作（描述你需要的技能功能，例如 'pptx generation' 或 'weather forecast'）");
        properties.put("query", queryParam);

        Map<String, Object> repoParam = new HashMap<>();
        repoParam.put("type", "string");
        repoParam.put("description", "GitHub 仓库指定符，用于 install 操作（例如 'owner/repo' 或 'owner/repo/skill-name'）");
        properties.put("repo", repoParam);

        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description",
                "用于 create/edit 操作的 Markdown 格式技能内容。"
                        + "应包含 YAML frontmatter（---\\nname: ...\\ndescription: ...\\n---）后跟技能指令。");
        properties.put("content", contentParam);

        Map<String, Object> descriptionParam = new HashMap<>();
        descriptionParam.put("type", "string");
        descriptionParam.put("description", "技能的简短描述（创建新技能时使用）");
        properties.put("skill_description", descriptionParam);

        params.put("properties", properties);
        params.put("required", new String[]{"action"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("操作参数是必需的");
        }

        return switch (action) {
            case "list" -> executeList();
            case "show" -> executeShow(args);
            case "invoke" -> executeInvoke(args);
            case "search" -> executeSearch(args);
            case "search_install" -> executeSearchInstall(args);
            case "install" -> executeInstall(args);
            case "create" -> executeCreate(args);
            case "edit" -> executeEdit(args);
            case "remove" -> executeRemove(args);
            default -> throw new IllegalArgumentException("未知操作: " + action
                    + "。有效操作：list、show、invoke、search、search_install、install、create、edit、remove");
        };
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

        StringBuilder result = new StringBuilder();
        result.append("已安装技能 (").append(skills.size()).append("):\n\n");
        for (SkillInfo skill : skills) {
            result.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                result.append(" — ").append(skill.getDescription());
            }
            result.append("\n  来源: ").append(skill.getSource());
            result.append(" | 路径: ").append(skill.getPath());
            result.append("\n");
        }
        return result.toString();
    }

    /**
     * 查看指定技能的完整内容。
     * 
     * @param args 参数映射，必须包含 name 字段
     * @return 技能内容或错误信息
     */
    private String executeShow(Map<String, Object> args) {
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("对于 'show' 操作，name 参数是必需的");
        }

        String content = skillsLoader.loadSkill(skillName);
        if (content == null) {
            return "技能 '" + skillName + "' 未找到。使用 'list' 操作查看可用技能。";
        }

        return "=== 技能: " + skillName + " ===\n\n" + content;
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
    private String executeInvoke(Map<String, Object> args) {
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("对于 'invoke' 操作，name 参数是必需的");
        }

        // 查找技能并获取其路径
        SkillLocation location = findSkillLocation(skillName);
        if (location == null) {
            return "技能 '" + skillName + "' 未找到。使用 'list' 操作查看可用技能。";
        }

        String content = skillsLoader.loadSkill(skillName);
        if (content == null) {
            return "技能 '" + skillName + "' 内容加载失败。";
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
     * 技能位置信息封装类。
     */
    private static class SkillLocation {
        final String basePath;  // 技能目录绝对路径
        final String source;    // 来源：workspace/global/builtin

        SkillLocation(String basePath, String source) {
            this.basePath = basePath;
            this.source = source;
        }
    }

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
        String query = (String) args.get("query");
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("对于 'search' 操作，query 参数是必需的（描述你需要的技能功能）");
        }

        logger.info("Searching skill registries", Map.of("query", query));

        List<SkillsSearcher.SkillSearchResult> results = skillsSearcher.search(query, 5);

        StringBuilder response = new StringBuilder();

        // 显示搜索源信息
        response.append("🔍 搜索源: ");
        List<String> sourceNames = skillsSearcher.getRegistries().stream()
                .filter(r -> r.isEnabled())
                .map(r -> r.getName())
                .toList();
        response.append(String.join(", ", sourceNames));
        if (skillsSearcher.isAllowGlobalSearch()) {
            response.append(" + GitHub 全网");
        }
        response.append("\n\n");

        response.append(skillsSearcher.formatResults(results, query));
        return response.toString();
    }

    /**
     * 搜索 GitHub 并自动安装最匹配的技能。
     * 
     * 这是自动搜索安装的核心方法，执行流程：
     * 1. 使用关键词在 GitHub 上搜索技能仓库
     * 2. 从搜索结果中选择最匹配的仓库（优先选择已验证包含 SKILL.md 的）
     * 3. 如果指定了 repo 参数，则从搜索结果中匹配该仓库
     * 4. 自动调用 SkillsInstaller 安装选中的技能
     * 
     * @param args 参数映射，必须包含 query 字段，可选 repo 字段指定安装哪个搜索结果
     * @return 搜索和安装结果信息
     * @throws Exception 搜索或安装失败时抛出异常
     */
    private String executeSearchInstall(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("对于 'search_install' 操作，query 参数是必需的（描述你需要的技能功能）");
        }

        String targetRepo = (String) args.get("repo");

        logger.info("Search and install skill", Map.of(
                "query", query,
                "target_repo", targetRepo != null ? targetRepo : "auto-select"
        ));

        // 搜索技能
        List<SkillsSearcher.SkillSearchResult> results = skillsSearcher.search(query, 5);

        if (results.isEmpty()) {
            return "未找到与 '" + query + "' 相关的技能仓库。\n\n"
                    + "建议：\n"
                    + "- 尝试使用不同的关键词：`skills(action='search', query='...')`\n"
                    + "- 自己创建一个技能：`skills(action='create', name='...', content='...')`";
        }

        // 选择要安装的仓库
        SkillsSearcher.SkillSearchResult selectedResult = null;

        if (targetRepo != null && !targetRepo.isEmpty()) {
            // 用户指定了仓库，从搜索结果中匹配
            for (SkillsSearcher.SkillSearchResult result : results) {
                if (result.getFullName().equalsIgnoreCase(targetRepo)
                        || result.getFullName().toLowerCase().contains(targetRepo.toLowerCase())) {
                    selectedResult = result;
                    break;
                }
            }
            if (selectedResult == null) {
                // 用户指定的仓库不在搜索结果中，直接尝试安装
                logger.info("Direct install from specified repo", Map.of("repo", targetRepo));
                String installResult = skillsInstaller.install(targetRepo);
                return "🔍 搜索到 " + results.size() + " 个结果，但按你指定的仓库直接安装：\n\n"
                        + installResult + "\n技能现已可用，将在下次上下文构建时加载。";
            }
        } else {
            // 自动选择最佳结果：优先选择已验证包含 SKILL.md 的仓库
            for (SkillsSearcher.SkillSearchResult result : results) {
                if (result.isHasSkillFile()) {
                    selectedResult = result;
                    break;
                }
            }

            // 如果没有已验证的，验证第一个结果是否包含 SKILL.md
            if (selectedResult == null) {
                SkillsSearcher.SkillSearchResult firstResult = results.get(0);
                boolean hasSkillFile = skillsSearcher.verifySkillFile(
                        firstResult.getFullName(), firstResult.getSkillSubdir());
                if (hasSkillFile) {
                    selectedResult = firstResult;
                }
            }

            // 如果仍然没有找到包含 SKILL.md 的仓库，返回搜索结果让用户选择
            if (selectedResult == null) {
                return "搜索到 " + results.size() + " 个相关仓库，但未能自动确认哪个包含有效的 SKILL.md 文件。\n\n"
                        + skillsSearcher.formatResults(results, query) + "\n\n"
                        + "请从上述结果中选择一个仓库，使用以下命令安装：\n"
                        + "`skills(action='install', repo='owner/repo')`";
            }
        }

        // 安装选中的技能
        String installSpecifier = selectedResult.getInstallSpecifier();
        logger.info("Auto-installing best match", Map.of(
                "repo", selectedResult.getFullName(),
                "install_specifier", installSpecifier,
                "stars", selectedResult.getStars()
        ));

        StringBuilder response = new StringBuilder();
        response.append("🔍 搜索到 ").append(results.size()).append(" 个与 '").append(query).append("' 相关的技能仓库。\n");
        response.append("📦 自动选择最佳匹配: **").append(selectedResult.getFullName()).append("**");
        if (selectedResult.getStars() > 0) {
            response.append(" ⭐ ").append(selectedResult.getStars());
        }
        response.append("\n");
        if (selectedResult.getDescription() != null && !selectedResult.getDescription().isEmpty()) {
            response.append("   ").append(selectedResult.getDescription()).append("\n");
        }
        response.append("\n");

        try {
            String installResult = skillsInstaller.install(installSpecifier);
            response.append(installResult).append("\n");
            response.append("技能现已可用，将在下次上下文构建时加载。\n\n");
            response.append("💡 使用 `skills(action='show', name='...")
                    .append("')` 查看技能详情，或使用 `skills(action='invoke', name='...")
                    .append("')` 调用技能。");
        } catch (Exception installError) {
            response.append("⚠️ 安装失败: ").append(installError.getMessage()).append("\n\n");
            response.append("其他搜索结果：\n");
            for (int i = 0; i < results.size(); i++) {
                SkillsSearcher.SkillSearchResult result = results.get(i);
                if (!result.getFullName().equals(selectedResult.getFullName())) {
                    response.append("- **").append(result.getFullName()).append("**");
                    if (result.getDescription() != null && !result.getDescription().isEmpty()) {
                        response.append(" — ").append(result.getDescription());
                    }
                    response.append("\n  安装: `skills(action='install', repo='")
                            .append(result.getInstallSpecifier()).append("')`\n");
                }
            }
        }

        return response.toString();
    }

    /**
     * 从 GitHub 安装技能。
     * 
     * @param args 参数映射，必须包含 repo 字段
     * @return 安装结果信息
     * @throws Exception 安装失败时抛出异常
     */
    private String executeInstall(Map<String, Object> args) throws Exception {
        String repo = (String) args.get("repo");
        if (repo == null || repo.isEmpty()) {
            throw new IllegalArgumentException("对于 'install' 操作，repo 参数是必需的（例如 'owner/repo' 或 'owner/repo/skill-name'）");
        }

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
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("对于 'create' 操作，name 参数是必需的");
        }

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
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("对于 'edit' 操作，name 参数是必需的");
        }

        String content = (String) args.get("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("对于 'edit' 操作，content 参数是必需的");
        }

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
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("对于 'remove' 操作，name 参数是必需的");
        }

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
