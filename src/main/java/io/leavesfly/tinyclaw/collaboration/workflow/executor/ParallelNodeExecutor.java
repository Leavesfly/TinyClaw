package io.leavesfly.tinyclaw.collaboration.workflow.executor;

import io.leavesfly.tinyclaw.collaboration.RoleAgent;
import io.leavesfly.tinyclaw.collaboration.AgentRole;
import io.leavesfly.tinyclaw.collaboration.ExecutionContext;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeExecutor;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeResult;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowContext;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowNode;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 并行节点执行器
 * 多 Agent 同时执行，并行处理任务
 */
public class ParallelNodeExecutor implements NodeExecutor {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");
    private static final long DEFAULT_LAYER_TIMEOUT_MINUTES = 10;

    private final ExecutorService executor;

    public ParallelNodeExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void execute(WorkflowNode node, NodeResult result, WorkflowContext context,
                        ExecutionContext executionContext) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("PARALLEL节点未配置Agent");
            return;
        }

        String input = buildNodeInput(node, context);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (AgentRole role : node.getAgents()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                RoleAgent roleAgent = createAgentExecutor(role, executionContext);
                String response = roleAgent.answer(input);
                synchronized (result) {
                    result.addAgentResult(role.getRoleName(), response);
                }
            }, executor);
            futures.add(future);
        }

        long parallelTimeoutMs = node.getTimeoutMs() > 0
                ? node.getTimeoutMs()
                : DEFAULT_LAYER_TIMEOUT_MINUTES * 60 * 1000;

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(parallelTimeoutMs, TimeUnit.MILLISECONDS);

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

    private RoleAgent createAgentExecutor(AgentRole role, ExecutionContext executionContext) {
        return executionContext.createAgentExecutor(role);
    }

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
}
