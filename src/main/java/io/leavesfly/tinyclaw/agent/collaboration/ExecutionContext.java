package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.nio.file.Paths;

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

    public ExecutionContext(LLMProvider provider, ToolRegistry tools,
                            String workspace, String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        String sessionPath = Paths.get(workspace, "sessions", "collaboration").toString();
        this.sharedSessionManager = new SessionManager(sessionPath);
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
}
