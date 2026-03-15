package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.evolution.MemoryStore;
import io.leavesfly.tinyclaw.agent.evolution.PromptOptimizer;
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
    
    /** 可选的 Prompt 优化器（进化功能启用时设置） */
    private volatile PromptOptimizer promptOptimizer;
    
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
    
    /** 上下文窗口大小，用于计算记忆 token 预算 */
    private int contextWindow = AgentConstants.DEFAULT_MAX_TOKENS;
    
    /**
     * 设置上下文窗口大小，用于动态计算记忆 token 预算。
     *
     * @param contextWindow 上下文窗口 token 数
     */
    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }
    
    /**
     * 设置 Prompt 优化器（可选，用于进化功能）。
     * 
     * 设置后，系统提示词将包含优化后的行为指导。
     * 
     * @param promptOptimizer Prompt 优化器实例
     */
    public void setPromptOptimizer(PromptOptimizer promptOptimizer) {
        this.promptOptimizer = promptOptimizer;
    }
    
    /**
     * 获取 Prompt 优化器。
     * 
     * @return 优化器实例，未设置时返回 null
     */
    public PromptOptimizer getPromptOptimizer() {
        return promptOptimizer;
    }
    
    /**
     * 构建系统提示词（无当前消息上下文版本）。
     * 
     * 使用默认记忆预算，不做相关性过滤。适用于不需要消息感知的场景。
     * 
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt() {
        return buildSystemPrompt(null);
    }
    
    /**
     * 构建系统提示词，支持基于当前消息的记忆相关性检索。
     * 
     * 这是上下文构建的核心方法，按照特定顺序组装各个部分：
     * 1. 身份信息：Agent 的基本身份和当前环境信息
     * 2. 引导文件：用户自定义的行为配置
     * 3. 工具部分：可用工具的简要说明
     * 4. 技能摘要：已安装技能的概述
     * 5. 记忆上下文：根据当前消息和 token 预算智能选取
     * 
     * @param currentMessage 当前用户消息，用于记忆相关性匹配（可为 null）
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt(String currentMessage) {
        List<String> parts = new ArrayList<>();
        
        // 1. 核心身份部分
        parts.add(getIdentity());
        
        // 2. 引导文件
        addSectionIfNotBlank(parts, loadBootstrapFiles());
        
        // 3. 工具部分
        addSectionIfNotBlank(parts, buildToolsSection());
        
        // 4. 技能摘要部分
        addSectionIfNotBlank(parts, buildSkillsSection());
        
        // 5. 记忆上下文（带 token 预算控制和相关性检索）
        int memoryBudget = calculateMemoryTokenBudget();
        String memoryContext = memory.getMemoryContext(currentMessage, memoryBudget);
        if (StringUtils.isNotBlank(memoryContext)) {
            parts.add("# Memory\n\n" + memoryContext);
        }
        
        return String.join(SECTION_SEPARATOR, parts);
    }
    
    /**
     * 根据上下文窗口大小计算记忆 token 预算。
     * 
     * 预算 = 上下文窗口 × MEMORY_TOKEN_BUDGET_PERCENTAGE%，
     * 并限制在 [MEMORY_MIN_TOKEN_BUDGET, MEMORY_MAX_TOKEN_BUDGET] 范围内。
     * 
     * @return 记忆 token 预算
     */
    private int calculateMemoryTokenBudget() {
        int budget = contextWindow * AgentConstants.MEMORY_TOKEN_BUDGET_PERCENTAGE / 100;
        return Math.max(AgentConstants.MEMORY_MIN_TOKEN_BUDGET,
                Math.min(AgentConstants.MEMORY_MAX_TOKEN_BUDGET, budget));
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
        sb.append("- 当你遇到现有技能无法覆盖的任务时，先**搜索 GitHub** 上是否有现成的技能可以安装。\n");
        sb.append("- 当用户提到社区技能或包含有用技能的 GitHub 仓库时，直接**安装它**。\n");
        sb.append("- 如果搜索不到合适的技能，考虑**创建新技能**来处理它。\n");
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
        sb.append("- `skills(action='search', query='...')` — **从可信技能市场搜索可用的技能**（按功能描述搜索）\n");
        sb.append("- `skills(action='search_install', query='...')` — **搜索并自动安装最匹配的技能**（一键搜索+安装）\n");
        sb.append("- `skills(action='install', repo='owner/repo')` — 从 GitHub 安装指定技能\n");
        sb.append("- `skills(action='create', name='...', content='...', skill_description='...')` — 根据经验创建新技能\n");
        sb.append("- `skills(action='edit', name='...', content='...')` — 改进现有技能\n");
        sb.append("- `skills(action='remove', name='...')` — 删除不再需要的技能\n\n");
        sb.append("### 自动搜索安装技能\n\n");
        sb.append("搜索功能默认从**可信技能市场**中搜索，确保安全性。内置市场源包括：\n");
        sb.append("- TinyClaw Official（官方技能集合）\n");
        sb.append("- VoltAgent Skills（500+ 多平台 agent 技能）\n");
        sb.append("- Composio Skills（Composio 社区精选技能）\n");
        sb.append("- Travis Skills（社区精选 Claude Skills 资源）\n");
        sb.append("- Jeffallan Skills（66+ 专业全栈开发技能）\n\n");
        sb.append("当你遇到无法处理的任务时，推荐使用以下流程：\n");
        sb.append("1. 先用 `skills(action='search', query='描述需要的功能')` 从可信市场搜索技能\n");
        sb.append("2. 如果找到合适的，用 `skills(action='search_install', query='...')` 一键安装\n");
        sb.append("3. 安装后用 `skills(action='invoke', name='...')` 调用技能解决问题\n");
        sb.append("4. 如果搜索不到，再考虑自己创建技能\n\n");
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
     * 获取 Agent 身份和基本信息（包含可选的优化内容）。
     * 
     * 如果配置了 PromptOptimizer 且有活跃的优化，
     * 会在基础身份信息后追加优化后的行为指导。
     * 
     * @return 身份信息字符串
     */
    private String getIdentity() {
        String baseIdentity = getBaseIdentity();
        
        // 如果有优化器且有活跃优化，追加优化内容
        if (promptOptimizer != null && promptOptimizer.hasActiveOptimization()) {
            String optimization = promptOptimizer.getActiveOptimization();
            if (StringUtils.isNotBlank(optimization)) {
                return baseIdentity + "\n\n## 优化后的行为指导\n\n" + optimization;
            }
        }
        
        return baseIdentity;
    }
    
    /**
     * 获取基础身份信息（不含优化）。
     * 
     * @return 基础身份信息字符串
     */
    private String getBaseIdentity() {
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
            logger.debug("Failed to load bootstrap file", Map.of(
                    "filename", filename,
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
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
        
        // 构建系统提示词（传入当前消息用于记忆相关性检索）
        String systemPrompt = buildSystemPromptWithSession(currentMessage, channel, chatId, summary);
        
        logger.debug("System prompt built", Map.of(
                "total_chars", systemPrompt.length(),
                "total_lines", systemPrompt.split("\n").length
        ));
        
        // 添加系统消息
        messages.add(Message.system(systemPrompt));
        
        // 添加历史记录（清理可能存在的孤立 tool 消息，防止 LLM API 报错）
        if (history != null && !history.isEmpty()) {
            messages.addAll(sanitizeHistory(new ArrayList<>(history)));
        }
        
        // 添加当前用户消息
        messages.add(Message.user(currentMessage));
        
        return messages;
    }
    
    /**
     * 构建包含会话信息的系统提示词。
     * 
     * @param currentMessage 当前用户消息（用于记忆相关性检索）
     * @param channel 通道名称
     * @param chatId 聊天 ID
     * @param summary 对话摘要
     * @return 完整的系统提示词
     */
    private String buildSystemPromptWithSession(String currentMessage, String channel, String chatId, String summary) {
        StringBuilder systemPrompt = new StringBuilder(buildSystemPrompt(currentMessage));
        
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
     * 清理历史消息，确保 tool 消息前面有对应的 assistant(tool_calls) 消息。
     * 
     * LLM API 要求每条 role="tool" 的消息前面必须紧跟一条包含 tool_calls 的
     * role="assistant" 消息。此方法会跳过历史开头处缺少配对 assistant 消息的
     * 孤立 tool 消息，防止发送给 LLM 时报 400 错误。
     * 
     * @param history 原始历史消息列表
     * @return 清理后的历史消息列表
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history.isEmpty()) {
            return history;
        }
        
        // 找到第一条非孤立 tool 消息的位置
        int startIndex = 0;
        while (startIndex < history.size() && "tool".equals(history.get(startIndex).getRole())) {
            startIndex++;
        }
        
        if (startIndex == 0) {
            return history;
        }
        
        if (startIndex > 0) {
            logger.warn("Skipped orphaned tool messages at history start", Map.of(
                    "skipped_count", startIndex));
        }
        
        return new ArrayList<>(history.subList(startIndex, history.size()));
    }
    
    /**
     * 获取技能加载器实例。
     * 
     * 用于与其他组件（如 SkillsTool）共享同一个 SkillsLoader 实例，
     * 确保技能列表视图的一致性。
     * 
     * @return 技能加载器实例
     */
    public SkillsLoader getSkillsLoader() {
        return skillsLoader;
    }

    /**
     * 获取记忆存储实例，供外部组件（如 SessionSummarizer、工具层）访问记忆读写能力。
     *
     * @return 记忆存储实例
     */
    public MemoryStore getMemoryStore() {
        return memory;
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