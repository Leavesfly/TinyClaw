package io.leavesfly.tinyclaw.agent.collaboration.workflow;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.AgentRole;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationExecutorPool;
import io.leavesfly.tinyclaw.agent.collaboration.ExecutionContext;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.*;
import java.util.concurrent.*;

/**
 * Workflow 执行引擎
 * 解析和执行 WorkflowDefinition，支持各种节点类型
 */
public class WorkflowEngine {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");

    /** 默认层级并行超时（分钟） */
    private static final long DEFAULT_LAYER_TIMEOUT_MINUTES = 10;

    /** 重试基础等待时间（毫秒），指数退避基数 */
    private static final long RETRY_BASE_DELAY_MS = 500;

    /** 公共线程池（由 AgentOrchestrator 统一管理生命周期） */
    private final ExecutorService executor;

    public WorkflowEngine(CollaborationExecutorPool executorPool) {
        this.executor = executorPool.getExecutor();
    }

    /**
     * 执行完整的 Workflow
     */
    public String execute(WorkflowDefinition workflow, SharedContext sharedContext,
                          ExecutionContext executionContext) {

        // 验证工作流
        WorkflowDefinition.ValidationResult validation = workflow.validate();
        if (!validation.isValid()) {
            return "工作流定义无效: " + validation;
        }

        logger.info("开始执行工作流", Map.of(
                "name", workflow.getName() != null ? workflow.getName() : "unnamed",
                "nodeCount", workflow.getNodes().size()
        ));

        // 初始化执行上下文
        WorkflowContext context = new WorkflowContext(sharedContext, workflow.getVariables());

        // 构建 nodeId → WorkflowNode 映射，供条件分支跳过判断使用
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode node : workflow.getNodes()) {
            nodeMap.put(node.getId(), node);
        }

        // 拓扑排序获取执行顺序（循环依赖时抛出异常）
        List<List<WorkflowNode>> executionLayers = topologicalSort(workflow.getNodes());

        // 按层执行（同层可并行）
        for (List<WorkflowNode> layer : executionLayers) {
            // 检查全局超时
            if (workflow.getTimeoutMs() > 0 && context.getElapsedTime() > workflow.getTimeoutMs()) {
                logger.warn("工作流执行超时");
                break;
            }

            // 检查最大执行数
            if (context.getExecutedNodeCount() >= workflow.getMaxNodeExecutions()) {
                logger.warn("达到最大节点执行数");
                break;
            }

            executeLayer(layer, context, executionContext, nodeMap);
        }

        // 解析输出表达式
        String output = resolveOutput(workflow, context);

        logger.info("工作流执行完成", Map.of(
                "executedNodes", context.getExecutedNodeCount(),
                "elapsedTime", context.getElapsedTime() + "ms"
        ));

        return output;
    }

    // -------------------------------------------------------------------------
    // 拓扑排序
    // -------------------------------------------------------------------------

    /**
     * 拓扑排序，按依赖关系分层。
     * 同层内的节点可以并行执行。
     *
     * @throws IllegalStateException 当检测到循环依赖时
     */
    private List<List<WorkflowNode>> topologicalSort(List<WorkflowNode> nodes) {
        List<List<WorkflowNode>> layers = new ArrayList<>();
        Set<String> scheduled = new HashSet<>();
        List<WorkflowNode> remaining = new ArrayList<>(nodes);

        while (!remaining.isEmpty()) {
            List<WorkflowNode> currentLayer = new ArrayList<>();

            for (WorkflowNode node : remaining) {
                boolean allDepsScheduled = node.getDependsOn().stream()
                        .allMatch(scheduled::contains);
                if (allDepsScheduled) {
                    currentLayer.add(node);
                }
            }

            if (currentLayer.isEmpty()) {
                List<String> remainingIds = remaining.stream()
                        .map(WorkflowNode::getId)
                        .toList();
                throw new IllegalStateException(
                        "检测到循环依赖，无法调度以下节点: " + remainingIds);
            }

            for (WorkflowNode node : currentLayer) {
                scheduled.add(node.getId());
                remaining.remove(node);
            }

            layers.add(currentLayer);
        }

        return layers;
    }

    // -------------------------------------------------------------------------
    // 层级执行
    // -------------------------------------------------------------------------

    /**
     * 执行一层节点（同层并行）
     */
    private void executeLayer(List<WorkflowNode> layer, WorkflowContext context,
                               ExecutionContext executionContext, Map<String, WorkflowNode> nodeMap) {
        if (layer.size() == 1) {
            executeNodeWithRetry(layer.get(0), context, executionContext, nodeMap);
            return;
        }

        // 多节点并行执行
        List<CompletableFuture<Void>> futures = layer.stream()
                .map(node -> CompletableFuture.runAsync(
                        () -> executeNodeWithRetry(node, context, executionContext, nodeMap), executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(DEFAULT_LAYER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            logger.error("层级执行超时", Map.of("layerSize", layer.size()));
        } catch (Exception e) {
            logger.error("层级执行异常", Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // 节点执行（含重试 + 节点级超时）
    // -------------------------------------------------------------------------

    /**
     * 执行单个节点，支持指数退避重试和节点级超时控制
     */
    private void executeNodeWithRetry(WorkflowNode node, WorkflowContext context,
                                      ExecutionContext executionContext,
                                      Map<String, WorkflowNode> nodeMap) {
        // 检查是否被条件分支跳过（未激活的分支目标节点直接跳过）
        if (context.isNodeBranchSkipped(node, nodeMap)) {
            NodeResult skipped = new NodeResult(node.getId());
            skipped.markSkipped("未被激活的条件分支");
            context.setNodeResult(node.getId(), skipped);
            logger.info("节点被条件分支跳过", Map.of("nodeId", node.getId()));
            return;
        }

        // 检查依赖是否有失败
        if (context.hasFailedDependency(node)) {
            NodeResult skipped = new NodeResult(node.getId());
            skipped.markSkipped("依赖节点执行失败");
            context.setNodeResult(node.getId(), skipped);
            return;
        }

        int maxRetries = node.getMaxRetries();
        NodeResult result = new NodeResult(node.getId());

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                result.incrementRetry();
                long delayMs = RETRY_BASE_DELAY_MS * (1L << (attempt - 1)); // 指数退避
                logger.info("节点重试", Map.of(
                        "nodeId", node.getId(),
                        "attempt", attempt,
                        "delayMs", delayMs
                ));
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // 重置状态准备重试
                result = new NodeResult(node.getId());
            }

            result.markStarted();

            try {
                executeNodeOnce(node, result, context, executionContext);
            } catch (Exception e) {
                result.markFailed(e.getMessage());
                logger.error("节点执行异常", Map.of(
                        "nodeId", node.getId(),
                        "attempt", attempt,
                        "error", e.getMessage()
                ));
            }

            if (result.isSuccess() || result.getStatus() == NodeResult.Status.SKIPPED) {
                break; // 成功或跳过，不再重试
            }

            if (attempt == maxRetries && result.getStatus() == NodeResult.Status.FAILED) {
                logger.error("节点重试耗尽", Map.of(
                        "nodeId", node.getId(),
                        "totalAttempts", attempt + 1
                ));
            }
        }

        context.setNodeResult(node.getId(), result);

        // 记录到共享上下文
        if (result.isSuccess()) {
            context.getSharedContext().addMessage(
                    "workflow:" + node.getId(),
                    node.getName() != null ? node.getName() : node.getId(),
                    result.getResult()
            );
        }
    }

    /**
     * 执行节点一次，支持节点级超时（通过 CompletableFuture 包装）
     */
    private void executeNodeOnce(WorkflowNode node, NodeResult result,
                                  WorkflowContext context, ExecutionContext executionContext)
            throws Exception {

        logger.info("执行节点", Map.of(
                "nodeId", node.getId(),
                "type", node.getType().name(),
                "retryCount", result.getRetryCount()
        ));

        long nodeTimeoutMs = node.getTimeoutMs();

        if (nodeTimeoutMs > 0) {
            // 有节点级超时：用 Future 包装并限时等待
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> dispatchNodeType(node, result, context, executionContext), executor);
            try {
                future.get(nodeTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RuntimeException("节点执行超时 (" + nodeTimeoutMs + "ms): " + node.getId());
            }
        } else {
            dispatchNodeType(node, result, context, executionContext);
        }
    }

    /**
     * 根据节点类型分发执行
     */
    private void dispatchNodeType(WorkflowNode node, NodeResult result,
                                   WorkflowContext context, ExecutionContext executionContext) {
        switch (node.getType()) {
            case SINGLE -> executeSingleNode(node, result, context, executionContext);
            case PARALLEL -> executeParallelNode(node, result, context, executionContext);
            case SEQUENTIAL -> executeSequentialNode(node, result, context, executionContext);
            case CONDITIONAL -> executeConditionalNode(node, result, context, executionContext);
            case LOOP -> executeLoopNode(node, result, context, executionContext);
            case AGGREGATE -> executeAggregateNode(node, result, context, executionContext);
        }
    }

    // -------------------------------------------------------------------------
    // 各类型节点执行逻辑
    // -------------------------------------------------------------------------

    /**
     * 执行单 Agent 节点
     */
    private void executeSingleNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                    ExecutionContext executionContext) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("SINGLE节点未配置Agent");
            return;
        }

        AgentRole role = node.getAgents().get(0);
        AgentExecutor agentExecutor = createAgentExecutor(role, executionContext);

        String input = buildNodeInput(node, context);
        String response = agentExecutor.answer(input);

        result.addAgentResult(role.getRoleName(), response);
        result.markCompleted(response);
    }

    /**
     * 执行并行节点（多 Agent 同时执行）
     */
    private void executeParallelNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                      ExecutionContext executionContext) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("PARALLEL节点未配置Agent");
            return;
        }

        String input = buildNodeInput(node, context);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (AgentRole role : node.getAgents()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                AgentExecutor agentExecutor = createAgentExecutor(role, executionContext);
                String response = agentExecutor.answer(input);
                synchronized (result) {
                    result.addAgentResult(role.getRoleName(), response);
                }
            }, executor);
            futures.add(future);
        }

        // 优先使用节点级超时，否则退化为层级默认超时
        long parallelTimeoutMs = node.getTimeoutMs() > 0
                ? node.getTimeoutMs()
                : DEFAULT_LAYER_TIMEOUT_MINUTES * 60 * 1000;

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(parallelTimeoutMs, TimeUnit.MILLISECONDS);

            // 汇总结果
            StringBuilder combined = new StringBuilder();
            for (Map.Entry<String, String> entry : result.getAgentResults().entrySet()) {
                combined.append("【").append(entry.getKey()).append("】\n");
                combined.append(entry.getValue()).append("\n\n");
            }
            result.markCompleted(combined.toString().trim());

        } catch (Exception e) {
            result.markFailed("并行执行超时或失败: " + e.getMessage());
        }
    }

    /**
     * 执行顺序节点（多 Agent 依次执行，前一个输出作为下一个输入）
     */
    private void executeSequentialNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                        ExecutionContext executionContext) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("SEQUENTIAL节点未配置Agent");
            return;
        }

        String input = buildNodeInput(node, context);
        StringBuilder accumulated = new StringBuilder(input);

        for (AgentRole role : node.getAgents()) {
            AgentExecutor agentExecutor = createAgentExecutor(role, executionContext);
            String response = agentExecutor.answer(accumulated.toString());

            result.addAgentResult(role.getRoleName(), response);

            accumulated.append("\n\n【").append(role.getRoleName()).append("的输出】\n");
            accumulated.append(response);
        }

        String lastAgentName = node.getAgents().get(node.getAgents().size() - 1).getRoleName();
        result.markCompleted(result.getAgentResults().get(lastAgentName));
    }

    /**
     * 执行条件节点，支持多分支路由。
     *
     * <p>当节点配置了 {@code branches} 时，引擎将条件解析结果与分支 key 匹配，
     * 并在 WorkflowContext 中记录激活的目标节点 ID（变量名 {@code _branch_<nodeId>}），
     * 未匹配到任何分支时尝试匹配 "default" 分支，仍无匹配则跳过。
     *
     * <p>当节点未配置 {@code branches} 时，退化为简单的二值判断（向后兼容）。
     */
    private void executeConditionalNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                         ExecutionContext executionContext) {
        String condition = node.getCondition();
        if (condition == null || condition.isEmpty()) {
            result.markFailed("CONDITIONAL节点未配置条件表达式");
            return;
        }

        String resolvedValue = context.resolveExpression(condition).trim();
        Map<String, String> branches = node.getBranches();

        if (!branches.isEmpty()) {
            // 多分支路由模式
            String matchedBranch = branches.containsKey(resolvedValue)
                    ? resolvedValue
                    : (branches.containsKey("default") ? "default" : null);

            if (matchedBranch == null) {
                result.markSkipped("条件值 [" + resolvedValue + "] 未匹配任何分支");
                return;
            }

            String targetNodeId = branches.get(matchedBranch);
            // 将激活的分支目标写入上下文，供后续节点判断是否跳过
            context.setVariable("_branch_" + node.getId(), targetNodeId);

            logger.info("条件分支路由", Map.of(
                    "nodeId", node.getId(),
                    "conditionValue", resolvedValue,
                    "matchedBranch", matchedBranch,
                    "targetNode", targetNodeId
            ));

            result.markCompleted("条件路由至: " + targetNodeId);
        } else {
            // 简单二值判断模式（向后兼容）
            boolean conditionMet = !resolvedValue.isEmpty()
                    && !"false".equalsIgnoreCase(resolvedValue)
                    && !"0".equals(resolvedValue);

            if (conditionMet && !node.getAgents().isEmpty()) {
                executeSingleNode(node, result, context, executionContext);
            } else {
                result.markSkipped("条件不满足: " + condition);
            }
        }
    }

    /**
     * 执行循环节点，支持结构化 JSON 退出条件。
     *
     * <p>退出条件优先解析 LLM 返回的 JSON 格式 {@code {"continue": false}}，
     * 同时兼容旧的字面量 "true"/"done" 格式。
     */
    private void executeLoopNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                  ExecutionContext executionContext) {
        String condition = node.getCondition();
        int maxLoops = node.getConfig().containsKey("maxLoops")
                ? ((Number) node.getConfig().get("maxLoops")).intValue()
                : 5;

        int loopCount = 0;
        StringBuilder loopResults = new StringBuilder();

        while (loopCount < maxLoops) {
            loopCount++;

            if (!node.getAgents().isEmpty()) {
                AgentRole role = node.getAgents().get(0);
                AgentExecutor agentExecutor = createAgentExecutor(role, executionContext);
                String loopPrompt = buildNodeInput(node, context)
                        + "\n\n当前循环次数: " + loopCount + " / " + maxLoops
                        + "\n\n如果任务已完成，请在回复末尾附上 JSON: {\"continue\": false, \"reason\": \"完成原因\"}";
                String response = agentExecutor.answer(loopPrompt);

                loopResults.append("【循环").append(loopCount).append("】\n");
                loopResults.append(response).append("\n\n");

                context.setVariable("_loop_result", response);

                // 优先解析结构化 JSON 退出信号
                if (shouldExitLoop(response, condition, context)) {
                    logger.info("循环节点退出", Map.of(
                            "nodeId", node.getId(),
                            "loopCount", loopCount
                    ));
                    break;
                }
            } else {
                // 无 Agent 时仅检查条件表达式
                if (condition != null) {
                    String resolved = context.resolveExpression(condition);
                    if (isLoopExitSignal(resolved)) break;
                }
            }
        }

        result.markCompleted(loopResults.toString().trim());
    }

    /**
     * 判断是否应退出循环。
     * 优先解析 JSON {"continue": false}，兼容字面量 "true"/"done"。
     */
    private boolean shouldExitLoop(String response, String condition, WorkflowContext context) {
        // 1. 解析结构化 JSON 退出信号（从响应末尾查找最后一个 JSON 块）
        if (response != null) {
            int jsonStart = response.lastIndexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonPart = response.substring(jsonStart, jsonEnd + 1);
                if (jsonPart.contains("\"continue\"") &&
                        (jsonPart.contains("\"continue\": false") ||
                         jsonPart.contains("\"continue\":false"))) {
                    return true;
                }
            }
        }

        // 2. 兼容旧的字面量条件
        if (condition != null) {
            String resolved = context.resolveExpression(condition);
            return isLoopExitSignal(resolved);
        }

        return false;
    }

    private boolean isLoopExitSignal(String value) {
        return "true".equalsIgnoreCase(value) || "done".equalsIgnoreCase(value);
    }

    /**
     * 执行聚合节点。
     *
     * <p>当节点配置了 Agent 时，使用 LLM 对所有依赖节点的结果进行智能语义聚合（Reducer 模式）；
     * 否则退化为简单的文本拼接。
     */
    private void executeAggregateNode(WorkflowNode node, NodeResult result,
                                       WorkflowContext context, ExecutionContext executionContext) {
        // 收集所有依赖节点的结果
        StringBuilder rawResults = new StringBuilder();
        rawResults.append("=== 待聚合的各节点结果 ===\n\n");

        for (String depId : node.getDependsOn()) {
            NodeResult depResult = context.getNodeResult(depId);
            if (depResult != null && depResult.isSuccess()) {
                rawResults.append("【").append(depId).append("】\n");
                rawResults.append(depResult.getResult()).append("\n\n");
            }
        }

        if (node.getAgents().isEmpty()) {
            // 无 Agent：简单拼接
            result.markCompleted(rawResults.toString().trim());
            return;
        }

        // 有 Agent：LLM 智能聚合（Reducer 模式）
        AgentRole aggregatorRole = node.getAgents().get(0);
        AgentExecutor aggregator = createAgentExecutor(aggregatorRole, executionContext);

        String aggregationPrompt = buildNodeInput(node, context)
                + "\n\n" + rawResults
                + "\n请对以上各方结果进行综合分析，去除重复内容，提炼关键信息，给出统一的最终结论。";

        String aggregatedResult = aggregator.answer(aggregationPrompt);

        result.addAgentResult(aggregatorRole.getRoleName(), aggregatedResult);
        result.markCompleted(aggregatedResult);

        logger.info("聚合节点完成（LLM 智能聚合）", Map.of(
                "nodeId", node.getId(),
                "dependencyCount", node.getDependsOn().size()
        ));
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    /**
     * 构建节点输入
     */
    private String buildNodeInput(WorkflowNode node, WorkflowContext context) {
        StringBuilder input = new StringBuilder();

        if (context.getSharedContext().getTopic() != null) {
            input.append("【任务目标】").append(context.getSharedContext().getTopic()).append("\n\n");
        }

        if (context.getSharedContext().getUserInput() != null) {
            input.append("【用户需求】").append(context.getSharedContext().getUserInput()).append("\n\n");
        }

        String depInput = context.buildDependencyInput(node);
        if (!depInput.isEmpty()) {
            input.append(depInput);
        }

        if (node.getInputExpression() != null) {
            String resolved = context.resolveExpression(node.getInputExpression());
            if (!resolved.isEmpty()) {
                input.append("\n").append(resolved);
            }
        }

        return input.toString();
    }

    /**
     * 解析输出表达式
     */
    private String resolveOutput(WorkflowDefinition workflow, WorkflowContext context) {
        String outputExpr = workflow.getOutputExpression();

        if (outputExpr != null && !outputExpr.isEmpty()) {
            return context.resolveExpression(outputExpr);
        }

        // 默认返回最后完成节点的结果
        List<WorkflowNode> terminals = workflow.getTerminalNodes();
        if (!terminals.isEmpty()) {
            NodeResult result = context.getNodeResult(terminals.get(0).getId());
            if (result != null && result.isSuccess()) {
                return result.getResult();
            }
        }

        return "工作流执行完成，但没有输出结果";
    }

    /**
     * 创建 Agent 执行器，使用 ExecutionContext 中的共享 SessionManager
     */
    private AgentExecutor createAgentExecutor(AgentRole role, ExecutionContext executionContext) {
        return new AgentExecutor(role,
                executionContext.getProvider(),
                executionContext.getTools(),
                executionContext.getSharedSessionManager(),
                executionContext.getModel(),
                executionContext.getMaxIterations());
    }
}
