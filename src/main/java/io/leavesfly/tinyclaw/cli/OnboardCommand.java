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
 * 初始化命令 - 初始化 TinyClaw 配置
 */
public class OnboardCommand extends CliCommand {
    
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
        
        File configFile = new File(configPath);
        if (configFile.exists()) {
            System.out.println("配置已存在于 " + configPath);
            System.out.print("覆盖？");
            Scanner scanner = new Scanner(System.in);
            String response = scanner.nextLine().trim().toLowerCase();
            if (!response.equals("y")) {
                System.out.println("已中止。");
                return 0;
            }
        }
        
        // 创建默认配置
        Config config = Config.defaultConfig();
        
        // 确保父目录存在
        configFile.getParentFile().mkdirs();
        
        // 保存配置
        ConfigLoader.save(configPath, config);
        
        // 创建工作空间目录结构
        String workspace = config.getWorkspacePath();
        createDirectory(workspace);
        createDirectory(workspace + "/memory");
        createDirectory(workspace + "/skills");
        createDirectory(workspace + "/sessions");
        createDirectory(workspace + "/cron");
        
        // 创建工作空间模板
        createWorkspaceTemplates(workspace);
        
        System.out.println(LOGO + " tinyclaw 已就绪！");
        System.out.println();
        System.out.println("下一步：");
        System.out.println("  1. 将你的 API 密钥添加到 " + configPath);
        System.out.println("     在此获取：https://openrouter.ai/keys");
        System.out.println("  2. 聊天：java -jar tinyclaw.jar agent -m \"Hello!\"");
        
        return 0;
    }
    
    private void createDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    private void createWorkspaceTemplates(String workspace) {
        Map<String, String> templates = new HashMap<>();
        
        templates.put("AGENTS.md", "# Agent 指令\n\n" +
                "你是一个有用的 AI 助手。要简洁、准确和友好。\n\n" +
                "## 指导原则\n\n" +
                "- 在采取行动之前始终解释你在做什么\n" +
                "- 当请求不明确时要求澄清\n" +
                "- 使用工具来帮助完成任务\n" +
                "- 在你的记忆文件中记住重要信息\n" +
                "- 要积极主动和乐于助人\n" +
                "- 从用户反馈中学习\n");
        
        templates.put("SOUL.md", "# 灵魂\n\n" +
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
                "- 持续改进\n");
        
        templates.put("USER.md", "# 用户\n\n" +
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
                "- 兴趣领域\n");
        
        templates.put("IDENTITY.md", "# 身份\n\n" +
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
                "- 内存和上下文管理\n");
        
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            String filename = entry.getKey();
            String content = entry.getValue();
            Path filePath = Paths.get(workspace, filename);
            
            if (!Files.exists(filePath)) {
                try {
                    Files.writeString(filePath, content);
                    System.out.println("  已创建 " + filename);
                } catch (IOException e) {
                    System.err.println("  创建文件失败 " + filename + ": " + e.getMessage());
                }
            }
        }
        
        // 创建内存文件
        Path memoryFile = Paths.get(workspace, "memory", "MEMORY.md");
        if (!Files.exists(memoryFile)) {
            String memoryContent = "# 长期记忆\n\n" +
                    "此文件存储应该在各会话之间持久化的重要信息。\n\n" +
                    "## 用户信息\n\n" +
                    "（关于用户的重要事实）\n\n" +
                    "## 偏好\n\n" +
                    "（随时间学习到的用户偏好）\n\n" +
                    "## 重要笔记\n\n" +
                    "（需要记住的事情）\n";
            try {
                Files.writeString(memoryFile, memoryContent);
                System.out.println("  已创建 memory/MEMORY.md");
            } catch (IOException e) {
                System.err.println("  创建内存文件失败: " + e.getMessage());
            }
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
        System.out.println("  - 创建模板文件（AGENTS.md, SOUL.md, USER.md 等）");
    }
}