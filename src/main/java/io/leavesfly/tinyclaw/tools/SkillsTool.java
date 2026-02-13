package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillsInstaller;
import io.leavesfly.tinyclaw.skills.SkillsLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理工具 - 赋予 Agent 自主学习和管理技能的能力
 * 
 * <p>这是实现"AI 自主学习 Skill"的核心工具，让 Agent 不再依赖人工安装技能，
 * 而是能够自主发现、安装、创建和编辑技能。</p>
 * 
 * <h2>支持的操作：</h2>
 * <ul>
 *   <li><b>list</b> - 列出所有已安装的技能</li>
 *   <li><b>show</b> - 查看指定技能的完整内容</li>
 *   <li><b>install</b> - 从 GitHub 仓库安装技能</li>
 *   <li><b>create</b> - 创建新技能（AI 自主学习的核心能力）</li>
 *   <li><b>edit</b> - 编辑已有技能的内容</li>
 *   <li><b>remove</b> - 删除指定技能</li>
 * </ul>
 * 
 * <h2>设计理念：</h2>
 * <p>传统的 Skill 是人工预定义的静态指令模板；而通过此工具，AI 可以：</p>
 * <ol>
 *   <li>在交互中识别重复模式，主动创建新技能固化经验</li>
 *   <li>从社区（GitHub）按需安装技能来解决新问题</li>
 *   <li>迭代优化已有技能，使其越来越好</li>
 * </ol>
 */
public class SkillsTool implements Tool {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("skills");

    private final SkillsLoader skillsLoader;
    private final SkillsInstaller skillsInstaller;
    private final String workspace;

    /**
     * 创建技能管理工具
     *
     * @param workspace 工作空间路径
     */
    public SkillsTool(String workspace) {
        this.workspace = workspace;
        this.skillsLoader = new SkillsLoader(workspace, null, null);
        this.skillsInstaller = new SkillsInstaller(workspace);
    }

    /**
     * 创建带完整配置的技能管理工具
     *
     * @param workspace     工作空间路径
     * @param globalSkills  全局技能目录路径
     * @param builtinSkills 内置技能目录路径
     */
    public SkillsTool(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        this.skillsLoader = new SkillsLoader(workspace, globalSkills, builtinSkills);
        this.skillsInstaller = new SkillsInstaller(workspace);
    }

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "管理您的技能：列出、查看、从 GitHub 安装、创建新技能、编辑现有技能或删除技能。"
                + "使用此工具通过安装社区技能或基于经验创建自己的技能来学习新能力。";
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
                        + "'install' - 从 GitHub 安装技能（例如 'owner/repo' 或 'owner/repo/skill-name'）; "
                        + "'create' - 创建新技能，指定名称和内容; "
                        + "'edit' - 更新现有技能的内容; "
                        + "'remove' - 按名称删除技能");
        actionParam.put("enum", new String[]{"list", "show", "install", "create", "edit", "remove"});
        properties.put("action", actionParam);

        Map<String, Object> nameParam = new HashMap<>();
        nameParam.put("type", "string");
        nameParam.put("description", "技能名称（show、create、edit、remove 操作必需）");
        properties.put("name", nameParam);

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

        switch (action) {
            case "list":
                return executeList();
            case "show":
                return executeShow(args);
            case "install":
                return executeInstall(args);
            case "create":
                return executeCreate(args);
            case "edit":
                return executeEdit(args);
            case "remove":
                return executeRemove(args);
            default:
                throw new IllegalArgumentException("未知操作: " + action
                        + "。有效操作：list、show、install、create、edit、remove");
        }
    }

    /**
     * 列出所有已安装的技能
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
     * 查看指定技能的完整内容
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
     * 从 GitHub 安装技能
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
     * 创建新技能 — AI 自主学习的核心能力
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
        if (!content.trim().startsWith("---")) {
            String description = skillDescription != null ? skillDescription : "A skill for " + skillName;
            content = "---\nname: \"" + skillName + "\"\ndescription: \"" + description + "\"\n---\n\n" + content;
        }

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
     * 编辑已有技能的内容
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
        if (!content.trim().startsWith("---")) {
            String skillDescription = (String) args.get("skill_description");
            String description = skillDescription != null ? skillDescription : "A skill for " + skillName;
            content = "---\nname: \"" + skillName + "\"\ndescription: \"" + description + "\"\n---\n\n" + content;
        }

        Files.writeString(workspaceSkillFile, content);

        logger.info("AI edited skill", Map.of(
                "skill", skillName,
                "path", workspaceSkillFile.toString(),
                "content_length", content.length()
        ));

        return "✓ 技能 '" + skillName + "' 已成功更新于 " + workspaceSkillFile;
    }

    /**
     * 删除指定技能
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
     * 构建技能模板
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
     * 递归删除目录
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            Files.list(directory).forEach(path -> {
                try {
                    deleteDirectory(path);
                } catch (IOException e) {
                    throw new RuntimeException("删除失败: " + path, e);
                }
            });
        }
        Files.deleteIfExists(directory);
    }
}
