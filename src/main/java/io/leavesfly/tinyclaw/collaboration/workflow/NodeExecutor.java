package io.leavesfly.tinyclaw.collaboration.workflow;

import io.leavesfly.tinyclaw.collaboration.ExecutionContext;

/**
 * 节点执行器接口
 * 定义所有节点类型执行的统一接口
 */
public interface NodeExecutor {

    /**
     * 执行节点
     *
     * @param node             要执行的节点
     * @param result           节点结果对象
     * @param context          工作流上下文
     * @param executionContext 执行上下文
     */
    void execute(WorkflowNode node, NodeResult result, WorkflowContext context, ExecutionContext executionContext);
}
