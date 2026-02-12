package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文构建器 - 用于构建 Agent 运行所需的完整上下文
 * 
 * 这是 Agent 系统的核心组件之一，负责组装发送给 LLM 的系统提示词和消息上下文。
 * 
 * 核心职责：
 * - 构建系统提示词：包含身份信息、工具说明、技能摘要、记忆上下文
 * - 加载引导文件：从工作空间加载 AGENTS.md、SOUL.md 等自定义配置
 * - 集成技能系统：将已安装技能的摘要添加到系统提示词中
 * - 管理记忆上下文：加载和整合长期记忆内容
 * 
 * 上下文层次结构：
 * 1. 身份信息：Agent 名称、当前时间、运行环境、工作空间路径
 * 2. 引导文件：用户自定义的行为指导和身份定义
 * 3. 工具说明：已注册工具的功能描述和使用方法
 * 4. 技能摘要：已安装技能的简要说明和位置信息
 * 5. 记忆上下文：长期记忆和近期对话摘要
 * 
 * 设计原则：
 * - 渐进式披露：提供摘要而非完整内容，减少 token 消耗
 * - 模块化组装：各部分独立构建，便于扩展和维护
 * - 优先级覆盖：workspace > global > builtin 的技能加载顺序
 */
public class ContextBuilder {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("context");
    
    private final String workspace;
    private ToolRegistry tools;
    private final MemoryStore memory;
    private final SkillsLoader skillsLoader;
    
    /**
     * 创建上下文构建器
     * 
     * 初始化时会自动创建 MemoryStore 和 SkillsLoader 实例。
     * SkillsLoader 会尝试从多个位置加载技能：
     * - workspace/skills：项目级技能（最高优先级）
     * - 全局技能目录
     * - 内置技能目录
     * 
     * @param workspace 工作空间路径
     */
    public ContextBuilder(String workspace) {
        this.workspace = workspace;
        this.memory = new MemoryStore(workspace);
        // 初始化技能加载器，使用默认路径
        // 实际使用时可传入全局和内置技能目录路径
        this.skillsLoader = new SkillsLoader(workspace, null, null);
    }
    
    /**
     * 创建带完整配置的上下文构建器
     * 
     * 允许指定全局和内置技能目录，用于高级配置场景。
     * 
     * @param workspace 工作空间路径
     * @param globalSkills 全局技能目录路径
     * @param builtinSkills 内置技能目录路径
     */
    public ContextBuilder(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        this.memory = new MemoryStore(workspace);
        this.skillsLoader = new SkillsLoader(workspace, globalSkills, builtinSkills);
    }
    
    /**
     * 设置工具注册表用于动态工具摘要生成
     */
    public void setTools(ToolRegistry tools) {
        this.tools = tools;
    }
    
    /**
     * 构建系统提示词
     * 
     * 这是上下文构建的核心方法，按照特定顺序组装各个部分：
     * 1. 身份信息：Agent 的基本身份和当前环境信息
     * 2. 引导文件：用户自定义的行为配置
     * 3. 工具部分：可用工具的简要说明
     * 4. 技能摘要：已安装技能的概述
     * 5. 记忆上下文：长期记忆和重要信息
     * 
     * 各部分之间使用 "---" 分隔，便于 LLM 理解结构。
     * 
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();
        
        // 核心身份部分
        parts.add(getIdentity());
        
        // 引导文件
        String bootstrapContent = loadBootstrapFiles();
        if (StringUtils.isNotBlank(bootstrapContent)) {
            parts.add(bootstrapContent);
        }
        
        // 工具部分
        String toolsSection = buildToolsSection();
        if (StringUtils.isNotBlank(toolsSection)) {
            parts.add(toolsSection);
        }
        
        // 技能摘要部分
        String skillsSection = buildSkillsSection();
        if (StringUtils.isNotBlank(skillsSection)) {
            parts.add(skillsSection);
        }
        
        // 内存上下文
        String memoryContext = memory.getMemoryContext();
        if (StringUtils.isNotBlank(memoryContext)) {
            parts.add("# Memory\n\n" + memoryContext);
        }
        
        return String.join("\n\n---\n\n", parts);
    }
    
    /**
     * 构建技能摘要部分
     * 
     * 生成已安装技能的简要说明，采用渐进式披露策略：
     * - 只显示技能名称、描述和位置
     * - 完整内容需要使用 read_file 工具读取
     * 
     * 这样可以减少系统提示词的长度，同时让 LLM 知道有哪些技能可用。
     * 
     * @return 技能摘要字符串，如果没有技能则返回空字符串
     */
    private String buildSkillsSection() {
        String skillsSummary = skillsLoader.buildSkillsSummary();
        if (StringUtils.isBlank(skillsSummary)) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");
        sb.append("The following skills extend your capabilities. ");
        sb.append("To use a skill, read its SKILL.md file using the read_file tool.\n\n");
        sb.append(skillsSummary);
        
        return sb.toString();
    }
    
    /**
     * 获取 Agent 身份和基本信息
     */
    private String getIdentity() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));
        String workspacePath = Paths.get(workspace).toAbsolutePath().toString();
        String runtime = System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", Java " + System.getProperty("java.version");
        
        StringBuilder sb = new StringBuilder();
        sb.append("# tinyclaw 🦞\n\n");
        sb.append("你是 tinyclaw，一个有用的 AI 助手。\n\n");
        sb.append("## 当前时间\n");
        sb.append(now).append("\n\n");
        sb.append("## 运行环境\n");
        sb.append(runtime).append("\n\n");
        sb.append("## 工作空间\n");
        sb.append("你的工作空间位于: ").append(workspacePath).append("\n");
        sb.append("- 内存: ").append(workspacePath).append("/memory/MEMORY.md\n");
        sb.append("- 每日笔记: ").append(workspacePath).append("/memory/YYYYMM/YYYYMMDD.md\n");
        sb.append("- 技能: ").append(workspacePath).append("/skills/{skill-name}/SKILL.md\n\n");
        sb.append("## 重要规则\n\n");
        sb.append("1. **始终使用工具** - 当你需要执行操作（安排提醒、发送消息、执行命令等）时，你必须调用适当的工具。不要只是说你会做或假装做。\n\n");
        sb.append("2. **乐于助人和准确** - 使用工具时，简要说明你在做什么。\n\n");
        sb.append("3. **记忆** - 记住某些内容时，写入 ").append(workspacePath).append("/memory/MEMORY.md\n");
        
        return sb.toString();
    }
    
    /**
     * 构建系统提示词的工具部分
     */
    private String buildToolsSection() {
        if (tools == null) {
            return "";
        }
        
        List<String> summaries = tools.getSummaries();
        if (summaries.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        sb.append("**重要**: 你必须使用工具来执行操作。不要假装执行命令或安排任务。\n\n");
        sb.append("你可以访问以下工具:\n\n");
        for (String s : summaries) {
            sb.append(s).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 从工作空间加载引导文件
     */
    private String loadBootstrapFiles() {
        String[] bootstrapFiles = {"AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md"};
        
        StringBuilder result = new StringBuilder();
        for (String filename : bootstrapFiles) {
            String filePath = Paths.get(workspace, filename).toString();
            try {
                if (Files.exists(Paths.get(filePath))) {
                    String content = Files.readString(Paths.get(filePath));
                    result.append("## ").append(filename).append("\n\n");
                    result.append(content).append("\n\n");
                }
            } catch (IOException e) {
                // 忽略读取个别文件时的错误
            }
        }
        
        return result.toString();
    }
    
    /**
     * 为 LLM 构建消息
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage, 
                                        String channel, String chatId) {
        List<Message> messages = new ArrayList<>();
        
        // 构建系统提示词
        String systemPrompt = buildSystemPrompt();
        
        // 如果提供了当前会话信息则添加
        if (StringUtils.isNotBlank(channel) && StringUtils.isNotBlank(chatId)) {
            systemPrompt += "\n\n## 当前会话\n通道: " + channel + "\n聊天 ID: " + chatId;
        }
        
        logger.debug("System prompt built", Map.of(
                "total_chars", systemPrompt.length(),
                "total_lines", systemPrompt.split("\n").length
        ));
        
        // 如果有摘要则添加
        if (StringUtils.isNotBlank(summary)) {
            systemPrompt += "\n\n## 之前对话的摘要\n\n" + summary;
        }
        
        // 添加系统消息
        messages.add(Message.system(systemPrompt));
        
        // 添加历史记录
        if (history != null) {
            messages.addAll(history);
        }
        
        // 添加当前用户消息
        messages.add(Message.user(currentMessage));
        
        return messages;
    }
    
    /**
     * 获取已加载技能的信息
     * 
     * 返回当前已安装技能的统计信息，包括：
     * - total: 技能总数
     * - available: 可用技能数（与 total 相同）
     * - names: 所有技能名称列表
     * 
     * 这些信息用于状态报告和监控目的。
     * 
     * @return 包含技能信息的映射
     */
    public Map<String, Object> getSkillsInfo() {
        List<SkillInfo> allSkills = skillsLoader.listSkills();
        List<String> skillNames = new ArrayList<>();
        for (SkillInfo s : allSkills) {
            skillNames.add(s.getName());
        }
        
        Map<String, Object> info = new HashMap<>();
        info.put("total", allSkills.size());
        info.put("available", allSkills.size());
        info.put("names", skillNames);
        return info;
    }
}