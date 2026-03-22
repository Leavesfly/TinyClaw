package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.LLMExecutor;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 单个Agent执行器
 * 封装Agent的执行能力，用于多Agent协同场景
 */
public class AgentExecutor {
    
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(1);
    
    /** Agent唯一标识 */
    private final String agentId;
    
    /** Agent角色 */
    private final AgentRole role;
    
    /** LLM执行器 */
    private final LLMExecutor llmExecutor;
    
    /** 会话管理器 */
    private final SessionManager sessionManager;
    
    /** 会话键 */
    private final String sessionKey;
    
    /**
     * 构造 AgentExecutor，使用外部传入的共享 SessionManager。
     *
     * <p>协同场景下所有 AgentExecutor 共享同一个 SessionManager 实例（由 ExecutionContext 持有），
     * 避免每个 AgentExecutor 独立初始化 SessionManager 带来的重复磁盘 IO 开销。
     *
     * @param role           Agent 角色定义
     * @param provider       LLM 服务提供者
     * @param tools          工具注册表
     * @param sharedSessions 共享会话管理器（由调用方统一创建）
     * @param model          默认模型名称
     * @param maxIterations  最大迭代次数
     */
    public AgentExecutor(AgentRole role, LLMProvider provider, ToolRegistry tools,
                         SessionManager sharedSessions, String model, int maxIterations) {
        this.agentId = "agent-" + ID_COUNTER.getAndIncrement();
        this.role = role;
        this.sessionManager = sharedSessions;
        this.sessionKey = "collab:" + agentId;

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
     * Agent直接回答（不使用共享上下文历史）
     * 
     * @param userMessage 用户消息
     * @return Agent的回复内容
     */
    public String answer(String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", role.getSystemPrompt()));
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
        
        // 系统提示
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append(role.getSystemPrompt());
        
        if (customPrompt != null && !customPrompt.isEmpty()) {
            systemPrompt.append("\n\n").append(customPrompt);
        }
        
        messages.add(new Message("system", systemPrompt.toString()));
        
        // 添加协同主题和历史
        StringBuilder userContent = new StringBuilder();
        userContent.append("【协同主题】").append(context.getTopic()).append("\n\n");
        
        // 添加用户原始输入
        if (context.getUserInput() != null && !context.getUserInput().isEmpty()) {
            userContent.append("【用户需求】").append(context.getUserInput()).append("\n\n");
        }
        
        // 添加对话历史
        String historyText = context.buildHistoryText();
        if (!historyText.isEmpty()) {
            userContent.append(historyText).append("\n");
        }
        
        userContent.append("请基于以上信息，以【").append(role.getRoleName()).append("】的角色给出你的观点或回复。");
        
        messages.add(new Message("user", userContent.toString()));
        
        return messages;
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
        return "AgentExecutor{" +
                "agentId='" + agentId + '\'' +
                ", role=" + role.getRoleName() +
                '}';
    }
}
