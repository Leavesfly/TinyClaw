# 14 · 定时任务与心跳

> `cron/` + `heartbeat/`：让 Agent 能按时间表自主行动与周期性自省。

---

## 14.1 CronService — 定时任务引擎

### 14.1.1 能力

- **Cron 表达式**：5 字段（分 时 日 月 周），由 `cron-utils 9.2` 解析（Unix 风格）
- **固定间隔**：每 N 秒执行
- **单次定时**：在指定时间点执行一次
- **持久化**：任务变更即落盘 `workspace/cron/jobs.json`
- **并发安全**：`ReentrantReadWriteLock` 保护任务列表

### 14.1.2 核心数据模型

| 类 | 作用 |
|----|------|
| `CronJob` | 任务实体：ID、名称、payload、schedule、启用状态、创建/更新时间 |
| `CronSchedule` | 调度策略：`type=CRON/EVERY/AT`，`cron` 表达式、`every` 秒数、`at` 时间点 |
| `CronJobState` | 运行态：`enabled` / `lastRunAt` / `nextRunAt` / `runCount` / `lastError` |
| `CronPayload` | 任务内容：`message`（消息文本）+ 目标 `channel` + `chatId` |
| `CronStore` | 存储接口，当前唯一实现为 JSON 文件 |

### 14.1.3 运行流程

```text
守护线程（每秒循环）
   │
   ▼
遍历所有 enabled=true 的 CronJob
   │
   ▼
计算 nextRunAt 是否 <= now
   │  是
   ▼
组装 InboundMessage{
    channel = job.payload.channel ?: "system",
    chatId  = job.payload.chatId  ?: "cron",
    content = job.payload.message,
    metadata = {"cronJobId": job.id}
}
   │
   ▼
调用 AgentRuntime.processDirectWithChannel(...)
   │
   ▼
回写 lastRunAt / nextRunAt / runCount
   │
   ▼
CronStore.save(...)
```

### 14.1.4 CLI / 工具 / Web 入口

- **CLI**：`tinyclaw cron list|add|remove|enable|disable ...`
- **工具**：`cron` 工具（Agent 可自主创建任务）
- **Web**：`CronHandler`（REST + UI）

三路入口最终都落到同一个 `CronService` API。

### 14.1.5 消息回流

- 若 `payload.channel == null` → 回到原调用通道（工具调用的上下文）
- 否则 → 直接推送到指定通道（如定时发送飞书提醒给某用户）

---

## 14.2 HeartbeatService — 心跳服务

### 14.2.1 定位

心跳服务让 Agent "活着"：周期性地被动触发思考，不依赖用户输入。用于：

- 整理待办
- 汇总当日反馈
- 触发 `runEvolutionCycle()`（记忆进化、Prompt 优化、工具反思）
- 自主写周报、自检任务队列

### 14.2.2 启用

```json
{
  "agent": {
    "heartbeatEnabled": true
  }
}
```

只在 **gateway 模式**生效（CLI 直连不启动心跳）。

### 14.2.3 流程

```text
守护线程（间隔可配，默认 1 小时）
   │
   ▼
读取 workspace/memory/HEARTBEAT.md
   │   （没有则跳过一次）
   ▼
组装为 InboundMessage（channel="system", chatId="heartbeat"）
   │
   ▼
AgentRuntime.processDirectWithChannel(...)
   │
   ▼
在回调中调用 runEvolutionCycle()
   ├── MemoryEvolver.evolve()
   ├── PromptOptimizer.maybeOptimize()
   ├── ReflectionEngine.runCycle()（若启用）
   └── 会话清理
```

### 14.2.4 HEARTBEAT.md 示例

```markdown
# 心跳上下文

你现在进入了「自省时间」。请按顺序执行：

1. 查看今天未完成的 cron 任务并调整
2. 总结最近 10 条会话的共性问题
3. 如果发现明显可优化的行为，调用 `skills` 工具 create 一个新技能
4. 最后简要报告你做的事

当前时间会被自动注入。
```

此文件**完全由用户自定义**。

---

## 14.3 两者的关系

| 维度 | Cron | Heartbeat |
|------|------|-----------|
| 触发源 | 时间表（Cron/固定间隔/单次） | 固定周期（通常较长，如 1h） |
| 任务来源 | `CronJob.payload.message` | `HEARTBEAT.md` 内容 |
| 目标 | 外部事件（提醒用户、生成日报） | 内部自省（进化、反思、整理） |
| 持久化 | `jobs.json` | `HEARTBEAT.md` |
| 管理界面 | CLI + Tool + Web | 仅文件 |

可以把 Heartbeat 理解为一个"**内置的、特殊的定时任务**"，但它的触发逻辑独立，且能调用 `runEvolutionCycle()`。

---

## 14.4 消息路由特殊性

Cron 与 Heartbeat 产生的消息 `channel="system"`：

- `MessageRouter.routeSystem(...)` 会解析 `metadata` 中的原始通道
- 若能找到原通道，回复路由回原会话
- 若无原通道（如用户从未对话过），回复写入日志或由 `message` 工具主动推送

---

## 14.5 存储文件

```text
workspace/
├── cron/
│   └── jobs.json        ← Cron 任务列表（数组，每项一个 CronJob）
└── memory/
    └── HEARTBEAT.md     ← Heartbeat 上下文
```

`jobs.json` 示例结构：

```json
[
  {
    "id": "job-abc",
    "name": "每日日报",
    "schedule": {"type": "CRON", "cron": "0 18 * * *"},
    "payload": {
      "message": "总结今天的工作并发到飞书",
      "channel": "feishu",
      "chatId": "open_id:xxx"
    },
    "state": {
      "enabled": true,
      "lastRunAt": "2026-05-01T10:00:00Z",
      "nextRunAt": "2026-05-02T10:00:00Z",
      "runCount": 42,
      "lastError": null
    },
    "createdAt": "2026-04-01T09:00:00Z",
    "updatedAt": "2026-05-01T10:00:00Z"
  }
]
```

---

## 14.6 Cron 表达式速查

TinyClaw 采用 **Unix 标准 5 字段**（与 Linux `crontab` 一致）：

```
*  *  *  *  *
分 时 日 月 周
```

| 示例 | 含义 |
|------|------|
| `0 9 * * *` | 每天 09:00 |
| `*/15 * * * *` | 每 15 分钟 |
| `0 9 * * 1` | 每周一 09:00 |
| `0 18 1 * *` | 每月 1 号 18:00 |
| `30 8 * * 1-5` | 工作日（周一到五）08:30 |

如果对更复杂的 Quartz 式 7 字段有需求（含秒），可扩展 `CronSchedule` 的解析器。

---

## 14.7 故障处理

| 场景 | 行为 |
|------|------|
| 单次任务执行失败 | 记 `lastError`，保留任务继续按下次调度运行 |
| JSON 文件损坏 | 启动时告警，回退为空任务列表；原文件备份为 `.bak` |
| Agent 主进程异常 | 守护线程会尝试继续运行，但不恢复丢失的那一次触发 |
| 系统休眠后"补跑" | 默认**不补跑**，以当前时间为起点重新计算 `nextRunAt` |

---

## 14.8 最佳实践

| 建议 | 原因 |
|------|------|
| 心跳周期 ≥ 30 分钟 | 避免频繁 LLM 调用和上下文膨胀 |
| Cron 任务消息写清楚上下文 | LLM 收到时没有历史，需自带背景 |
| 指定 `channel + chatId` | 确保回复正确派送 |
| 在 `HEARTBEAT.md` 里限定职责 | 避免 Agent 在自省时"跑偏" |
| 监控 `lastError` | Web 控制台或日志定期巡检 |

---

## 14.9 下一步

- 自我进化流程 → [12 · 自我进化](12-self-evolution.md)
- 消息路由细节 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
- 工具系统中的 `cron` 工具 → [09 · 工具系统](09-tools-system.md)
