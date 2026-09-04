package io.leavesfly.tinyclaw.collaboration;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协同关系拓扑快照 —— 把一次协同的「结构」归一为 nodes + edges (+ layers)。
 *
 * <h2>为什么要归一</h2>
 * <p>四种协同形态（讨论的角色交互、工作流的 DAG、任务的依赖图、分层决策的金字塔）
 * 底层数据结构完全不同：{@link AgentMessage#getTargetRole()}、
 * {@code WorkflowNode#getDependsOn()}、{@link TeamTask#getDependsOn()}、
 * {@link HierarchyConfig#getLevels()}。若把它们各自原样塞进协同记录，前端就得写四套渲染器。
 * 归一成有向图之后，前端只需一个渲染器，靠 {@link Kind} 与 {@code layers} 是否为空
 * 决定用分层布局还是环形布局。</p>
 *
 * <h2>为什么是快照而非引用</h2>
 * <p>本对象随 {@link CollaborationRecord} 落盘，供事后回看。因此只存渲染所需的
 * 标量字段（id / label / status / 权重），不持有 {@code WorkflowDefinition}、
 * {@code WorkflowContext} 等运行期对象——它们既不可序列化，也会让记录文件体积失控。</p>
 *
 * <h2>向后兼容</h2>
 * <p>旧记录文件没有本字段，反序列化后为 {@code null}；前端据此降级为纯线性时间线，
 * 不报错、不空白。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollaborationTopology {

    /**
     * 拓扑形态。决定前端的布局策略与图例文案。
     */
    public enum Kind {
        /** 角色交互图：节点=角色，边=定向发言（或按发言顺序推导的轮次链） */
        DISCUSSION,
        /** 有向无环图：节点=工作流节点，边=dependsOn，带拓扑分层 */
        DAG,
        /** 任务依赖图：节点=TeamTask，边=任务依赖 */
        TASK_GRAPH,
        /** 金字塔层级图：节点=各层角色，边=下层向上层汇报 */
        HIERARCHY
    }

    /**
     * 节点状态。与各来源枚举（{@code NodeResult.Status}、{@code TeamTask.TaskStatus}）
     * 取值一致，避免前端再做一次映射。
     */
    public enum NodeStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    /** 拓扑形态 */
    private Kind kind;

    /** 子风格名（DiscussStyle / TasksStyle），仅用于前端标题展示 */
    private String style;

    /** 节点列表 */
    private List<TopoNode> nodes = new ArrayList<>();

    /** 边列表 */
    private List<TopoEdge> edges = new ArrayList<>();

    /**
     * 分层布局提示，自底向上（索引 0 为最下层）。
     *
     * <p>为 {@code null} 时前端改用环形布局。DAG 直接复用
     * {@code WorkflowEngine#topologicalSort} 的分层结果，HIERARCHY 用
     * {@link HierarchyConfig} 的层级，TASK_GRAPH 按依赖深度分层。</p>
     */
    private List<List<String>> layers;

    /** 附加元信息（工作流名、超时、输出表达式等），前端按需展示 */
    private Map<String, Object> meta;

    public CollaborationTopology() {
    }

    public CollaborationTopology(Kind kind) {
        this.kind = kind;
    }

    // -------------------------------------------------------------------------
    // 构建辅助
    // -------------------------------------------------------------------------

    /**
     * 追加一个节点。
     *
     * @return this，支持链式调用
     */
    public CollaborationTopology addNode(TopoNode node) {
        if (node != null) {
            nodes.add(node);
        }
        return this;
    }

    /**
     * 追加一条边。自环（from == to）会被丢弃：角色对自己发言在图上没有信息量，
     * 且会让环形布局的贝塞尔控制点退化成一个点。
     *
     * @return this，支持链式调用
     */
    public CollaborationTopology addEdge(String from, String to, String label, int weight) {
        if (from == null || to == null || from.equals(to)) {
            return this;
        }
        TopoEdge edge = new TopoEdge();
        edge.setFrom(from);
        edge.setTo(to);
        edge.setLabel(label);
        edge.setWeight(Math.max(1, weight));
        edges.add(edge);
        return this;
    }

    /**
     * 写入一条元信息。
     *
     * @return this，支持链式调用
     */
    public CollaborationTopology putMeta(String key, Object value) {
        if (key == null || value == null) {
            return this;
        }
        if (meta == null) {
            meta = new LinkedHashMap<>();
        }
        meta.put(key, value);
        return this;
    }

    /**
     * 按 id 查找节点。
     *
     * @return 匹配的节点，未找到时返回 {@code null}
     */
    public TopoNode findNode(String id) {
        if (id == null) {
            return null;
        }
        for (TopoNode node : nodes) {
            if (id.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 判断拓扑是否值得渲染。空图（无节点）会让前端画出一张空白画布，
     * 不如直接返回 {@code null} 走线性时间线降级。
     */
    public boolean isRenderable() {
        return kind != null && !nodes.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public List<TopoNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<TopoNode> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    public List<TopoEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<TopoEdge> edges) {
        this.edges = edges != null ? edges : new ArrayList<>();
    }

    public List<List<String>> getLayers() {
        return layers;
    }

    public void setLayers(List<List<String>> layers) {
        this.layers = layers;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    // -------------------------------------------------------------------------
    // 节点与边
    // -------------------------------------------------------------------------

    /**
     * 拓扑节点。对应一个角色、一个工作流节点或一个团队任务。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TopoNode {

        /** 节点唯一标识（角色名 / 工作流节点 id / 任务 id） */
        private String id;

        /** 展示名 */
        private String label;

        /**
         * 节点类型：{@code AGENT} / {@code ROUTER} / {@code TASK}，
         * 或工作流的 {@code NodeType} 名（SINGLE/PARALLEL/…）。前端据此选形状与图标。
         */
        private String type;

        /** 执行状态 */
        private NodeStatus status;

        /** 节点内参与的角色名（工作流的多 Agent 节点会有多个） */
        private List<String> agents;

        /** 详情摘要（提示词 / 任务描述 / 条件表达式），前端在点击节点时展示 */
        private String detail;

        public TopoNode() {
        }

        public TopoNode(String id, String label, String type, NodeStatus status) {
            this.id = id;
            this.label = label != null ? label : id;
            this.type = type;
            this.status = status;
        }

        /**
         * 设置节点详情，超长时截断。
         *
         * <p>截断是必要的：角色提示词动辄上千字，全量写进每条协同记录会让
         * {@code workspace/collaboration/} 迅速膨胀，而图上只需要一个悬浮提示。</p>
         */
        public TopoNode withDetail(String detail) {
            this.detail = truncate(detail, 240);
            return this;
        }

        public TopoNode withAgents(List<String> agents) {
            this.agents = agents != null && !agents.isEmpty() ? new ArrayList<>(agents) : null;
            return this;
        }

        private static String truncate(String value, int max) {
            if (value == null) {
                return null;
            }
            String trimmed = value.strip();
            if (trimmed.length() <= max) {
                return trimmed.isEmpty() ? null : trimmed;
            }
            return trimmed.substring(0, max) + "…";
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public NodeStatus getStatus() {
            return status;
        }

        public void setStatus(NodeStatus status) {
            this.status = status;
        }

        public List<String> getAgents() {
            return agents;
        }

        public void setAgents(List<String> agents) {
            this.agents = agents;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    /**
     * 拓扑有向边。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TopoEdge {

        /** 起点节点 id */
        private String from;

        /** 终点节点 id */
        private String to;

        /** 边标签（如消息条数、"汇报"），可为空 */
        private String label;

        /**
         * 权重，默认 1。DISCUSSION 下为两个角色间的消息条数，
         * 前端映射为线宽与不透明度，让「互动最密集的一对」一眼可见。
         */
        private int weight = 1;

        public TopoEdge() {
        }

        public TopoEdge(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
