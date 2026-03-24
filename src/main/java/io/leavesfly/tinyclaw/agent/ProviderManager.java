package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.collaboration.AgentOrchestrator;
import io.leavesfly.tinyclaw.agent.evolution.EvaluationFeedback;
import io.leavesfly.tinyclaw.agent.evolution.EvolutionConfig;
import io.leavesfly.tinyclaw.agent.evolution.FeedbackManager;
import io.leavesfly.tinyclaw.agent.evolution.MemoryEvolver;
import io.leavesfly.tinyclaw.agent.evolution.MemoryStore;
import io.leavesfly.tinyclaw.agent.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ModelsConfig;
import io.leavesfly.tinyclaw.config.ProvidersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.HTTPProvider;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * Provider 管理器，负责 LLM Provider 的初始化、热重载和组件构建。
 *
 * <p>将 Provider 相关的职责从 {@link AgentLoop} 中抽取出来，
 * 使 AgentLoop 专注于消息路由和生命周期管理。</p>
 */
class ProviderManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent");

    /* ---------- 依赖 ---------- */
    private final Config config;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry tools;
    private final SessionManager sessions;
    private final String workspace;

    /* ---------- Provider 相关字段 ---------- */
    private volatile LLMProvider provider;
    private volatile boolean providerConfigured = false;
    private final Object providerLock = new Object();
    private volatile ProviderComponents components;

    ProviderManager(Config config, ContextBuilder contextBuilder, ToolRegistry tools,
                    SessionManager sessions, String workspace) {
        this.config = config;
        this.contextBuilder = contextBuilder;
        this.tools = tools;
        this.sessions = sessions;
        this.workspace = workspace;
    }

    // ==================== Provider 管理 ====================

    /** 动态设置或替换 LLM Provider，线程安全。 */
    void setProvider(LLMProvider provider) {
        if (provider == null) {
            return;
        }
        synchronized (providerLock) {
            applyProvider(provider);
        }
        logger.info("Provider configured dynamically", Map.of(
                "model", config.getAgent().getModel()));
    }

    /**
     * 根据当前 config 中的 provider/model 配置热重载 LLM Provider，无需重启即可生效。
     *
     * <p>优先从 ModelsConfig 中通过 model 名称反查对应的 provider，保证 api_base 与 model
     * 始终来自同一个绑定关系，避免 AgentConfig.provider 与 model 手动错配的问题。
     * 若 model 未在 ModelsConfig 中定义，则 fallback 到 AgentConfig.provider。</p>
     *
     * @return true 表示重载成功，false 表示 provider 未配置或无效
     */
    boolean reloadModel() {
        String modelName = config.getAgent().getModel();
        String providerName = resolveProviderName(modelName);

        if (providerName == null || providerName.isEmpty()) {
            logger.warn("reloadModel skipped: provider name could not be resolved",
                    Map.of("model", modelName));
            return false;
        }

        ProvidersConfig.ProviderConfig providerConfig = config.getProviders().getByName(providerName);
        if (providerConfig == null || !providerConfig.isValid()) {
            logger.warn("reloadModel skipped: provider not configured or invalid",
                    Map.of("provider", providerName, "model", modelName));
            return false;
        }

        String apiBase = providerConfig.getApiBaseOrDefault(ProvidersConfig.getDefaultApiBase(providerName));
        setProvider(new HTTPProvider(providerConfig.getApiKey(), apiBase));

        logger.info("Model reloaded successfully", Map.of("provider", providerName, "model", modelName));
        return true;
    }

    /**
     * 从 ModelsConfig 中反查 model 对应的 provider 名称。
     * 若 model 未在 ModelsConfig 中定义，则 fallback 到 AgentConfig.provider。
     */
    private String resolveProviderName(String modelName) {
        ModelsConfig.ModelDefinition modelDef = config.getModels().getDefinitions().get(modelName);
        if (modelDef != null) {
            return modelDef.getProvider();
        }
        String fallback = config.getAgent().getProvider();
        logger.warn("reloadModel: model not found in ModelsConfig, falling back to agent config provider",
                Map.of("model", modelName, "fallback_provider", fallback != null ? fallback : ""));
        return fallback;
    }

    /**
     * 从 ModelsConfig 中解析当前模型的上下文窗口大小。
     *
     * 优先使用 ModelsConfig 中配置的 maxContextSize，
     * 若模型未在配置中定义则 fallback 到 DEFAULT_CONTEXT_WINDOW。
     *
     * @param model 模型名称
     * @return 上下文窗口 token 数
     */
    private int resolveContextWindow(String model) {
        ModelsConfig.ModelDefinition definition = config.getModels().getDefinitions().get(model);
        if (definition != null && definition.getMaxContextSize() != null) {
            return definition.getMaxContextSize();
        }
        logger.warn("Model not found in ModelsConfig, using default context window",
                Map.of("model", model, "default", AgentConstants.DEFAULT_CONTEXT_WINDOW));
        return AgentConstants.DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * 将 provider 及其派生组件一次性赋值，消除构造器与 setProvider 之间的重复逻辑。
     * 调用方需自行保证线程安全（构造器天然安全，setProvider 通过 providerLock 保护）。
     */
    private void applyProvider(LLMProvider newProvider) {
        this.provider = newProvider;

        String model = config.getAgent().getModel();
        int maxIterations = config.getAgent().getMaxToolIterations();
        int contextWindow = resolveContextWindow(model);
        String providerName = resolveProviderName(model);

        // 同步上下文窗口到 ContextBuilder，用于计算记忆 token 预算
        contextBuilder.setContextWindow(contextWindow);

        MemoryStore memoryStore = contextBuilder.getMemoryStore();
        MemoryEvolver memoryEvolver = new MemoryEvolver(memoryStore, newProvider, model);

        TokenUsageStore tokenUsageStore = new TokenUsageStore(workspace);
        LLMExecutor llmExecutor = new LLMExecutor(newProvider, tools, sessions, model, providerName, maxIterations);
        llmExecutor.setTokenUsageStore(tokenUsageStore);

        SessionSummarizer summarizer = new SessionSummarizer(
                sessions, newProvider, model, contextWindow, memoryStore, memoryEvolver);

        this.components = buildOptionalComponents(
                newProvider, model, maxIterations, llmExecutor, summarizer, memoryEvolver, tokenUsageStore);

        this.providerConfigured = true;
    }

    /**
     * 构建完整的 {@link ProviderComponents}，包含核心组件与可选的进化/协同组件。
     *
     * <p>将各可选功能的初始化逻辑收敛在此处，使 {@link #applyProvider} 保持高层编排视角，
     * 不感知各组件的构造细节。</p>
     */
    private ProviderComponents buildOptionalComponents(
            LLMProvider newProvider, String model, int maxIterations,
            LLMExecutor llmExecutor, SessionSummarizer summarizer,
            MemoryEvolver memoryEvolver, TokenUsageStore tokenUsageStore) {

        FeedbackManager feedbackManager = null;
        PromptOptimizer promptOptimizer = null;
        AgentOrchestrator orchestrator = null;

        // 进化组件（反馈收集 + Prompt 优化）
        EvolutionConfig evolutionConfig = config.getAgent().getEvolution();
        if (evolutionConfig != null && evolutionConfig.isAnyEvolutionEnabled()) {
            if (evolutionConfig.isFeedbackEnabled()) {
                feedbackManager = new FeedbackManager(workspace, evolutionConfig);
                llmExecutor.setFeedbackManager(feedbackManager);
                logger.info("Feedback collection enabled");
            }
            if (evolutionConfig.isPromptOptimizationEnabled() && feedbackManager != null) {
                promptOptimizer = new PromptOptimizer(
                        newProvider, model, workspace, feedbackManager, evolutionConfig);
                contextBuilder.setPromptOptimizer(promptOptimizer);
                logger.info("Prompt optimization enabled");
            }
        } else {
            logger.debug("Evolution features disabled");
        }

        // 协同组件（多 Agent 编排）
        if (config.getAgent().isCollaborationEnabled()) {
            orchestrator = new AgentOrchestrator(newProvider, tools, workspace, model, maxIterations);

            // 注入会话管理器，使协同结论可回流到主会话历史
            orchestrator.setCallerSessionManager(sessions);

            // 注入反馈管理器（如果已启用），使协同结果可驱动 Agent 自我进化
            if (feedbackManager != null) {
                orchestrator.setFeedbackManager(feedbackManager);
            }

            logger.info("Collaboration features enabled",
                    Map.of("supportedModes", "debate,team,roleplay,consensus,hierarchy"));
        } else {
            logger.debug("Collaboration features disabled");
        }

        return new ProviderComponents(llmExecutor, summarizer, memoryEvolver, tokenUsageStore,
                feedbackManager, promptOptimizer, orchestrator);
    }

    // ==================== Getter 方法 ====================

    LLMProvider getProvider() {
        return provider;
    }

    boolean isConfigured() {
        return providerConfigured;
    }

    ProviderComponents getComponents() {
        return components;
    }
}
