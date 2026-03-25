package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.LLMExecutor;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个Agent执行器
 * 封装Agent的执行能力，用于多Agent协同场景
 */
public class RoleAgent {
    
    /** Agent唯一标识（格式：collab-<sessionId>-<sequence>） */
    private final String agentId;
    
    /** Agent角色 */
    private final AgentRole role;
    
    /** LLM执行器 */
    private final LLMExecutor llmExecutor;
    
    /** 会话管理器 */
    private final SessionManager sessionManager;
    
    /** 会话键 */
    private final String sessionKey;
    
    /** 基础系统提示词（可选，继承自主 Agent 的核心身份信息） */
    private final String baseSystemPrompt;
    
    /**
     * 构造 RoleAgent，使用外部传入的共享 SessionManager。
     *
     * <p>协同场景下所有 RoleAgent 共享同一个 SessionManager 实例（由 ExecutionContext 持有），
     * 避免每个 RoleAgent 独立初始化 SessionManager 带来的重复磁盘 IO 开销。
     *
     * @param role           Agent 角色定义
     * @param provider       LLM 服务提供者
     * @param tools          工具注册表
     * @param sharedSessions 共享会话管理器（由调用方统一创建）
     * @param model          默认模型名称
     * @param maxIterations  最大迭代次数
     * @param sessionId      协同会话 ID（用于日志关联和调试）
     * @param sequence       Agent 序号（在协同会话内唯一）
     * @param baseSystemPrompt 基础系统提示词（可选，继承自主 Agent 的核心身份信息）
     */
    public RoleAgent(AgentRole role, LLMProvider provider, ToolRegistry tools,
                     SessionManager sharedSessions, String model, int maxIterations,
                     String sessionId, int sequence, String baseSystemPrompt) {
        this.agentId = "collab-" + sessionId + "-" + sequence;
        this.role = role;
        this.sessionManager = sharedSessions;
        this.sessionKey = "collab:" + agentId;
        this.baseSystemPrompt = baseSystemPrompt;

        // 使用角色指定的模型，如果没有则使用默认模型
        String effectiveModel = (role.getModel() != null && !role.getModel().isEmpty())
                ? role.getModel() : model;

        // 按角色的工具白名单过滤工具集，实现差异化工具权限
        ToolRegistry effectiveTools = role.hasToolRestrictions()
                ? tools.filter(role.getAllowedTools())
                : tools;

        this.llmExecutor = new LLMExecutor(provider, effectiveTools, sessionManager,
                effectiveModel, null, maxIterations);
    }
    
    /**
     * Agent发言（基于共享上下文）
     * 
     * @param context 共享上下文
     * @return Agent的回复内容
     */
    public String speak(SharedContext context) {
        List<Message> messages = buildMessages(context);
        try {
            return llmExecutor.execute(messages, sessionKey);
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }
    
    /**
     * Agent发言（带自定义提示）
     * 
     * @param context 共享上下文
     * @param customPrompt 自定义提示（追加到系统提示后）
     * @return Agent的回复内容
     */
    public String speak(SharedContext context, String customPrompt) {
        List<Message> messages = buildMessages(context, customPrompt);
        try {
            return llmExecutor.execute(messages, sessionKey);
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * Agent 流式发言（基于共享上下文）
     * <p>LLM 生成回复时逐 chunk 通过 {@link StreamEvent#collaborateAgentChunk} 事件输出，
     * 用户无需等待完整回复即可看到 Agent 的发言过程。
     *
     * @param context  共享上下文
     * @param callback 流式回调，接收 COLLABORATE_AGENT_CHUNK 事件
     * @return Agent 的完整回复内容
     */
    public String speakStream(SharedContext context, LLMProvider.EnhancedStreamCallback callback) {
        return speakStream(context, null, callback);
    }

    /**
     * Agent 流式发言（带自定义提示）
     * <p>LLM 生成回复时逐 chunk 通过 {@link StreamEvent#collaborateAgentChunk} 事件输出。
     *
     * @param context      共享上下文
     * @param customPrompt 自定义提示（追加到系统提示后）
     * @param callback     流式回调，接收 COLLABORATE_AGENT_CHUNK 事件
     * @return Agent 的完整回复内容
     */
    public String speakStream(SharedContext context, String customPrompt,
                              LLMProvider.EnhancedStreamCallback callback) {
        List<Message> messages = buildMessages(context, customPrompt);
        try {
            // 将 LLM 的流式 CONTENT chunk 转换为 COLLABORATE_AGENT_CHUNK 事件
            LLMProvider.StreamCallback chunkRelay = chunk ->
                    callback.onEvent(StreamEvent.collaborateAgentChunk(role.getRoleName(), chunk));
            return llmExecutor.executeStream(messages, sessionKey, chunkRelay);
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * Agent直接回答（不使用共享上下文历史）
     * 
     * @param userMessage 用户消息
     * @return Agent的回复内容
     */
    public String answer(String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", buildSystemPrompt(null)));
        messages.add(new Message("user", userMessage));
        try {
            return llmExecutor.execute(messages, sessionKey);
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 构建消息列表（包含系统提示和共享上下文历史）
     */
    private List<Message> buildMessages(SharedContext context) {
        return buildMessages(context, null);
    }

    /**
     * 构建消息列表（包含系统提示、共享上下文历史和可选的自定义提示）
     */
    private List<Message> buildMessages(SharedContext context, String customPrompt) {
        List<Message> messages = new ArrayList<>();

        String systemPromptText = buildSystemPrompt(customPrompt);
        messages.add(new Message("system", systemPromptText));

        String userContent = buildUserContent(context);
        messages.add(new Message("user", userContent));

        return messages;
    }

    /**
     * 构建系统提示词
     * 优先使用 baseSystemPrompt 作为前缀，然后追加角色提示和自定义提示
     */
    private String buildSystemPrompt(String customPrompt) {
        StringBuilder promptBuilder = new StringBuilder();
        
        // 添加基础系统提示词（如果存在）
        if (baseSystemPrompt != null && !baseSystemPrompt.isEmpty()) {
            promptBuilder.append(baseSystemPrompt);
            promptBuilder.append("\n\n");
        }
        
        // 添加角色系统提示词
        promptBuilder.append(role.getSystemPrompt());
        
        // 追加自定义提示（如果存在）
        if (customPrompt != null && !customPrompt.isEmpty()) {
            promptBuilder.append("\n\n").append(customPrompt);
        }
        
        return promptBuilder.toString();
    }

    /**
     * 构建用户侧消息内容：协同主题 + 用户需求 + 对话历史 + 发言引导
     */
    private String buildUserContent(SharedContext context) {
        StringBuilder content = new StringBuilder();
        content.append("【协同主题】").append(context.getTopic()).append("\n\n");

        String userInput = context.getUserInput();
        if (userInput != null && !userInput.isEmpty()) {
            content.append("【用户需求】").append(userInput).append("\n\n");
        }

        String historyText = context.buildHistoryText();
        if (!historyText.isEmpty()) {
            content.append(historyText).append("\n");
        }

        content.append("请基于以上信息，以【").append(role.getRoleName()).append("】的角色给出你的观点或回复。");
        return content.toString();
    }
    
    // Getters
    
    public String getAgentId() {
        return agentId;
    }
    
    public AgentRole getRole() {
        return role;
    }
    
    public String getRoleName() {
        return role.getRoleName();
    }
    
    public SessionManager getSessionManager() {
        return sessionManager;
    }
    
    @Override
    public String toString() {
        return "RoleAgent{" +
                "agentId='" + agentId + '\'' +
                ", role=" + role.getRoleName() +
                '}';
    }
}