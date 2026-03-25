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
 * 条件节点执行器
 * 支持多分支路由和简单二值判断
 */
public class ConditionalNodeExecutor implements NodeExecutor {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");

    @Override
    public void execute(WorkflowNode node, NodeResult result, WorkflowContext context,
                        ExecutionContext executionContext) {
        String condition = node.getCondition();
        if (condition == null || condition.isEmpty()) {
            result.markFailed("CONDITIONAL节点未配置条件表达式");
            return;
        }

        String resolvedValue = context.resolveExpression(condition).trim();
        Map<String, String> branches = node.getBranches();

        if (!branches.isEmpty()) {
            String matchedBranch = branches.containsKey(resolvedValue)
                    ? resolvedValue
                    : (branches.containsKey("default") ? "default" : null);

            if (matchedBranch == null) {
                result.markSkipped("条件值 [" + resolvedValue + "] 未匹配任何分支");
                return;
            }

            String targetNodeId = branches.get(matchedBranch);
            context.setVariable("_branch_" + node.getId(), targetNodeId);

            logger.info("条件分支路由", Map.of(
                    "nodeId", node.getId(),
                    "conditionValue", resolvedValue,
                    "matchedBranch", matchedBranch,
                    "targetNode", targetNodeId
            ));

            result.markCompleted("条件路由至: " + targetNodeId);
        } else {
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

    private void executeSingleNode(WorkflowNode node, NodeResult result, WorkflowContext context,
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
