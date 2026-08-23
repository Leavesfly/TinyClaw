package io.leavesfly.tinyclaw.collaboration.workflow;

import io.leavesfly.tinyclaw.collaboration.SharedContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowContext} 测试。
 *
 * <p>这是工作流的数据管道：节点的输入靠它解析表达式拼出来，节点该不该跑靠它判断依赖。
 * 这里出错不会抛异常，只会让某个节点拿到空输入或被悄悄跳过，最终表现为「结果不对但没报错」。</p>
 */
class WorkflowContextTest {

    private WorkflowContext context;

    @BeforeEach
    void setUp() {
        context = new WorkflowContext(new SharedContext(), new HashMap<>());
    }

    /** 造一个已完成并带结果的节点结果。 */
    private static NodeResult completed(String nodeId, String result) {
        NodeResult r = new NodeResult(nodeId);
        r.markCompleted(result);
        return r;
    }

    // ==================== 变量 ====================

    @Nested
    @DisplayName("变量")
    class Variables {

        @Test
        @DisplayName("null 值以空字符串存入：ConcurrentHashMap 不接受 null，否则并行节点写入即崩")
        void setVariable_NullBecomesEmptyString() {
            context.setVariable("k", null);

            assertEquals("", context.getVariable("k"));
        }

        @Test
        @DisplayName("初始变量里的 null 同样被替换，不会在构造时抛 NPE")
        void constructor_NullInitialValueIsTolerated() {
            Map<String, Object> initial = new HashMap<>();
            initial.put("a", null);
            initial.put("b", "v");

            WorkflowContext ctx = new WorkflowContext(new SharedContext(), initial);

            assertEquals("", ctx.getVariable("a"));
            assertEquals("v", ctx.getVariable("b"));
        }

        @Test
        @DisplayName("initialVariables 为 null 时不抛异常")
        void constructor_NullMapIsTolerated() {
            WorkflowContext ctx = new WorkflowContext(new SharedContext(), null);

            assertNull(ctx.getVariable("anything"));
        }
    }

    // ==================== 表达式解析 ====================

    @Nested
    @DisplayName("表达式解析")
    class ExpressionResolution {

        @Test
        @DisplayName("解析 ${nodeId.result} 与 ${variables.key}")
        void resolveExpression_NodeResultAndVariable() {
            context.setNodeResult("a", completed("a", "结果A"));
            context.setVariable("topic", "选题");

            assertEquals("前置：结果A，主题：选题",
                    context.resolveExpression("前置：${a.result}，主题：${variables.topic}"));
        }

        @Test
        @DisplayName("省略 .result 时直接取节点结果")
        void resolveExpression_BareNodeIdYieldsResult() {
            context.setNodeResult("a", completed("a", "结果A"));

            assertEquals("结果A", context.resolveExpression("${a}"));
        }

        @Test
        @DisplayName("可解析 status / error / agentResults.<agent>")
        void resolveExpression_SupportsStatusErrorAndAgentResults() {
            NodeResult failed = new NodeResult("bad");
            failed.markFailed("超时");
            context.setNodeResult("bad", failed);

            NodeResult multi = new NodeResult("multi");
            multi.addAgentResult("评审", "通过");
            multi.markCompleted("汇总");
            context.setNodeResult("multi", multi);

            assertEquals("FAILED", context.resolveExpression("${bad.status}"));
            assertEquals("超时", context.resolveExpression("${bad.error}"));
            assertEquals("通过", context.resolveExpression("${multi.agentResults.评审}"));
        }

        @Test
        @DisplayName("未知节点或未知变量解析为空串，不残留 ${} 占位符")
        void resolveExpression_UnknownReferencesBecomeEmpty() {
            assertEquals("值=", context.resolveExpression("值=${nosuch.result}"));
            assertEquals("值=", context.resolveExpression("值=${variables.nosuch}"));
        }

        @Test
        @DisplayName("节点结果里的 $ 与反斜杠按字面量替换，不被当成正则替换语法")
        void resolveExpression_TreatsResultAsLiteral() {
            // 若实现漏了 Matcher.quoteReplacement，$1 会被当作分组引用、\ 会被当作转义，
            // 结果是要么抛异常要么静默吞掉字符——LLM 输出里出现 $ 和 \ 是常事
            context.setNodeResult("a", completed("a", "价格 $100 与 $1 以及 C:\\path"));

            assertEquals("价格 $100 与 $1 以及 C:\\path", context.resolveExpression("${a.result}"));
        }

        @Test
        @DisplayName("空串与 null 表达式返回空串")
        void resolveExpression_NullOrEmpty() {
            assertEquals("", context.resolveExpression(null));
            assertEquals("", context.resolveExpression(""));
        }

        @Test
        @DisplayName("不含占位符的文本原样返回")
        void resolveExpression_PlainTextUnchanged() {
            assertEquals("没有占位符", context.resolveExpression("没有占位符"));
        }

        @Test
        @DisplayName("同一表达式里的多个占位符全部替换")
        void resolveExpression_ReplacesAllPlaceholders() {
            context.setNodeResult("a", completed("a", "A"));
            context.setNodeResult("b", completed("b", "B"));

            assertEquals("A-B-A", context.resolveExpression("${a}-${b}-${a.result}"));
        }
    }

    // ==================== 依赖判定 ====================

    @Nested
    @DisplayName("依赖判定")
    class Dependencies {

        @Test
        @DisplayName("无依赖节点视为依赖已满足")
        void areDependenciesCompleted_NoDependencies() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE);

            assertTrue(context.areDependenciesCompleted(node));
        }

        @Test
        @DisplayName("依赖只要有一个未完成就不算满足")
        void areDependenciesCompleted_RequiresAll() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("a", "b");
            context.setNodeResult("a", completed("a", "A"));

            assertFalse(context.areDependenciesCompleted(node));

            context.setNodeResult("b", completed("b", "B"));
            assertTrue(context.areDependenciesCompleted(node));
        }

        @Test
        @DisplayName("RUNNING 的依赖不算已完成")
        void areDependenciesCompleted_RunningIsNotFinished() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("a");
            NodeResult running = new NodeResult("a");
            running.markStarted();
            context.setNodeResult("a", running);

            assertFalse(context.areDependenciesCompleted(node));
        }

        @Test
        @DisplayName("失败与跳过都算已完成：否则失败分支会让后继永远等下去")
        void areDependenciesCompleted_FailedAndSkippedCount() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("a", "b");
            NodeResult failed = new NodeResult("a");
            failed.markFailed("boom");
            NodeResult skipped = new NodeResult("b");
            skipped.markSkipped("未激活");
            context.setNodeResult("a", failed);
            context.setNodeResult("b", skipped);

            assertTrue(context.areDependenciesCompleted(node));
        }

        @Test
        @DisplayName("hasFailedDependency 只认 FAILED，跳过不算失败")
        void hasFailedDependency_OnlyCountsFailed() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("a");
            NodeResult skipped = new NodeResult("a");
            skipped.markSkipped("未激活");
            context.setNodeResult("a", skipped);

            assertFalse(context.hasFailedDependency(node));

            NodeResult failed = new NodeResult("a");
            failed.markFailed("boom");
            context.setNodeResult("a", failed);
            assertTrue(context.hasFailedDependency(node));
        }

        @Test
        @DisplayName("buildDependencyInput 只拼接成功依赖的产出，失败依赖不污染输入")
        void buildDependencyInput_SkipsUnsuccessfulDependencies() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("ok", "bad");
            context.setNodeResult("ok", completed("ok", "有用内容"));
            NodeResult failed = new NodeResult("bad");
            failed.markFailed("超时");
            context.setNodeResult("bad", failed);

            String input = context.buildDependencyInput(node);

            assertTrue(input.contains("有用内容"));
            assertFalse(input.contains("超时"), "失败依赖的错误信息不应混进下游节点的输入");
        }

        @Test
        @DisplayName("无依赖时 buildDependencyInput 返回空串，不带表头")
        void buildDependencyInput_NoDependenciesYieldsEmpty() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE);

            assertEquals("", context.buildDependencyInput(node));
        }
    }

    // ==================== 条件分支跳过 ====================

    @Nested
    @DisplayName("条件分支跳过")
    class BranchSkipping {

        private Map<String, WorkflowNode> nodesOf(WorkflowNode... nodes) {
            Map<String, WorkflowNode> map = new HashMap<>();
            for (WorkflowNode n : nodes) {
                map.put(n.getId(), n);
            }
            return map;
        }

        @Test
        @DisplayName("前置不是 CONDITIONAL 时不跳过")
        void isNodeBranchSkipped_NonConditionalDependency() {
            WorkflowNode dep = new WorkflowNode("dep", WorkflowNode.NodeType.SINGLE);
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("dep");

            assertFalse(context.isNodeBranchSkipped(node, nodesOf(dep, node)));
        }

        @Test
        @DisplayName("CONDITIONAL 前置未配置分支时不跳过")
        void isNodeBranchSkipped_ConditionalWithoutBranches() {
            WorkflowNode cond = new WorkflowNode("c", WorkflowNode.NodeType.CONDITIONAL);
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("c");

            assertFalse(context.isNodeBranchSkipped(node, nodesOf(cond, node)));
        }

        @Test
        @DisplayName("被激活的分支目标不跳过，其他分支目标跳过")
        void isNodeBranchSkipped_OnlyActivatedTargetRuns() {
            WorkflowNode cond = new WorkflowNode("c", WorkflowNode.NodeType.CONDITIONAL);
            cond.addBranch("yes", "hit").addBranch("no", "miss");
            WorkflowNode hit = new WorkflowNode("hit", WorkflowNode.NodeType.SINGLE).dependsOn("c");
            WorkflowNode miss = new WorkflowNode("miss", WorkflowNode.NodeType.SINGLE).dependsOn("c");
            context.setVariable("_branch_c", "hit");

            Map<String, WorkflowNode> all = nodesOf(cond, hit, miss);
            assertFalse(context.isNodeBranchSkipped(hit, all));
            assertTrue(context.isNodeBranchSkipped(miss, all));
        }

        @Test
        @DisplayName("条件节点尚未激活任何分支时，后继一律跳过")
        void isNodeBranchSkipped_NoActivatedBranchSkipsAll() {
            WorkflowNode cond = new WorkflowNode("c", WorkflowNode.NodeType.CONDITIONAL);
            cond.addBranch("yes", "hit");
            WorkflowNode hit = new WorkflowNode("hit", WorkflowNode.NodeType.SINGLE).dependsOn("c");

            assertTrue(context.isNodeBranchSkipped(hit, nodesOf(cond, hit)));
        }

        @Test
        @DisplayName("依赖的节点不在映射里时不跳过，避免误杀")
        void isNodeBranchSkipped_UnknownDependencyDoesNotSkip() {
            WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE).dependsOn("ghost");

            assertFalse(context.isNodeBranchSkipped(node, new HashMap<>()));
        }
    }

    // ==================== 并发 ====================

    @Nested
    @DisplayName("并发")
    class Concurrency {

        @Test
        @DisplayName("同层节点并行写入变量与结果不丢数据、计数不漏")
        void parallelWrites_AreSafeAndCountedExactly() throws InterruptedException {
            int writers = 16;
            int perWriter = 200;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writers);

            try {
                for (int w = 0; w < writers; w++) {
                    final int id = w;
                    pool.execute(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perWriter; i++) {
                                String key = "n" + id + "_" + i;
                                context.setVariable(key, id);
                                context.setNodeResult(key, completed(key, "r" + i));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入未在超时内完成");
            } finally {
                pool.shutdownNow();
            }

            int expected = writers * perWriter;
            assertEquals(expected, context.getVariables().size(), "变量写入有丢失");
            assertEquals(expected, context.getNodeResults().size(), "节点结果写入有丢失");
            assertEquals(expected, context.getExecutedNodeCount(),
                    "已执行计数必须原子累加，否则 maxNodeExecutions 限流会失准");
        }

        @Test
        @DisplayName("未完成的节点结果不计入已执行数")
        void setNodeResult_OnlyCountsFinished() {
            NodeResult running = new NodeResult("a");
            running.markStarted();
            context.setNodeResult("a", running);

            assertEquals(0, context.getExecutedNodeCount());

            context.setNodeResult("b", completed("b", "B"));
            assertEquals(1, context.getExecutedNodeCount());
        }
    }

    // ==================== 其他 ====================

    @Test
    @DisplayName("getElapsedTime 单调不减")
    void getElapsedTime_IsNonNegative() {
        assertTrue(context.getElapsedTime() >= 0);
    }

    @Test
    @DisplayName("getSharedContext 返回构造时传入的实例")
    void getSharedContext_ReturnsInjectedInstance() {
        SharedContext shared = new SharedContext("主题", "输入");
        WorkflowContext ctx = new WorkflowContext(shared, Map.of());

        assertEquals(shared, ctx.getSharedContext());
    }

    @Test
    @DisplayName("isNodeCompleted 对未知节点返回 false 而非抛异常")
    void isNodeCompleted_UnknownNode() {
        assertFalse(context.isNodeCompleted("nosuch"));
        assertNull(context.getNodeResult("nosuch"));
    }

    @Test
    @DisplayName("依赖列表为空集合时按无依赖处理")
    void dependencies_EmptyListBehavesAsNone() {
        WorkflowNode node = new WorkflowNode("n", WorkflowNode.NodeType.SINGLE);
        node.setDependsOn(List.of());

        assertTrue(context.areDependenciesCompleted(node));
        assertFalse(context.hasFailedDependency(node));
        assertEquals("", context.buildDependencyInput(node));
    }
}
