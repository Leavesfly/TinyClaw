package io.leavesfly.tinyclaw.collaboration.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowDefinition#validate()} 测试。
 *
 * <p>validate() 是执行前唯一的闸门：`WorkflowStrategy` 与 `WorkflowEngine` 都先调它，
 * 不通过就直接返回错误文案。工作流定义多由 LLM 生成，字段缺失、ID 重复、依赖写错都是常态，
 * 这里放过去的问题最终会变成运行期异常或空结果。</p>
 */
class WorkflowDefinitionTest {

    private static WorkflowNode node(String id, String... dependsOn) {
        return new WorkflowNode(id, WorkflowNode.NodeType.SINGLE).dependsOn(dependsOn);
    }

    // ==================== 通过 ====================

    @Test
    @DisplayName("单个无依赖节点是合法工作流")
    void validate_SingleEntryNode_IsValid() {
        WorkflowDefinition wf = new WorkflowDefinition("w").addNode(node("a"));

        WorkflowDefinition.ValidationResult result = wf.validate();

        assertTrue(result.isValid(), "实际错误：" + result.getErrors());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("依赖齐全的多节点工作流合法")
    void validate_ResolvedDependencies_IsValid() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("a"))
                .addNode(node("b", "a"))
                .addNode(node("c", "a", "b"));

        assertTrue(wf.validate().isValid());
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("没有任何节点时判定无效")
    void validate_NoNodes_IsInvalid() {
        WorkflowDefinition.ValidationResult result = new WorkflowDefinition("w").validate();

        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size(), "空工作流应短路返回，不再累积其他错误");
        assertTrue(result.getErrors().get(0).contains("没有定义任何节点"));
    }

    @Test
    @DisplayName("节点 ID 缺失时判定无效")
    void validate_MissingNodeId_IsInvalid() {
        WorkflowDefinition wf = new WorkflowDefinition("w").addNode(node("a"));
        wf.addNode(new WorkflowNode(null, WorkflowNode.NodeType.SINGLE));

        WorkflowDefinition.ValidationResult result = wf.validate();

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("未设置ID")),
                "实际错误：" + result.getErrors());
    }

    @Test
    @DisplayName("节点 ID 为空串时判定无效")
    void validate_EmptyNodeId_IsInvalid() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("a"))
                .addNode(new WorkflowNode("", WorkflowNode.NodeType.SINGLE));

        assertFalse(wf.validate().isValid());
    }

    @Test
    @DisplayName("节点 ID 重复时判定无效：重复 ID 会让依赖指向不确定的节点")
    void validate_DuplicateNodeId_IsInvalid() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("dup"))
                .addNode(node("dup"));

        WorkflowDefinition.ValidationResult result = wf.validate();

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("节点ID重复: dup")),
                "实际错误：" + result.getErrors());
    }

    @Test
    @DisplayName("重复 ID 只报一次，不按出现次数刷屏")
    void validate_DuplicateNodeId_ReportedOnce() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("dup"))
                .addNode(node("dup"))
                .addNode(node("dup"));

        long count = wf.validate().getErrors().stream()
                .filter(e -> e.contains("节点ID重复")).count();

        assertEquals(1, count);
    }

    @Test
    @DisplayName("依赖了不存在的节点时判定无效，并指出是谁依赖了谁")
    void validate_UnknownDependency_IsInvalid() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("a"))
                .addNode(node("b", "ghost"));

        WorkflowDefinition.ValidationResult result = wf.validate();

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                        .anyMatch(e -> e.contains("b") && e.contains("ghost")),
                "错误信息需同时给出来源节点与缺失依赖，实际：" + result.getErrors());
    }

    @Test
    @DisplayName("所有节点都有依赖时判定无效：没有入口节点意味着无从开始")
    void validate_NoEntryNode_IsInvalid() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("a", "b"))
                .addNode(node("b", "a"));

        WorkflowDefinition.ValidationResult result = wf.validate();

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("没有入口节点")),
                "实际错误：" + result.getErrors());
    }

    @Test
    @DisplayName("多个问题同时存在时一次全部报出，不是只报第一个")
    void validate_ReportsAllProblemsAtOnce() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("dup"))
                .addNode(node("dup"))
                .addNode(node("x", "ghost"));

        List<String> errors = wf.validate().getErrors();

        assertTrue(errors.size() >= 2, "应同时报出 ID 重复与依赖缺失，实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("重复")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ghost")));
    }

    // ==================== 校验漏网的一类环 ====================

    @Test
    @DisplayName("环与入口节点共存时 validate 判定为合法，环要到分层阶段才被发现")
    void validate_CycleCoexistingWithEntryNode_PassesValidation() {
        // 存在入口节点 ok，因此「没有入口节点」这条检查不触发，
        // x↔y 的环被放过；真正拦下它的是 WorkflowEngine 的拓扑分层。
        // 这里固定当前行为：validate() 并非完整的环检测，别把它当成唯一防线。
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("ok"))
                .addNode(node("x", "y"))
                .addNode(node("y", "x"));

        assertTrue(wf.validate().isValid(),
                "若此断言失败说明 validate() 已能检出该类环，属于行为增强，请同步更新本测试");

        assertThrowsCycle(wf.getNodes());
    }

    private static void assertThrowsCycle(List<WorkflowNode> nodes) {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> WorkflowEngine.topologicalSort(nodes));
        assertTrue(e.getMessage().contains("循环依赖"));
    }

    // ==================== 查询与构建 ====================

    @Test
    @DisplayName("getNode 按 ID 查找，未命中返回 null")
    void getNode_ReturnsMatchOrNull() {
        WorkflowDefinition wf = new WorkflowDefinition("w").addNode(node("a"));

        assertNotNull(wf.getNode("a"));
        assertNull(wf.getNode("nosuch"));
    }

    @Test
    @DisplayName("getEntryNodes 只返回无依赖的节点")
    void getEntryNodes_ReturnsOnlyDependencyFreeNodes() {
        WorkflowDefinition wf = new WorkflowDefinition("w")
                .addNode(node("a"))
                .addNode(node("b"))
                .addNode(node("c", "a"));

        List<String> entryIds = wf.getEntryNodes().stream().map(WorkflowNode::getId).sorted().toList();

        assertEquals(List.of("a", "b"), entryIds);
    }

    @Test
    @DisplayName("setVariable 写入的变量可被读回，供表达式解析使用")
    void setVariable_IsReadable() {
        WorkflowDefinition wf = new WorkflowDefinition("w").setVariable("topic", "选题");

        assertEquals("选题", wf.getVariables().get("topic"));
    }
}
