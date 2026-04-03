package io.leavesfly.tinyclaw.collaboration.workflow.executor;

import io.leavesfly.tinyclaw.collaboration.RoleAgent;
import io.leavesfly.tinyclaw.collaboration.AgentRole;
import io.leavesfly.tinyclaw.collaboration.ExecutionContext;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeExecutor;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeResult;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowContext;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowNode;

/**
 * 顺序节点执行器
 * 多 Agent 依次执行，前一个输出作为下一个输入
 */
public class SequentialNodeExecutor implements NodeExecutor {

    @Override
    public void execute(WorkflowNode node, NodeResult result, WorkflowContext context,
                        ExecutionContext executionContext) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("SEQUENTIAL节点未配置Agent");
            return;
        }

        String input = buildNodeInput(node, context);
        StringBuilder accumulated = new StringBuilder(input);

        for (AgentRole role : node.getAgents()) {
            RoleAgent roleAgent = createAgentExecutor(role, executionContext);
            String response = roleAgent.answer(accumulated.toString());

            result.addAgentResult(role.getRoleName(), response);

            accumulated.append("\n\n【").append(role.getRoleName()).append("的输出】\n");
            accumulated.append(response);
        }

        String lastAgentName = node.getAgents().get(node.getAgents().size() - 1).getRoleName();
        result.markCompleted(result.getAgentResults().get(lastAgentName));
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
