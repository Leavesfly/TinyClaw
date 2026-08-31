package io.leavesfly.tinyclaw.collaboration.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流检查点测试。
 *
 * <p>三条核心语义：runId 必须可复现（否则续跑永远不命中）、快照只带已完成节点、
 * 恢复后已完成节点被跳过。</p>
 */
class WorkflowCheckpointTest {

    // ==================== runId 稳定性 ====================

    @Test
    void runIdIsStableAcrossIdenticalDefinitions() {
        String first = WorkflowCheckpointStore.deriveRunId(sampleWorkflow());
        String second = WorkflowCheckpointStore.deriveRunId(sampleWorkflow());

        assertEquals(first, second, "相同定义必须得到相同 runId，否则检查点永远读不到");
    }

    @Test
    void runIdIgnoresRuntimeVariables() {
        WorkflowDefinition withVars = sampleWorkflow();
        withVars.getVariables().put("contextSummary", "本轮对话摘要，每次都不同");

        assertEquals(WorkflowCheckpointStore.deriveRunId(sampleWorkflow()),
                WorkflowCheckpointStore.deriveRunId(withVars),
                "运行期注入的变量不应影响运行标识");
    }

    @Test
    void runIdIsIndependentOfNodeOrder() {
        WorkflowDefinition reordered = new WorkflowDefinition();
        reordered.setName("demo");
        reordered.setOutputExpression("${b.result}");
        reordered.setNodes(List.of(node("b", "a"), node("a")));

        assertEquals(WorkflowCheckpointStore.deriveRunId(sampleWorkflow()),
                WorkflowCheckpointStore.deriveRunId(reordered),
                "节点列表顺序不同但结构相同，应视为同一次运行");
    }

    @Test
    void runIdDiffersForDifferentStructures() {
        WorkflowDefinition other = sampleWorkflow();
        other.setNodes(List.of(node("a"), node("c", "a")));

        assertNotEquals(WorkflowCheckpointStore.deriveRunId(sampleWorkflow()),
                WorkflowCheckpointStore.deriveRunId(other));
    }

    // ==================== 快照与恢复 ====================

    @Test
    void snapshotKeepsOnlyFinishedNodes() {
        WorkflowContext context = new WorkflowContext(null, Map.of("k", "v"));
        context.setNodeResult("done", completed("done", "结果"));
        context.setNodeResult("running", running("running"));

        WorkflowCheckpoint snapshot = context.snapshot("run-1", "demo");

        assertEquals(1, snapshot.completedNodeCount());
        assertTrue(snapshot.getNodeResults().containsKey("done"));
        assertFalse(snapshot.getNodeResults().containsKey("running"),
                "进程退出后 RUNNING 节点并未真的在跑，不能当已完成存下来");
        assertEquals("v", snapshot.getVariables().get("k"));
    }

    @Test
    void restoreSkipsUnfinishedNodesFromCheckpoint() {
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint("run-1", "demo",
                Map.of(), Map.of("done", completed("done", "r"), "running", running("running")));

        WorkflowContext context = new WorkflowContext(null, Map.of());
        int restored = context.restoreFrom(checkpoint);

        assertEquals(1, restored);
        assertTrue(context.isNodeCompleted("done"));
        assertFalse(context.isNodeCompleted("running"), "未完成节点应重跑");
        assertEquals(1, context.getExecutedNodeCount());
    }

    @Test
    void restoreDoesNotOverrideCurrentVariables() {
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint("run-1", "demo",
                Map.of("mode", "旧值", "onlyInCheckpoint", "保留"), Map.of());

        WorkflowContext context = new WorkflowContext(null, Map.of("mode", "新值"));
        context.restoreFrom(checkpoint);

        assertEquals("新值", context.getVariable("mode"), "当前调用方的意图优先");
        assertEquals("保留", context.getVariable("onlyInCheckpoint"));
    }

    @Test
    void restoreFromNullIsNoop() {
        WorkflowContext context = new WorkflowContext(null, Map.of());
        assertEquals(0, context.restoreFrom(null));
    }

    // ==================== 存储 ====================

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        WorkflowCheckpointStore store = new WorkflowCheckpointStore(dir.toString());
        WorkflowContext context = new WorkflowContext(null, Map.of("k", "v"));
        NodeResult result = completed("a", "节点 a 的产出");
        result.getAgentResults().put("研究员", "研究结论");
        context.setNodeResult("a", result);

        store.save(context.snapshot("run-1", "demo"));
        WorkflowCheckpoint loaded = store.load("run-1");

        assertNotNull(loaded);
        assertEquals("run-1", loaded.getRunId());
        assertEquals("demo", loaded.getWorkflowName());
        assertEquals("v", loaded.getVariables().get("k"));
        NodeResult restored = loaded.getNodeResults().get("a");
        assertNotNull(restored);
        assertEquals(NodeResult.Status.COMPLETED, restored.getStatus());
        assertEquals("节点 a 的产出", restored.getResult());
        assertEquals("研究结论", restored.getAgentResults().get("研究员"));
    }

    @Test
    void loadReturnsNullWhenAbsent(@TempDir Path dir) {
        assertNull(new WorkflowCheckpointStore(dir.toString()).load("never-saved"));
    }

    @Test
    void deleteRemovesCheckpoint(@TempDir Path dir) {
        WorkflowCheckpointStore store = new WorkflowCheckpointStore(dir.toString());
        store.save(new WorkflowCheckpoint("run-1", "demo", Map.of(), Map.of()));
        assertNotNull(store.load("run-1"));

        store.delete("run-1");

        assertNull(store.load("run-1"));
    }

    @Test
    void corruptCheckpointIsQuarantinedAndTreatedAsAbsent(@TempDir Path dir) throws Exception {
        WorkflowCheckpointStore store = new WorkflowCheckpointStore(dir.toString());
        store.save(new WorkflowCheckpoint("run-1", "demo", Map.of(), Map.of()));

        Path checkpointFile = dir.resolve("collaboration").resolve("checkpoints").resolve("run-1.json");
        Files.writeString(checkpointFile, "{ this is not json");

        assertNull(store.load("run-1"), "坏掉的检查点不应阻断工作流执行");
        assertFalse(Files.exists(checkpointFile), "原文件应被移入 corrupt/ 而不是留在原地");
    }

    @Test
    void storeWithoutWorkspaceIsDisabledButSafe() {
        WorkflowCheckpointStore store = new WorkflowCheckpointStore(null);

        assertFalse(store.isEnabled());
        assertNull(store.load("run-1"));
        assertDoesNotThrow(() -> store.save(new WorkflowCheckpoint("run-1", "d", Map.of(), Map.of())));
        assertDoesNotThrow(() -> store.delete("run-1"));
    }

    // ==================== 辅助构造 ====================

    private WorkflowDefinition sampleWorkflow() {
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setName("demo");
        workflow.setOutputExpression("${b.result}");
        workflow.setNodes(List.of(node("a"), node("b", "a")));
        return workflow;
    }

    private WorkflowNode node(String id, String... dependsOn) {
        WorkflowNode node = new WorkflowNode(id, WorkflowNode.NodeType.SINGLE);
        node.setDependsOn(List.of(dependsOn));
        return node;
    }

    private NodeResult completed(String nodeId, String result) {
        NodeResult node = new NodeResult(nodeId);
        node.markStarted();
        node.markCompleted(result);
        return node;
    }

    private NodeResult running(String nodeId) {
        NodeResult node = new NodeResult(nodeId);
        node.markStarted();
        return node;
    }
}
