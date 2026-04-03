package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.evolution.FeedbackManager;
import io.leavesfly.tinyclaw.memory.MemoryEvolver;
import io.leavesfly.tinyclaw.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.collaboration.AgentOrchestrator;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;

/**
 * Provider 切换时一次性创建的组件集合。
 *
 * <p>将 {@link AgentRuntime} 中散落的组件构造逻辑收敛到此处，
 * 使 AgentRuntime 只需持有引用、不再感知各组件的构造细节。
 * 所有字段均为包级可见，仅供 agent 包内部使用。</p>
 */
class ProviderComponents {

    final ReActExecutor reActExecutor;
    final SessionSummarizer summarizer;
    final MemoryEvolver memoryEvolver;
    final TokenUsageStore tokenUsageStore;

    /* ---------- 进化组件（可选） ---------- */
    final FeedbackManager feedbackManager;
    final PromptOptimizer promptOptimizer;

    /* ---------- 协同组件（可选） ---------- */
    final AgentOrchestrator orchestrator;

    ProviderComponents(
            ReActExecutor reActExecutor,
            SessionSummarizer summarizer,
            MemoryEvolver memoryEvolver,
            TokenUsageStore tokenUsageStore,
            FeedbackManager feedbackManager,
            PromptOptimizer promptOptimizer,
            AgentOrchestrator orchestrator) {
        this.reActExecutor = reActExecutor;
        this.summarizer = summarizer;
        this.memoryEvolver = memoryEvolver;
        this.tokenUsageStore = tokenUsageStore;
        this.feedbackManager = feedbackManager;
        this.promptOptimizer = promptOptimizer;
        this.orchestrator = orchestrator;
    }
}
