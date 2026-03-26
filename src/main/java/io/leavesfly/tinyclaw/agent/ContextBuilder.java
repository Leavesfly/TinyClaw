package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.context.*;
import io.leavesfly.tinyclaw.agent.evolution.MemoryStore;
import io.leavesfly.tinyclaw.agent.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.nio.file.Paths;
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
    
    private static final String SECTION_SEPARATOR = "\n\n---\n\n";
    
    private final String workspace;
    private ToolRegistry tools;
    private final MemoryStore memory;
    private final SkillsLoader skillsLoader;
    
    private volatile PromptOptimizer promptOptimizer;
    
    private int contextWindow = AgentConstants.DEFAULT_CONTEXT_WINDOW;
    
    private final List<ContextSection> sections = new ArrayList<>();
    
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
        SectionContext ctx = new SectionContext(
            currentMessage, workspace, contextWindow,
            tools, promptOptimizer, skillsLoader, memory
        );
        
        List<String> parts = new ArrayList<>();
        for (ContextSection section : sections) {
            String content = section.build(ctx);
            if (StringUtils.isNotBlank(content)) {
                parts.add(content);
            }
        }
        
        return String.join(SECTION_SEPARATOR, parts);
    }
    
    /**
     * 设置上下文窗口大小，用于动态计算记忆 token 预算。
     *
     * @param contextWindow 上下文窗口 token 数
     */
    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
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
        return buildMessages(history, summary, currentMessage, null, channel, chatId);
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
            // 处理历史消息中的图片路径
            List<Message> processedHistory = processHistoryImages(sanitizeHistory(new ArrayList<>(history)));
            messages.addAll(processedHistory);
        }
        
        // 添加当前用户消息（支持多模态）
        if (images != null && !images.isEmpty()) {
            // 将相对路径转换为完整路径
            List<String> fullPaths = resolveImagePaths(images);
            messages.add(Message.user(currentMessage, fullPaths));
        } else {
            messages.add(Message.user(currentMessage));
        }
        
        return messages;
    }
    
    /**
     * 将相对图片路径转换为完整路径。
     * 如果路径已经是完整路径或 data URI，则不做转换。
     */
    private List<String> resolveImagePaths(List<String> images) {
        List<String> resolved = new ArrayList<>();
        for (String imagePath : images) {
            if (imagePath.startsWith("data:") || imagePath.startsWith("/")) {
                // 已经是 data URI 或完整路径
                resolved.add(imagePath);
                logger.info("图片路径保持不变", Map.of("path", imagePath.length() > 50 ? imagePath.substring(0, 50) + "..." : imagePath));
            } else {
                // 相对路径，转换为完整路径
                String fullPath = Paths.get(workspace, imagePath).toAbsolutePath().toString();
                resolved.add(fullPath);
                logger.info("图片路径转换", Map.of(
                        "relative", imagePath,
                        "workspace", workspace,
                        "full_path", fullPath));
            }
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