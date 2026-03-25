package io.leavesfly.tinyclaw.agent.collaboration.workflow.executor;

import io.leavesfly.tinyclaw.agent.collaboration.RoleAgent;
import io.leavesfly.tinyclaw.agent.collaboration.AgentRole;
import io.leavesfly.tinyclaw.agent.collaboration.ExecutionContext;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.NodeExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.NodeResult;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowContext;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowNode;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;

/**
 * 聚合节点执行器
 * 使用 LLM 对依赖节点结果进行智能语义聚合或简单文本拼接
 */
public class AggregateNodeExecutor implements NodeExecutor {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");

    @Override
    public void execute(WorkflowNode node, NodeResult result, WorkflowContext context,
                        ExecutionContext executionContext) {
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
            result.markCompleted(rawResults.toString().trim());
            return;
        }

        AgentRole aggregatorRole = node.getAgents().get(0);
        RoleAgent aggregator = createAgentExecutor(aggregatorRole, executionContext);

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
