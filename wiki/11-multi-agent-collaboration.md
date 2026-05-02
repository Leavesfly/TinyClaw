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
- 协同内部若再触发 `collaborate`，会被安全检查拦截（防递归爆炸）

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
