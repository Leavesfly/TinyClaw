# 12 · 自我进化引擎

> `evolution/` + `memory/`：让 Agent 基于反馈持续改进自己。

---

## 12.1 三条进化路径

| 维度 | 目标 | 主要组件 |
|------|------|----------|
| **Prompt 进化** | 持续优化 System Prompt | `FeedbackManager` + `PromptOptimizer` + `VariantManager` |
| **记忆进化** | 从对话中沉淀长期记忆 | `MemoryEvolver` + `MemoryStore` |
| **工具反思（Reflection 2.0）** | 基于工具失败模式生成修复方案 | `ToolCallRecorder` + `ReflectionEngine` + `RepairApplier` |

三条路径彼此独立，可按需启用，统一由心跳 / 摘要节点触发。

---

## 12.2 配置入口

```json
{
  "agent": {
    "evolution": {
      "enabled": true,
      "feedbackEnabled": true,
      "promptOptimizationEnabled": true,
      "strategy": "TEXTUAL_GRADIENT",
      "minFeedbackToTrigger": 10,
      "maxVariants": 5,
      "memoryEvolveEnabled": true
    }
  }
}
```

- `strategy` ∈ `TEXTUAL_GRADIENT` / `OPRO` / `SELF_REFINE`
- `minFeedbackToTrigger` 累积到此数量才跑一次优化
- `maxVariants` 保留最多 N 个变体，超出淘汰低分

---

## 12.3 FeedbackManager — 反馈收集

### 12.3.1 反馈类型（`FeedbackType`）

| 类型 | 说明 |
|------|------|
| `EXPLICIT_RATING` | 用户显式评分（点赞/点踩/1-5 星） |
| `EXPLICIT_TEXT` | 用户文字评价 |
| `IMPLICIT_POSITIVE` | 隐式正信号（「继续」「好」） |
| `IMPLICIT_NEGATIVE` | 隐式负信号（「不对」「重来」「不是这个意思」） |
| `SELF_EVALUATION` | Critic Agent 自评 |
| `MESSAGE_EXCHANGE` | 原始消息交换（供后续分析） |

### 12.3.2 入口

- `recordRating(sessionKey, score, comment)` — Web 控制台 Feedback 页 / 工具 API
- `recordMessageExchange(sessionKey, userMsg, assistantMsg)` — `ReActExecutor` 每轮对话后自动调用
- `recordImplicitSignal(sessionKey, type, text)` — 由启发式规则识别

### 12.3.3 存储

`workspace/evolution/feedback.jsonl`（Append 追加），异步批量写入。

---

## 12.4 PromptOptimizer — 3 种优化策略

### 12.4.1 TEXTUAL_GRADIENT（文本梯度，默认）

```text
1. 聚合最近 N 条负反馈
2. 调 LLM：
   "基于以下反馈，指出当前 Prompt 的问题，并给出改进建议（像计算梯度一样）"
3. 再调 LLM：
   "将改进建议应用到当前 Prompt，输出新 Prompt"
4. 保存为新变体，下轮调用使用
```

适合场景：反馈多样、用户直接指出问题。

### 12.4.2 OPRO（Optimization by PROmpting）

```text
1. 读取历史所有变体及其平均评分
2. 调 LLM（Meta-Prompter）：
   "下面是历史变体与它们的评分，请总结规律并生成更优的 Prompt"
3. 保存为新变体
```

适合场景：需要从变体间的趋势学习。

### 12.4.3 SELF_REFINE（自我反思）

位于 `evolution/strategy/SelfReflectionStrategy.java`：

```text
1. 读取近期会话（含完整对话 + 工具调用）
2. 调 LLM：
   "回顾这些会话，自我评估表现的优劣；列出可改进的 Prompt 行为指令"
3. 调 LLM：将改进指令融入 Prompt
4. 保存为新变体
```

适合场景：少量会话、深度自省。

### 12.4.4 maybeOptimize() 触发机制

- 由 `runEvolutionCycle()` 调用（心跳 / 会话摘要后）
- 当累计反馈 >= `minFeedbackToTrigger` 时才实际跑优化
- 单次优化在**后台线程**执行，不阻塞主流程

---

## 12.5 VariantManager — 变体管理

| 职责 | 实现 |
|------|------|
| 存储变体 | `workspace/evolution/prompts/PROMPT_VARIANTS.json` |
| 激活变体 | `workspace/evolution/prompts/PROMPT_ACTIVE.md` |
| 历史归档 | `workspace/evolution/prompts/PROMPT_HISTORY/` |
| 淘汰策略 | 保留得分 top-N；其余归档 |
| 回滚 | 支持按 ID 回滚到历史变体 |

每个变体含：ID、内容、创建时间、策略、当前得分、使用次数、来源反馈引用。

`ContextBuilder.setPromptOptimizer(...)` 注入后，`IdentitySection` 会用 `PROMPT_ACTIVE.md` 覆盖默认身份段。

---

## 12.6 MemoryEvolver — 记忆进化

### 12.6.1 何时触发

- `SessionSummarizer` 完成一次会话摘要后
- 心跳服务 `runEvolutionCycle()` 周期性调用
- 用户显式调用（如通过 `memory` 相关 API）

### 12.6.2 流程

```text
1. 读最近会话的摘要 + 关键事实
2. 调 LLM：
   "从这段对话中提取值得长期保留的信息（偏好、事实、约定、计划），
    以 MemoryEntry 格式返回"
3. 去重合并（MemoryStore 按内容指纹去重）
4. 追加到 memory/MEMORY.md（或对应 topic 文件）
```

### 12.6.3 MemoryEntry 字段

- `id`：指纹
- `content`：记忆正文
- `source`：来源会话 / 时间
- `tags`：主题标签（可用于分主题存储到 `memory/topics/`）
- `importance`：重要度（用于未来淘汰与检索排序）
- `createdAt` / `updatedAt`

### 12.6.4 MemoryStore

- 文件存储：`memory/MEMORY.md`（主索引） + `memory/topics/{tag}.md`（分主题）
- 读取：`readMemoryContext(limit, relevantTopics)`，供 `MemorySection` 注入
- 写入：`append(MemoryEntry)`；并发用写锁保护
- 提供检索（基于简单关键词匹配，未来可接入向量检索）

---

## 12.7 Reflection 2.0 — 工具反思

目录：`evolution/reflection/`

### 12.7.1 组件链

```text
ToolRegistry.execute(...)
    │
    ▼
ToolCallRecorder （异步批量落盘）
    │
    ▼
workspace/reflection/tool_calls.jsonl
    │
    ▼（定期）
ReflectionEngine.runCycle()
    │
    ├── ErrorClassifier：把 ToolCallEvent 归类（TIMEOUT / BAD_ARGS / PERMISSION / …）
    ├── FailureDetector：识别异常工具（失败率 > 阈值）
    ├── PatternMiner：挖掘失败模式（相同错误聚类）
    ├── 生成 RepairProposal：改工具描述 / 改默认参数 / 加前置校验
    └── RepairApplier.apply(...)
         └── 注入 ToolRegistry.repairApplier：execute 前做参数校验 / 描述覆写
```

### 12.7.2 ToolHealthAggregator

- 聚合每个工具的健康指标：成功率、平均耗时、P95、最近错误样本
- 供 Web 控制台展示「工具健康墙」

### 12.7.3 启用

跟随 `agent.evolution.enabled=true` 自动启用，由 `ProviderManager.applyProvider(...)` 构造 `ProviderComponents` 时一起初始化。

---

## 12.8 runEvolutionCycle() — 统一调度

`AgentRuntime.runEvolutionCycle()` 是心跳的"脑内 CPU 调度"，依次执行：

1. **基于反馈的记忆进化**：对负反馈场景提取改进记忆
2. **常规记忆进化**：从最近会话提取长期记忆
3. **Prompt 优化**：若反馈累积达到阈值则跑一次 `PromptOptimizer`
4. **会话清理**：清理过期会话
5. **工具反思**：（若开启）运行 `ReflectionEngine`

每一步独立容错，单步异常不影响后续。

---

## 12.9 Web 控制台集成

| 页面 | Handler | 能力 |
|------|---------|------|
| Feedback | `FeedbackHandler` | 查看反馈列表、手动提交反馈 |
| Prompts | `ReflectionHandler` / `ConfigHandler` | 查看变体、激活 / 回滚 |
| Tools Health | `ReflectionHandler` | 工具健康指标 |
| Memory | `WorkspaceHandler` | 浏览 memory/*.md |

---

## 12.10 最佳实践

| 建议 | 原因 |
|------|------|
| 生产初期先开 `feedbackEnabled=true`，关 `promptOptimizationEnabled` | 先积累数据再做优化，避免过早优化破坏基线 |
| 新场景先用 `TEXTUAL_GRADIENT` | 反馈信号最直接，效果可预测 |
| 数据积累后尝试 `OPRO` | 能跳出局部最优 |
| 开启 `memoryEvolveEnabled` | 长期对话的关键体验来源 |
| 定期 Web 控制台回滚验证 | 保证「进化」不变成「退化」 |

---

## 12.11 扩展

- **新增优化策略**：实现 `OptimizationStrategy`，在 `PromptOptimizer.initStrategies()` 注册
- **自定义反馈源**：调用 `FeedbackManager.record*`
- **自定义记忆提取**：继承 `MemoryEvolver`
- **自定义修复应用**：扩展 `RepairApplier`

详见 [20 · 扩展开发](20-extending.md)。

---

## 12.12 下一步

- 会话与记忆存储 → [15 · 会话与记忆](15-session-memory.md)
- 心跳触发进化 → [14 · 定时 & 心跳](14-cron-heartbeat.md)
- 工具系统 → [09 · 工具系统](09-tools-system.md)
