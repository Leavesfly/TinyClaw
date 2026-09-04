package io.leavesfly.tinyclaw.collaboration;

import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.NodeStatus;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowDefinition;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowNode;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实时协同拓扑的事件链路测试。
 *
 * <p>覆盖三层：</p>
 * <ol>
 *   <li>{@link StreamEvent} 两个新事件的序列化（toJson 供 SSE、format 供 CLI 降级）；</li>
 *   <li>{@link SharedContext#reportNodeStatus} 的静默降级与异常吞没；</li>
 *   <li>{@link AgentOrchestrator#orchestrateWithStream} 端到端的事件顺序与拓扑内容——
 *       初始版（全 PENDING、无历史边）→ 节点点亮 → 终版（真实状态 + 边）。</li>
 * </ol>
 *
 * <p>端到端用 Mockito 打桩 LLMProvider（固定文本回复、无工具调用），
 * 不依赖任何真实模型即可验证完整事件流。</p>
 */
class CollaborationLiveTopologyTest {

    /** 记录全部事件的回调。synchronizedList：工作流并行层可能在池线程上报事件 */
    private static class RecordingCallback implements LLMProvider.EnhancedStreamCallback {
        final List<StreamEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onChunk(String content) {
        }

        @Override
        public void onEvent(StreamEvent event) {
            events.add(event);
        }

        List<StreamEvent> allOf(StreamEvent.EventType type) {
            return events.stream().filter(e -> e.getType() == type).toList();
        }
    }

    private static AgentRole role(String name) {
        return AgentRole.of(name, "你是" + name);
    }

    // ==================== StreamEvent 序列化 ====================

    @Nested
    @DisplayName("StreamEvent —— 事件序列化")
    class EventSerialization {

        @Test
        @DisplayName("COLLABORATE_NODE 的 toJson 携带 nodeId/label/status")
        void nodeEventJson() {
            String json = StreamEvent.collaborateNode("t1", "设计接口", "COMPLETED").toJson();

            assertTrue(json.contains("\"type\":\"COLLABORATE_NODE\""));
            assertTrue(json.contains("\"nodeId\":\"t1\""));
            assertTrue(json.contains("\"label\":\"设计接口\""));
            assertTrue(json.contains("\"status\":\"COMPLETED\""));
        }

        @Test
        @DisplayName("COLLABORATE_NODE 的 format 是可读的单行文本（CLI/IM 降级）")
        void nodeEventFormat() {
            String text = StreamEvent.collaborateNode("t1", "设计接口", "FAILED").format();
            assertTrue(text.contains("设计接口"));
            assertTrue(text.contains("FAILED"));
        }

        @Test
        @DisplayName("label 缺失时 format 回落到 nodeId")
        void nodeEventFormatFallsBackToId() {
            String text = StreamEvent.collaborateNode("node-9", "", "RUNNING").format();
            assertTrue(text.contains("node-9"));
        }

        @Test
        @DisplayName("COLLABORATE_TOPOLOGY 的 toJson 序列化真实拓扑对象")
        void topologyEventJson() {
            CollaborationConfig config = CollaborationConfig.discuss("讨论", 1)
                    .addRole(role("甲")).addRole(role("乙"));
            SharedContext context = new SharedContext("讨论", "输入");
            context.addMessage("a1", "甲", "我先说");
            context.addMessage("a2", "乙", "我再说");
            CollaborationTopology topology = CollaborationTopologyBuilder.build(config, context);
            assertNotNull(topology);

            String json = StreamEvent.collaborateTopology(topology).toJson();

            assertTrue(json.contains("\"type\":\"COLLABORATE_TOPOLOGY\""));
            assertTrue(json.contains("\"kind\":\"DISCUSSION\""));
            assertTrue(json.contains("\"id\":\"甲\""), "节点应完整序列化");
            // @JsonInclude(NON_NULL) 应剔除 null 字段，而不是输出 null
            assertFalse(json.contains("\"detail\":null"));
        }

        @Test
        @DisplayName("COLLABORATE_TOPOLOGY 的 format 是简短提示（图形信息不进纯文本通道）")
        void topologyEventFormat() {
            String text = StreamEvent.collaborateTopology(new CollaborationTopology(
                    CollaborationTopology.Kind.DAG)).format();
            assertTrue(text.contains("拓扑"));
            assertFalse(text.contains("nodes"), "结构数据不应泄漏进 CLI 文本");
        }

        @Test
        @DisplayName("拓扑为 null 时不抛异常，JSON 只含 type")
        void nullTopologySafe() {
            String json = StreamEvent.collaborateTopology(null).toJson();
            assertTrue(json.contains("\"type\":\"COLLABORATE_TOPOLOGY\""));
            assertFalse(json.contains("\"topology\""));
        }

        @Test
        @DisplayName("不可序列化的拓扑对象降级为空拓扑而非断流")
        void unserializableTopologyDegrades() {
            // 数组自引用，Jackson 序列化必然抛异常
            Object[] cyclic = new Object[1];
            cyclic[0] = cyclic;

            String json = StreamEvent.collaborateTopology(cyclic).toJson();
            assertTrue(json.contains("\"type\":\"COLLABORATE_TOPOLOGY\""),
                    "序列化失败时事件本身仍要能发出");
        }
    }

    // ==================== SharedContext.reportNodeStatus ====================

    @Nested
    @DisplayName("SharedContext —— 节点状态上报")
    class NodeStatusReporting {

        @Test
        @DisplayName("有回调时发出 COLLABORATE_NODE 事件")
        void emitsEvent() {
            SharedContext context = new SharedContext();
            RecordingCallback callback = new RecordingCallback();
            context.setStreamCallback(callback);

            context.reportNodeStatus("甲", "甲", NodeStatus.COMPLETED);

            assertEquals(1, callback.events.size());
            StreamEvent event = callback.events.get(0);
            assertEquals(StreamEvent.EventType.COLLABORATE_NODE, event.getType());
            assertEquals("甲", event.getMeta("nodeId"));
            assertEquals("COMPLETED", event.getMeta("status"));
        }

        @Test
        @DisplayName("无回调时静默（非流式路径零开销）")
        void silentWithoutCallback() {
            SharedContext context = new SharedContext();
            // 不设回调，也不该抛异常
            context.reportNodeStatus("甲", "甲", NodeStatus.RUNNING);
        }

        @Test
        @DisplayName("回调抛异常时被吞掉，不拖垮协同执行")
        void swallowsCallbackFailure() {
            SharedContext context = new SharedContext();
            context.setStreamCallback(new LLMProvider.EnhancedStreamCallback() {
                @Override
                public void onChunk(String content) {
                }

                @Override
                public void onEvent(StreamEvent event) {
                    throw new IllegalStateException("渲染端崩了");
                }
            });

            // 不抛异常即通过：上报只是过程展示，协同本身不能被它中断
            context.reportNodeStatus("甲", "甲", NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("null 参数时静默忽略")
        void ignoresNullArgs() {
            SharedContext context = new SharedContext();
            RecordingCallback callback = new RecordingCallback();
            context.setStreamCallback(callback);

            context.reportNodeStatus(null, "甲", NodeStatus.COMPLETED);
            context.reportNodeStatus("甲", "甲", null);

            assertTrue(callback.events.isEmpty());
        }
    }

    // ==================== AgentOrchestrator 端到端 ====================

    @Nested
    @DisplayName("AgentOrchestrator —— 端到端事件序列")
    class Orchestration {

        @Test
        @DisplayName("DISCUSS：初始拓扑(全PENDING) → 节点点亮 → 终版拓扑(含边) → 结束")
        @SuppressWarnings("unchecked")
        void discussEventSequence(@TempDir Path workspace) {
            LLMProvider provider = mock(LLMProvider.class);
            // 任何角色发言都返回固定文本；无工具调用，ReAct 单轮即返回
            when(provider.chat(any(), any(), anyString(), any()))
                    .thenReturn(new LLMResponse("观点"));

            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    provider, new ToolRegistry(), workspace.toString(), "test-model", 2);
            try {
                CollaborationConfig config = CollaborationConfig.discuss("测试讨论", 1)
                        .addRole(role("甲"))
                        .addRole(role("乙"));
                RecordingCallback callback = new RecordingCallback();

                String result = orchestrator.orchestrateWithStream(config, "测试输入", callback);

                // discuss 工厂不设风格，策略结论走 null style 的既定分支
                assertEquals("讨论完成。", result);
                List<StreamEvent> topologies = callback.allOf(StreamEvent.EventType.COLLABORATE_TOPOLOGY);
                List<StreamEvent> nodeEvents = callback.allOf(StreamEvent.EventType.COLLABORATE_NODE);

                // 顺序：START 最先，END 最后
                assertEquals(StreamEvent.EventType.COLLABORATE_START, callback.events.get(0).getType());
                assertEquals(StreamEvent.EventType.COLLABORATE_END,
                        callback.events.get(callback.events.size() - 1).getType());

                // 初始版 + 终版共两次拓扑下发
                assertEquals(2, topologies.size(), "应下发初始版与终版两份拓扑");

                // 初始版：全 PENDING、无历史边（发言链还没有形成）
                CollaborationTopology initial = (CollaborationTopology) topologies.get(0).getMeta("topology");
                assertNotNull(initial);
                assertEquals(2, initial.getNodes().size());
                assertTrue(initial.getNodes().stream().allMatch(n -> n.getStatus() == NodeStatus.PENDING),
                        "初始版所有节点应为 PENDING");
                assertTrue(initial.getEdges().isEmpty(), "无历史时画不出任何边");

                // 两个角色各点亮一次
                assertEquals(2, nodeEvents.size());
                assertEquals("甲", nodeEvents.get(0).getMeta("nodeId"));
                assertEquals("COMPLETED", nodeEvents.get(0).getMeta("status"));
                assertEquals("乙", nodeEvents.get(1).getMeta("nodeId"));

                // 终版：全部完成，且补上了发言链边
                CollaborationTopology fin = (CollaborationTopology) topologies.get(1).getMeta("topology");
                assertNotNull(fin);
                assertTrue(fin.getNodes().stream().allMatch(n -> n.getStatus() == NodeStatus.COMPLETED));
                assertEquals(1, fin.getEdges().size(), "甲→乙的发言顺序链应已形成");
                assertEquals("甲", fin.getEdges().get(0).getFrom());
                assertEquals("乙", fin.getEdges().get(0).getTo());

                // 拓扑事件在 START 之后、END 之前；节点事件夹在两版拓扑之间
                int startIdx = callback.events.indexOf(callback.allOf(StreamEvent.EventType.COLLABORATE_START).get(0));
                int initialIdx = callback.events.indexOf(topologies.get(0));
                int firstNodeIdx = callback.events.indexOf(nodeEvents.get(0));
                int finalIdx = callback.events.indexOf(topologies.get(1));
                int endIdx = callback.events.indexOf(
                        callback.allOf(StreamEvent.EventType.COLLABORATE_END).get(0));
                assertTrue(startIdx < initialIdx && initialIdx < firstNodeIdx
                        && firstNodeIdx < finalIdx && finalIdx < endIdx,
                        "事件顺序应为 START < 初始拓扑 < 节点点亮 < 终版拓扑 < END");
            } finally {
                orchestrator.shutdown();
            }
        }

        @Test
        @DisplayName("WORKFLOW：节点 RUNNING → COMPLETED 逐个上报，初始/终版拓扑都带分层")
        @SuppressWarnings("unchecked")
        void workflowEventSequence(@TempDir Path workspace) {
            LLMProvider provider = mock(LLMProvider.class);
            when(provider.chat(any(), any(), anyString(), any()))
                    .thenReturn(new LLMResponse("节点产出"));

            WorkflowDefinition definition = new WorkflowDefinition("两步流程")
                    .addNode(WorkflowNode.single("step1", role("分析")))
                    .addNode(WorkflowNode.single("step2", role("执行")).dependsOn("step1"));

            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    provider, new ToolRegistry(), workspace.toString(), "test-model", 2);
            try {
                CollaborationConfig config = CollaborationConfig.workflow("两步任务", definition);
                RecordingCallback callback = new RecordingCallback();

                orchestrator.orchestrateWithStream(config, "工作流输入", callback);

                List<StreamEvent> topologies = callback.allOf(StreamEvent.EventType.COLLABORATE_TOPOLOGY);
                assertEquals(2, topologies.size());

                // 初始版：DAG 分层 [step1] / [step2]，全 PENDING
                CollaborationTopology initial = (CollaborationTopology) topologies.get(0).getMeta("topology");
                assertNotNull(initial);
                assertEquals(CollaborationTopology.Kind.DAG, initial.getKind());
                assertNotNull(initial.getLayers());
                assertEquals(2, initial.getLayers().size());
                assertTrue(initial.getNodes().stream().allMatch(n -> n.getStatus() == NodeStatus.PENDING));

                // 节点事件：每个节点先 RUNNING 后 COMPLETED，按层序到达
                List<StreamEvent> nodeEvents = callback.allOf(StreamEvent.EventType.COLLABORATE_NODE);
                assertEquals(4, nodeEvents.size(), "两个节点各两次（RUNNING + COMPLETED）");
                assertEquals("step1", nodeEvents.get(0).getMeta("nodeId"));
                assertEquals("RUNNING", nodeEvents.get(0).getMeta("status"));
                assertEquals("step1", nodeEvents.get(1).getMeta("nodeId"));
                assertEquals("COMPLETED", nodeEvents.get(1).getMeta("status"));
                assertEquals("step2", nodeEvents.get(2).getMeta("nodeId"));
                assertEquals("RUNNING", nodeEvents.get(2).getMeta("status"));
                assertEquals("step2", nodeEvents.get(3).getMeta("nodeId"));
                assertEquals("COMPLETED", nodeEvents.get(3).getMeta("status"));

                // 终版：全部完成
                CollaborationTopology fin = (CollaborationTopology) topologies.get(1).getMeta("topology");
                assertNotNull(fin);
                assertTrue(fin.getNodes().stream().allMatch(n -> n.getStatus() == NodeStatus.COMPLETED));
                assertEquals(1, fin.getEdges().size(), "step1 → step2 的依赖边");
            } finally {
                orchestrator.shutdown();
            }
        }

        @Test
        @DisplayName("无流式回调时事件链路完全静默且协同照常完成")
        void withoutCallbackStillWorks(@TempDir Path workspace) {
            LLMProvider provider = mock(LLMProvider.class);
            when(provider.chat(any(), any(), anyString(), any()))
                    .thenReturn(new LLMResponse("结论"));

            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    provider, new ToolRegistry(), workspace.toString(), "test-model", 2);
            try {
                CollaborationConfig config = CollaborationConfig.discuss("静默讨论", 1)
                        .addRole(role("甲"));

                String result = orchestrator.orchestrate(config, "输入");

                // 结论由策略构建（null style 的既定分支），不是最后一次发言
                assertEquals("讨论完成。", result, "无回调路径不应被拓扑上报拖累");
            } finally {
                orchestrator.shutdown();
            }
        }
    }
}
