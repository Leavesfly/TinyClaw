package io.leavesfly.tinyclaw.agent.collaboration.workflow;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.AgentRole;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.*;
import java.util.concurrent.*;

/**
 * Workflow 执行引擎
 * 解析和执行 WorkflowDefinition，支持各种节点类型
 */
public class WorkflowEngine {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");
    
    /** 线程池 */
    private final ExecutorService executor;
    
    public WorkflowEngine() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("workflow-engine-" + t.getId());
            return t;
        });
    }
    
    /**
     * 执行完整的 Workflow
     */
    public String execute(WorkflowDefinition workflow, SharedContext sharedContext,
                          LLMProvider provider, ToolRegistry tools,
                          String workspace, String model, int maxIterations) {
        
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
        
        // 拓扑排序获取执行顺序
        List<List<WorkflowNode>> executionLayers = topologicalSort(workflow.getNodes());
        
        // 按层执行（同层可并行）
        for (List<WorkflowNode> layer : executionLayers) {
            // 检查超时
            if (workflow.getTimeoutMs() > 0 && context.getElapsedTime() > workflow.getTimeoutMs()) {
                logger.warn("工作流执行超时");
                break;
            }
            
            // 检查最大执行数
            if (context.getExecutedNodeCount() >= workflow.getMaxNodeExecutions()) {
                logger.warn("达到最大节点执行数");
                break;
            }
            
            // 执行当前层的节点
            executeLayer(layer, context, provider, tools, workspace, model, maxIterations);
        }
        
        // 解析输出表达式
        String output = resolveOutput(workflow, context);
        
        logger.info("工作流执行完成", Map.of(
                "executedNodes", context.getExecutedNodeCount(),
                "elapsedTime", context.getElapsedTime() + "ms"
        ));
        
        return output;
    }
    
    /**
     * 拓扑排序，按依赖关系分层
     * 返回的每层内的节点可以并行执行
     */
    private List<List<WorkflowNode>> topologicalSort(List<WorkflowNode> nodes) {
        List<List<WorkflowNode>> layers = new ArrayList<>();
        Set<String> executed = new HashSet<>();
        List<WorkflowNode> remaining = new ArrayList<>(nodes);
        
        while (!remaining.isEmpty()) {
            List<WorkflowNode> currentLayer = new ArrayList<>();
            
            for (WorkflowNode node : remaining) {
                // 检查所有依赖是否已在前面的层中
                boolean canExecute = true;
                for (String depId : node.getDependsOn()) {
                    if (!executed.contains(depId)) {
                        canExecute = false;
                        break;
                    }
                }
                
                if (canExecute) {
                    currentLayer.add(node);
                }
            }
            
            if (currentLayer.isEmpty()) {
                // 可能存在循环依赖
                logger.error("检测到循环依赖或无法调度的节点");
                break;
            }
            
            // 记录已调度的节点
            for (WorkflowNode node : currentLayer) {
                executed.add(node.getId());
                remaining.remove(node);
            }
            
            layers.add(currentLayer);
        }
        
        return layers;
    }
    
    /**
     * 执行一层节点（同层并行）
     */
    private void executeLayer(List<WorkflowNode> layer, WorkflowContext context,
                               LLMProvider provider, ToolRegistry tools,
                               String workspace, String model, int maxIterations) {
        
        if (layer.size() == 1) {
            // 单节点直接执行
            executeNode(layer.get(0), context, provider, tools, workspace, model, maxIterations);
        } else {
            // 多节点并行执行
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (WorkflowNode node : layer) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    executeNode(node, context, provider, tools, workspace, model, maxIterations);
                }, executor);
                futures.add(future);
            }
            
            // 等待所有节点完成
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.MINUTES);
            } catch (Exception e) {
                logger.error("层执行失败", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 执行单个节点
     */
    private void executeNode(WorkflowNode node, WorkflowContext context,
                              LLMProvider provider, ToolRegistry tools,
                              String workspace, String model, int maxIterations) {
        
        // 检查依赖是否有失败
        if (context.hasFailedDependency(node)) {
            NodeResult result = new NodeResult(node.getId());
            result.markSkipped("依赖节点执行失败");
            context.setNodeResult(node.getId(), result);
            return;
        }
        
        logger.info("执行节点", Map.of(
                "nodeId", node.getId(),
                "type", node.getType().name()
        ));
        
        NodeResult result = new NodeResult(node.getId());
        result.markStarted();
        
        try {
            switch (node.getType()) {
                case SINGLE -> executeSingleNode(node, result, context, provider, tools, workspace, model, maxIterations);
                case PARALLEL -> executeParallelNode(node, result, context, provider, tools, workspace, model, maxIterations);
                case SEQUENTIAL -> executeSequentialNode(node, result, context, provider, tools, workspace, model, maxIterations);
                case CONDITIONAL -> executeConditionalNode(node, result, context, provider, tools, workspace, model, maxIterations);
                case LOOP -> executeLoopNode(node, result, context, provider, tools, workspace, model, maxIterations);
                case AGGREGATE -> executeAggregateNode(node, result, context);
            }
        } catch (Exception e) {
            result.markFailed(e.getMessage());
            logger.error("节点执行异常", Map.of("nodeId", node.getId(), "error", e.getMessage()));
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
     * 执行单Agent节点
     */
    private void executeSingleNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                    LLMProvider provider, ToolRegistry tools,
                                    String workspace, String model, int maxIterations) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("SINGLE节点未配置Agent");
            return;
        }
        
        AgentRole role = node.getAgents().get(0);
        AgentExecutor executor = new AgentExecutor(role, provider, tools, workspace, model, maxIterations);
        
        // 构建输入
        String input = buildNodeInput(node, context);
        String response = executor.answer(input);
        
        result.addAgentResult(role.getRoleName(), response);
        result.markCompleted(response);
    }
    
    /**
     * 执行并行节点（多Agent同时执行）
     */
    private void executeParallelNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                      LLMProvider provider, ToolRegistry tools,
                                      String workspace, String model, int maxIterations) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("PARALLEL节点未配置Agent");
            return;
        }
        
        String input = buildNodeInput(node, context);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (AgentRole role : node.getAgents()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                AgentExecutor executor = new AgentExecutor(role, provider, tools, workspace, model, maxIterations);
                String response = executor.answer(input);
                synchronized (result) {
                    result.addAgentResult(role.getRoleName(), response);
                }
            }, executor);
            futures.add(future);
        }
        
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.MINUTES);
            
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
     * 执行顺序节点（多Agent依次执行）
     */
    private void executeSequentialNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                        LLMProvider provider, ToolRegistry tools,
                                        String workspace, String model, int maxIterations) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("SEQUENTIAL节点未配置Agent");
            return;
        }
        
        String input = buildNodeInput(node, context);
        StringBuilder accumulated = new StringBuilder(input);
        
        for (AgentRole role : node.getAgents()) {
            AgentExecutor executor = new AgentExecutor(role, provider, tools, workspace, model, maxIterations);
            String response = executor.answer(accumulated.toString());
            
            result.addAgentResult(role.getRoleName(), response);
            
            // 将结果追加到下一个Agent的输入
            accumulated.append("\n\n【").append(role.getRoleName()).append("的输出】\n");
            accumulated.append(response);
        }
        
        // 最后一个Agent的结果作为节点结果
        String lastAgent = node.getAgents().get(node.getAgents().size() - 1).getRoleName();
        result.markCompleted(result.getAgentResults().get(lastAgent));
    }
    
    /**
     * 执行条件节点
     */
    private void executeConditionalNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                         LLMProvider provider, ToolRegistry tools,
                                         String workspace, String model, int maxIterations) {
        String condition = node.getCondition();
        if (condition == null || condition.isEmpty()) {
            result.markFailed("CONDITIONAL节点未配置条件表达式");
            return;
        }
        
        // 解析条件表达式
        String resolvedCondition = context.resolveExpression(condition);
        
        // 简单条件判断：非空且不为"false"/"0"即为真
        boolean conditionMet = resolvedCondition != null 
                && !resolvedCondition.isEmpty()
                && !"false".equalsIgnoreCase(resolvedCondition)
                && !"0".equals(resolvedCondition);
        
        if (conditionMet && !node.getAgents().isEmpty()) {
            // 条件满足，执行Agent
            executeSingleNode(node, result, context, provider, tools, workspace, model, maxIterations);
        } else {
            result.markSkipped("条件不满足: " + condition);
        }
    }
    
    /**
     * 执行循环节点
     */
    private void executeLoopNode(WorkflowNode node, NodeResult result, WorkflowContext context,
                                  LLMProvider provider, ToolRegistry tools,
                                  String workspace, String model, int maxIterations) {
        String condition = node.getCondition();
        int maxLoops = node.getConfig().containsKey("maxLoops") 
                ? ((Number) node.getConfig().get("maxLoops")).intValue() 
                : 5;
        
        int loopCount = 0;
        StringBuilder loopResults = new StringBuilder();
        
        while (loopCount < maxLoops) {
            loopCount++;
            
            // 执行循环体
            if (!node.getAgents().isEmpty()) {
                AgentRole role = node.getAgents().get(0);
                AgentExecutor executor = new AgentExecutor(role, provider, tools, workspace, model, maxIterations);
                String input = buildNodeInput(node, context) + "\n\n当前循环次数: " + loopCount;
                String response = executor.answer(input);
                
                loopResults.append("【循环").append(loopCount).append("】\n");
                loopResults.append(response).append("\n\n");
                
                // 临时存储结果供条件判断
                context.setVariable("_loop_result", response);
            }
            
            // 检查退出条件
            if (condition != null) {
                String resolvedCondition = context.resolveExpression(condition);
                if ("true".equalsIgnoreCase(resolvedCondition) || "done".equalsIgnoreCase(resolvedCondition)) {
                    break;
                }
            }
        }
        
        result.markCompleted(loopResults.toString().trim());
    }
    
    /**
     * 执行聚合节点
     */
    private void executeAggregateNode(WorkflowNode node, NodeResult result, WorkflowContext context) {
        StringBuilder aggregated = new StringBuilder();
        aggregated.append("=== 聚合结果 ===\n\n");
        
        for (String depId : node.getDependsOn()) {
            NodeResult depResult = context.getNodeResult(depId);
            if (depResult != null && depResult.isSuccess()) {
                aggregated.append("【").append(depId).append("】\n");
                aggregated.append(depResult.getResult()).append("\n\n");
            }
        }
        
        result.markCompleted(aggregated.toString().trim());
    }
    
    /**
     * 构建节点输入
     */
    private String buildNodeInput(WorkflowNode node, WorkflowContext context) {
        StringBuilder input = new StringBuilder();
        
        // 添加工作流目标
        if (context.getSharedContext().getTopic() != null) {
            input.append("【任务目标】").append(context.getSharedContext().getTopic()).append("\n\n");
        }
        
        // 添加用户原始输入
        if (context.getSharedContext().getUserInput() != null) {
            input.append("【用户需求】").append(context.getSharedContext().getUserInput()).append("\n\n");
        }
        
        // 添加依赖节点结果
        String depInput = context.buildDependencyInput(node);
        if (!depInput.isEmpty()) {
            input.append(depInput);
        }
        
        // 解析输入表达式
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
     * 关闭引擎
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
