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
 * 循环节点执行器
 * 支持结构化 JSON 退出条件和字面量条件
 */
public class LoopNodeExecutor implements NodeExecutor {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");

    @Override
    public void execute(WorkflowNode node, NodeResult result, WorkflowContext context,
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
                RoleAgent roleAgent = createAgentExecutor(role, executionContext);
                String loopPrompt = buildNodeInput(node, context)
                        + "\n\n当前循环次数: " + loopCount + " / " + maxLoops
                        + "\n\n如果任务已完成，请在回复末尾附上 JSON: {\"continue\": false, \"reason\": \"完成原因\"}";
                String response = roleAgent.answer(loopPrompt);

                loopResults.append("【循环").append(loopCount).append("】\n");
                loopResults.append(response).append("\n\n");

                context.setVariable("_loop_result", response);

                if (shouldExitLoop(response, condition, context)) {
                    logger.info("循环节点退出", Map.of(
                            "nodeId", node.getId(),
                            "loopCount", loopCount
                    ));
                    break;
                }
            } else {
                if (condition != null) {
                    String resolved = context.resolveExpression(condition);
                    if (isLoopExitSignal(resolved)) break;
                }
            }
        }

        result.markCompleted(loopResults.toString().trim());
    }

    private boolean shouldExitLoop(String response, String condition, WorkflowContext context) {
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

        if (condition != null) {
            String resolved = context.resolveExpression(condition);
            return isLoopExitSignal(resolved);
        }

        return false;
    }

    private boolean isLoopExitSignal(String value) {
        return "true".equalsIgnoreCase(value) || "done".equalsIgnoreCase(value);
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
