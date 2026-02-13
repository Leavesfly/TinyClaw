package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.skills.SkillsInstaller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 技能命令 - 管理技能的安装、列表、移除和查看
 * 
 * 提供完整的技能管理功能，支持从多个来源管理技能：
 * - 本地技能：工作空间中的 skills 目录
 * - 内置技能：预装在系统中的技能模板
 * - 远程技能：从 GitHub 仓库安装（需手动克隆）
 * 
 * 命令列表：
 * - list: 列出已安装的技能
 * - install-builtin: 安装所有内置技能到工作空间
 * - list-builtin: 列出可用的内置技能
 * - install: 从 GitHub 仓库安装技能（提示手动操作）
 * - remove: 移除已安装的技能
 * - show: 显示技能的详细内容
 */
public class SkillsCommand extends CliCommand {
    
    // 内置技能列表 - 这些是预定义的技能模板
    private static final List<String> BUILTIN_SKILLS = List.of(
        "weather",      // 天气查询技能
        "github",       // GitHub 操作技能
        "summarize",    // 文本摘要技能
        "tmux",         // tmux 会话管理技能
        "skill-creator" // 技能创建辅助技能
    );
    
    @Override
    public String name() {
        return "skills";
    }
    
    @Override
    public String description() {
        return "管理技能（安装、列表、移除）";
    }
    
    @Override
    public int execute(String[] args) throws Exception {
        if (args.length < 1) {
            printHelp();
            return 1;
        }
        
        String subcommand = args[0];
        
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            System.err.println("加载配置错误: " + e.getMessage());
            return 1;
        }
        
        String workspace = config.getWorkspacePath();
        String skillsDir = Paths.get(workspace, "skills").toString();
        
        switch (subcommand) {
            case "list":
                return listSkills(skillsDir);
            case "install-builtin":
                return installBuiltinSkills(skillsDir);
            case "list-builtin":
                return listBuiltinSkills();
            case "install":
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw skills install <github-repo>");
                    System.out.println("Example: tinyclaw skills install sipeed/tinyclaw-skills/weather");
                    return 1;
                }
                return installSkill(skillsDir, args[1]);
            case "remove":
            case "uninstall":
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw skills remove <skill-name>");
                    return 1;
                }
                return removeSkill(skillsDir, args[1]);
            case "show":
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw skills show <skill-name>");
                    return 1;
                }
                return showSkill(skillsDir, args[1]);
            default:
                System.out.println("未知的技能命令: " + subcommand);
                printHelp();
                return 1;
        }
    }
    
    /**
     * 列出已安装的技能
     * 
     * 扫描工作空间的 skills 目录，显示所有已安装技能的信息。
     * 每个技能显示名称和描述（从 SKILL.md 中提取）。
     * 
     * @param skillsDir 技能目录路径
     * @return 退出码（0 表示成功）
     */
    private int listSkills(String skillsDir) {
        File dir = new File(skillsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("未安装技能。");
            return 0;
        }
        
        File[] skillDirs = dir.listFiles(File::isDirectory);
        if (skillDirs == null || skillDirs.length == 0) {
            System.out.println("未安装技能。");
            return 0;
        }
        
        System.out.println();
        System.out.println("已安装的技能：");
        System.out.println("------------------");
        
        for (File skillDir : skillDirs) {
            String skillName = skillDir.getName();
            File skillFile = new File(skillDir, "SKILL.md");
            
            String description = "无描述";
            if (skillFile.exists()) {
                try {
                    String content = Files.readString(skillFile.toPath());
                    // 从前几行提取描述
                    String[] lines = content.split("\n");
                    for (String line : lines) {
                        if (line.startsWith("description:")) {
                            description = line.substring("description:".length()).trim();
                            break;
                        }
                        if (line.startsWith("# ")) {
                            description = line.substring(2).trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }
            
            System.out.println("  ✓ " + skillName);
            System.out.println("    " + description);
        }
        
        return 0;
    }
    
    /**
     * 列出可用的内置技能
     * 
     * 显示所有预定义的内置技能列表，这些技能可以通过
     * install-builtin 命令安装到工作空间。
     */
    private int listBuiltinSkills() {
        System.out.println();
        System.out.println("可用的内置技能：");
        System.out.println("------------------");
        System.out.println("  • weather        - 天气查询技能");
        System.out.println("  • github         - GitHub 操作技能");
        System.out.println("  • summarize      - 文本摘要技能");
        System.out.println("  • tmux           - tmux 会话管理技能");
        System.out.println("  • skill-creator  - 技能创建辅助技能");
        System.out.println();
        System.out.println("使用 'tinyclaw skills install-builtin' 安装所有内置技能。");
        return 0;
    }
    
    /**
     * 安装所有内置技能到工作空间
     * 
     * 将预定义的内置技能模板复制到工作空间的 skills 目录。
     * 每个技能包含一个 SKILL.md 文件，定义了技能的用途和使用方法。
     * 
     * @param skillsDir 目标技能目录路径
     * @return 退出码（0 表示成功）
     */
    private int installBuiltinSkills(String skillsDir) {
        System.out.println("正在安装内置技能到工作空间...");
        System.out.println();
        
        // 确保技能目录存在
        Path skillsPath = Paths.get(skillsDir);
        try {
            Files.createDirectories(skillsPath);
        } catch (IOException e) {
            System.out.println("✗ 无法创建技能目录: " + e.getMessage());
            return 1;
        }
        
        int installed = 0;
        int skipped = 0;
        
        for (String skillName : BUILTIN_SKILLS) {
            Path targetPath = skillsPath.resolve(skillName);
            
            // 检查技能是否已存在
            if (Files.exists(targetPath)) {
                System.out.println("  ⊘ " + skillName + " (已存在，跳过)");
                skipped++;
                continue;
            }
            
            try {
                // 创建技能目录
                Files.createDirectories(targetPath);
                
                // 创建基础的 SKILL.md 文件
                String skillContent = createBuiltinSkillContent(skillName);
                Files.writeString(targetPath.resolve("SKILL.md"), skillContent);
                
                System.out.println("  ✓ " + skillName + " 已安装");
                installed++;
            } catch (IOException e) {
                System.out.println("  ✗ " + skillName + " 安装失败: " + e.getMessage());
            }
        }
        
        System.out.println();
        System.out.println("安装完成！");
        System.out.println("  已安装: " + installed + " 个技能");
        if (skipped > 0) {
            System.out.println("  已跳过: " + skipped + " 个技能（已存在）");
        }
        
        return 0;
    }
    
    /**
     * 创建内置技能的 SKILL.md 内容
     * 
     * 为每个内置技能生成基础的内容模板。
     * 实际使用时，用户可以根据需要修改这些内容。
     * 
     * @param skillName 技能名称
     * @return SKILL.md 文件内容
     */
    private String createBuiltinSkillContent(String skillName) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skillName).append("\n");
        sb.append("description: \"").append(getSkillDescription(skillName)).append("\"\n");
        sb.append("---\n\n");
        sb.append("# ").append(skillName).append(" Skill\n\n");
        sb.append(getSkillDescription(skillName)).append(".\n\n");
        sb.append("## Usage\n\n");
        sb.append("This skill provides specialized capabilities for ").append(skillName).append(" related tasks.\n");
        return sb.toString();
    }
    
    /**
     * 获取技能的描述文本
     */
    private String getSkillDescription(String skillName) {
        switch (skillName) {
            case "weather":
                return "Query weather information for any location";
            case "github":
                return "Interact with GitHub repositories and issues";
            case "summarize":
                return "Summarize long texts and documents";
            case "tmux":
                return "Manage tmux sessions and windows";
            case "skill-creator":
                return "Help create new skills for tinyclaw";
            default:
                return "A skill for " + skillName;
        }
    }
    
    /**
     * 从 GitHub 安装技能
     * 
     * 使用 SkillsInstaller 从 GitHub 仓库克隆技能到工作空间。
     * 支持多种仓库格式：
     * - owner/repo
     * - owner/repo/skill-name
     * - 完整的 GitHub URL
     * 
     * @param skillsDir 技能目录路径
     * @param repo GitHub 仓库说明符
     * @return 退出码（0 表示成功）
     */
    private int installSkill(String skillsDir, String repo) {
        System.out.println("正在从 " + repo + " 安装技能...");
        
        try {
            // 从 skillsDir 获取 workspace 路径（skillsDir 是 workspace/skills）
            String workspace = Paths.get(skillsDir).getParent().toString();
            SkillsInstaller installer = new SkillsInstaller(workspace);
            String result = installer.install(repo);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.out.println("✗ 安装失败: " + e.getMessage());
            return 1;
        }
    }
    
    private int removeSkill(String skillsDir, String skillName) {
        Path skillPath = Paths.get(skillsDir, skillName);
        
        if (!Files.exists(skillPath)) {
            System.out.println("✗ 未找到技能 '" + skillName + "'");
            return 1;
        }
        
        try {
            deleteDirectory(skillPath.toFile());
            System.out.println("✓ 技能 '" + skillName + "' 已成功移除！");
            return 0;
        } catch (Exception e) {
            System.out.println("✗ 移除技能失败: " + e.getMessage());
            return 1;
        }
    }
    
    private int showSkill(String skillsDir, String skillName) {
        Path skillPath = Paths.get(skillsDir, skillName, "SKILL.md");
        
        if (!Files.exists(skillPath)) {
            System.out.println("✗ 未找到技能 '" + skillName + "'");
            return 1;
        }
        
        try {
            String content = Files.readString(skillPath);
            System.out.println();
            System.out.println("📦 技能: " + skillName);
            System.out.println("----------------------");
            System.out.println(content);
            return 0;
        } catch (Exception e) {
            System.out.println("✗ 读取技能失败: " + e.getMessage());
            return 1;
        }
    }
    
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }
    
    @Override
    public void printHelp() {
        System.out.println();
        System.out.println("技能命令：");
        System.out.println("  list                    列出已安装的技能");
        System.out.println("  install-builtin         安装所有内置技能到工作空间");
        System.out.println("  list-builtin            列出可用的内置技能");
        System.out.println("  install <repo>          从 GitHub 安装技能");
        System.out.println("  remove <name>           移除已安装的技能");
        System.out.println("  show <name>             显示技能详情");
        System.out.println();
        System.out.println("示例：");
        System.out.println("  tinyclaw skills list");
        System.out.println("  tinyclaw skills install-builtin");
        System.out.println("  tinyclaw skills list-builtin");
        System.out.println("  tinyclaw skills install sipeed/tinyclaw-skills/weather");
        System.out.println("  tinyclaw skills remove weather");
    }
}