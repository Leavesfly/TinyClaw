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
 * 上下文构建器，用于构建 Agent 运行所需的完整上下文。
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
    
    private static final String SECTION_SEPARATOR = "\n\n---\n\n";  // 部分分隔符
    private static final String[] BOOTSTRAP_FILES = {               // 引导文件列表
        "AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md"
    };
    
    private final String workspace;          // 工作空间路径
    private ToolRegistry tools;              // 工具注册表
    private final MemoryStore memory;        // 记忆存储
    private final SkillsLoader skillsLoader; // 技能加载器
    
    /**
     * 创建上下文构建器。
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
     * 创建带完整配置的上下文构建器。
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
     * 设置工具注册表用于动态工具摘要生成。
     * 
     * @param tools 工具注册表实例
     */
    public void setTools(ToolRegistry tools) {
        this.tools = tools;
    }
    
    /**
     * 构建系统提示词。
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
        
        // 1. 核心身份部分
        parts.add(getIdentity());
        
        // 2. 引导文件
        addSectionIfNotBlank(parts, loadBootstrapFiles());
        
        // 3. 工具部分
        addSectionIfNotBlank(parts, buildToolsSection());
        
        // 4. 技能摘要部分
        addSectionIfNotBlank(parts, buildSkillsSection());
        
        // 5. 记忆上下文
        String memoryContext = memory.getMemoryContext();
        if (StringUtils.isNotBlank(memoryContext)) {
            parts.add("# Memory\n\n" + memoryContext);
        }
        
        return String.join(SECTION_SEPARATOR, parts);
    }
    
    /**
     * 添加非空部分到列表。
     * 
     * @param parts 部分列表
     * @param section 要添加的部分内容
     */
    private void addSectionIfNotBlank(List<String> parts, String section) {
        if (StringUtils.isNotBlank(section)) {
            parts.add(section);
        }
    }
    
    /**
     * 构建技能摘要部分。
     * 
     * 生成已安装技能的简要说明，采用渐进式披露策略：
     * - 只显示技能名称、描述和位置
     * - 完整内容需要使用 read_file 工具读取
     * - 引导 AI 自主学习：安装社区技能、创建新技能、迭代优化已有技能
     * 
     * @return 技能摘要字符串（即使没有技能也返回自主学习引导）
     */
    private String buildSkillsSection() {
        String skillsSummary = skillsLoader.buildSkillsSummary();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");
        
        // 已安装技能摘要
        if (StringUtils.isNotBlank(skillsSummary)) {
            appendInstalledSkillsSummary(sb, skillsSummary);
        }
        
        // AI 自主学习技能的引导
        appendSkillSelfLearningGuide(sb);
        
        return sb.toString();
    }
    
    /**
     * 追加已安装技能摘要。
     * 
     * @param sb 字符串构建器
     * @param skillsSummary 技能摘要内容
     */
    private void appendInstalledSkillsSummary(StringBuilder sb, String skillsSummary) {
        sb.append("## 已安装技能\n\n");
        sb.append("以下技能扩展了你的能力。");
        sb.append("要使用某个技能，请使用 read_file 工具读取其 SKILL.md 文件。\n\n");
        sb.append(skillsSummary);
        sb.append("\n\n");
    }
    
    /**
     * 追加技能自主学习引导。
     * 
     * @param sb 字符串构建器
     */
    private void appendSkillSelfLearningGuide(StringBuilder sb) {
        sb.append("## 技能自主学习\n\n");
        sb.append("你有能力使用 `skills` 工具**自主学习和管理技能**。");
        sb.append("这意味着你不局限于预安装的技能——你可以随着时间增长你的能力。\n\n");
        
        appendWhenToLearnSkills(sb);
        appendHowToManageSkills(sb);
        appendInvokingSkillsWithScripts(sb);
        appendCreatingLearnableSkills(sb);
    }
    
    /**
     * 追加何时学习新技能的说明。
     * 
     * @param sb 字符串构建器
     */
    private void appendWhenToLearnSkills(StringBuilder sb) {
        sb.append("### 何时学习新技能\n\n");
        sb.append("- 当你遇到现有技能无法覆盖的任务时，考虑**创建新技能**来处理它。\n");
        sb.append("- 当用户提到社区技能或包含有用技能的 GitHub 仓库时，直接**安装它**。\n");
        sb.append("- 当你发现自己重复执行类似的多步操作时，**将模式提取为可复用的技能**。\n");
        sb.append("- 当现有技能可以根据新经验改进时，**编辑它**使其更好。\n\n");
    }
    
    /**
     * 追加如何管理技能的说明。
     * 
     * @param sb 字符串构建器
     */
    private void appendHowToManageSkills(StringBuilder sb) {
        sb.append("### 如何管理技能\n\n");
        sb.append("使用 `skills` 工具执行以下操作：\n");
        sb.append("- `skills(action='list')` — 查看所有已安装技能\n");
        sb.append("- `skills(action='show', name='...')` — 查看技能的完整内容\n");
        sb.append("- `skills(action='invoke', name='...')` — **调用技能并获取其基础路径**（用于带脚本的技能）\n");
        sb.append("- `skills(action='install', repo='owner/repo')` — 从 GitHub 安装技能\n");
        sb.append("- `skills(action='create', name='...', content='...', skill_description='...')` — 根据经验创建新技能\n");
        sb.append("- `skills(action='edit', name='...', content='...')` — 改进现有技能\n");
        sb.append("- `skills(action='remove', name='...')` — 删除不再需要的技能\n\n");
    }
    
    /**
     * 追加调用带脚本技能的说明。
     * 
     * @param sb 字符串构建器
     */
    private void appendInvokingSkillsWithScripts(StringBuilder sb) {
        sb.append("### 调用带脚本的技能\n\n");
        sb.append("当技能包含可执行脚本（如 Python 文件）时，使用 `invoke` 而非 `show`：\n");
        sb.append("1. 调用 `skills(action='invoke', name='技能名')` 获取技能的基础路径和指令\n");
        sb.append("2. 响应中包含指向技能目录的 `<base-path>`\n");
        sb.append("3. 使用基础路径执行脚本，例如：`exec(command='python3 {base-path}/script.py 参数1')`\n\n");
        sb.append("带脚本技能的示例工作流：\n");
        sb.append("```\n");
        sb.append("1. skills(action='invoke', name='pptx')  → 获取基础路径: /path/to/skills/pptx/\n");
        sb.append("2. exec(command='python3 /path/to/skills/pptx/create_pptx.py output.pptx')\n");
        sb.append("```\n\n");
    }
    
    /**
     * 追加创建可学习技能的说明。
     * 
     * @param sb 字符串构建器
     */
    private void appendCreatingLearnableSkills(StringBuilder sb) {
        sb.append("### 创建可学习技能\n\n");
        sb.append("创建技能时，将其编写为带有 YAML frontmatter 的 **Markdown 指令手册**。好的技能应包含：\n");
        sb.append("1. 清晰描述技能的功能\n");
        sb.append("2. 逐步执行的指令\n");
        sb.append("3. （可选）在哪里找到和安装依赖或相关社区技能\n");
        sb.append("4. 何时以及如何使用该技能的示例\n\n");
        
        sb.append("你创建的技能保存在 `").append(Paths.get(workspace).toAbsolutePath())
                .append("/skills/`，将在未来的对话中自动可用。\n");
    }
    
    /**
     * 获取 Agent 身份和基本信息。
     * 
     * @return 身份信息字符串
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
     * 构建系统提示词的工具部分。
     * 
     * @return 工具部分字符串，无工具时返回空字符串
     */
    private String buildToolsSection() {
        if (tools == null || tools.getSummaries().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        sb.append("**重要**: 你必须使用工具来执行操作。不要假装执行命令或安排任务。\n\n");
        sb.append("你可以访问以下工具:\n\n");
        
        for (String summary : tools.getSummaries()) {
            sb.append(summary).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 从工作空间加载引导文件。
     * 
     * 尝试加载 AGENTS.md、SOUL.md、USER.md、IDENTITY.md 等文件。
     * 
     * @return 引导文件内容，无文件时返回空字符串
     */
    private String loadBootstrapFiles() {
        StringBuilder result = new StringBuilder();
        
        for (String filename : BOOTSTRAP_FILES) {
            String content = loadBootstrapFile(filename);
            if (StringUtils.isNotBlank(content)) {
                result.append("## ").append(filename).append("\n\n");
                result.append(content).append("\n\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * 加载单个引导文件。
     * 
     * @param filename 文件名
     * @return 文件内容，失败时返回空字符串
     */
    private String loadBootstrapFile(String filename) {
        try {
            String filePath = Paths.get(workspace, filename).toString();
            if (Files.exists(Paths.get(filePath))) {
                return Files.readString(Paths.get(filePath));
            }
        } catch (IOException e) {
            // 忽略读取个别文件时的错误
        }
        return "";
    }
    
    /**
     * 为 LLM 构建消息列表。
     * 
     * 组装完整的消息上下文，包括系统提示词、历史消息和当前用户消息。
     * 
     * @param history 历史消息列表
     * @param summary 之前对话的摘要
     * @param currentMessage 当前用户消息
     * @param channel 当前通道名称
     * @param chatId 当前聊天 ID
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage, 
                                        String channel, String chatId) {
        List<Message> messages = new ArrayList<>();
        
        // 构建系统提示词
        String systemPrompt = buildSystemPromptWithSession(channel, chatId, summary);
        
        logger.debug("System prompt built", Map.of(
                "total_chars", systemPrompt.length(),
                "total_lines", systemPrompt.split("\n").length
        ));
        
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
     * 构建包含会话信息的系统提示词。
     * 
     * @param channel 通道名称
     * @param chatId 聊天 ID
     * @param summary 对话摘要
     * @return 完整的系统提示词
     */
    private String buildSystemPromptWithSession(String channel, String chatId, String summary) {
        StringBuilder systemPrompt = new StringBuilder(buildSystemPrompt());
        
        // 添加当前会话信息
        if (StringUtils.isNotBlank(channel) && StringUtils.isNotBlank(chatId)) {
            systemPrompt.append("\n\n## 当前会话\n通道: ").append(channel)
                       .append("\n聊天 ID: ").append(chatId);
        }
        
        // 添加对话摘要
        if (StringUtils.isNotBlank(summary)) {
            systemPrompt.append("\n\n## 之前对话的摘要\n\n").append(summary);
        }
        
        return systemPrompt.toString();
    }
    
    /**
     * 获取已加载技能的信息。
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
        List<String> skillNames = allSkills.stream()
                .map(SkillInfo::getName)
                .toList();
        
        Map<String, Object> info = new HashMap<>();
        info.put("total", allSkills.size());
        info.put("available", allSkills.size());
        info.put("names", skillNames);
        return info;
    }
}