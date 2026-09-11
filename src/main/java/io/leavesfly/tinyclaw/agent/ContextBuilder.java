package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.context.*;
import io.leavesfly.tinyclaw.memory.MemoryScope;
import io.leavesfly.tinyclaw.memory.MemorySelection;
import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.ToolCall;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.MediaPaths;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
    
    private static final String SECTION_SEPARATOR = "\n\n---\n\n";
    
    private final String workspace;
    private ToolRegistry tools;
    private final MemoryStore memory;
    private final SkillsLoader skillsLoader;
    
    private volatile PromptOptimizer promptOptimizer;
    
    private int contextWindow = AgentConstants.DEFAULT_CONTEXT_WINDOW;
    
    private final List<ContextSection> sections = new ArrayList<>();

    /**
     * 会话记忆模式门（P5）：sessionKey → 是否启用长期记忆。
     * null 表示未配置（全部会话启用，保持旧行为）。装配方（GatewayBootstrap）注入，
     * 读路径（记忆注入）与写路径（摘要后提取）共用同一门。
     */
    private volatile Predicate<String> memoryGate;

    /**
     * 每会话最近一次记忆选择快照（P5 透明度）：供 Web 查询「本轮使用」。
     * 同源保证：登记的正是本次注入系统提示词的那次计算结果。
     */
    private final ConcurrentHashMap<String, MemorySelection> lastSelectionBySession =
            new ConcurrentHashMap<>();

    /** 登记容量上限：超出后整体重置（快照由下一轮重建，丢失仅影响展示）。 */
    private static final int MAX_SELECTION_SESSIONS = 256;

    /**
     * 项目解析器（P4）：sessionKey → 项目信息；null 表示未装配（项目能力未启用，
     * 任何会话都不注入项目指令与项目记忆域）。
     */
    private volatile java.util.function.Function<String, SectionContext.ProjectInfo> projectResolver;
    
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
        this.skillsLoader = new SkillsLoader(workspace, null, null);
        initializeSections();
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
        initializeSections();
    }
    
    /**
     * 初始化内置的 section 列表。
     */
    private void initializeSections() {
        sections.add(new IdentitySection());
        sections.add(new BootstrapSection());
        sections.add(new ProjectSection());
        sections.add(new ToolsSection());
        sections.add(new SkillsSection());
        sections.add(new MemorySection());
    }
    
    /**
     * 添加自定义 section。
     * 
     * @param section 要添加的 section
     */
    public void addSection(ContextSection section) {
        sections.add(section);
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
     * 构建系统提示词（轻量上下文版本）。
     *
     * <p>不传归属域，因此只会注入全局可见的长期记忆。</p>
     *
     * @param currentMessage 当前用户消息（可为 null）
     * @param lightContext 为 true 时跳过 workspace bootstrap 文件注入，降低 token 成本
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt(String currentMessage, boolean lightContext) {
        return buildSystemPrompt(currentMessage, lightContext, MemoryScope.globalOnly());
    }

    /**
     * 构建系统提示词，指定本次可见的记忆归属域。
     *
     * @param currentMessage 当前用户消息（可为 null）
     * @param lightContext 为 true 时跳过 workspace bootstrap 文件注入
     * @param memoryScopes 可见的记忆归属域，null 或空时退化为仅全局域
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt(String currentMessage, boolean lightContext, Set<String> memoryScopes) {
        return buildSystemPrompt(currentMessage, lightContext, memoryScopes, false, null, null);
    }

    /**
     * 构建系统提示词（完整控制版）。
     *
     * @param skipMemory 为 true 时跳过 MemorySection（会话 memoryMode=OFF）
     * @param presetMemoryContext 预计算的记忆文本；非 null 时 MemorySection 直接使用不再重算，
     *                            空串表示已计算且无内容（与 null 语义不同）
     * @param projectInfo 会话归属的项目信息（P4）；null 表示无项目（ProjectSection 输出空）
     */
    private String buildSystemPrompt(String currentMessage, boolean lightContext, Set<String> memoryScopes,
                                     boolean skipMemory, String presetMemoryContext,
                                     SectionContext.ProjectInfo projectInfo) {
        SectionContext ctx = new SectionContext(
            currentMessage, workspace, contextWindow,
            tools, promptOptimizer, skillsLoader, memory, memoryScopes
        );
        if (presetMemoryContext != null) {
            ctx.setPresetMemoryContext(presetMemoryContext);
        }
        if (projectInfo != null) {
            ctx.setProjectInfo(projectInfo);
        }

        List<String> parts = new ArrayList<>();
        for (ContextSection section : sections) {
            if (lightContext && section instanceof BootstrapSection) {
                continue;
            }
            if (skipMemory && section instanceof MemorySection) {
                continue;
            }
            String content = section.build(ctx);
            if (StringUtils.isNotBlank(content)) {
                parts.add(content);
            }
        }

        return String.join(SECTION_SEPARATOR, parts);
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
        return buildSystemPrompt(currentMessage, false);
    }
    
    /**
     * 设置上下文窗口大小，用于动态计算记忆 token 预算。
     * 
     * @param contextWindow 上下文窗口 token 数
     */
    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }
    
    /** 当前上下文窗口 token 数（P5：Web 端估算上下文占用时使用）。 */
    public int getContextWindow() {
        return contextWindow;
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
        return buildMessages(history, summary, currentMessage, null, channel, chatId, null, false);
    }

    /**
     * 为 LLM 构建消息列表，支持轻量上下文模式。
     *
     * @param history 历史消息列表
     * @param summary 之前对话的摘要
     * @param currentMessage 当前用户消息
     * @param channel 当前通道名称
     * @param chatId 当前聊天 ID
     * @param lightContext 为 true 时跳过 workspace bootstrap 文件注入
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage,
                                        String channel, String chatId, boolean lightContext) {
        return buildMessages(history, summary, currentMessage, null, channel, chatId, null, lightContext);
    }
    
    /**
     * 为 LLM 构建消息列表，支持多模态内容（文本+图片）。
     * 
     * 组装完整的消息上下文，包括系统提示词、历史消息和当前用户消息。
     * 当包含图片时，使用多模态消息格式。
     * 
     * @param history 历史消息列表
     * @param summary 之前对话的摘要
     * @param currentMessage 当前用户消息
     * @param images 图片路径列表（可为 null，可以是相对路径或完整路径）
     * @param channel 当前通道名称
     * @param chatId 当前聊天 ID
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage, 
                                        List<String> images, String channel, String chatId) {
        return buildMessages(history, summary, currentMessage, images, channel, chatId, null, false);
    }

    /**
     * 为 LLM 构建消息列表（多模态 + 轻量上下文模式）。
     *
     * @param history 历史消息列表
     * @param summary 之前对话的摘要
     * @param currentMessage 当前用户消息
     * @param images 图片路径列表（可为 null）
     * @param channel 当前通道名称
     * @param chatId 当前聊天 ID
     * @param senderId 发言人标识，用于确定可见的记忆归属域（可为 null）
     * @param lightContext 为 true 时跳过 workspace bootstrap 文件注入
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage, 
                                        List<String> images, String channel, String chatId,
                                        String senderId, boolean lightContext) {
        return buildMessages(history, summary, currentMessage, images, channel, chatId,
                senderId, lightContext, null);
    }

    /**
     * 为 LLM 构建消息列表（完整版，P5）：携带 sessionKey 用于会话级记忆模式判断与
     * 「本轮使用记忆」登记。sessionKey 为 null 时保持旧行为（记忆启用、不登记）。
     *
     * @param sessionKey 会话键（如 web:1725968000000），null 表示会话未知
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage,
                                        List<String> images, String channel, String chatId,
                                        String senderId, boolean lightContext, String sessionKey) {
        List<Message> messages = new ArrayList<>();
        
        // 构建系统提示词（传入当前消息用于记忆相关性检索）
        String systemPrompt = buildSystemPromptWithSession(
                currentMessage, channel, chatId, senderId, summary, lightContext, sessionKey);
        
        logger.debug("System prompt built", Map.of(
                "total_chars", systemPrompt.length(),
                "total_lines", systemPrompt.split("\n").length
        ));
        
        // 添加系统消息
        messages.add(Message.system(systemPrompt));
        
        // 添加历史记录（清理可能存在的孤立 tool 消息，防止 LLM API 报错）
        if (history != null && !history.isEmpty()) {
            // 处理历史消息中的图片路径
            List<Message> processedHistory = processHistoryImages(sanitizeHistory(new ArrayList<>(history)));
            messages.addAll(processedHistory);
        }
        
        // 添加当前用户消息（支持多模态）
        List<String> fullPaths = resolveImagePaths(images);
        if (fullPaths != null && !fullPaths.isEmpty()) {
            messages.add(Message.user(currentMessage, fullPaths));
        } else {
            messages.add(Message.user(currentMessage));
        }
        
        return messages;
    }
    
    /**
     * 把图片路径解析为绝对路径，并丢弃越界路径。
     *
     * <p>data URI 原样保留。其余路径（相对或绝对）交由
     * {@link MediaPaths#resolveMediaPath} 归一化并校验归属：只允许 workspace 与通道媒体
     * 目录内的文件。图片路径来自 HTTP 请求体等不可信输入，越界路径会被
     * {@code LLMRequestBuilder} 读成 Base64 外发给模型服务商，等价于任意文件读取，
     * 因此必须在进入消息之前拦掉，而不是等读文件时才失败。</p>
     *
     * <p>包级可见：{@link AgentRuntime} 复用同一份校验逻辑，避免两处各自解析路径导致
     * 其中一处漏掉校验。</p>
     *
     * @param images 原始图片路径列表
     * @return 校验通过的绝对路径列表，越界项已剔除
     */
    List<String> resolveImagePaths(List<String> images) {
        if (images == null || images.isEmpty()) {
            return images;
        }
        List<String> resolved = new ArrayList<>();
        for (String imagePath : images) {
            if (imagePath == null || imagePath.isEmpty()) {
                continue;
            }
            if (imagePath.startsWith("data:")) {
                resolved.add(imagePath);
                continue;
            }
            Path safePath = MediaPaths.resolveMediaPath(workspace, imagePath);
            if (safePath == null) {
                logger.warn("拒绝越界图片路径", Map.of(
                        "path", StringUtils.truncate(imagePath, 200),
                        "workspace", workspace));
                continue;
            }
            resolved.add(safePath.toString());
        }
        return resolved;
    }
    
    /**
     * 处理历史消息中的图片：去除图片数据，只保留文字内容。
     *
     * 图片 Base64 数据体积巨大（1MB 原图 ≈ 35K tokens），若将历史消息中的图片
     * 随每轮对话重复发送，会导致上下文窗口迅速膨胀。
     * 因此历史消息中的图片一律丢弃——模型在当轮已经看过图片，后续轮次无需重复传入。
     */
    private List<Message> processHistoryImages(List<Message> history) {
        List<Message> processed = new ArrayList<>();
        for (Message msg : history) {
            if (msg.hasImages()) {
                // 创建不含图片的副本，保留文字内容和工具调用信息
                Message textOnlyMsg = new Message(msg.getRole(), msg.getContent());
                textOnlyMsg.setToolCalls(msg.getToolCalls());
                textOnlyMsg.setToolCallId(msg.getToolCallId());
                processed.add(textOnlyMsg);
                logger.debug("Dropped images from history message to reduce context size", Map.of(
                        "role", msg.getRole(),
                        "image_count", msg.getImages().size()
                ));
            } else {
                processed.add(msg);
            }
        }
        return processed;
    }
    
    /**
     * 构建包含会话信息的系统提示词。
     * 
     * <p>P5：sessionKey 非 null 时按会话记忆模式（memoryGate）判断是否注入长期记忆，
     * 并一次性预计算记忆选择（同源保证：登记的选中清单与真实注入文本来自同一次计算）。</p>
     *
     * @param currentMessage 当前用户消息（用于记忆相关性检索）
     * @param channel 通道名称
     * @param chatId 聊天 ID
     * @param senderId 发言人标识，用于确定可见的记忆归属域
     * @param summary 对话摘要
     * @param lightContext 为 true 时跳过 workspace bootstrap 文件注入
     * @param sessionKey 会话键（null 表示会话未知，记忆启用且不登记）
     * @return 完整的系统提示词
     */
    private String buildSystemPromptWithSession(String currentMessage, String channel, String chatId,
                                                String senderId, String summary, boolean lightContext,
                                                String sessionKey) {

        Set<String> memoryScopes = MemoryScope.visibleScopes(channel, senderId, chatId);

        // P4：解析会话归属项目（未装配/无归属返回 null）
        SectionContext.ProjectInfo project = resolveProject(sessionKey);
        if (project != null && sessionKey != null) {
            // 项目域作为新增 scope 加入可见集合：只扩大本项目会话可见性，
            // 不默认注入其他项目域
            String projectDomain = MemoryScope.projectDomain(project.id());
            if (projectDomain != null) {
                Set<String> expanded = new java.util.LinkedHashSet<>(memoryScopes);
                expanded.add(projectDomain);
                memoryScopes = expanded;
            }
        }

        // P5：会话级记忆模式与「本轮使用」登记
        boolean memoryEnabled = isMemoryEnabled(sessionKey);
        String presetMemoryContext = null;
        if (sessionKey != null) {
            MemorySelection selection;
            if (memoryEnabled) {
                int budget = MemorySection.calculateMemoryTokenBudget(contextWindow);
                selection = memory.buildMemorySelection(currentMessage, budget, memoryScopes);
            } else {
                selection = MemorySelection.disabled();
            }
            recordLastSelection(sessionKey, selection);
            // 空串与 null 语义不同：空串表示已计算且无内容，避免 MemorySection 二次检索
            presetMemoryContext = selection.text;
        }

        StringBuilder systemPrompt = new StringBuilder(buildSystemPrompt(
                currentMessage, lightContext, memoryScopes, !memoryEnabled, presetMemoryContext, project));
        
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

    // ==================== P5：会话记忆模式与选中登记 ====================

    /**
     * 注入会话记忆模式门（装配方调用）：sessionKey → 是否启用长期记忆。
     * null 清除门（全部启用）。门在读路径（记忆注入）与写路径（摘要后提取）间共享。
     */
    public void setMemoryGate(Predicate<String> memoryGate) {
        this.memoryGate = memoryGate;
    }

    /** 当前会话记忆模式门（供 ProviderManager 构造 SessionSummarizer 时传递，保持读写同门）。 */
    public Predicate<String> getMemoryGate() {
        return memoryGate;
    }

    /**
     * 注入项目解析器（P4 装配方调用）：sessionKey → 项目信息。null 清除（项目能力未启用）。
     * 解析发生在每次构建时（实时读），项目修改/删除后下一轮立即生效。
     */
    public void setProjectResolver(java.util.function.Function<String, SectionContext.ProjectInfo> projectResolver) {
        this.projectResolver = projectResolver;
    }

    /**
     * 解析会话归属的项目信息：未装配解析器或无归属时返回 null。
     * 归属标记指向已删除的项目时同样返回 null（指令不再注入）。
     */
    public SectionContext.ProjectInfo resolveProject(String sessionKey) {
        java.util.function.Function<String, SectionContext.ProjectInfo> resolver = this.projectResolver;
        if (resolver == null || sessionKey == null) {
            return null;
        }
        try {
            return resolver.apply(sessionKey);
        } catch (Exception e) {
            logger.warn("Failed to resolve project for session", Map.of(
                    "session_key", sessionKey,
                    "error", String.valueOf(e.getMessage())));
            return null;
        }
    }

    /** 当前会话是否启用长期记忆：门未配置、sessionKey 为 null 或门返回 true 均视为启用。 */
    public boolean isMemoryEnabled(String sessionKey) {
        Predicate<String> gate = this.memoryGate;
        return gate == null || sessionKey == null || gate.test(sessionKey);
    }

    /**
     * 查询某会话最近一次的记忆选择快照（「本轮使用」）。
     *
     * @return 快照；null 表示该会话尚无登记（未发过消息或门未装配）
     */
    public MemorySelection getLastMemorySelection(String sessionKey) {
        return sessionKey != null ? lastSelectionBySession.get(sessionKey) : null;
    }

    /** 登记会话最近一次选择快照；超容量时整体重置（快照可由下一轮重建）。 */
    private void recordLastSelection(String sessionKey, MemorySelection selection) {
        if (sessionKey == null || selection == null) {
            return;
        }
        if (lastSelectionBySession.size() >= MAX_SELECTION_SESSIONS
                && !lastSelectionBySession.containsKey(sessionKey)) {
            lastSelectionBySession.clear();
        }
        lastSelectionBySession.put(sessionKey, selection);
    }
    
    /**
     * 修复历史消息中的 tool_calls / tool 配对关系，避免 LLM API 返回 400。
     * 
     * <p>OpenAI 兼容协议要求：每条 {@code role="tool"} 消息必须能对应到前面某条
     * {@code assistant.tool_calls} 里的 id；反之，带 tool_calls 的 assistant 消息也必须有
     * tool 消息应答。两侧都可能被破坏：</p>
     * <ul>
     *   <li><b>头部孤立 tool</b>：上下文被摘要压缩后，起点落在 tool 消息上；</li>
     *   <li><b>尾部孤立 assistant(tool_calls)</b>：工具循环中途异常退出时，
     *       {@code ReActExecutor} 的 finally 会把已产生的 assistant 消息落盘，但工具结果还没写入，
     *       下一轮带上这段历史就会被 API 拒结，使会话永久卡死。</li>
     * </ul>
     * 
     * @param history 原始历史消息列表
     * @return 清理后的历史消息列表
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history.isEmpty()) {
            return history;
        }
        
        // 跳过历史开头缺少配对 assistant 的孤立 tool 消息
        int startIndex = 0;
        while (startIndex < history.size() && "tool".equals(history.get(startIndex).getRole())) {
            startIndex++;
        }
        if (startIndex > 0) {
            logger.warn("Skipped orphaned tool messages at history start", Map.of(
                    "skipped_count", startIndex));
        }
        
        List<Message> sanitized = startIndex == 0
                ? history
                : new ArrayList<>(history.subList(startIndex, history.size()));
        
        // 剔除尾部未被应答的 assistant(tool_calls)：从尾部往前看，末尾的 tool 消息是已有应答，
        // 紧接着的 assistant(tool_calls) 则需判断应答数量是否足够
        int endIndex = sanitized.size();
        int toolReplies = 0;
        while (endIndex > 0 && "tool".equals(sanitized.get(endIndex - 1).getRole())) {
            toolReplies++;
            endIndex--;
        }
        if (endIndex > 0) {
            Message last = sanitized.get(endIndex - 1);
            List<ToolCall> calls = last.getToolCalls();
            boolean unanswered = "assistant".equals(last.getRole())
                    && calls != null && !calls.isEmpty()
                    && toolReplies < calls.size();
            if (unanswered) {
                logger.warn("Dropped trailing assistant(tool_calls) without complete tool replies",
                        Map.of("tool_calls", calls.size(), "tool_replies", toolReplies));
                // 连同已有的部分应答一起丢弃，否则它们反过来变成孤立 tool 消息
                sanitized = new ArrayList<>(sanitized.subList(0, endIndex - 1));
            }
        }
        
        return sanitized;
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