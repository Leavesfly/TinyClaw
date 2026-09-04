package io.leavesfly.tinyclaw.collaboration;

import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.Kind;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.NodeStatus;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.TopoEdge;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.TopoNode;
import io.leavesfly.tinyclaw.collaboration.workflow.NodeResult;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowContext;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowDefinition;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowEngine;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollaborationTopologyBuilder} 测试。
 *
 * <p>拓扑图是纯派生数据：它不参与协同执行，出错的后果是「图画错了但没人报错」。
 * 因此这里重点验证三件事——边的来源与权重是否忠实于原始数据、分层是否与引擎实际
 * 执行顺序一致、以及各种畸形输入下是否安全降级为 {@code null} 而不是抛异常。</p>
 */
class CollaborationTopologyBuilderTest {

    // ==================== 构造辅助 ====================

    private static AgentRole role(String name) {
        return AgentRole.of(name, "你是" + name + "，请从你的专业视角发言。");
    }

    private static AgentMessage say(String roleName, String content) {
        return new AgentMessage("agent-" + roleName, roleName, content);
    }

    /** 定向消息：from 对 to 说 */
    private static AgentMessage directed(String from, String to, String content) {
        return AgentMessage.builder("agent-" + from, from, content)
                .targetRole(to)
                .build();
    }

    /** Router 的路由决策消息（DYNAMIC 风格每轮发一条） */
    private static AgentMessage routing(String routerName, String decision) {
        return AgentMessage.builder("router", routerName, decision)
                .type(AgentMessage.MessageType.SYSTEM)
                .build();
    }

    private static SharedContext contextWith(AgentMessage... messages) {
        SharedContext context = new SharedContext("测试目标", "测试输入");
        for (AgentMessage msg : messages) {
            context.addMessage(msg);
        }
        return context;
    }

    /** 把边摊平成 {@code "from->to" → weight}，便于断言 */
    private static Map<String, Integer> edgeWeights(CollaborationTopology topology) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (TopoEdge edge : topology.getEdges()) {
            map.put(edge.getFrom() + "->" + edge.getTo(), edge.getWeight());
        }
        return map;
    }

    private static TopoNode node(CollaborationTopology topology, String id) {
        TopoNode found = topology.findNode(id);
        assertNotNull(found, "应当存在节点: " + id);
        return found;
    }

    // ==================== DISCUSS ====================

    @Nested
    @DisplayName("DISCUSS —— 角色交互图")
    class Discussion {

        @Test
        @DisplayName("定向消息聚合为带权重的边")
        void directedMessages_aggregatedIntoWeightedEdges() {
            CollaborationConfig config = CollaborationConfig.discuss("是否采用微服务", 3)
                    .addRole(role("架构师"))
                    .addRole(role("运维"));
            SharedContext context = contextWith(
                    directed("架构师", "运维", "拆成三个服务"),
                    directed("运维", "架构师", "部署成本太高"),
                    directed("架构师", "运维", "那就两个"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertEquals(Kind.DISCUSSION, topology.getKind());
            assertEquals(2, topology.getNodes().size());
            Map<String, Integer> weights = edgeWeights(topology);
            assertEquals(2, weights.get("架构师->运维"), "架构师对运维说了两次，权重应为 2");
            assertEquals(1, weights.get("运维->架构师"));
        }

        @Test
        @DisplayName("没有定向消息时退化为发言顺序链")
        void noDirectedMessages_fallsBackToSpeechChain() {
            CollaborationConfig config = CollaborationConfig.discuss("方案评审", 2)
                    .addRole(role("正方"))
                    .addRole(role("反方"));
            // DEBATE 的实际形态：策略不设置 targetRole，只有轮流发言
            SharedContext context = contextWith(
                    say("正方", "支持"),
                    say("反方", "反对"),
                    say("正方", "再支持"),
                    say("反方", "再反对"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            Map<String, Integer> weights = edgeWeights(topology);
            assertEquals(2, weights.get("正方->反方"), "正方→反方的转换发生了两次，权重应累加为 2");
            assertEquals(1, weights.get("反方->正方"), "反方→正方只发生一次");
            // 同一角色连续发言不构成边（addEdge 丢弃自环）
            assertFalse(weights.containsKey("正方->正方"));
        }

        @Test
        @DisplayName("同一角色连续发言不产生自环边")
        void consecutiveSameSpeaker_producesNoSelfLoop() {
            CollaborationConfig config = CollaborationConfig.discuss("独白", 1)
                    .addRole(role("讲述者"));
            SharedContext context = contextWith(
                    say("讲述者", "第一段"),
                    say("讲述者", "第二段"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertTrue(topology.getEdges().isEmpty(), "单一角色不应有任何边");
        }

        @Test
        @DisplayName("DYNAMIC 风格：Router 的 SYSTEM 消息还原为星形路由边")
        void dynamicStyle_routerDecisions_produceStarEdges() {
            CollaborationConfig config = CollaborationConfig.discuss("下一步做什么", 3);
            config.setDiscussStyle(CollaborationConfig.DiscussStyle.DYNAMIC);
            config.addRole(role("产品")).addRole(role("研发"));

            // executeDynamic 的实际序列：Router 发 SYSTEM 决策 → 被点名的角色发言
            SharedContext context = contextWith(
                    routing("调度员", "下一个发言者: 产品"),
                    say("产品", "先做需求"),
                    routing("调度员", "下一个发言者: 研发"),
                    say("研发", "再评估工时"),
                    routing("调度员", "下一个发言者: 产品"),
                    say("产品", "确认优先级"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            TopoNode router = node(topology, "调度员");
            assertEquals("ROUTER", router.getType(), "Router 不在 roles 里，应单独补节点并标为 ROUTER");

            Map<String, Integer> weights = edgeWeights(topology);
            assertEquals(2, weights.get("调度员->产品"));
            assertEquals(1, weights.get("调度员->研发"));
            assertFalse(weights.containsKey("产品->研发"), "已有路由边时不应再叠加发言链");
        }

        @Test
        @DisplayName("编排器的 system 提示消息不被当作角色发言")
        void orchestratorSystemMessage_notTreatedAsSpeaker() {
            CollaborationConfig config = CollaborationConfig.discuss("讨论", 2)
                    .addRole(role("甲"))
                    .addRole(role("乙"));
            SharedContext context = contextWith(
                    say("甲", "开始"),
                    AgentMessage.system("未找到角色 [丙]，跳过本轮"),
                    say("乙", "接上"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertNull(topology.findNode("System"), "编排器消息不应成为图上的节点");
            assertEquals(1, edgeWeights(topology).get("甲->乙"),
                    "系统消息不打断发言链：甲之后仍是乙");
        }

        @Test
        @DisplayName("配置无角色时从历史发言者还原节点")
        void emptyRoles_nodesDerivedFromHistory() {
            CollaborationConfig config = CollaborationConfig.discuss("临时讨论", 2);
            SharedContext context = contextWith(say("张三", "你好"), say("李四", "你好"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertEquals(2, topology.getNodes().size());
            assertNotNull(topology.findNode("张三"));
            assertNotNull(topology.findNode("李四"));
        }

        @Test
        @DisplayName("发过言的角色标记 COMPLETED，未发言的保持 PENDING")
        void spokeRoles_completed_unspokenPending() {
            CollaborationConfig config = CollaborationConfig.discuss("评审", 2)
                    .addRole(role("发言者"))
                    .addRole(role("沉默者"));
            SharedContext context = contextWith(say("发言者", "我认为..."));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertEquals(NodeStatus.COMPLETED, node(topology, "发言者").getStatus());
            assertEquals(NodeStatus.PENDING, node(topology, "沉默者").getStatus());
        }

        @Test
        @DisplayName("指向图外角色的定向消息被忽略")
        void directedMessageToUnknownRole_ignored() {
            CollaborationConfig config = CollaborationConfig.discuss("讨论", 2)
                    .addRole(role("甲"))
                    .addRole(role("乙"));
            SharedContext context = contextWith(directed("甲", "不存在的人", "喂"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            // 定向边全被丢弃后应退回发言链，但只有一条消息也构不成链
            assertTrue(topology.getEdges().isEmpty(), "悬空定向消息不应产生边");
        }

        @Test
        @DisplayName("既无角色也无历史时返回 null（不值得渲染）")
        void noRolesNoHistory_returnsNull() {
            CollaborationConfig config = CollaborationConfig.discuss("空讨论", 1);

            assertNull(CollaborationTopologyBuilder.build(config, new SharedContext()));
        }

        @Test
        @DisplayName("上下文为 null 时仍能画出结构")
        void nullContext_stillBuildsStructure() {
            CollaborationConfig config = CollaborationConfig.discuss("讨论", 2)
                    .addRole(role("甲"))
                    .addRole(role("乙"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, null);

            assertNotNull(topology);
            assertEquals(2, topology.getNodes().size());
            assertEquals(NodeStatus.PENDING, node(topology, "甲").getStatus(),
                    "无历史时无法判断谁发过言，一律 PENDING");
            assertTrue(topology.getEdges().isEmpty());
        }

        @Test
        @DisplayName("记录子风格与规模元信息")
        void recordsStyleAndMeta() {
            CollaborationConfig config = CollaborationConfig.discuss("辩论", 4)
                    .addRole(role("正方")).addRole(role("反方"));
            config.setDiscussStyle(CollaborationConfig.DiscussStyle.DEBATE);
            SharedContext context = contextWith(say("正方", "a"), say("反方", "b"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertEquals("DEBATE", topology.getStyle());
            assertEquals(2, topology.getMeta().get("participants"));
            assertEquals(2, topology.getMeta().get("totalMessages"));
        }
    }

    // ==================== TASKS / PARALLEL ====================

    @Nested
    @DisplayName("TASKS · PARALLEL —— 任务依赖图")
    class TaskGraph {

        private TeamTask task(String id, String name, AgentRole assignee, String... deps) {
            TeamTask t = new TeamTask(id, name, assignee);
            t.setDescription(name + "的详细描述");
            for (String dep : deps) {
                t.addDependency(dep);
            }
            return t;
        }

        @Test
        @DisplayName("任务成为节点，依赖成为边，状态原样映射")
        void tasksBecomeNodes_dependenciesBecomeEdges() {
            CollaborationConfig config = CollaborationConfig.tasks("开发登录模块")
                    .addTask(task("t1", "设计接口", role("架构师")))
                    .addTask(task("t2", "写前端", role("前端"), "t1"))
                    .addTask(task("t3", "写后端", role("后端"), "t1"));
            config.getTasks().get(0).markCompleted("接口已定");
            config.getTasks().get(1).markFailed("缺少设计稿");

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            assertEquals(Kind.TASK_GRAPH, topology.getKind());
            assertEquals(3, topology.getNodes().size());
            assertEquals("TASK", node(topology, "t1").getType());
            assertEquals(NodeStatus.COMPLETED, node(topology, "t1").getStatus());
            assertEquals(NodeStatus.FAILED, node(topology, "t2").getStatus());
            assertEquals(NodeStatus.PENDING, node(topology, "t3").getStatus());

            Map<String, Integer> weights = edgeWeights(topology);
            assertEquals(2, weights.size());
            assertTrue(weights.containsKey("t1->t2"));
            assertTrue(weights.containsKey("t1->t3"));
        }

        @Test
        @DisplayName("节点带上负责角色与任务描述")
        void nodeCarriesAssigneeAndDescription() {
            CollaborationConfig config = CollaborationConfig.tasks("任务")
                    .addTask(task("t1", "调研", role("研究员")));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            TopoNode n = node(topology, "t1");
            assertEquals(List.of("研究员"), n.getAgents());
            assertEquals("调研", n.getLabel());
            assertTrue(n.getDetail().contains("调研的详细描述"));
        }

        @Test
        @DisplayName("按依赖深度分层：无依赖为第 0 层，其余为最大前置深度 +1")
        void layersByDependencyDepth() {
            CollaborationConfig config = CollaborationConfig.tasks("流水线")
                    .addTask(task("a", "A", role("r1")))
                    .addTask(task("b", "B", role("r2")))
                    .addTask(task("c", "C", role("r3"), "a", "b"))
                    .addTask(task("d", "D", role("r4"), "c"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            List<List<String>> layers = topology.getLayers();
            assertNotNull(layers);
            assertEquals(3, layers.size());
            assertEquals(List.of("a", "b"), layers.get(0), "a、b 无依赖，同属第 0 层可并行");
            assertEquals(List.of("c"), layers.get(1));
            assertEquals(List.of("d"), layers.get(2));
        }

        @Test
        @DisplayName("依赖集合外的任务 id 不产生边")
        void dependencyOnUnknownTask_edgeSkipped() {
            CollaborationConfig config = CollaborationConfig.tasks("任务")
                    .addTask(task("t1", "T1", role("r"), "不存在的任务"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            assertTrue(topology.getEdges().isEmpty());
            assertEquals(List.of(List.of("t1")), topology.getLayers(),
                    "无效依赖视作无依赖，仍应落在第 0 层");
        }

        @Test
        @DisplayName("任务依赖成环时放弃分层但保留结构")
        void cyclicDependency_layersNullButStructureKept() {
            CollaborationConfig config = CollaborationConfig.tasks("有环")
                    .addTask(task("x", "X", role("rx"), "y"))
                    .addTask(task("y", "Y", role("ry"), "x"))
                    .addTask(task("ok", "OK", role("rok")));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology, "有环也应出图——画出来正是定位环在哪的最快方式");
            assertNull(topology.getLayers(), "深度无法收敛，放弃分层让前端退化为环形布局");
            assertEquals(3, topology.getNodes().size());
            assertEquals(2, topology.getEdges().size());
        }

        @Test
        @DisplayName("任务列表为空时返回 null")
        void emptyTasks_returnsNull() {
            CollaborationConfig config = CollaborationConfig.tasks("没有任务");

            assertNull(CollaborationTopologyBuilder.build(config, new SharedContext()));
        }
    }

    // ==================== TASKS / HIERARCHY ====================

    @Nested
    @DisplayName("TASKS · HIERARCHY —— 金字塔层级图")
    class Hierarchy {

        @Test
        @DisplayName("节点 id 按层命名空间化，避免同名角色跨层覆盖")
        void nodeIdsNamespacedByLevel() {
            AgentRole shared = role("负责人");
            HierarchyConfig hierarchy = HierarchyConfig.of(
                    List.of(role("分析师A"), shared),
                    List.of(shared));
            CollaborationConfig config = CollaborationConfig.tasks("分层决策");
            config.setTasksStyle(CollaborationConfig.TasksStyle.HIERARCHY);
            config.setHierarchy(hierarchy);

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            assertEquals(Kind.HIERARCHY, topology.getKind());
            assertEquals(3, topology.getNodes().size(), "同名角色在两层各算一个节点");
            assertNotNull(topology.findNode("L0:负责人"));
            assertNotNull(topology.findNode("L1:负责人"));
            assertEquals("负责人", node(topology, "L0:负责人").getLabel(), "展示名不带层前缀");
        }

        @Test
        @DisplayName("下层每个角色向上层每个角色连汇报边")
        void bipartiteReportEdges() {
            HierarchyConfig hierarchy = HierarchyConfig.of(
                    List.of(role("分析师A"), role("分析师B")),
                    List.of(role("经理"), role("总监")),
                    List.of(role("CEO")));
            CollaborationConfig config = CollaborationConfig.tasks("决策");
            config.setTasksStyle(CollaborationConfig.TasksStyle.HIERARCHY);
            config.setHierarchy(hierarchy);

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            Map<String, Integer> weights = edgeWeights(topology);
            // 2×2 + 2×1 = 6 条边，忠实反映「下一层全部结果传给上层每个 Agent」
            assertEquals(6, weights.size());
            assertTrue(weights.containsKey("L0:分析师A->L1:经理"));
            assertTrue(weights.containsKey("L0:分析师B->L1:总监"));
            assertTrue(weights.containsKey("L1:经理->L2:CEO"));
            assertEquals("汇报", topology.getEdges().get(0).getLabel());
        }

        @Test
        @DisplayName("分层自底向上，与引擎逐层执行的顺序一致")
        void layersOrderedBottomUp() {
            HierarchyConfig hierarchy = HierarchyConfig.of(
                    List.of(role("底层1"), role("底层2")),
                    List.of(role("中层")),
                    List.of(role("顶层")));
            CollaborationConfig config = CollaborationConfig.tasks("决策");
            config.setTasksStyle(CollaborationConfig.TasksStyle.HIERARCHY);
            config.setHierarchy(hierarchy);

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            List<List<String>> layers = topology.getLayers();
            assertEquals(3, layers.size());
            assertEquals(List.of("L0:底层1", "L0:底层2"), layers.get(0), "索引 0 是最底层");
            assertEquals(List.of("L2:顶层"), layers.get(2));
            assertEquals(3, topology.getMeta().get("levelCount"));
        }

        @Test
        @DisplayName("按角色是否发言标记状态（层级消息的 agentId 是 level-N）")
        void statusFromHierarchyMessages() {
            HierarchyConfig hierarchy = HierarchyConfig.of(
                    List.of(role("分析师A"), role("分析师B")),
                    List.of(role("决策者")));
            CollaborationConfig config = CollaborationConfig.tasks("决策");
            config.setTasksStyle(CollaborationConfig.TasksStyle.HIERARCHY);
            config.setHierarchy(hierarchy);

            // TasksStrategy 用 addMessage("level-" + i, roleName, result) 记录层级产出
            SharedContext context = new SharedContext("决策", "输入");
            context.addMessage("level-0", "分析师A", "分析结果");
            context.addMessage("level-1", "决策者", "最终决策");

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);

            assertNotNull(topology);
            assertEquals(NodeStatus.COMPLETED, node(topology, "L0:分析师A").getStatus());
            assertEquals(NodeStatus.PENDING, node(topology, "L0:分析师B").getStatus(),
                    "未产出结果的角色不应显示为已完成");
            assertEquals(NodeStatus.COMPLETED, node(topology, "L1:决策者").getStatus());
        }

        @Test
        @DisplayName("层级配置缺失或无效时返回 null")
        void invalidHierarchy_returnsNull() {
            CollaborationConfig config = CollaborationConfig.tasks("决策");
            config.setTasksStyle(CollaborationConfig.TasksStyle.HIERARCHY);

            assertNull(CollaborationTopologyBuilder.build(config, new SharedContext()),
                    "未配置 hierarchy 时应返回 null");

            config.setHierarchy(new HierarchyConfig());
            assertNull(CollaborationTopologyBuilder.build(config, new SharedContext()),
                    "空层级 isValid() 为 false，同样返回 null");
        }

        @Test
        @DisplayName("超大金字塔截断边数并打标，避免生成病态大小的记录")
        void hugePyramid_truncatesEdgesWithFlag() {
            List<AgentRole> bottom = new ArrayList<>();
            List<AgentRole> top = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                bottom.add(role("底层" + i));
                top.add(role("上层" + i));
            }
            CollaborationConfig config = CollaborationConfig.tasks("超大层级");
            config.setTasksStyle(CollaborationConfig.TasksStyle.HIERARCHY);
            config.setHierarchy(HierarchyConfig.of(bottom, top));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, new SharedContext());

            assertNotNull(topology);
            assertEquals(500, topology.getEdges().size(), "全连接 625 条应被截到上限 500");
            assertEquals(Boolean.TRUE, topology.getMeta().get("edgesTruncated"));
        }
    }

    // ==================== WORKFLOW ====================

    @Nested
    @DisplayName("WORKFLOW —— DAG")
    class Workflow {

        private CollaborationConfig workflowConfig(WorkflowDefinition definition) {
            return CollaborationConfig.workflow("部署方案", definition);
        }

        /** 把执行期上下文按引擎的约定透出到 SharedContext meta */
        private SharedContext contextWithWorkflowState(WorkflowDefinition definition,
                                                       Map<String, NodeResult.Status> statuses) {
            SharedContext shared = new SharedContext("部署方案", "输入");
            WorkflowContext wfContext = new WorkflowContext(shared, new HashMap<>());
            statuses.forEach((nodeId, status) -> {
                NodeResult result = new NodeResult(nodeId);
                result.setStatus(status);
                wfContext.setNodeResult(nodeId, result);
            });
            shared.setMeta(WorkflowEngine.META_WORKFLOW_CONTEXT, wfContext);
            return shared;
        }

        @Test
        @DisplayName("节点、依赖边与分层均来自工作流定义")
        void nodesEdgesAndLayersFromDefinition() {
            WorkflowDefinition definition = new WorkflowDefinition("发布流程")
                    .addNode(WorkflowNode.single("analyze", role("分析师")))
                    .addNode(WorkflowNode.single("dev", role("开发")).dependsOn("analyze"))
                    .addNode(WorkflowNode.single("test", role("测试")).dependsOn("analyze"))
                    .addNode(WorkflowNode.aggregate("merge", List.of("dev", "test")));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    workflowConfig(definition), new SharedContext());

            assertNotNull(topology);
            assertEquals(Kind.DAG, topology.getKind());
            assertEquals(4, topology.getNodes().size());
            assertEquals("SINGLE", node(topology, "analyze").getType());
            assertEquals("AGGREGATE", node(topology, "merge").getType());
            assertEquals(List.of("分析师"), node(topology, "analyze").getAgents());

            Map<String, Integer> weights = edgeWeights(topology);
            assertEquals(4, weights.size());
            assertTrue(weights.containsKey("analyze->dev"));
            assertTrue(weights.containsKey("dev->merge"));

            // 分层必须与引擎的拓扑排序一致，否则图上看到的顺序和实际执行顺序不符
            List<List<String>> layers = topology.getLayers();
            assertNotNull(layers);
            assertEquals(3, layers.size());
            assertEquals(List.of("analyze"), layers.get(0));
            assertEquals(List.of("dev", "test"), layers.get(1), "同层可并行");
            assertEquals(List.of("merge"), layers.get(2));
        }

        @Test
        @DisplayName("节点状态取自引擎透出的执行期上下文")
        void nodeStatusFromStashedWorkflowContext() {
            WorkflowDefinition definition = new WorkflowDefinition("流程")
                    .addNode(WorkflowNode.single("a", role("A")))
                    .addNode(WorkflowNode.single("b", role("B")).dependsOn("a"))
                    .addNode(WorkflowNode.single("c", role("C")).dependsOn("a"));
            SharedContext shared = contextWithWorkflowState(definition, Map.of(
                    "a", NodeResult.Status.COMPLETED,
                    "b", NodeResult.Status.FAILED,
                    "c", NodeResult.Status.SKIPPED));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    workflowConfig(definition), shared);

            assertNotNull(topology);
            assertEquals(NodeStatus.COMPLETED, node(topology, "a").getStatus());
            assertEquals(NodeStatus.FAILED, node(topology, "b").getStatus());
            assertEquals(NodeStatus.SKIPPED, node(topology, "c").getStatus());
        }

        @Test
        @DisplayName("拿不到执行期上下文时仍能出图，状态全为 PENDING")
        void withoutWorkflowContext_allPending() {
            WorkflowDefinition definition = new WorkflowDefinition("流程")
                    .addNode(WorkflowNode.single("a", role("A")));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    workflowConfig(definition), new SharedContext());

            assertNotNull(topology, "校验阶段就被拒的工作流，结构依然值得画出来");
            assertEquals(NodeStatus.PENDING, node(topology, "a").getStatus());
        }

        @Test
        @DisplayName("节点依赖成环时放弃分层，但保留结构与边")
        void cyclicDefinition_layersNullButRenderable() {
            WorkflowDefinition definition = new WorkflowDefinition("有环")
                    .addNode(WorkflowNode.single("x", role("X")).dependsOn("y"))
                    .addNode(WorkflowNode.single("y", role("Y")).dependsOn("x"));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    workflowConfig(definition), new SharedContext());

            assertNotNull(topology);
            assertNull(topology.getLayers(), "topologicalSort 抛异常时应降级为环形布局");
            assertEquals(2, topology.getNodes().size());
            assertEquals(2, topology.getEdges().size());
        }

        @Test
        @DisplayName("未提供工作流定义时返回 null")
        void noDefinition_returnsNull() {
            assertNull(CollaborationTopologyBuilder.build(
                    CollaborationConfig.workflow("空流程", null), new SharedContext()));
        }

        @Test
        @DisplayName("附加工作流级元信息")
        void attachesWorkflowMeta() {
            WorkflowDefinition definition = new WorkflowDefinition("发布流程")
                    .addNode(WorkflowNode.single("a", role("A")))
                    .withOutput("${a.result}");
            definition.setDescription("灰度发布");
            definition.setTimeoutMs(60000);

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    workflowConfig(definition), new SharedContext());

            assertNotNull(topology);
            assertEquals("发布流程", topology.getMeta().get("workflowName"));
            assertEquals("灰度发布", topology.getMeta().get("description"));
            assertEquals("${a.result}", topology.getMeta().get("outputExpression"));
            assertEquals(1, topology.getMeta().get("nodeCount"));
            assertEquals(60000L, topology.getMeta().get("timeoutMs"));
        }

        @Test
        @DisplayName("节点详情汇集条件、分支、输入表达式与审批要求")
        void nodeDetailCollectsConfiguration() {
            WorkflowNode conditional = WorkflowNode.single("check", role("评审"))
                    .withCondition("${score} > 80")
                    .withInput("${analyze.result}")
                    .withApproval("发布前需人工确认");
            conditional.addBranch("true", "deploy");
            WorkflowDefinition definition = new WorkflowDefinition("流程").addNode(conditional);

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    workflowConfig(definition), new SharedContext());

            assertNotNull(topology);
            String detail = node(topology, "check").getDetail();
            assertNotNull(detail);
            assertTrue(detail.contains("条件: ${score} > 80"));
            assertTrue(detail.contains("输入: ${analyze.result}"));
            assertTrue(detail.contains("需人工审批"));
            assertTrue(detail.contains("deploy"));
        }
    }

    // ==================== 通用行为 ====================

    @Nested
    @DisplayName("通用 —— 降级与边界")
    class General {

        @Test
        @DisplayName("配置为 null 时返回 null 而非抛异常")
        void nullConfig_returnsNull() {
            assertNull(CollaborationTopologyBuilder.build(null, new SharedContext()));
        }

        @Test
        @DisplayName("模式为 null 时返回 null")
        void nullMode_returnsNull() {
            assertNull(CollaborationTopologyBuilder.build(new CollaborationConfig(), new SharedContext()));
        }

        @Test
        @DisplayName("详情超长时截断，避免协同记录文件膨胀")
        void detailTruncated() {
            String longPrompt = "很长的提示词".repeat(200);
            CollaborationConfig config = CollaborationConfig.discuss("讨论", 1)
                    .addRole(AgentRole.of("话痨", longPrompt));

            CollaborationTopology topology = CollaborationTopologyBuilder.build(
                    config, contextWith(say("话痨", "开始")));

            assertNotNull(topology);
            String detail = node(topology, "话痨").getDetail();
            assertNotNull(detail);
            assertTrue(detail.length() <= 241, "应截断到 240 字 + 省略号，实际 " + detail.length());
            assertTrue(detail.endsWith("…"));
        }

        @Test
        @DisplayName("自环边在模型层就被丢弃")
        void selfLoopDroppedAtModelLevel() {
            CollaborationTopology topology = new CollaborationTopology(Kind.DISCUSSION);
            topology.addNode(new TopoNode("a", "a", "AGENT", NodeStatus.COMPLETED));
            topology.addEdge("a", "a", null, 3);

            assertTrue(topology.getEdges().isEmpty());
        }

        @Test
        @DisplayName("边权重下限为 1，避免线宽算成 0")
        void edgeWeightFloorIsOne() {
            CollaborationTopology topology = new CollaborationTopology(Kind.DISCUSSION);
            topology.addEdge("a", "b", null, 0);
            topology.addEdge("a", "b", null, -5);

            assertEquals(1, topology.getEdges().get(0).getWeight());
            assertEquals(1, topology.getEdges().get(1).getWeight());
        }

        @Test
        @DisplayName("空图不可渲染，build 返回 null 让前端降级")
        void emptyTopologyNotRenderable() {
            assertFalse(new CollaborationTopology(Kind.DAG).isRenderable());
            assertFalse(new CollaborationTopology(null).isRenderable());

            CollaborationTopology withNode = new CollaborationTopology(Kind.DAG);
            withNode.addNode(new TopoNode("a", "a", "SINGLE", NodeStatus.PENDING));
            assertTrue(withNode.isRenderable());
        }

        @Test
        @DisplayName("addNode 忽略 null，不污染节点列表")
        void addNodeIgnoresNull() {
            CollaborationTopology topology = new CollaborationTopology(Kind.DISCUSSION);
            topology.addNode(null);

            assertTrue(topology.getNodes().isEmpty());
        }
    }
}
