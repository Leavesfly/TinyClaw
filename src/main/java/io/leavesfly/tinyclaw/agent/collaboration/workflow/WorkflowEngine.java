package io.leavesfly.tinyclaw.agent.collaboration.workflow;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.agent.collaboration.RoleAgent;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowNode.NodeType;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.executor.*;
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

    /** 节点执行器策略映射 */
    private final Map<NodeType, NodeExecutor> nodeExecutors;

    public WorkflowEngine(CollaborationExecutorPool executorPool) {
        this.executor = executorPool.getExecutor();
        this.nodeExecutors = initializeNodeExecutors();
    }

    /**
     * 初始化节点执行器策略映射
     */
    private Map<NodeType, NodeExecutor> initializeNodeExecutors() {
        Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);
        executors.put(NodeType.SINGLE, new SingleNodeExecutor());
        executors.put(NodeType.PARALLEL, new ParallelNodeExecutor(executor));
        executors.put(NodeType.SEQUENTIAL, new SequentialNodeExecutor());
        executors.put(NodeType.CONDITIONAL, new ConditionalNodeExecutor());
        executors.put(NodeType.LOOP, new LoopNodeExecutor());
        executors.put(NodeType.AGGREGATE, new AggregateNodeExecutor());
        return executors;
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

        // 自反馈循环：由 Critic Agent 评估结果质量，不满意则重新生成
        if (workflow.getVariables().containsKey("selfReflectionEnabled")
                && Boolean.TRUE.equals(workflow.getVariables().get("selfReflectionEnabled"))) {
            int maxRetries = 2;
            Object retriesObj = workflow.getVariables().get("maxReflectionRetries");
            if (retriesObj instanceof Number) {
                maxRetries = ((Number) retriesObj).intValue();
            }
            output = applySelfReflection(output, sharedContext, executionContext, maxRetries);
        }

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

        // Human-in-the-Loop：检查节点是否需要人类审批
        if (node.isRequireApproval()) {
            ApprovalCallback approvalCallback = context.getSharedContext().getMeta("approvalCallback");
            if (approvalCallback != null) {
                String approvalDesc = node.getApprovalDescription() != null
                        ? node.getApprovalDescription()
                        : "节点 " + node.getId() + " 请求执行审批";
                ApprovalCallback.ApprovalContext approvalCtx = new ApprovalCallback.ApprovalContext(
                        node.getId(), approvalDesc, buildNodeInput(node, context), context.getSharedContext());
                ApprovalCallback.ApprovalResult approvalResult = approvalCallback.requestApproval(approvalCtx);
                if (!approvalResult.isApproved()) {
                    NodeResult rejected = new NodeResult(node.getId());
                    rejected.markSkipped("人类审批拒绝: " + approvalResult.getFeedback());
                    context.setNodeResult(node.getId(), rejected);
                    logger.info("节点被人类审批拒绝", Map.of("nodeId", node.getId(), "feedback", approvalResult.getFeedback()));
                    return;
                }
                logger.info("节点通过人类审批", Map.of("nodeId", node.getId()));
            } else {
                logger.warn("节点需要审批但未配置审批回调，跳过审批", Map.of("nodeId", node.getId()));
            }
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
        NodeExecutor executor = nodeExecutors.get(node.getType());
        if (executor != null) {
            executor.execute(node, result, context, executionContext);
        } else {
            result.markFailed("不支持的节点类型: " + node.getType());
        }
    }

    // -------------------------------------------------------------------------
    // 各类型节点执行逻辑（已迁移到 NodeExecutor 实现类）
    // -------------------------------------------------------------------------

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
     * 自反馈循环：由 Critic Agent 评估结果质量，不满意则重新生成
     */
    private String applySelfReflection(String currentOutput, SharedContext sharedContext,
                                       ExecutionContext executionContext, int maxRetries) {
        AgentRole criticRole = AgentRole.of("Critic",
                "你是一个严格的质量评审员。评估以下输出的质量，判断是否满足用户需求。\n"
                + "如果质量合格，回复 [PASS] 并简要说明理由。\n"
                + "如果质量不合格，回复 [FAIL] 并详细说明需要改进的地方。");
        RoleAgent criticAgent = executionContext.createAgentExecutor(criticRole);

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String evaluationPrompt = "【任务目标】" + sharedContext.getTopic() + "\n\n"
                    + "【待评估输出】\n" + currentOutput + "\n\n"
                    + "请评估以上输出的质量。";
            String evaluation = criticAgent.answer(evaluationPrompt);

            if (evaluation.contains("[PASS]")) {
                logger.info("自反馈评估通过", Map.of("attempt", attempt + 1));
                break;
            }

            logger.info("自反馈评估未通过，尝试改进", Map.of("attempt", attempt + 1));

            // 使用改进建议重新生成
            AgentRole improverRole = AgentRole.of("Improver",
                    "你是一个内容改进专家。根据评审意见改进以下内容。");
            RoleAgent improverAgent = executionContext.createAgentExecutor(improverRole);
            String improvePrompt = "【原始输出】\n" + currentOutput + "\n\n"
                    + "【评审意见】\n" + evaluation + "\n\n"
                    + "请根据评审意见改进原始输出，直接输出改进后的完整内容。";
            currentOutput = improverAgent.answer(improvePrompt);
        }

        return currentOutput;
    }

    /**
     * 创建 Agent 执行器，统一使用 ExecutionContext 的工厂方法
     */
    private RoleAgent createAgentExecutor(AgentRole role, ExecutionContext executionContext) {
        return executionContext.createAgentExecutor(role);
    }
}