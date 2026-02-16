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
 * 技能命令，管理技能的安装、列表、移除和查看。
 * 
 * 核心功能：
 * - 列出已安装的技能
 * - 安装内置技能到工作空间
 * - 从 GitHub 安装技能
 * - 移除已安装的技能
 * - 查看技能详情
 * 
 * 技能来源：
 * 1. 本地技能：工作空间中的 skills 目录
 * 2. 内置技能：预装在系统中的技能模板
 * 3. 远程技能：从 GitHub 仓库安装
 * 
 * 支持的子命令：
 * - list：列出已安装的技能
 * - install-builtin：安装所有内置技能到工作空间
 * - list-builtin：列出可用的内置技能
 * - install <repo>：从 GitHub 仓库安装技能
 * - remove/uninstall <name>：移除已安装的技能
 * - show <name>：显示技能的详细内容
 * 
 * 技能结构：
 * - 每个技能是 skills 目录下的一个子目录
 * - 每个技能包含一个 SKILL.md 文件定义功能
 * - SKILL.md 包含 YAML frontmatter（name、description）和正文
 */
public class SkillsCommand extends CliCommand {
    
    private static final String SUBCOMMAND_LIST = "list";                  // 列出技能子命令
    private static final String SUBCOMMAND_INSTALL_BUILTIN = "install-builtin";  // 安装内置技能子命令
    private static final String SUBCOMMAND_LIST_BUILTIN = "list-builtin";  // 列出内置技能子命令
    private static final String SUBCOMMAND_INSTALL = "install";            // 安装技能子命令
    private static final String SUBCOMMAND_REMOVE = "remove";              // 移除技能子命令
    private static final String SUBCOMMAND_UNINSTALL = "uninstall";        // 卸载技能子命令（别名）
    private static final String SUBCOMMAND_SHOW = "show";                  // 显示技能子命令
    
    private static final String SKILLS_DIR = "skills";                     // 技能目录名
    private static final String SKILL_FILE = "SKILL.md";                   // 技能定义文件名
    
    private static final String CHECK_MARK = "✓";                          // 成功标记
    private static final String CROSS_MARK = "✗";                          // 失败标记
    private static final String SKIP_MARK = "⊘";                           // 跳过标记
    private static final String BULLET = "•";                              // 列表项标记
    private static final String BOX = "📦";                                // 技能图标
    
    private static final String NO_SKILLS_MESSAGE = "未安装技能。";
    private static final String NO_DESCRIPTION = "无描述";
    
    private static final String DESCRIPTION_PREFIX = "description:";       // 描述前缀
    private static final String HEADING_PREFIX = "# ";                     // 标题前缀
    
    private static final String SEPARATOR = "------------------";          // 分隔线
    private static final String INDENT = "  ";                             // 缩进
    private static final String INDENT_DESC = "    ";                      // 描述缩进
    
    private static final String YAML_SEPARATOR = "---\n";                  // YAML 分隔符
    private static final String NEWLINE = "\n";                            // 换行符
    
    private static final String CONFIG_ERROR_PREFIX = "加载配置错误: ";
    private static final String INSTALL_ERROR_PREFIX = "✗ 安装失败: ";
    private static final String REMOVE_ERROR_PREFIX = "✗ 移除技能失败: ";
    private static final String READ_ERROR_PREFIX = "✗ 读取技能失败: ";
    
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
    
    /**
     * 执行技能命令。
     * 
     * @param args 命令参数
     * @return 执行结果，0 表示成功，1 表示失败
     * @throws Exception 执行异常
     */
    @Override
    public int execute(String[] args) throws Exception {
        if (args.length < 1) {
            printHelp();
            return 1;
        }
        
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }
        
        String skillsDir = getSkillsDirectory(config);
        return executeSubcommand(args[0], args, skillsDir);
    }
    
    /**
     * 加载配置。
     * 
     * @return 配置对象，加载失败返回 null
     */
    private Config loadConfig() {
        try {
            return ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            System.err.println(CONFIG_ERROR_PREFIX + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取技能目录路径。
     * 
     * @param config 配置对象
     * @return 技能目录路径
     */
    private String getSkillsDirectory(Config config) {
        String workspace = config.getWorkspacePath();
        return Paths.get(workspace, SKILLS_DIR).toString();
    }
    
    /**
     * 执行子命令。
     * 
     * @param subcommand 子命令名称
     * @param args 完整参数数组
     * @param skillsDir 技能目录路径
     * @return 执行结果
     */
    private int executeSubcommand(String subcommand, String[] args, String skillsDir) {
        return switch (subcommand) {
            case SUBCOMMAND_LIST -> listSkills(skillsDir);
            case SUBCOMMAND_INSTALL_BUILTIN -> installBuiltinSkills(skillsDir);
            case SUBCOMMAND_LIST_BUILTIN -> listBuiltinSkills();
            case SUBCOMMAND_INSTALL -> handleInstallCommand(args, skillsDir);
            case SUBCOMMAND_REMOVE, SUBCOMMAND_UNINSTALL -> handleRemoveCommand(args, skillsDir);
            case SUBCOMMAND_SHOW -> handleShowCommand(args, skillsDir);
            default -> handleUnknownCommand(subcommand);
        };
    }
    
    /**
     * 处理 install 命令。
     * 
     * @param args 命令参数
     * @param skillsDir 技能目录路径
     * @return 执行结果
     */
    private int handleInstallCommand(String[] args, String skillsDir) {
        if (args.length < 2) {
            System.out.println("Usage: tinyclaw skills install <github-repo>");
            System.out.println("Example: tinyclaw skills install sipeed/tinyclaw-skills/weather");
            return 1;
        }
        return installSkill(skillsDir, args[1]);
    }
    
    /**
     * 处理 remove 命令。
     * 
     * @param args 命令参数
     * @param skillsDir 技能目录路径
     * @return 执行结果
     */
    private int handleRemoveCommand(String[] args, String skillsDir) {
        if (args.length < 2) {
            System.out.println("Usage: tinyclaw skills remove <skill-name>");
            return 1;
        }
        return removeSkill(skillsDir, args[1]);
    }
    
    /**
     * 处理 show 命令。
     * 
     * @param args 命令参数
     * @param skillsDir 技能目录路径
     * @return 执行结果
     */
    private int handleShowCommand(String[] args, String skillsDir) {
        if (args.length < 2) {
            System.out.println("Usage: tinyclaw skills show <skill-name>");
            return 1;
        }
        return showSkill(skillsDir, args[1]);
    }
    
    /**
     * 处理未知命令。
     * 
     * @param subcommand 子命令名称
     * @return 执行结果
     */
    private int handleUnknownCommand(String subcommand) {
        System.out.println("未知的技能命令: " + subcommand);
        printHelp();
        return 1;
    }
    
    /**
     * 列出已安装的技能。
     * 
     * 扫描工作空间的 skills 目录，显示所有已安装技能的信息。
     * 每个技能显示名称和描述（从 SKILL.md 中提取）。
     * 
     * @param skillsDir 技能目录路径
     * @return 退出码，0 表示成功
     */
    private int listSkills(String skillsDir) {
        File dir = new File(skillsDir);
        
        if (!isValidDirectory(dir)) {
            System.out.println(NO_SKILLS_MESSAGE);
            return 0;
        }
        
        File[] skillDirs = dir.listFiles(File::isDirectory);
        if (skillDirs == null || skillDirs.length == 0) {
            System.out.println(NO_SKILLS_MESSAGE);
            return 0;
        }
        
        printSkillsHeader();
        
        for (File skillDir : skillDirs) {
            printSkillInfo(skillDir);
        }
        
        return 0;
    }
    
    /**
     * 检查目录是否有效。
     * 
     * @param dir 目录对象
     * @return 目录存在且是目录返回 true，否则返回 false
     */
    private boolean isValidDirectory(File dir) {
        return dir.exists() && dir.isDirectory();
    }
    
    /**
     * 打印技能列表头部。
     */
    private void printSkillsHeader() {
        System.out.println();
        System.out.println("已安装的技能：");
        System.out.println(SEPARATOR);
    }
    
    /**
     * 打印单个技能的信息。
     * 
     * @param skillDir 技能目录
     */
    private void printSkillInfo(File skillDir) {
        String skillName = skillDir.getName();
        String description = extractSkillDescription(skillDir);
        
        System.out.println(INDENT + CHECK_MARK + " " + skillName);
        System.out.println(INDENT_DESC + description);
    }
    
    /**
     * 提取技能描述。
     * 
     * 从 SKILL.md 文件中提取描述信息。
     * 优先读取 description: 字段，其次读取第一个标题。
     * 
     * @param skillDir 技能目录
     * @return 技能描述
     */
    private String extractSkillDescription(File skillDir) {
        File skillFile = new File(skillDir, SKILL_FILE);
        
        if (!skillFile.exists()) {
            return NO_DESCRIPTION;
        }
        
        try {
            String content = Files.readString(skillFile.toPath());
            return parseDescription(content);
        } catch (Exception e) {
            return NO_DESCRIPTION;
        }
    }
    
    /**
     * 从内容中解析描述。
     * 
     * @param content 文件内容
     * @return 描述信息
     */
    private String parseDescription(String content) {
        String[] lines = content.split(NEWLINE);
        
        for (String line : lines) {
            if (line.startsWith(DESCRIPTION_PREFIX)) {
                return line.substring(DESCRIPTION_PREFIX.length()).trim();
            }
            if (line.startsWith(HEADING_PREFIX)) {
                return line.substring(HEADING_PREFIX.length()).trim();
            }
        }
        
        return NO_DESCRIPTION;
    }
    
    /**
     * 列出可用的内置技能。
     * 
     * 显示所有预定义的内置技能列表，这些技能可以通过
     * install-builtin 命令安装到工作空间。
     * 
     * @return 退出码，0 表示成功
     */
    private int listBuiltinSkills() {
        System.out.println();
        System.out.println("可用的内置技能：");
        System.out.println(SEPARATOR);
        System.out.println(INDENT + BULLET + " weather        - 天气查询技能");
        System.out.println(INDENT + BULLET + " github         - GitHub 操作技能");
        System.out.println(INDENT + BULLET + " summarize      - 文本摘要技能");
        System.out.println(INDENT + BULLET + " tmux           - tmux 会话管理技能");
        System.out.println(INDENT + BULLET + " skill-creator  - 技能创建辅助技能");
        System.out.println();
        System.out.println("使用 'tinyclaw skills install-builtin' 安装所有内置技能。");
        return 0;
    }
    
    /**
     * 安装所有内置技能到工作空间。
     * 
     * 将预定义的内置技能模板复制到工作空间的 skills 目录。
     * 每个技能包含一个 SKILL.md 文件，定义了技能的用途和使用方法。
     * 
     * @param skillsDir 目标技能目录路径
     * @return 退出码，0 表示成功，1 表示失败
     */
    private int installBuiltinSkills(String skillsDir) {
        System.out.println("正在安装内置技能到工作空间...");
        System.out.println();
        
        if (!ensureSkillsDirectory(skillsDir)) {
            return 1;
        }
        
        InstallResult result = installAllBuiltinSkills(skillsDir);
        printInstallSummary(result);
        
        return 0;
    }
    
    /**
     * 确保技能目录存在。
     * 
     * @param skillsDir 技能目录路径
     * @return 成功返回 true，失败返回 false
     */
    private boolean ensureSkillsDirectory(String skillsDir) {
        Path skillsPath = Paths.get(skillsDir);
        try {
            Files.createDirectories(skillsPath);
            return true;
        } catch (IOException e) {
            System.out.println(CROSS_MARK + " 无法创建技能目录: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 安装所有内置技能。
     * 
     * @param skillsDir 技能目录路径
     * @return 安装结果
     */
    private InstallResult installAllBuiltinSkills(String skillsDir) {
        Path skillsPath = Paths.get(skillsDir);
        int installed = 0;
        int skipped = 0;
        
        for (String skillName : BUILTIN_SKILLS) {
            Path targetPath = skillsPath.resolve(skillName);
            
            if (Files.exists(targetPath)) {
                System.out.println(INDENT + SKIP_MARK + " " + skillName + " (已存在，跳过)");
                skipped++;
            } else if (installSingleBuiltinSkill(skillName, targetPath)) {
                System.out.println(INDENT + CHECK_MARK + " " + skillName + " 已安装");
                installed++;
            }
        }
        
        return new InstallResult(installed, skipped);
    }
    
    /**
     * 安装单个内置技能。
     * 
     * @param skillName 技能名称
     * @param targetPath 目标路径
     * @return 安装成功返回 true，失败返回 false
     */
    private boolean installSingleBuiltinSkill(String skillName, Path targetPath) {
        try {
            Files.createDirectories(targetPath);
            String skillContent = createBuiltinSkillContent(skillName);
            Files.writeString(targetPath.resolve(SKILL_FILE), skillContent);
            return true;
        } catch (IOException e) {
            System.out.println(INDENT + CROSS_MARK + " " + skillName + " 安装失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 打印安装摘要。
     * 
     * @param result 安装结果
     */
    private void printInstallSummary(InstallResult result) {
        System.out.println();
        System.out.println("安装完成！");
        System.out.println(INDENT + "已安装: " + result.installed + " 个技能");
        if (result.skipped > 0) {
            System.out.println(INDENT + "已跳过: " + result.skipped + " 个技能（已存在）");
        }
    }
    
    /**
     * 安装结果记录。
     * 
     * @param installed 已安装数量
     * @param skipped 已跳过数量
     */
    private record InstallResult(int installed, int skipped) {}
    
    /**
     * 创建内置技能的 SKILL.md 内容。
     * 
     * 为每个内置技能生成基础的内容模板。
     * 实际使用时，用户可以根据需要修改这些内容。
     * 
     * @param skillName 技能名称
     * @return SKILL.md 文件内容
     */
    private String createBuiltinSkillContent(String skillName) {
        String description = getSkillDescription(skillName);
        
        StringBuilder sb = new StringBuilder();
        sb.append(YAML_SEPARATOR);
        sb.append("name: ").append(skillName).append(NEWLINE);
        sb.append("description: \"").append(description).append("\"").append(NEWLINE);
        sb.append(YAML_SEPARATOR).append(NEWLINE);
        sb.append(HEADING_PREFIX).append(skillName).append(" Skill").append(NEWLINE).append(NEWLINE);
        sb.append(description).append(".").append(NEWLINE).append(NEWLINE);
        sb.append("## Usage").append(NEWLINE).append(NEWLINE);
        sb.append("This skill provides specialized capabilities for ").append(skillName).append(" related tasks.").append(NEWLINE);
        return sb.toString();
    }
    
    /**
     * 获取技能的描述文本。
     * 
     * @param skillName 技能名称
     * @return 技能描述
     */
    private String getSkillDescription(String skillName) {
        return switch (skillName) {
            case "weather" -> "Query weather information for any location";
            case "github" -> "Interact with GitHub repositories and issues";
            case "summarize" -> "Summarize long texts and documents";
            case "tmux" -> "Manage tmux sessions and windows";
            case "skill-creator" -> "Help create new skills for tinyclaw";
            default -> "A skill for " + skillName;
        };
    }
    
    /**
     * 从 GitHub 安装技能。
     * 
     * 使用 SkillsInstaller 从 GitHub 仓库克隆技能到工作空间。
     * 支持多种仓库格式：
     * - owner/repo
     * - owner/repo/skill-name
     * - 完整的 GitHub URL
     * 
     * @param skillsDir 技能目录路径
     * @param repo GitHub 仓库说明符
     * @return 退出码，0 表示成功，1 表示失败
     */
    private int installSkill(String skillsDir, String repo) {
        System.out.println("正在从 " + repo + " 安装技能...");
        
        try {
            String workspace = Paths.get(skillsDir).getParent().toString();
            SkillsInstaller installer = new SkillsInstaller(workspace);
            String result = installer.install(repo);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.out.println(INSTALL_ERROR_PREFIX + e.getMessage());
            return 1;
        }
    }
    
    /**
     * 移除已安装的技能。
     * 
     * @param skillsDir 技能目录路径
     * @param skillName 技能名称
     * @return 退出码，0 表示成功，1 表示失败
     */
    private int removeSkill(String skillsDir, String skillName) {
        Path skillPath = Paths.get(skillsDir, skillName);
        
        if (!Files.exists(skillPath)) {
            System.out.println(CROSS_MARK + " 未找到技能 '" + skillName + "'");
            return 1;
        }
        
        try {
            deleteDirectory(skillPath.toFile());
            System.out.println(CHECK_MARK + " 技能 '" + skillName + "' 已成功移除！");
            return 0;
        } catch (Exception e) {
            System.out.println(REMOVE_ERROR_PREFIX + e.getMessage());
            return 1;
        }
    }
    
    /**
     * 显示技能详情。
     * 
     * @param skillsDir 技能目录路径
     * @param skillName 技能名称
     * @return 退出码，0 表示成功，1 表示失败
     */
    private int showSkill(String skillsDir, String skillName) {
        Path skillPath = Paths.get(skillsDir, skillName, SKILL_FILE);
        
        if (!Files.exists(skillPath)) {
            System.out.println(CROSS_MARK + " 未找到技能 '" + skillName + "'");
            return 1;
        }
        
        try {
            String content = Files.readString(skillPath);
            printSkillDetails(skillName, content);
            return 0;
        } catch (Exception e) {
            System.out.println(READ_ERROR_PREFIX + e.getMessage());
            return 1;
        }
    }
    
    /**
     * 打印技能详情。
     * 
     * @param skillName 技能名称
     * @param content 技能内容
     */
    private void printSkillDetails(String skillName, String content) {
        System.out.println();
        System.out.println(BOX + " 技能: " + skillName);
        System.out.println("----------------------");
        System.out.println(content);
    }
    
    /**
     * 递归删除目录。
     * 
     * @param dir 要删除的目录
     */
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