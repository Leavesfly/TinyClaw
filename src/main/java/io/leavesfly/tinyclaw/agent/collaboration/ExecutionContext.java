package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.nio.file.Paths;
import java.util.UUID;

/**
 * Agent 执行上下文
 * 封装 LLM 调用所需的基础依赖，避免在方法间反复传递多个参数。
 *
 * <p>持有一个共享的 {@link SessionManager}，供所有 {@link AgentExecutor} 复用，
 * 避免每个 AgentExecutor 独立初始化 SessionManager 带来的重复磁盘 IO 开销。
 */
public class ExecutionContext {

    private final LLMProvider provider;
    private final ToolRegistry tools;
    private final String workspace;
    private final String model;
    private final int maxIterations;

    /** 共享会话管理器（协同场景下所有 AgentExecutor 复用同一实例） */
    private final SessionManager sharedSessionManager;

    /** 协同会话 ID（使用 UUID 短格式，用于日志关联和调试） */
    private final String sessionId;

    /** 基础系统提示词（可选，用于传递主 Agent 的核心身份信息） */
    private String baseSystemPrompt;

    /** AgentExecutor 序号计数器（用于生成唯一的协同会话内序号） */
    private int agentSequence = 0;

    public ExecutionContext(LLMProvider provider, ToolRegistry tools,
                            String workspace, String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        String sessionPath = Paths.get(workspace, "sessions", "collaboration").toString();
        this.sharedSessionManager = new SessionManager(sessionPath);
        this.sessionId = generateShortUUID();
    }

    /**
     * 生成短格式 UUID（取前8位）
     */
    private String generateShortUUID() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 工厂方法：统一创建 AgentExecutor
     * 确保所有 AgentExecutor 都通过此方法创建，便于统一管理和追踪
     *
     * @param role Agent 角色
     * @return 新创建的 AgentExecutor
     */
    public AgentExecutor createAgentExecutor(AgentRole role) {
        return new AgentExecutor(role,
                provider,
                tools,
                sharedSessionManager,
                model,
                maxIterations,
                sessionId,
                ++agentSequence,
                baseSystemPrompt);
    }

    public LLMProvider getProvider() {
        return provider;
    }

    public ToolRegistry getTools() {
        return tools;
    }

    public String getWorkspace() {
        return workspace;
    }

    public String getModel() {
        return model;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public SessionManager getSharedSessionManager() {
        return sharedSessionManager;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getBaseSystemPrompt() {
        return baseSystemPrompt;
    }

    /**
     * 设置基础系统提示词（可选）
     * 子 Agent 将在构建消息时将其作为系统提示的前缀，继承主 Agent 的核心身份信息
     *
     * @param baseSystemPrompt 基础系统提示词
     */
    public void setBaseSystemPrompt(String baseSystemPrompt) {
        this.baseSystemPrompt = baseSystemPrompt;
    }
}