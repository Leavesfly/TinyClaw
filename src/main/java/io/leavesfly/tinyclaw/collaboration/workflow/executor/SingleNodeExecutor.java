package io.leavesfly.tinyclaw.collaboration.workflow.executor;

import io.leavesfly.tinyclaw.collaboration.RoleAgent;
import io.leavesfly.tinyclaw.collaboration.AgentRole;
import io.leavesfly.tinyclaw.collaboration.ExecutionContext;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeExecutor;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeResult;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowContext;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowNode;

/**
 * 单 Agent 节点执行器
 * 执行单个 Agent 的任务
 */
public class SingleNodeExecutor implements NodeExecutor {

    @Override
    public void execute(WorkflowNode node, NodeResult result, WorkflowContext context,
                        ExecutionContext executionContext) {
        if (node.getAgents().isEmpty()) {
            result.markFailed("SINGLE节点未配置Agent");
            return;
        }

        AgentRole role = node.getAgents().get(0);
        RoleAgent roleAgent = createAgentExecutor(role, executionContext);

        String input = buildNodeInput(node, context);
        String response = roleAgent.answer(input);

        result.addAgentResult(role.getRoleName(), response);
        result.markCompleted(response);
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
