package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.agent.collaboration.strategy.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

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

    /** 策略映射 */
    private final Map<CollaborationConfig.Mode, CollaborationStrategy> strategies;

    public AgentOrchestrator(LLMProvider provider, ToolRegistry tools, String workspace,
                              String model, int maxIterations) {
        this.executionContext = new ExecutionContext(provider, tools, workspace, model, maxIterations);
        this.executorPool = new CollaborationExecutorPool();
        this.strategies = new EnumMap<>(CollaborationConfig.Mode.class);
        initStrategies();
    }

    /**
     * 初始化所有协同策略
     * 所有策略通过构造函数注入依赖，无需额外的 setXxx 调用
     */
    private void initStrategies() {
        DiscussionStrategy discussionStrategy = new DiscussionStrategy();
        strategies.put(CollaborationConfig.Mode.DEBATE, discussionStrategy);
        strategies.put(CollaborationConfig.Mode.ROLEPLAY, discussionStrategy);
        strategies.put(CollaborationConfig.Mode.CONSENSUS, discussionStrategy);

        strategies.put(CollaborationConfig.Mode.TEAM, new TeamWorkStrategy(executorPool));
        strategies.put(CollaborationConfig.Mode.HIERARCHY, new HierarchyStrategy(executionContext, executorPool));
        strategies.put(CollaborationConfig.Mode.WORKFLOW, new WorkflowStrategy(executionContext, executorPool));
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
        
        // 2. 根据角色配置创建Agent执行器
        List<AgentExecutor> agents = createAgents(config);
        
        if (agents.isEmpty() && config.getMode() != CollaborationConfig.Mode.HIERARCHY) {
            return "未配置参与角色，无法启动协同";
        }
        
        // 3. 获取对应策略
        CollaborationStrategy strategy = strategies.get(config.getMode());
        if (strategy == null) {
            return "不支持的协同模式: " + config.getMode();
        }
        
        // 4. 全局超时前置检查：在策略执行前拦截已超时的请求
        if (config.getTimeoutMs() > 0 && context.getElapsedTime() > config.getTimeoutMs()) {
            logger.warn("协同启动前已超时，跳过执行", Map.of("mode", modeStr));
            return "协同超时，未能执行";
        }

        // 5. 执行协同流程
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

            return result;
        } catch (Exception e) {
            logger.error("协同执行失败", Map.of(
                    "mode", config.getMode().name(),
                    "error", e.getMessage()
            ));
            return "协同执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 为每个角色创建独立的Agent执行器
     */
    private List<AgentExecutor> createAgents(CollaborationConfig config) {
        List<AgentExecutor> agents = new ArrayList<>();
        
        List<AgentRole> roles = config.getRoles();
        if (roles == null || roles.isEmpty()) {
            // 对于分层决策，角色在HierarchyConfig中定义
            if (config.getMode() == CollaborationConfig.Mode.HIERARCHY) {
                return agents; // 返回空列表，HierarchyStrategy会自己创建
            }
            return agents;
        }
        
        for (AgentRole role : roles) {
            AgentExecutor executor = new AgentExecutor(role,
                    executionContext.getProvider(),
                    executionContext.getTools(),
                    executionContext.getSharedSessionManager(),
                    executionContext.getModel(),
                    executionContext.getMaxIterations());
            agents.add(executor);

            logger.debug("创建Agent", Map.of(
                    "roleId", role.getRoleId(),
                    "roleName", role.getRoleName()
            ));
        }
        
        return agents;
    }
    
    /**
     * 便捷方法：启动辩论
     */
    public String debate(String topic, List<AgentRole> roles, int maxRounds) {
        CollaborationConfig config = CollaborationConfig.debate(topic, maxRounds);
        for (AgentRole role : roles) {
            config.addRole(role);
        }
        return orchestrate(config, topic);
    }

    /**
     * 便捷方法：启动团队协作
     */
    public String teamWork(String goal, List<TeamTask> tasks) {
        CollaborationConfig config = CollaborationConfig.teamWork(goal);
        for (TeamTask task : tasks) {
            config.addTask(task);
            // 自动添加任务负责人作为角色
            if (task.getAssignee() != null) {
                config.addRole(task.getAssignee());
            }
        }
        return orchestrate(config, goal);
    }

    /**
     * 便捷方法：启动分层决策
     */
    public String hierarchyDecision(String topic, HierarchyConfig hierarchy) {
        CollaborationConfig config = CollaborationConfig.hierarchy(topic, hierarchy);
        return orchestrate(config, topic);
    }

    /**
     * 便捷方法：启动共识决策
     */
    public String consensus(String topic, List<AgentRole> roles, double threshold) {
        CollaborationConfig config = CollaborationConfig.consensus(topic, threshold);
        for (AgentRole role : roles) {
            config.addRole(role);
        }
        return orchestrate(config, topic);
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
