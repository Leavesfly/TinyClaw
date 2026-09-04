package io.leavesfly.tinyclaw.collaboration;

import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.Kind;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.NodeStatus;
import io.leavesfly.tinyclaw.collaboration.CollaborationTopology.TopoNode;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowContext;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowEngine;
import io.leavesfly.tinyclaw.collaboration.workflow.WorkflowTopologyBuilder;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从协同配置与共享上下文构建 {@link CollaborationTopology}。
 *
 * <h2>职责边界</h2>
 * <p>只负责「把已有结构翻译成图」，不参与协同执行，也不修改任何入参。
 * WORKFLOW 模式委托给 {@link WorkflowTopologyBuilder}（它需要 workflow 包内可见的
 * 拓扑排序），其余三种形态在本类内完成。</p>
 *
 * <h2>失败即放弃</h2>
 * <p>拓扑是协同记录的附加信息，不是结论本身。任何异常都吞掉并返回 {@code null}，
 * 让 {@link CollaborationRecord} 照常落盘、前端照常降级为线性时间线——
 * 画图失败不该让用户丢掉一次几分钟的协同结果。</p>
 */
public final class CollaborationTopologyBuilder {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    /** 边总数上限。超出说明配置已非人类可读的规模，画图也只会是一团糊 */
    private static final int MAX_EDGES = 500;

    /** 编排器/系统消息的 agentId，不作为图上的发言节点 */
    private static final String SYSTEM_AGENT_ID = "system";

    private CollaborationTopologyBuilder() {
    }

    /**
     * 按协同模式构建拓扑快照。
     *
     * @param config  协同配置，为 {@code null} 时返回 {@code null}
     * @param context 共享上下文，为 {@code null} 时仅能画出结构、画不出状态
     * @return 拓扑快照；无法构建或不值得渲染时返回 {@code null}
     */
    public static CollaborationTopology build(CollaborationConfig config, SharedContext context) {
        if (config == null || config.getMode() == null) {
            return null;
        }
        try {
            CollaborationTopology topology = switch (config.getMode()) {
                case DISCUSS -> buildDiscussion(config, context);
                case TASKS -> config.getTasksStyle() == CollaborationConfig.TasksStyle.HIERARCHY
                        ? buildHierarchy(config, context)
                        : buildTaskGraph(config, context);
                case WORKFLOW -> buildWorkflow(config, context);
            };
            return topology != null && topology.isRenderable() ? topology : null;
        } catch (RuntimeException e) {
            logger.warn("构建协同拓扑失败，降级为无拓扑记录", Map.of(
                    "mode", config.getMode().name(),
                    "error", String.valueOf(e.getMessage())));
            return null;
        }
    }

    // =========================================================================
    // DISCUSS —— 角色交互图
    // =========================================================================

    /**
     * 构建讨论型拓扑。
     *
     * <p>边的来源按优先级取三者之一：</p>
     * <ol>
     *   <li><b>定向消息</b>：{@link AgentMessage#getTargetRole()} 非空，直接构成 from→to，
     *       同一条边多次出现则累加权重；</li>
     *   <li><b>路由决策</b>：DYNAMIC 风格下 Router 每轮先发一条 SYSTEM 消息再点名下一个发言者
     *       （见 {@code DiscussionStrategy#executeDynamic}），据此还原 router→speaker 的星形结构；</li>
     *   <li><b>发言顺序链</b>：前两者都没有时（DEBATE / CONSENSUS 的常见情形），
     *       按历史顺序把相邻的不同发言者连成链，至少让「谁在谁之后说」可见。</li>
     * </ol>
     */
    private static CollaborationTopology buildDiscussion(CollaborationConfig config, SharedContext context) {
        List<AgentMessage> history = context != null ? context.getHistory() : List.of();

        // 角色集合：优先取配置，配置为空时从历史里的实际发言者还原
        Set<String> roleNames = new LinkedHashSet<>();
        if (config.getRoles() != null) {
            for (AgentRole role : config.getRoles()) {
                if (role != null && role.getRoleName() != null) {
                    roleNames.add(role.getRoleName());
                }
            }
        }
        for (AgentMessage msg : history) {
            if (!isSystemMessage(msg) && msg.getAgentRole() != null) {
                roleNames.add(msg.getAgentRole());
            }
        }
        if (roleNames.isEmpty()) {
            return null;
        }

        Set<String> spoken = collectSpeakers(history);

        CollaborationTopology topology = new CollaborationTopology(Kind.DISCUSSION);
        if (config.getDiscussStyle() != null) {
            topology.setStyle(config.getDiscussStyle().name());
        }

        Map<String, String> prompts = collectPrompts(config.getRoles());
        for (String roleName : roleNames) {
            topology.addNode(new TopoNode(
                    roleName,
                    roleName,
                    "AGENT",
                    spoken.contains(roleName) ? NodeStatus.COMPLETED : NodeStatus.PENDING)
                    .withDetail(prompts.get(roleName)));
        }

        // Router 是独立的发言者，但不在 config.getRoles() 里，需要单独补节点
        String routerName = addRouterNode(topology, config, history, spoken);

        Map<String, Integer> directed = collectDirectedEdges(history, roleNames);
        Map<String, Integer> routed = routerName != null
                ? collectRoutingEdges(history, routerName, roleNames)
                : Map.of();

        if (!directed.isEmpty()) {
            flushEdges(topology, directed);
        } else if (!routed.isEmpty()) {
            flushEdges(topology, routed);
        } else {
            flushEdges(topology, collectSpeechChain(history, roleNames));
        }

        topology.putMeta("participants", roleNames.size());
        topology.putMeta("totalMessages", history.size());
        topology.putMeta("totalRounds", context != null ? context.getCurrentRound() : 0);
        return topology;
    }

    /**
     * 补上 Router 节点。
     *
     * @return Router 的角色名，未识别出 Router 时返回 {@code null}
     */
    private static String addRouterNode(CollaborationTopology topology, CollaborationConfig config,
                                        List<AgentMessage> history, Set<String> spoken) {
        String routerName = config.getRouterRole() != null ? config.getRouterRole().getRoleName() : null;

        // 配置里没显式指定 Router 时，从历史中的 SYSTEM 消息回推（DYNAMIC 会留下痕迹）
        if (routerName == null) {
            for (AgentMessage msg : history) {
                if (msg.getMessageType() == AgentMessage.MessageType.SYSTEM
                        && msg.getAgentRole() != null
                        && !SYSTEM_AGENT_ID.equals(msg.getAgentId())) {
                    routerName = msg.getAgentRole();
                    break;
                }
            }
        }

        if (routerName == null) {
            return null;
        }
        // Router 也可能同时列在 config.getRoles() 里，此时节点已存在，不要重复添加
        if (topology.findNode(routerName) == null) {
            topology.addNode(new TopoNode(
                    routerName,
                    routerName,
                    "ROUTER",
                    spoken.contains(routerName) ? NodeStatus.COMPLETED : NodeStatus.PENDING)
                    .withDetail("动态路由：每轮决定下一个发言者"));
        }
        return routerName;
    }

    /**
     * 收集定向消息构成的边，键为 {@code from\u0000to}，值为消息条数。
     */
    private static Map<String, Integer> collectDirectedEdges(List<AgentMessage> history, Set<String> known) {
        Map<String, Integer> edges = new LinkedHashMap<>();
        for (AgentMessage msg : history) {
            if (isSystemMessage(msg) || !msg.isDirected()) {
                continue;
            }
            String from = msg.getAgentRole();
            String to = msg.getTargetRole();
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            // 指向图外角色的消息不画：孤立的悬空边只会让布局算出无意义的坐标
            if (!known.contains(from) || !known.contains(to)) {
                continue;
            }
            edges.merge(from + '\u0000' + to, 1, Integer::sum);
        }
        return edges;
    }

    /**
     * 收集 DYNAMIC 模式下的路由边：Router 的 SYSTEM 消息之后紧邻的发言者即为被点名人。
     */
    private static Map<String, Integer> collectRoutingEdges(List<AgentMessage> history,
                                                            String routerName, Set<String> known) {
        Map<String, Integer> edges = new LinkedHashMap<>();
        boolean pendingRouting = false;
        for (AgentMessage msg : history) {
            if (isSystemMessage(msg)) {
                // 只有 Router 自己发的 SYSTEM 消息才算一次点名意图；
                // 编排器的 "未找到角色" 之类提示不构成路由
                pendingRouting = routerName.equals(msg.getAgentRole());
                continue;
            }
            if (pendingRouting && msg.getAgentRole() != null && known.contains(msg.getAgentRole())) {
                edges.merge(routerName + '\u0000' + msg.getAgentRole(), 1, Integer::sum);
                pendingRouting = false;
            }
        }
        return edges;
    }

    /**
     * 按发言顺序把相邻的不同发言者连成链。
     *
     * <p>这是没有任何定向信息时的兜底：它表达的是「发言时序」而非「互动关系」，
     * 但对辩论/共识这类轮转发言的场景，时序本身就是最有意义的结构。</p>
     */
    private static Map<String, Integer> collectSpeechChain(List<AgentMessage> history, Set<String> known) {
        Map<String, Integer> edges = new LinkedHashMap<>();
        String previous = null;
        for (AgentMessage msg : history) {
            if (isSystemMessage(msg)) {
                continue;
            }
            String speaker = msg.getAgentRole();
            if (speaker == null || !known.contains(speaker) || speaker.equals(previous)) {
                continue;
            }
            if (previous != null) {
                edges.merge(previous + '\u0000' + speaker, 1, Integer::sum);
            }
            previous = speaker;
        }
        return edges;
    }

    /**
     * 把累加好的边权重写入拓扑。超过上限时截断并打标，避免生成病态大小的记录文件。
     */
    private static void flushEdges(CollaborationTopology topology, Map<String, Integer> edges) {
        int count = 0;
        for (Map.Entry<String, Integer> entry : edges.entrySet()) {
            if (count++ >= MAX_EDGES) {
                topology.putMeta("edgesTruncated", true);
                break;
            }
            String[] parts = entry.getKey().split("\u0000", 2);
            if (parts.length == 2) {
                topology.addEdge(parts[0], parts[1], null, entry.getValue());
            }
        }
    }

    // =========================================================================
    // TASKS / PARALLEL —— 任务依赖图
    // =========================================================================

    /**
     * 构建任务依赖图。节点为 {@link TeamTask}，边为 {@code dependsOn}。
     *
     * <p>{@code TeamTask} 的状态由 {@code TasksStrategy} 在执行中原地更新，
     * 而 {@code config.getTasks()} 返回的是同一批对象，因此记录时拿到的即最终状态。</p>
     */
    private static CollaborationTopology buildTaskGraph(CollaborationConfig config, SharedContext context) {
        List<TeamTask> tasks = config.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }

        CollaborationTopology topology = new CollaborationTopology(Kind.TASK_GRAPH);
        topology.setStyle(config.getTasksStyle() != null ? config.getTasksStyle().name() : null);

        Set<String> taskIds = new LinkedHashSet<>();
        for (TeamTask task : tasks) {
            if (task != null && task.getTaskId() != null) {
                taskIds.add(task.getTaskId());
            }
        }

        for (TeamTask task : tasks) {
            if (task == null || task.getTaskId() == null) {
                continue;
            }
            List<String> agents = new ArrayList<>();
            if (task.getAssignee() != null && task.getAssignee().getRoleName() != null) {
                agents.add(task.getAssignee().getRoleName());
            }
            topology.addNode(new TopoNode(
                    task.getTaskId(),
                    task.getTaskName() != null ? task.getTaskName() : task.getTaskId(),
                    "TASK",
                    mapTaskStatus(task.getStatus()))
                    .withAgents(agents)
                    .withDetail(task.getDescription()));
        }

        for (TeamTask task : tasks) {
            if (task == null || task.getDependsOn() == null) {
                continue;
            }
            for (String dep : task.getDependsOn()) {
                // 依赖了集合外的任务 id 时跳过：那属于配置错误，画一条指向虚空的边没有意义
                if (dep != null && taskIds.contains(dep)) {
                    topology.addEdge(dep, task.getTaskId(), null, 1);
                }
            }
        }

        topology.setLayers(layerByDepth(taskIds, tasks));
        topology.putMeta("taskCount", tasks.size());
        if (context != null) {
            topology.putMeta("totalRounds", context.getCurrentRound());
        }
        return topology;
    }

    /**
     * 按依赖深度分层：无依赖者为第 0 层，其余为「所有前置的最大深度 + 1」。
     *
     * <p>用 visiting 集合挡住环：任务依赖成环时深度无法收敛，
     * 此时放弃分层（返回 {@code null}）让前端退化为环形布局，而不是栈溢出。</p>
     */
    private static List<List<String>> layerByDepth(Set<String> taskIds, List<TeamTask> tasks) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (TeamTask task : tasks) {
            if (task == null || task.getTaskId() == null) {
                continue;
            }
            List<String> valid = new ArrayList<>();
            if (task.getDependsOn() != null) {
                for (String dep : task.getDependsOn()) {
                    if (dep != null && taskIds.contains(dep) && !dep.equals(task.getTaskId())) {
                        valid.add(dep);
                    }
                }
            }
            deps.put(task.getTaskId(), valid);
        }

        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String id : taskIds) {
            if (resolveDepth(id, deps, depth, new LinkedHashSet<>()) < 0) {
                return null;
            }
        }

        int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<String>> layers = new ArrayList<>(maxDepth + 1);
        for (int i = 0; i <= maxDepth; i++) {
            layers.add(new ArrayList<>());
        }
        for (Map.Entry<String, Integer> entry : depth.entrySet()) {
            layers.get(entry.getValue()).add(entry.getKey());
        }
        layers.removeIf(List::isEmpty);
        return layers.isEmpty() ? null : layers;
    }

    /**
     * 递归求深度。
     *
     * @return 深度；检测到环时返回 {@code -1}
     */
    private static int resolveDepth(String id, Map<String, List<String>> deps,
                                    Map<String, Integer> cache, Set<String> visiting) {
        Integer cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(id)) {
            return -1;
        }
        int result = 0;
        for (String dep : deps.getOrDefault(id, List.of())) {
            int depDepth = resolveDepth(dep, deps, cache, visiting);
            if (depDepth < 0) {
                visiting.remove(id);
                return -1;
            }
            result = Math.max(result, depDepth + 1);
        }
        visiting.remove(id);
        cache.put(id, result);
        return result;
    }

    private static NodeStatus mapTaskStatus(TeamTask.TaskStatus status) {
        if (status == null) {
            return NodeStatus.PENDING;
        }
        return switch (status) {
            case RUNNING -> NodeStatus.RUNNING;
            case COMPLETED -> NodeStatus.COMPLETED;
            case FAILED -> NodeStatus.FAILED;
            case PENDING -> NodeStatus.PENDING;
        };
    }

    // =========================================================================
    // TASKS / HIERARCHY —— 金字塔层级图
    // =========================================================================

    /**
     * 构建层级图。
     *
     * <p>边是「下层每个角色 → 上层每个角色」的全连接：{@code TasksStrategy} 的
     * {@code executeLevelInParallel} 把下一层的<b>全部</b>结果传给上层每个 Agent，
     * 全连接正是这一语义的忠实表达，不该为了好看而稀疏化。</p>
     *
     * <p>同一角色名可能出现在多层（例如同一个人在两层都参与），因此节点 id
     * 用 {@code L<层号>:<角色名>} 命名空间化，避免相互覆盖。</p>
     */
    private static CollaborationTopology buildHierarchy(CollaborationConfig config, SharedContext context) {
        HierarchyConfig hierarchy = config.getHierarchy();
        if (hierarchy == null || !hierarchy.isValid()) {
            return null;
        }

        List<AgentMessage> history = context != null ? context.getHistory() : List.of();
        Set<String> spoken = collectSpeakers(history);
        Map<String, String> prompts = new LinkedHashMap<>();
        for (List<AgentRole> level : hierarchy.getLevels()) {
            for (AgentRole role : level) {
                if (role != null && role.getRoleName() != null) {
                    prompts.putIfAbsent(role.getRoleName(), role.getSystemPrompt());
                }
            }
        }

        CollaborationTopology topology = new CollaborationTopology(Kind.HIERARCHY);
        topology.setStyle(CollaborationConfig.TasksStyle.HIERARCHY.name());

        List<List<AgentRole>> levels = hierarchy.getLevels();
        List<List<String>> layers = new ArrayList<>(levels.size());

        for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
            List<String> layerIds = new ArrayList<>();
            for (AgentRole role : levels.get(levelIndex)) {
                if (role == null || role.getRoleName() == null) {
                    continue;
                }
                String nodeId = "L" + levelIndex + ":" + role.getRoleName();
                topology.addNode(new TopoNode(
                        nodeId,
                        role.getRoleName(),
                        "AGENT",
                        spoken.contains(role.getRoleName()) ? NodeStatus.COMPLETED : NodeStatus.PENDING)
                        .withDetail(role.getSystemPrompt() != null
                                ? role.getSystemPrompt() : prompts.get(role.getRoleName())));
                layerIds.add(nodeId);
            }
            layers.add(layerIds);
        }

        edgeLoop:
        for (int levelIndex = 0; levelIndex + 1 < layers.size(); levelIndex++) {
            List<String> lower = layers.get(levelIndex);
            List<String> upper = layers.get(levelIndex + 1);
            for (String from : lower) {
                for (String to : upper) {
                    if (topology.getEdges().size() >= MAX_EDGES) {
                        topology.putMeta("edgesTruncated", true);
                        break edgeLoop;
                    }
                    topology.addEdge(from, to, "汇报", 1);
                }
            }
        }

        layers.removeIf(List::isEmpty);
        topology.setLayers(layers.isEmpty() ? null : layers);
        topology.putMeta("levelCount", levels.size());
        return topology;
    }

    // =========================================================================
    // WORKFLOW —— DAG
    // =========================================================================

    /**
     * 构建 DAG 拓扑，委托给 {@link WorkflowTopologyBuilder}。
     *
     * <p>执行期的 {@link WorkflowContext} 由 {@code WorkflowEngine} 在跑完后透出到
     * {@link SharedContext} 的 meta（键见 {@link WorkflowEngine#META_WORKFLOW_CONTEXT}），
     * 这里取出它以获得节点的真实终态。取不到时仍能画出结构，只是状态全为 PENDING。</p>
     */
    private static CollaborationTopology buildWorkflow(CollaborationConfig config, SharedContext context) {
        WorkflowContext workflowContext = null;
        if (context != null) {
            Object stashed = context.getMeta(WorkflowEngine.META_WORKFLOW_CONTEXT);
            if (stashed instanceof WorkflowContext wc) {
                workflowContext = wc;
            }
        }
        return WorkflowTopologyBuilder.from(config.getWorkflow(), workflowContext);
    }

    // =========================================================================
    // 公共辅助
    // =========================================================================

    /**
     * 判断是否为编排器产生的系统消息（不代表任何角色的发言）。
     */
    private static boolean isSystemMessage(AgentMessage msg) {
        return msg == null
                || msg.getMessageType() == AgentMessage.MessageType.SYSTEM
                || SYSTEM_AGENT_ID.equals(msg.getAgentId());
    }

    /**
     * 收集历史中实际发过言的角色名（排除系统消息）。
     */
    private static Set<String> collectSpeakers(List<AgentMessage> history) {
        Set<String> speakers = new LinkedHashSet<>();
        for (AgentMessage msg : history) {
            if (!isSystemMessage(msg) && msg.getAgentRole() != null) {
                speakers.add(msg.getAgentRole());
            }
        }
        return speakers;
    }

    /**
     * 角色名 → 系统提示词，用于节点详情。
     */
    private static Map<String, String> collectPrompts(List<AgentRole> roles) {
        Map<String, String> prompts = new LinkedHashMap<>();
        if (roles == null) {
            return prompts;
        }
        for (AgentRole role : roles) {
            if (role != null && role.getRoleName() != null) {
                prompts.putIfAbsent(role.getRoleName(), role.getSystemPrompt());
            }
        }
        return prompts;
    }
}
