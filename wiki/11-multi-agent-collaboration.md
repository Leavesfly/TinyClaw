# 11 · 多 Agent 协同

> `collaboration/` 包：通过 `collaborate` 工具让多个 Agent 角色协同完成复杂任务。

---

## 11.1 设计思路

TinyClaw 的多 Agent 协同系统遵循：

- **统一入口**：`collaborate` 工具 → `AgentOrchestrator.orchestrate(...)`
- **策略模式**：`CollaborationStrategy` 抽象「玩法」，7 种模式共享同一编排器
- **共享上下文**：`SharedContext` 管理讨论消息、Artifact、共识度
- **工作流引擎**：`workflow/` 子包提供 6 种节点类型，支持 LLM 动态生成流程
- **优雅降级**：协同失败自动回退到单 Agent
- **结果回流**：协同结论自动追加回主会话历史
- **可记录**：每次协同自动落盘到 `workspace/collaboration/`

---

## 11.2 组件全景

```text
CollaborateTool (工具入口，实现 StreamAwareTool)
       │
       ▼
AgentOrchestrator (编排器)
       │
       ├── CollaborationConfig  (模式、角色、轮次、超时、预算等)
       ├── SharedContext        (讨论消息 + Artifact + 共识度)
       ├── RoleAgent × N        (各角色独立 LLM 会话)
       ├── CollaborationExecutorPool (协同线程池)
       └── CollaborationRecord  (记录 + 落盘)
       │
       ▼
CollaborationStrategy (策略接口)
       │
       ├── DiscussionStrategy   → debate / roleplay / consensus
       ├── TasksStrategy        → team / hierarchy
       ├── WorkflowStrategy     → workflow（调用 WorkflowEngine）
       └── （Dynamic Routing）  → dynamic（由 DiscussionStrategy 变体实现）
```

---

## 11.3 7 种协同模式

| 模式 | 策略 | 典型用法 |
|------|------|----------|
| `debate` | `DiscussionStrategy` | 正反方辩论、利弊权衡、方案评审 |
| `roleplay` | `DiscussionStrategy` | 多角色对话模拟、场景演练、剧本推演 |
| `consensus` | `DiscussionStrategy` | 多方讨论 → 投票 → 达成共识 |
| `team` | `TasksStrategy` | 任务分解 → 子任务并行/串行执行 |
| `hierarchy` | `TasksStrategy` | 层级汇报：下级独立工作 → 上级汇总 → 必要时审批 |
| `workflow` | `WorkflowStrategy` | 多步骤工作流（支持 LLM 动态生成） |
| `dynamic` | 动态路由变体 | Router Agent 每轮决定下一个发言者 |

在调用 `collaborate` 工具时通过 `mode` 参数指定。

---

## 11.4 关键数据结构

### 11.4.1 CollaborationConfig

包含协同的所有参数（JSON 友好，LLM 可直接组装）：

- `mode`：上述 7 种之一
- `roles: List<AgentRole>`：参与的角色清单（name / systemPrompt / model? / temperature?）
- `maxRounds`：讨论最大轮次（默认 3）
- `consensusThreshold`：共识阈值（0.0–1.0）
- `timeoutMs`：整体超时
- `tokenBudget`：总 Token 预算，超额自动终止
- `criticEnabled`：是否启用 Critic Agent 做结果评审
- `fallbackToSingle`：失败时是否降级
- 进阶：`hierarchyConfig` / `workflowDefinition` / `approvalCallback`

### 11.4.2 AgentRole

```java
class AgentRole {
    String name;             // 如 "架构师"、"正方"
    String systemPrompt;     // 角色人设
    String model;            // 可覆盖默认模型
    Double temperature;      // 可覆盖默认温度
    List<String> allowedTools; // 受限工具集
}
```

### 11.4.3 SharedContext

```java
class SharedContext {
    String topic;                        // 协同主题
    List<AgentMessage> messages;         // 全部发言（含作者、时间、角色）
    Map<String, Artifact> artifacts;     // 中间产物（代码/文档/决策）
    double consensusScore;               // 当前共识度
    Map<String, Object> metadata;        // 扩展
}
```

### 11.4.4 CollaborationRecord

协同完整记录，`AgentOrchestrator` 在协同结束后落盘到：

```text
workspace/collaboration/{yyyyMMdd}/{sessionKey}-{timestamp}.json
```

字段包含：配置快照、SharedContext 最终状态、每轮 messages、Token 用量、耗时、结论。

### 11.4.5 CollaborationTopology（协同关系拓扑）

记录新增 `topology` 字段，把一次协同的「结构」归一为 nodes + edges (+ layers)：

| 模式 | Kind | 节点 | 边 | 分层 |
|------|------|------|-----|------|
| DISCUSS | `DISCUSSION` | 角色（+ Router） | 定向消息（`targetRole`）；否则 Router 路由边；否则发言顺序链 | 无（前端环形布局） |
| TASKS · PARALLEL | `TASK_GRAPH` | TeamTask | 任务依赖 | 按依赖深度 |
| TASKS · HIERARCHY | `HIERARCHY` | 各层角色（id 带 `L{层号}:` 前缀） | 下层→上层汇报（全连接） | HierarchyConfig 层级 |
| WORKFLOW | `DAG` | WorkflowNode | `dependsOn` | 复用 `WorkflowEngine.topologicalSort` |

节点状态（COMPLETED / FAILED / SKIPPED / RUNNING / PENDING）反映真实执行结果：
WORKFLOW 取自 `WorkflowEngine` 透出到 `SharedContext` meta 的执行期上下文
（`WorkflowEngine.META_WORKFLOW_CONTEXT`，中断时也能拿到已跑完的部分状态）。

构建器：`CollaborationTopologyBuilder`（讨论/任务/层级）+ `WorkflowTopologyBuilder`
（DAG，与引擎同包以便复用包级可见的拓扑排序）。
任何异常都吞掉并返回 null，记录照常落盘、前端降级为纯线性时间线。

### 11.4.6 实时拓扑（协同执行中）

协同进行中，拓扑会通过两个流式事件驱动 Web 控制台的实时图：

| 事件 | 时机 | 载荷 |
|------|------|------|
| `COLLABORATE_TOPOLOGY` | 协同开始（初始版，全 PENDING）与结束前（终版，含真实状态与边） | 完整拓扑结构 |
| `COLLABORATE_NODE` | 每个节点/任务/角色状态迁移时 | `nodeId` / `label` / `status` |

事件序列：`START → TOPOLOGY(初始) → NODE×N → TOPOLOGY(终版) → END`。
终版全量替换初始图，用户最终看到的图与落盘记录一致。

节点上报收敛在 `SharedContext.reportNodeStatus()`（无回调时零开销，回调异常被吞），
各模式的发射点：

- **WORKFLOW**：`WorkflowEngine.executeNodeWithRetry` 的六个迁移点（检查点恢复/分支跳过/
  审批拒绝/依赖失败/开始/终态），含 RUNNING → COMPLETED 的两次闪烁；
- **TASKS**：`TasksStrategy` 的任务 markStarted/markCompleted/markFailed 与阻塞标记，
  HIERARCHY 按层上报（节点 id 与拓扑的 `L{层号}:{角色名}` 命名空间一致）；
- **DISCUSS**：各角色发言完毕时上报 COMPLETED（DYNAMIC 还含 Router 总结）。

CLI/IM 纯文本通道下这两个事件降级为一行提示（`format()`），不污染正文。

---

## 11.5 DiscussionStrategy — 讨论族

用于 `debate` / `roleplay` / `consensus` / `dynamic`。

### 核心流程

```text
for round in 0..maxRounds:
    for role in roles:   # consensus/debate 轮询；dynamic 由 Router 选
        ctx = 组装角色 System + SharedContext.history
        reply = role.llm(ctx)
        SharedContext.messages.add(reply)
        向上游流式回调 TEXT_DELTA

    if mode == consensus:
        score = Critic.evaluate(SharedContext)
        if score >= consensusThreshold: break
    if aborted or tokenBudgetExceeded: break

# 汇总
summary = SummaryAgent.summarize(SharedContext)
return summary
```

### dynamic 模式

- 额外一个 **Router Agent**，每轮开始时读 SharedContext，输出「下一个发言者名字 + 提问/任务」
- 适合「专家咨询」：Router 根据话题把问题派给对应专家

---

## 11.6 TasksStrategy — 任务族

用于 `team` / `hierarchy`。

### team（团队分解）

```text
1. Planner 把 topic 拆成 TeamTask × N
   - 每个 task 指定负责角色、依赖关系、串/并行
2. 按 DAG 执行：并行任务用 CollaborationExecutorPool 多线程
3. 每个 task 的产出写进 SharedContext.artifacts
4. Aggregator 汇总所有 artifacts
```

### hierarchy（层级汇报）

```text
1. HierarchyConfig 定义 N 层（如：executor → manager → director）
2. 底层 executor 独立执行各自子任务
3. manager 逐层汇总下级产出，必要时发起 ApprovalCallback 请求审批
4. 最终 director 得到完整报告
```

`ApprovalCallback` 可以挂 LLM-as-Judge 或人工审批回调。

---

## 11.7 WorkflowStrategy — 工作流引擎

### 11.7.1 WorkflowDefinition

```java
class WorkflowDefinition {
    String name;
    String description;
    List<WorkflowNode> nodes;
    String outputExpression;   // 最终输出从哪个节点取
}
```

### 11.7.2 6 种 WorkflowNode

| 类型 | 说明 |
|------|------|
| `SINGLE` | 单个 Agent 节点，执行一次 LLM 调用 |
| `PARALLEL` | 并行执行多个子节点（`CollaborationExecutorPool`） |
| `SEQUENTIAL` | 顺序执行子节点，前一个结果传给后一个 |
| `CONDITIONAL` | 条件分支，根据表达式结果选择分支 |
| `LOOP` | 循环，直到满足退出条件或达到上限 |
| `AGGREGATE` | 聚合多个输入节点的结果（合并/投票/最优） |

### 11.7.3 WorkflowEngine

关键能力：

- **依赖解析**：基于节点 `dependencies` 构建 DAG 并拓扑排序
- **条件执行**：表达式支持 `{{var}}` 替换（从 `WorkflowContext` 读）
- **超时 / 重试**：每个节点可设置
- **结果存储**：节点结果入 `WorkflowContext.variables`，供后续节点引用
- **优雅失败**：某节点失败可选择跳过或整体终止

### 11.7.4 WorkflowGenerator（LLM 动态生成）

输入 `topic` → LLM 产出合法的 `WorkflowDefinition` JSON：

```text
用户："调研 Rust 异步运行时，对比 3 个主流方案"
   ↓
WorkflowGenerator.generate(topic)
   ↓
{
  "nodes": [
    { "type": "PARALLEL", "children": [调研 tokio, 调研 async-std, 调研 smol] },
    { "type": "SINGLE", "name": "对比表", "dependsOn": [above] },
    { "type": "SINGLE", "name": "推荐结论", "dependsOn": [对比表] }
  ]
}
   ↓
WorkflowEngine.execute(definition)
```

---

## 11.8 增强特性

| 特性 | 说明 |
|------|------|
| **Token 预算** | `tokenBudget` 超出即终止，防失控 |
| **Critic Agent** | 协同结束前评估结果质量，不合格触发重试 / 降级 |
| **优雅降级** | 协同初始化失败、超时等场景自动落回单 Agent 处理 |
| **结论回流** | 最终 summary 自动 append 到调用方主会话 history，保持上下文连续 |
| **自反馈循环** | Critic 给出评分 → `FeedbackManager.record` → 驱动 Prompt 优化 |
| **流式输出** | `CollaborateTool` 实现 `StreamAwareTool`，逐轮发言实时推送 |
| **协同记录** | JSON 落盘，Web 控制台可回放 |

---

## 11.9 `collaborate` 工具参数

| 参数 | 说明 |
|------|------|
| `mode` | 模式（7 种之一） |
| `topic` | 主题/任务描述 |
| `roles` | 角色数组（可省略，使用 `AgentConfig.collaboration.roleTemplates[mode]`） |
| `maxRounds` | 覆盖默认轮次 |
| `tokenBudget` | 覆盖默认预算 |
| `workflow` | 模式为 `workflow` 时：`auto` 触发 `WorkflowGenerator`，或传 `WorkflowDefinition` JSON |

LLM 调用示例（`tool_call`）：

```json
{
  "name": "collaborate",
  "arguments": {
    "mode": "debate",
    "topic": "是否应该把我们的后端从 Java 迁移到 Go",
    "roles": [
      {"name": "支持迁移", "systemPrompt": "你认为迁移利大于弊，从性能、人力成本、生态角度论证"},
      {"name": "反对迁移", "systemPrompt": "你认为迁移弊大于利，强调稳定性、学习成本、既有投资"}
    ],
    "maxRounds": 3
  }
}
```

---

## 11.10 线程模型

- `AgentOrchestrator.orchestrate(...)` 在调用者线程（通常是 ReActExecutor 的主线程）执行
- 并行子任务通过 `CollaborationExecutorPool`（有界线程池）执行
- 每个 `RoleAgent` 独立持有一份 `LLMProvider` 引用，但共享全局 OkHttpClient
- **协同只能由主 Agent 发起**：`RoleAgent` 与子代理的工具集均通过 `ToolRegistry.exclude(...)`
  剔除 `collaborate`，因此嵌套协同在工具可见性层面就不存在（而不是运行时拦截）。
  编排器、各策略实例与协同线程池都是单例，嵌套会让流式回调、进度卡、策略内部状态
  与线程池队列互相覆盖

---

## 11.11 与 SubagentManager 的区别

| 维度 | `spawn`（SubagentManager） | `collaborate`（AgentOrchestrator） |
|------|----------------------------|------------------------------------|
| 场景 | 单个子任务委托 | 多角色协同 |
| 数量 | 1 个子代理 | N 个角色 |
| 上下文 | 独立会话，不共享 | SharedContext 可见 |
| 策略 | 只有「执行」 | 7 种策略 |
| 成本 | 低 | 中-高 |

---

## 11.12 Web 控制台

网关模式下可在 Web UI：

- 查看历史协同记录（`workspace/collaboration/` 下的 JSON）
- 回放协同过程（逐轮展示 messages 与 artifacts）
- **协同关系拓扑图**：会话历史的协同卡片可在「时间线 / 拓扑图」之间切换，
  手写 SVG 零依赖渲染，四种形态对应四种布局：
  - DISCUSS → 环形布局，边宽随互动频次变化；
  - DAG / TASK / HIERARCHY → 分层布局（自底向上，与引擎执行顺序一致）；
  - 悬停节点高亮相邻关系，点击查看详情（提示词/条件/依赖）；
  - 节点状态色点区分 COMPLETED（绿）/ FAILED（红）/ SKIPPED（灰）/ PENDING（浅灰）。
  数据来自 `CollaborationRecord.topology`，旧记录无此字段时自动降级为纯时间线。
- **实时拓扑（协同执行中）**：协同卡片顶部内嵌可折叠的实时图，节点随
  `COLLABORATE_NODE` 事件逐个点亮（外科手术式更新状态圆点，不重建 SVG），
  头部显示进度（已完成/总数），终版拓扑到达后全量替换（见 11.4.6）。
- 手动触发一次协同

对应 REST Handler 位于 `web/handler/` 中（`SessionsHandler` / `WorkspaceHandler`）。

---

## 11.13 扩展：新增策略

1. 实现 `CollaborationStrategy`（或继承 `AbstractCollaborationStrategy`）
2. 在 `AgentOrchestrator.initStrategies()` 注册
3. 在 `CollaborationConfig.Mode` 增加枚举值

详见 [20 · 扩展开发](20-extending.md)。

---

## 11.14 下一步

- 想玩自动进化 → [12 · 自我进化](12-self-evolution.md)
- 工具系统背景 → [09 · 工具系统](09-tools-system.md)
- 角色模板配置 → [04 · 配置指南 §4.2.2](04-configuration.md)
