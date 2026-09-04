package io.leavesfly.tinyclaw.collaboration.workflow;

import io.leavesfly.tinyclaw.collaboration.AgentRole;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.NodeStatus;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.TopoNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 {@link WorkflowDefinition} 构建 DAG 拓扑快照。
 *
 * <h2>为什么单独放在 workflow 包</h2>
 * <p>分层结果直接复用 {@link WorkflowEngine#topologicalSort}，而它是包级可见的
 * （刻意收窄的 API，仅供引擎与测试使用）。把本类放在同包内即可复用，
 * 无需为了画图把它放宽成 public，也不必在别处重写一份分层逻辑——
 * 两份实现一旦分叉，图上看到的层序就会和引擎实际执行的层序不一致。</p>
 *
 * <h2>状态来源</h2>
 * <p>节点执行状态取自 {@link WorkflowContext#getNodeResults()}。该上下文由
 * {@code WorkflowEngine} 在执行末尾透出到 {@code SharedContext} 的 meta 中，
 * 因此这里拿到的是最终状态（含 FAILED / SKIPPED），而非执行前的全 PENDING。</p>
 */
public final class WorkflowTopologyBuilder {

    /** 节点类型标签的最大长度，超出截断，避免图上的节点框被撑破 */
    private static final int MAX_LABEL_LENGTH = 28;

    private WorkflowTopologyBuilder() {
    }

    /**
     * 构建 DAG 拓扑。
     *
     * @param definition      工作流定义，为 {@code null} 时返回 {@code null}
     * @param workflowContext 执行期上下文，可为 {@code null}（此时所有节点状态为 PENDING）
     * @return 拓扑快照，定义无效时返回 {@code null}
     */
    public static CollaborationTopology from(WorkflowDefinition definition,
                                             WorkflowContext workflowContext) {
        if (definition == null || definition.getNodes() == null || definition.getNodes().isEmpty()) {
            return null;
        }

        CollaborationTopology topology = new CollaborationTopology(CollaborationTopology.Kind.DAG);

        for (WorkflowNode node : definition.getNodes()) {
            topology.addNode(buildNode(node, workflowContext));
        }

        for (WorkflowNode node : definition.getNodes()) {
            if (node.getDependsOn() == null) {
                continue;
            }
            for (String dep : node.getDependsOn()) {
                // 依赖了不存在的节点时仍然画边：这种图恰恰需要让人看见断链在哪
                topology.addEdge(dep, node.getId(), null, 1);
            }
        }

        topology.setLayers(buildLayers(definition.getNodes()));
        attachMeta(topology, definition, workflowContext);

        return topology.isRenderable() ? topology : null;
    }

    /**
     * 构建单个节点。
     */
    private static TopoNode buildNode(WorkflowNode node, WorkflowContext workflowContext) {
        String label = node.getName() != null && !node.getName().isBlank()
                ? node.getName() : node.getId();

        TopoNode topoNode = new TopoNode(
                node.getId(),
                truncate(label),
                node.getType() != null ? node.getType().name() : "SINGLE",
                resolveStatus(node.getId(), workflowContext));

        List<String> agentNames = new ArrayList<>();
        if (node.getAgents() != null) {
            for (AgentRole role : node.getAgents()) {
                if (role != null && role.getRoleName() != null) {
                    agentNames.add(role.getRoleName());
                }
            }
        }
        topoNode.withAgents(agentNames);
        topoNode.withDetail(buildDetail(node));

        return topoNode;
    }

    /**
     * 从执行期上下文解析节点状态。
     *
     * <p>上下文缺失或该节点没有结果时返回 PENDING：工作流可能在校验阶段就被拒，
     * 此时结构仍值得画出来，只是所有节点都还没跑。</p>
     */
    private static NodeStatus resolveStatus(String nodeId, WorkflowContext workflowContext) {
        if (workflowContext == null) {
            return NodeStatus.PENDING;
        }
        NodeResult result = workflowContext.getNodeResult(nodeId);
        if (result == null || result.getStatus() == null) {
            return NodeStatus.PENDING;
        }
        return switch (result.getStatus()) {
            case RUNNING -> NodeStatus.RUNNING;
            case COMPLETED -> NodeStatus.COMPLETED;
            case FAILED -> NodeStatus.FAILED;
            case SKIPPED -> NodeStatus.SKIPPED;
            case PENDING -> NodeStatus.PENDING;
        };
    }

    /**
     * 拼装节点详情：条件、分支、输入表达式，以及参与角色的提示词首行。
     */
    private static String buildDetail(WorkflowNode node) {
        StringBuilder detail = new StringBuilder();

        if (node.getCondition() != null && !node.getCondition().isBlank()) {
            detail.append("条件: ").append(node.getCondition().strip()).append('\n');
        }
        Map<String, String> branches = node.getBranches();
        if (branches != null && !branches.isEmpty()) {
            detail.append("分支: ").append(branches).append('\n');
        }
        if (node.getInputExpression() != null && !node.getInputExpression().isBlank()) {
            detail.append("输入: ").append(node.getInputExpression().strip()).append('\n');
        }
        if (node.isRequireApproval()) {
            detail.append("需人工审批\n");
        }
        if (node.getAgents() != null) {
            for (AgentRole role : node.getAgents()) {
                if (role == null || role.getSystemPrompt() == null) {
                    continue;
                }
                String prompt = role.getSystemPrompt().strip();
                int newline = prompt.indexOf('\n');
                String firstLine = newline > 0 ? prompt.substring(0, newline) : prompt;
                detail.append(role.getRoleName()).append(": ").append(firstLine.strip()).append('\n');
            }
        }

        return detail.length() == 0 ? null : detail.toString().strip();
    }

    /**
     * 复用引擎的拓扑排序得到分层。
     *
     * <p>排序在存在环时会抛异常。此时返回 {@code null} 而不是让整张图失败：
     * 有环的工作流虽然跑不起来，但把它的结构画出来正是定位环在哪的最快方式，
     * 前端会退化为环形布局。</p>
     */
    private static List<List<String>> buildLayers(List<WorkflowNode> nodes) {
        try {
            List<List<WorkflowNode>> sorted = WorkflowEngine.topologicalSort(nodes);
            List<List<String>> layers = new ArrayList<>(sorted.size());
            for (List<WorkflowNode> layer : sorted) {
                List<String> ids = new ArrayList<>(layer.size());
                for (WorkflowNode node : layer) {
                    ids.add(node.getId());
                }
                layers.add(ids);
            }
            return layers.isEmpty() ? null : layers;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 附加工作流级别的元信息，供前端在图上方展示概要。
     */
    private static void attachMeta(CollaborationTopology topology, WorkflowDefinition definition,
                                   WorkflowContext workflowContext) {
        if (definition.getName() != null) {
            topology.putMeta("workflowName", definition.getName());
        }
        if (definition.getDescription() != null) {
            topology.putMeta("description", definition.getDescription());
        }
        if (definition.getOutputExpression() != null) {
            topology.putMeta("outputExpression", definition.getOutputExpression());
        }
        topology.putMeta("nodeCount", definition.getNodes().size());
        if (definition.getTimeoutMs() > 0) {
            topology.putMeta("timeoutMs", definition.getTimeoutMs());
        }
        if (workflowContext != null) {
            topology.putMeta("executedNodes", workflowContext.getExecutedNodeCount());
            topology.putMeta("elapsedMs", workflowContext.getElapsedTime());
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= MAX_LABEL_LENGTH ? trimmed : trimmed.substring(0, MAX_LABEL_LENGTH) + "…";
    }
}
