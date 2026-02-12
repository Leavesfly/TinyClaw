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
        return "Manage your skills: list, show, install from GitHub, create new skills, edit existing skills, or remove skills. "
                + "Use this to learn new capabilities by installing community skills or creating your own skills based on experience.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> actionParam = new HashMap<>();
        actionParam.put("type", "string");
        actionParam.put("description",
                "Action to perform: "
                        + "'list' - list all installed skills; "
                        + "'show' - show full content of a skill; "
                        + "'install' - install a skill from GitHub (e.g. 'owner/repo' or 'owner/repo/skill-name'); "
                        + "'create' - create a new skill with given name and content; "
                        + "'edit' - update an existing skill's content; "
                        + "'remove' - remove a skill by name");
        actionParam.put("enum", new String[]{"list", "show", "install", "create", "edit", "remove"});
        properties.put("action", actionParam);

        Map<String, Object> nameParam = new HashMap<>();
        nameParam.put("type", "string");
        nameParam.put("description", "Skill name (required for show, create, edit, remove)");
        properties.put("name", nameParam);

        Map<String, Object> repoParam = new HashMap<>();
        repoParam.put("type", "string");
        repoParam.put("description", "GitHub repo specifier for install action (e.g. 'owner/repo' or 'owner/repo/skill-name')");
        properties.put("repo", repoParam);

        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description",
                "Skill content in Markdown format for create/edit actions. "
                        + "Should include YAML frontmatter (---\\nname: ...\\ndescription: ...\\n---) followed by the skill instructions.");
        properties.put("content", contentParam);

        Map<String, Object> descriptionParam = new HashMap<>();
        descriptionParam.put("type", "string");
        descriptionParam.put("description", "Short description of the skill (used when creating a new skill)");
        properties.put("skill_description", descriptionParam);

        params.put("properties", properties);
        params.put("required", new String[]{"action"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("action is required");
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
                throw new IllegalArgumentException("Unknown action: " + action
                        + ". Valid actions: list, show, install, create, edit, remove");
        }
    }

    /**
     * 列出所有已安装的技能
     */
    private String executeList() {
        List<SkillInfo> skills = skillsLoader.listSkills();
        if (skills.isEmpty()) {
            return "No skills installed. You can:\n"
                    + "- Install from GitHub: use action 'install' with repo='owner/repo'\n"
                    + "- Create a new skill: use action 'create' with name and content";
        }

        StringBuilder result = new StringBuilder();
        result.append("Installed skills (").append(skills.size()).append("):\n\n");
        for (SkillInfo skill : skills) {
            result.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                result.append(" — ").append(skill.getDescription());
            }
            result.append("\n  Source: ").append(skill.getSource());
            result.append(" | Path: ").append(skill.getPath());
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
            throw new IllegalArgumentException("name is required for 'show' action");
        }

        String content = skillsLoader.loadSkill(skillName);
        if (content == null) {
            return "Skill '" + skillName + "' not found. Use action 'list' to see available skills.";
        }

        return "=== Skill: " + skillName + " ===\n\n" + content;
    }

    /**
     * 从 GitHub 安装技能
     */
    private String executeInstall(Map<String, Object> args) throws Exception {
        String repo = (String) args.get("repo");
        if (repo == null || repo.isEmpty()) {
            throw new IllegalArgumentException("repo is required for 'install' action (e.g. 'owner/repo' or 'owner/repo/skill-name')");
        }

        logger.info("AI-initiated skill install", Map.of("repo", repo));
        String result = skillsInstaller.install(repo);
        return result + "\nThe skill is now available and will be loaded in the next context build.";
    }

    /**
     * 创建新技能 — AI 自主学习的核心能力
     */
    private String executeCreate(Map<String, Object> args) throws Exception {
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("name is required for 'create' action");
        }

        String content = (String) args.get("content");
        String skillDescription = (String) args.get("skill_description");

        if (content == null || content.isEmpty()) {
            if (skillDescription == null || skillDescription.isEmpty()) {
                throw new IllegalArgumentException("content or skill_description is required for 'create' action");
            }
            content = buildSkillTemplate(skillName, skillDescription);
        }

        // Ensure content has frontmatter
        if (!content.trim().startsWith("---")) {
            String description = skillDescription != null ? skillDescription : "A skill for " + skillName;
            content = "---\nname: \"" + skillName + "\"\ndescription: \"" + description + "\"\n---\n\n" + content;
        }

        Path skillDir = Paths.get(workspace, "skills", skillName);
        Path skillFile = skillDir.resolve("SKILL.md");

        if (Files.exists(skillFile)) {
            throw new IllegalArgumentException("Skill '" + skillName + "' already exists. Use 'edit' action to modify it, or 'remove' first.");
        }

        Files.createDirectories(skillDir);
        Files.writeString(skillFile, content);

        logger.info("AI created new skill", Map.of(
                "skill", skillName,
                "path", skillFile.toString(),
                "content_length", content.length()
        ));

        return "✓ Skill '" + skillName + "' created successfully at " + skillFile
                + "\nThe skill will be automatically loaded in the next context build.";
    }

    /**
     * 编辑已有技能的内容
     */
    private String executeEdit(Map<String, Object> args) throws Exception {
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("name is required for 'edit' action");
        }

        String content = (String) args.get("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("content is required for 'edit' action");
        }

        // Find the skill file
        Path workspaceSkillFile = Paths.get(workspace, "skills", skillName, "SKILL.md");

        if (!Files.exists(workspaceSkillFile)) {
            // Check if skill exists in other locations (global/builtin)
            String existingContent = skillsLoader.loadSkill(skillName);
            if (existingContent != null) {
                // Copy to workspace for editing (workspace has highest priority)
                Files.createDirectories(workspaceSkillFile.getParent());
                Files.writeString(workspaceSkillFile, content);

                logger.info("AI copied and edited skill to workspace", Map.of(
                        "skill", skillName,
                        "path", workspaceSkillFile.toString()
                ));

                return "✓ Skill '" + skillName + "' copied to workspace and updated at " + workspaceSkillFile
                        + "\nThe workspace version will override the original.";
            }
            throw new IllegalArgumentException("Skill '" + skillName + "' not found. Use 'create' action to create a new skill.");
        }

        // Ensure content has frontmatter
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

        return "✓ Skill '" + skillName + "' updated successfully at " + workspaceSkillFile;
    }

    /**
     * 删除指定技能
     */
    private String executeRemove(Map<String, Object> args) throws Exception {
        String skillName = (String) args.get("name");
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("name is required for 'remove' action");
        }

        Path skillDir = Paths.get(workspace, "skills", skillName);
        if (!Files.exists(skillDir)) {
            return "Skill '" + skillName + "' not found in workspace skills directory.";
        }

        deleteDirectory(skillDir);

        logger.info("AI removed skill", Map.of("skill", skillName));

        return "✓ Skill '" + skillName + "' removed successfully.";
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
                + "When the user asks to perform tasks related to this skill, follow these steps:\n\n"
                + "1. Understand the user's request\n"
                + "2. Execute the appropriate actions\n"
                + "3. Report the results\n";
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
                    throw new RuntimeException("Failed to delete: " + path, e);
                }
            });
        }
        Files.deleteIfExists(directory);
    }
}
