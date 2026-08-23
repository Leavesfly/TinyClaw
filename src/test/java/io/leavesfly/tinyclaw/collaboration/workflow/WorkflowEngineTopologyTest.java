package io.leavesfly.tinyclaw.collaboration.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowEngine#topologicalSort(List)} 测试。
 *
 * <p>分层决定了节点的执行顺序与并行度。分错层不会报错：依赖还没产出结果，
 * 下游节点就已经带着空输入跑掉了，最终只是「结果不对」。</p>
 */
class WorkflowEngineTopologyTest {

    private static WorkflowNode node(String id, String... dependsOn) {
        return new WorkflowNode(id, WorkflowNode.NodeType.SINGLE).dependsOn(dependsOn);
    }

    /** 把分层结果转成便于断言的 ID 列表。 */
    private static List<List<String>> idsOf(List<List<WorkflowNode>> layers) {
        List<List<String>> result = new ArrayList<>();
        for (List<WorkflowNode> layer : layers) {
            result.add(layer.stream().map(WorkflowNode::getId).sorted().toList());
        }
        return result;
    }

    // ==================== 分层 ====================

    @Test
    @DisplayName("无依赖的节点全部落在第一层，可并行")
    void independentNodes_ShareOneLayer() {
        List<List<WorkflowNode>> layers =
                WorkflowEngine.topologicalSort(List.of(node("a"), node("b"), node("c")));

        assertEquals(List.of(List.of("a", "b", "c")), idsOf(layers));
    }

    @Test
    @DisplayName("链式依赖逐层展开")
    void chainedDependencies_FormSequentialLayers() {
        List<List<WorkflowNode>> layers = WorkflowEngine.topologicalSort(
                List.of(node("c", "b"), node("a"), node("b", "a")));

        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")), idsOf(layers));
    }

    @Test
    @DisplayName("菱形依赖：中间两节点同层并行，汇聚节点单独一层")
    void diamondDependencies_ParallelMiddleLayer() {
        List<List<WorkflowNode>> layers = WorkflowEngine.topologicalSort(List.of(
                node("start"),
                node("left", "start"),
                node("right", "start"),
                node("join", "left", "right")));

        assertEquals(List.of(
                List.of("start"),
                List.of("left", "right"),
                List.of("join")), idsOf(layers));
    }

    @Test
    @DisplayName("节点必须排在其所有依赖之后：这是分层唯一不能违反的性质")
    void everyNode_IsScheduledAfterAllItsDependencies() {
        List<WorkflowNode> nodes = List.of(
                node("f", "d", "e"),
                node("a"),
                node("d", "b"),
                node("b", "a"),
                node("e", "a"),
                node("c"));

        List<List<WorkflowNode>> layers = WorkflowEngine.topologicalSort(nodes);

        // 记录每个节点所在层号，再逐条依赖校验层号严格递增
        Map<String, Integer> layerOf = new HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            for (WorkflowNode n : layers.get(i)) {
                layerOf.put(n.getId(), i);
            }
        }
        assertEquals(nodes.size(), layerOf.size(), "每个节点都必须被排入某一层");
        for (WorkflowNode n : nodes) {
            for (String dep : n.getDependsOn()) {
                assertTrue(layerOf.get(dep) < layerOf.get(n.getId()),
                        n.getId() + " 排在了依赖 " + dep + " 的同层或更早层");
            }
        }
    }

    @Test
    @DisplayName("互不相关的两条链并行推进，层数取决于最长链")
    void disjointChains_AdvanceInParallel() {
        List<List<WorkflowNode>> layers = WorkflowEngine.topologicalSort(List.of(
                node("a1"), node("a2", "a1"), node("a3", "a2"),
                node("b1"), node("b2", "b1")));

        assertEquals(3, layers.size(), "层数应等于最长链的长度");
        assertEquals(List.of(
                List.of("a1", "b1"),
                List.of("a2", "b2"),
                List.of("a3")), idsOf(layers));
    }

    @Test
    @DisplayName("空节点列表得到空分层，不抛异常")
    void emptyInput_YieldsNoLayers() {
        assertTrue(WorkflowEngine.topologicalSort(List.of()).isEmpty());
    }

    @Test
    @DisplayName("重复声明同一依赖不影响分层")
    void duplicateDependency_IsHarmless() {
        WorkflowNode b = node("b", "a", "a");

        List<List<WorkflowNode>> layers = WorkflowEngine.topologicalSort(List.of(node("a"), b));

        assertEquals(List.of(List.of("a"), List.of("b")), idsOf(layers));
    }

    // ==================== 环检测 ====================

    @Test
    @DisplayName("互相依赖的两节点被判定为循环依赖")
    void mutualDependency_IsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WorkflowEngine.topologicalSort(List.of(node("a", "b"), node("b", "a"))));

        assertTrue(e.getMessage().contains("循环依赖"));
        assertTrue(e.getMessage().contains("a") && e.getMessage().contains("b"),
                "错误信息需列出无法调度的节点以便定位，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("自依赖节点被判定为循环依赖")
    void selfDependency_IsRejected() {
        assertThrows(IllegalStateException.class,
                () -> WorkflowEngine.topologicalSort(List.of(node("a", "a"))));
    }

    @Test
    @DisplayName("三节点环被判定为循环依赖")
    void threeNodeCycle_IsRejected() {
        assertThrows(IllegalStateException.class, () -> WorkflowEngine.topologicalSort(
                List.of(node("a", "c"), node("b", "a"), node("c", "b"))));
    }

    @Test
    @DisplayName("健康节点与环共存时整个工作流仍被拒绝，健康部分不会被部分执行")
    void cycleAlongsideHealthyNodes_RejectsWholeWorkflow() {
        // 这类定义能通过 WorkflowDefinition.validate()（存在入口节点 ok），
        // 直到分层阶段才被拦下，因此失败信息来自运行期而非校验期
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WorkflowEngine.topologicalSort(
                        List.of(node("ok"), node("x", "y"), node("y", "x"))));

        assertTrue(e.getMessage().contains("x") && e.getMessage().contains("y"));
        assertFalse(e.getMessage().contains("ok"),
                "已成功调度的节点不应出现在无法调度列表里，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("依赖一个不存在的节点同样无法调度")
    void missingDependency_CannotBeScheduled() {
        // 正常路径由 WorkflowDefinition.validate() 提前报出「依赖的节点不存在」，
        // 分层阶段只能笼统归为无法调度
        assertThrows(IllegalStateException.class,
                () -> WorkflowEngine.topologicalSort(List.of(node("a", "ghost"))));
    }
}
