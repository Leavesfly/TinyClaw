package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 初始化命令，初始化 TinyClaw 配置和工作空间。
 * 
 * 核心功能：
 * - 创建默认配置文件（~/.tinyclaw/config.json）
 * - 创建工作空间目录结构（workspace、memory、skills、sessions、cron）
 * - 生成模板文件（AGENTS.md、SOUL.md、USER.md、IDENTITY.md、PROFILE.md、MEMORY.md、HEARTBEAT.md）
 * - 提供下一步操作指引
 * 
 * 工作空间结构：
 * - workspace/：主工作目录
 *   - memory/：长期记忆和每日笔记
 *     - MEMORY.md：长期记忆文件
 *     - HEARTBEAT.md：心跳上下文文件
 *   - skills/：技能目录
 *   - sessions/：会话历史
 *   - cron/：定时任务配置
 *   - AGENTS.md：Agent 行为指令
 *   - SOUL.md：Agent 个性定义
 *   - USER.md：用户信息模板
 *   - IDENTITY.md：Agent 身份信息
 *   - PROFILE.md：配置信息
 * 
 * 使用场景：
 * - 首次安装 TinyClaw
 * - 重置配置和工作空间
 * - 创建新的工作环境
 */
public class OnboardCommand extends CliCommand {
    
    private static final String CONFIRM_YES = "y";                    // 确认覆盖的输入
    private static final String ABORT_MESSAGE = "已中止。";            // 中止消息
    private static final String READY_MESSAGE = " tinyclaw 已就绪！"; // 就绪消息
    
    private static final String DIR_MEMORY = "memory";      // 记忆目录
    private static final String DIR_SKILLS = "skills";      // 技能目录
    private static final String DIR_SESSIONS = "sessions";  // 会话目录
    private static final String DIR_CRON = "cron";          // 定时任务目录
    
    private static final String FILE_AGENTS = "AGENTS.md";     // Agent 指令文件
    private static final String FILE_SOUL = "SOUL.md";         // Agent 灵魂文件
    private static final String FILE_USER = "USER.md";         // 用户信息文件
    private static final String FILE_IDENTITY = "IDENTITY.md"; // 身份信息文件
    private static final String FILE_PROFILE = "PROFILE.md";   // 配置文件
    private static final String FILE_MEMORY = "MEMORY.md";     // 记忆文件
    private static final String FILE_HEARTBEAT = "HEARTBEAT.md"; // 心跳上下文文件
    
    @Override
    public String name() {
        return "onboard";
    }
    
    @Override
    public String description() {
        return "初始化 tinyclaw 配置和工作空间";
    }
    
    @Override
    public int execute(String[] args) throws Exception {
        String configPath = getConfigPath();
        
        // 检查配置是否存在并确认覆盖
        if (!confirmOverwriteIfExists(configPath)) {
            return 0;
        }
        
        // 创建并保存默认配置
        Config config = createAndSaveConfig(configPath);
        
        // 创建工作空间目录结构
        createWorkspaceDirectories(config.getWorkspacePath());
        
        // 创建工作空间模板文件
        createWorkspaceTemplates(config.getWorkspacePath());
        
        // 打印完成信息和下一步指引
        printCompletionMessage(configPath);
        
        return 0;
    }
    
    /**
     * 确认覆盖已存在的配置。
     * 
     * @param configPath 配置文件路径
     * @return 如果可以继续返回 true，否则返回 false
     */
    private boolean confirmOverwriteIfExists(String configPath) {
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            return true;
        }
        
        System.out.println("配置已存在于 " + configPath);
        System.out.print("覆盖？");
        
        Scanner scanner = new Scanner(System.in);
        String response = scanner.nextLine().trim().toLowerCase();
        
        if (!CONFIRM_YES.equals(response)) {
            System.out.println(ABORT_MESSAGE);
            return false;
        }
        
        return true;
    }
    
    /**
     * 创建并保存默认配置。
     * 
     * @param configPath 配置文件路径
     * @return 配置对象
     */
    private Config createAndSaveConfig(String configPath) throws IOException {
        Config config = Config.defaultConfig();
        
        // 确保父目录存在
        File configFile = new File(configPath);
        configFile.getParentFile().mkdirs();
        
        // 保存配置
        ConfigLoader.save(configPath, config);
        
        return config;
    }
    
    /**
     * 创建工作空间目录结构。
     * 
     * @param workspace 工作空间路径
     */
    private void createWorkspaceDirectories(String workspace) {
        createDirectory(workspace);
        createDirectory(workspace + "/" + DIR_MEMORY);
        createDirectory(workspace + "/" + DIR_SKILLS);
        createDirectory(workspace + "/" + DIR_SESSIONS);
        createDirectory(workspace + "/" + DIR_CRON);
    }
    
    /**
     * 打印完成信息和下一步指引。
     * 
     * @param configPath 配置文件路径
     */
    private void printCompletionMessage(String configPath) {
        System.out.println(LOGO + READY_MESSAGE);
        System.out.println();
        System.out.println("下一步：");
        System.out.println("  1. 将你的 API 密钥添加到 " + configPath);
        System.out.println("     在此获取：https://openrouter.ai/keys");
        System.out.println("  2. 聊天：java -jar tinyclaw.jar agent -m \"Hello!\"");
    }
    
    /**
     * 创建目录（如果不存在）。
     * 
     * @param path 目录路径
     */
    private void createDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    /**
     * 创建工作空间模板文件。
     * 
     * @param workspace 工作空间路径
     */
    private void createWorkspaceTemplates(String workspace) {
        Map<String, String> templates = buildTemplateMap();
        
        // 创建模板文件
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            createTemplateFile(workspace, entry.getKey(), entry.getValue());
        }
        
        // 创建记忆文件
        createMemoryFile(workspace);
        
        // 创建心跳上下文文件
        createHeartbeatFile(workspace);
    }
    
    /**
     * 构建模板映射。
     * 
     * @return 文件名到内容的映射
     */
    private Map<String, String> buildTemplateMap() {
        Map<String, String> templates = new HashMap<>();
        
        templates.put(FILE_AGENTS, buildAgentsTemplate());
        templates.put(FILE_SOUL, buildSoulTemplate());
        templates.put(FILE_USER, buildUserTemplate());
        templates.put(FILE_IDENTITY, buildIdentityTemplate());
        templates.put(FILE_PROFILE, buildProfileTemplate());
        
        return templates;
    }
    
    /**
     * 构建 AGENTS.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildAgentsTemplate() {
        return "# Agent 指令\n\n" +
                "你是一个有用的 AI 助手。要简洁、准确和友好。\n\n" +
                "## 指导原则\n\n" +
                "- 在采取行动之前始终解释你在做什么\n" +
                "- 当请求不明确时要求澄清\n" +
                "- 使用工具来帮助完成任务\n" +
                "- 在你的记忆文件中记住重要信息\n" +
                "- 要积极主动和乐于助人\n" +
                "- 从用户反馈中学习\n";
    }
    
    /**
     * 构建 SOUL.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildSoulTemplate() {
        return "# 灵魂\n\n" +
                "我是 tinyclaw，一个由 AI 驱动的轻量级 AI 助手。\n\n" +
                "## 个性\n\n" +
                "- 乐于助人和友好\n" +
                "- 简洁扼要\n" +
                "- 好奇且渴望学习\n" +
                "- 诚实和透明\n\n" +
                "## 价值观\n\n" +
                "- 准确性优于速度\n" +
                "- 用户隐私和安全\n" +
                "- 行动透明\n" +
                "- 持续改进\n";
    }
    
    /**
     * 构建 USER.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildUserTemplate() {
        return "# 用户\n\n" +
                "此处填写用户信息。\n\n" +
                "## 偏好\n\n" +
                "- 沟通风格：（随意/正式）\n" +
                "- 时区：（你的时区）\n" +
                "- 语言：（你的首选语言）\n\n" +
                "## 个人信息\n\n" +
                "- 姓名：（可选）\n" +
                "- 位置：（可选）\n" +
                "- 职业：（可选）\n\n" +
                "## 学习目标\n\n" +
                "- 用户希望从 AI 学到什么\n" +
                "- 首选的交互风格\n" +
                "- 兴趣领域\n";
    }
    
    /**
     * 构建 IDENTITY.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildIdentityTemplate() {
        return "# 身份\n\n" +
                "## 名称\n" +
                "TinyClaw 🦞\n\n" +
                "## 描述\n" +
                "用 Java 编写的超轻量级个人 AI 助手。\n\n" +
                "## 版本\n" +
                "0.1.0\n\n" +
                "## 目的\n" +
                "- 以最少的资源使用提供智能 AI 辅助\n" +
                "- 支持多个 LLM 提供商（OpenAI、Anthropic、智谱等）\n" +
                "- 通过技能系统实现简单定制\n\n" +
                "## 能力\n\n" +
                "- 网络搜索和内容获取\n" +
                "- 文件系统操作（读取、写入、编辑）\n" +
                "- Shell 命令执行\n" +
                "- 多通道消息传递（Telegram、Discord、WhatsApp）\n" +
                "- 基于技能的可扩展性\n" +
                "- 内存和上下文管理\n";
    }
    
    /**
     * 构建 PROFILE.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildProfileTemplate() {
        return "# 配置文件\n\n" +
                "此文件包含 Agent 的运行配置和状态信息。\n\n" +
                "## 系统信息\n\n" +
                "- 启动时间：（首次启动时记录）\n" +
                "- 工作空间：~/.tinyclaw/workspace/\n" +
                "- 配置文件：~/.tinyclaw/config.json\n\n" +
                "## 运行统计\n\n" +
                "- 总会话数：0\n" +
                "- 总任务数：0\n" +
                "- 最后活跃时间：（自动更新）\n\n" +
                "## 状态\n\n" +
                "- 健康状态：正常\n" +
                "- 活跃通道：（记录已连接的通道）\n" +
                "- 已加载技能：（记录已加载的技能列表）\n";
    }
    
    /**
     * 创建模板文件。
     * 
     * @param workspace 工作空间路径
     * @param filename 文件名
     * @param content 文件内容
     */
    private void createTemplateFile(String workspace, String filename, String content) {
        Path filePath = Paths.get(workspace, filename);
        
        if (Files.exists(filePath)) {
            return;
        }
        
        try {
            Files.writeString(filePath, content);
            System.out.println("  已创建 " + filename);
        } catch (IOException e) {
            System.err.println("  创建文件失败 " + filename + ": " + e.getMessage());
        }
    }
    
    /**
     * 创建记忆文件。
     * 
     * @param workspace 工作空间路径
     */
    private void createMemoryFile(String workspace) {
        Path memoryFile = Paths.get(workspace, DIR_MEMORY, FILE_MEMORY);
        
        if (Files.exists(memoryFile)) {
            return;
        }
        
        String memoryContent = buildMemoryTemplate();
        
        try {
            Files.writeString(memoryFile, memoryContent);
            System.out.println("  已创建 " + DIR_MEMORY + "/" + FILE_MEMORY);
        } catch (IOException e) {
            System.err.println("  创建内存文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建 MEMORY.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildMemoryTemplate() {
        return "# 长期记忆\n\n" +
                "此文件存储应该在各会话之间持久化的重要信息。\n\n" +
                "## 用户信息\n\n" +
                "（关于用户的重要事实）\n\n" +
                "## 偏好\n\n" +
                "（随时间学习到的用户偏好）\n\n" +
                "## 重要笔记\n\n" +
                "（需要记住的事情）\n";
    }
    
    /**
     * 构建 HEARTBEAT.md 模板内容。
     * 
     * @return 模板内容
     */
    private String buildHeartbeatTemplate() {
        return "# 心跳检查\n\n" +
                "此文件定义心跳服务的检查内容。保持简洁以降低 token 消耗。\n\n" +
                "## 日常检查\n\n" +
                "- 确保今日日志 memory/YYYY-MM-DD.md 存在\n" +
                "- 检查定时任务执行状态\n\n" +
                "## 主动行为\n\n" +
                "- 如果发现系统异常，主动报告\n" +
                "- 如果有未完成的重要任务，继续推进\n\n" +
                "## 注意事项\n\n" +
                "- 心跳内容会在每个心跳周期被读取\n" +
                "- 保持内容简洁，避免过多 token 消耗\n" +
                "- 定期任务应使用 cron 而非心跳\n";
    }
    
    /**
     * 创建心跳上下文文件。
     * 
     * @param workspace 工作空间路径
     */
    private void createHeartbeatFile(String workspace) {
        Path heartbeatFile = Paths.get(workspace, DIR_MEMORY, FILE_HEARTBEAT);
        
        if (Files.exists(heartbeatFile)) {
            return;
        }
        
        String heartbeatContent = buildHeartbeatTemplate();
        
        try {
            Files.writeString(heartbeatFile, heartbeatContent);
            System.out.println("  已创建 " + DIR_MEMORY + "/" + FILE_HEARTBEAT);
        } catch (IOException e) {
            System.err.println("  创建心跳文件失败: " + e.getMessage());
        }
    }
    
    @Override
    public void printHelp() {
        System.out.println(LOGO + " tinyclaw onboard - 初始化配置");
        System.out.println();
        System.out.println("Usage: tinyclaw onboard");
        System.out.println();
        System.out.println("此命令将：");
        System.out.println("  - 在 ~/.tinyclaw/config.json 创建默认配置");
        System.out.println("  - 在 ~/.tinyclaw/workspace 创建工作空间目录");
        System.out.println("  - 创建模板文件（AGENTS.md, SOUL.md, USER.md, IDENTITY.md, PROFILE.md 等）");
        System.out.println("  - 在 memory/ 目录创建 MEMORY.md 和 HEARTBEAT.md");
    }
}