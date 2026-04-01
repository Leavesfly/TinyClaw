package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.collaboration.strategy.*;
import io.leavesfly.tinyclaw.agent.evolution.EvaluationFeedback;
import io.leavesfly.tinyclaw.agent.evolution.FeedbackManager;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.nio.file.Paths;
import java.util.*;

/**
 * 多 Agent 协同编排器
 * 负责协调多个 Agent 的协同工作，支持多种协同模式
 */
public class AgentOrchestrator {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    /** 执行上下文（封装 LLM 调用所需基础依赖） */
    private final ExecutionContext executionContext;

    /** 公共线程池（统一管理所有策略的并发执行） */
    private final CollaborationExecutorPool executorPool;

    /** 调用方的会话管理器（可选，用于将协同结论回流到主会话） */
    private volatile SessionManager callerSessionManager;

    /** 反馈管理器（可选，用于将协同结果反馈到 evolution 系统） */
    private volatile FeedbackManager feedbackManager;

    public AgentOrchestrator(LLMProvider provider, ToolRegistry tools, String workspace,
                              String model, int maxIterations) {
        this.executionContext = new ExecutionContext(provider, tools, workspace, model, maxIterations);
        this.executorPool = new CollaborationExecutorPool();
        initStrategies();
    }

    /**
     * 注入调用方的会话管理器，用于将协同结论回流到主会话历史
     */
    public void setCallerSessionManager(SessionManager callerSessionManager) {
        this.callerSessionManager = callerSessionManager;
    }

    /**
     * 注入反馈管理器，用于将协同结果反馈到 evolution 系统
     */
    public void setFeedbackManager(FeedbackManager feedbackManager) {
        this.feedbackManager = feedbackManager;
    }

    // ----- 策略实例（每种 Mode 对应一个策略） -----
    private DiscussionStrategy discussionStrategy;
    private TasksStrategy tasksStrategy;
    private WorkflowStrategy workflowStrategy;

    /**
     * 初始化所有协同策略
     */
    private void initStrategies() {
        discussionStrategy = new DiscussionStrategy();
        discussionStrategy.setExecutionContext(executionContext);

        tasksStrategy = new TasksStrategy(executorPool);
        tasksStrategy.setExecutionContext(executionContext);

        workflowStrategy = new WorkflowStrategy(executionContext, executorPool);
    }

    /**
     * 根据 Mode 解析具体的策略实现
     */
    private CollaborationStrategy resolveStrategy(CollaborationConfig config) {
        return switch (config.getMode()) {
            case DISCUSS -> discussionStrategy;
            case TASKS -> tasksStrategy;
            case WORKFLOW -> workflowStrategy;
        };
    }
    
    /**
     * 启动多Agent协同
     * 
     * @param config 协同配置
     * @param userInput 用户输入
     * @return 最终结论
     */
    public String orchestrate(CollaborationConfig config, String userInput) {
        return orchestrateWithStream(config, userInput, null);
    }
    
    /**
     * 启动多Agent协同（流式版本）
     * 支持通过回调输出协同过程信息。
     * 
     * @param config 协同配置
     * @param userInput 用户输入
     * @param callback 流式回调，用于输出协同过程（可为 null）
     * @return 最终结论
     */
    public String orchestrateWithStream(CollaborationConfig config, String userInput,
                                        LLMProvider.EnhancedStreamCallback callback) {
        String modeStr = config.getMode().name();
        String goal = config.getGoal();
        
        logger.info("启动多Agent协同", Map.of(
                "mode", modeStr,
                "goal", goal != null ? goal : "N/A"
        ));
        
        // 通过回调输出协同开始事件
        if (callback != null) {
            callback.onEvent(StreamEvent.collaborateStart(modeStr, goal != null ? goal : userInput));
        }
        
        // 1. 创建共享上下文，传递流式回调
        SharedContext context = new SharedContext(config.getGoal(), userInput);
        context.setStreamCallback(callback);

        // 注入主 Agent 的对话上下文摘要
        String contextSummary = config.getMeta("contextSummary");
        if (contextSummary != null && !contextSummary.isEmpty()) {
            context.setContextSummary(contextSummary);
        }

        // 注入审批回调到 SharedContext（供 WorkflowEngine 使用）
        if (config.getApprovalCallback() != null) {
            context.setMeta("approvalCallback", config.getApprovalCallback());
        }

        // 设置 Token 预算
        if (config.getMaxTokenBudget() > 0) {
            context.setTokenBudget(config.getMaxTokenBudget());
        }

        // 传递自反馈配置到 Workflow 变量
        if (config.isSelfReflectionEnabled() && config.getWorkflow() != null) {
            config.getWorkflow().setVariable("selfReflectionEnabled", true);
            config.getWorkflow().setVariable("maxReflectionRetries", config.getMaxReflectionRetries());
        }
        
        // 2. 根据角色配置创建Agent执行器
        List<RoleAgent> agents = createAgents(config);
        
        boolean isHierarchyStyle = config.getMode() == CollaborationConfig.Mode.TASKS
                && config.getTasksStyle() == CollaborationConfig.TasksStyle.HIERARCHY;
        if (agents.isEmpty() && !isHierarchyStyle) {
            return "未配置参与角色，无法启动协同";
        }
        
        // 3. 获取对应策略
        CollaborationStrategy strategy = resolveStrategy(config);
        
        // 4. 执行协同流程
        try {
            String result = strategy.execute(context, agents, config);

            // 后置终止状态检查：记录策略是否认为应该终止（用于监控和调试）
            boolean terminated = strategy.shouldTerminate(context, config);
            logger.info("协同完成", Map.of(
                    "mode", config.getMode().name(),
                    "totalMessages", context.getHistory().size(),
                    "elapsedTime", context.getElapsedTime(),
                    "strategyTerminated", terminated
            ));

            // 通过回调输出协同结束事件
            if (callback != null) {
                callback.onEvent(StreamEvent.collaborateEnd(modeStr, result));
            }

            // 6. 构建并保存协同记录
            saveCollaborationRecord(context, config, result);

            // 7. 将协同结论回流到调用方的主会话（含 Artifact 摘要和质量指标）
            flowbackToCallerSession(context, config, result);

            // 8. 向 evolution 系统记录协同反馈
            recordCollaborationFeedback(context, config, true);

            return result;
        } catch (Exception e) {
            logger.error("协同执行失败", Map.of(
                    "mode", config.getMode().name(),
                    "error", e.getMessage()
            ));

            // 记录失败反馈
            recordCollaborationFeedback(context, config, false);

            // 优雅降级：协同失败时尝试单 Agent 模式
            if (config.isFallbackEnabled()) {
                logger.info("协同失败，启用优雅降级为单 Agent 模式", Map.of("error", e.getMessage()));
                try {
                    RoleAgent fallbackAgent = executionContext.createAgentExecutor(
                            AgentRole.of("Fallback", "你是一个通用助手。请直接回答用户的问题。"));
                    String fallbackResult = fallbackAgent.answer(userInput);
                    if (callback != null) {
                        callback.onEvent(StreamEvent.collaborateEnd("FALLBACK", fallbackResult));
                    }
                    return "[降级为单Agent模式] " + fallbackResult;
                } catch (Exception fallbackError) {
                    logger.error("降级执行也失败", Map.of("error", fallbackError.getMessage()));
                }
            }
            return "协同执行失败: " + e.getMessage();
        }
    }

    /**
     * 构建并保存协同记录到 workspace/collaboration/ 目录
     *
     * @param context 共享上下文
     * @param config  协同配置
     * @param result  协同结果
     */
    private void saveCollaborationRecord(SharedContext context, CollaborationConfig config, String result) {
        try {
            String sessionId = executionContext.getSessionId();
            String workspace = executionContext.getWorkspace();
            
            // 构建协同记录
            CollaborationRecord record = CollaborationRecord.fromContext(context, config, sessionId);
            record.setConclusion(result);
            record.setStatus(CollaborationRecord.Status.SUCCESS.name());
            
            // 保存到 workspace/collaboration/ 目录
            String collabDir = Paths.get(workspace, "collaboration").toString();
            String filePath = record.saveTo(collabDir);
            
            logger.info("协同记录已保存", Map.of(
                    "sessionId", sessionId,
                    "filePath", filePath
            ));
        } catch (Exception e) {
            // 保存失败只记录日志，不影响协同结果返回
            logger.error("保存协同记录失败", Map.of(
                    "sessionId", executionContext.getSessionId(),
                    "error", e.getMessage()
            ));
        }
    }
    
    /**
     * 将协同结论回流到调用方的主会话历史
     * 使协同结果在用户的会话历史中可见，提升用户体验
     *
     * @param config 协同配置
     * @param result 协同结论
     */
    private void flowbackToCallerSession(CollaborationConfig config, String result) {
        flowbackToCallerSession(null, config, result);
    }

    /**
     * 将协同结论回流到调用方的主会话历史（增强版）
     * <p>除了协同结论外，还回流 Artifact 摘要和质量指标，
     * 使主 Agent 后续能引用协同过程的细节。
     *
     * @param context 共享上下文（可为 null，降级时无上下文）
     * @param config  协同配置
     * @param result  协同结论
     */
    private void flowbackToCallerSession(SharedContext context, CollaborationConfig config, String result) {
        if (callerSessionManager == null) {
            return;
        }
        try {
            String sessionId = executionContext.getSessionId();
            String sessionKey = "collab:" + sessionId;

            StringBuilder flowbackContent = new StringBuilder();
            flowbackContent.append(String.format("【%s协同完成】目标: %s\n\n",
                    config.getMode().name(),
                    config.getGoal() != null ? config.getGoal() : "N/A"));

            // 回流协同结论
            flowbackContent.append(result);

            // 回流质量指标摘要（如果有）
            if (context != null) {
                long tokensUsed = context.getTotalTokensUsed();
                int rounds = context.getCurrentRound();
                int messageCount = context.getHistory().size();
                flowbackContent.append(String.format(
                        "\n\n【协同统计】轮次: %d, 消息数: %d, Token消耗: %d",
                        rounds, messageCount, tokensUsed));
            }

            callerSessionManager.addMessage(sessionKey, "assistant", flowbackContent.toString());

            logger.debug("协同结论已回流到会话", Map.of(
                    "sessionKey", sessionKey,
                    "resultLength", result.length()
            ));
        } catch (Exception e) {
            logger.warn("协同结论回流失败", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 向 evolution 系统记录协同反馈
     * 将协同过程的隐式指标（成功率、轮次、参与者数量等）反馈给 FeedbackManager，
     * 驱动 Agent 的自我进化
     *
     * @param context 共享上下文（可能为 null，如果协同在创建上下文前就失败）
     * @param config  协同配置
     * @param success 协同是否成功
     */
    private void recordCollaborationFeedback(SharedContext context, CollaborationConfig config, boolean success) {
        if (feedbackManager == null) {
            return;
        }
        try {
            String sessionKey = "collab:" + executionContext.getSessionId();

            // 记录工具调用结果（collaborate 工具的成功/失败）
            feedbackManager.recordToolResult(sessionKey, "collaborate", success);

            // 构建协同专属的隐式反馈
            double score = success ? 0.7 : 0.2;

            // 根据协同质量指标调整评分
            if (success && context != null) {
                int messageCount = context.getHistory().size();
                int totalRounds = context.getCurrentRound();

                // 有实质性的多轮对话说明协同质量较高
                if (messageCount >= 4 && totalRounds >= 2) {
                    score += 0.1;
                }
                // 协同在合理轮次内完成（未超时或达到上限）
                if (totalRounds < config.getMaxRounds()) {
                    score += 0.1;
                }
            }

            score = Math.max(0.0, Math.min(1.0, score));

            EvaluationFeedback feedback = EvaluationFeedback.builder()
                    .sessionKey(sessionKey)
                    .evalMode(EvaluationFeedback.EvalMode.IMPLICIT)
                    .primaryScore(score)
                    .sampleCount(1)
                    .build();

            feedback.putMetric("collaboration_mode", config.getMode().ordinal() / 10.0);
            feedback.putMetric("collaboration_success", success ? 1.0 : 0.0);

            if (context != null) {
                feedback.putMetric("message_count",
                        Math.min(1.0, context.getHistory().size() / 30.0));
                feedback.putMetric("round_count",
                        Math.min(1.0, context.getCurrentRound() / 10.0));
            }

            // 通过 recordToolResult 已经触发了 sessionMetrics 追踪，
            // 这里额外记录一条完整的协同反馈
            feedbackManager.recordMessageExchange(sessionKey);

            logger.debug("协同反馈已记录", Map.of(
                    "sessionKey", sessionKey,
                    "success", success,
                    "score", score
            ));
        } catch (Exception e) {
            logger.warn("记录协同反馈失败", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 为每个角色创建独立的Agent执行器
     */
    private List<RoleAgent> createAgents(CollaborationConfig config) {
        List<RoleAgent> agents = new ArrayList<>();
        
        List<AgentRole> roles = config.getRoles();
        if (roles == null || roles.isEmpty()) {
            // 对于分层决策，角色在 HierarchyConfig 中定义
            if (config.getMode() == CollaborationConfig.Mode.TASKS
                    && config.getTasksStyle() == CollaborationConfig.TasksStyle.HIERARCHY) {
                return agents;
            }
            return agents;
        }
        
        for (AgentRole role : roles) {
            // 使用 ExecutionContext 的工厂方法统一创建 RoleAgent
            RoleAgent executor = executionContext.createAgentExecutor(role);
            agents.add(executor);

            logger.debug("创建Agent", Map.of(
                    "roleId", role.getRoleId(),
                    "roleName", role.getRoleName()
            ));
        }
        
        return agents;
    }
    
    /**
     * 获取支持的协同模式列表
     */
    public List<String> getSupportedModes() {
        return Arrays.stream(CollaborationConfig.Mode.values())
                .map(Enum::name)
                .toList();
    }

    /**
     * 关闭编排器（释放资源）
     * 统一关闭公共线程池，无需逐个策略处理
     */
    public void shutdown() {
        executorPool.shutdown();
        logger.info("AgentOrchestrator已关闭");
    }
}