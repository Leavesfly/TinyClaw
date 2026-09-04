# 14 · 定时任务与心跳

> `cron/` + `heartbeat/`：让 Agent 能按时间表自主行动与周期性自省。
> 心跳机制对齐 OpenClaw 模型：默认安静、按需冒泡，确定性任务归 Cron。

---

## 14.1 CronService — 定时任务引擎

### 14.1.1 能力

- **Cron 表达式**：5 字段（分 时 日 月 周），由 `cron-utils 9.2` 解析（Unix 风格）
- **固定间隔（EVERY）**：每 N 毫秒执行，支持 **misfire 补跑**（见 14.7）
- **单次定时（AT）**：在指定时间点执行一次
- **执行历史**：每次运行记录 `CronRunRecord`（状态 ok/error/timeout、触发方式 schedule/misfire/manual、耗时、错误、结果摘要），每任务保留最近 20 条，随 `jobs.json` 持久化
- **手动触发**：`CronService.runJobNow(jobId)` / Web `POST /api/cron/{id}/run`，异步执行不阻塞调用方
- **持久化**：任务变更即落盘 `workspace/cron/jobs.json`，含 `state.lastRunAtMs` 用于重启补跑判断
- **并发安全**：`ReentrantReadWriteLock` 保护任务列表
- **系统内置 job**：以 `__` 前后缀命名（`__heartbeat__`、`__memory_evolution__`），由 `GatewayBootstrap` 的复合 onJob handler 按名称分发

### 14.1.2 核心数据模型

| 类 | 作用 |
|----|------|
| `CronJob` | 任务实体：ID、名称、payload、schedule、启用状态、创建/更新时间 |
| `CronSchedule` | 调度策略：`kind=cron/every/at`，`cron` 表达式、`everyMs`、`at` 时间点 |
| `CronJobState` | 运行态：`lastRunAtMs` / `nextRunAtMs` / `lastStatus` / `lastError` / `history` |
| `CronRunRecord` | 单次执行记录：`startedAtMs` / `durationMs` / `status` / `trigger` / `error` / `result` |
| `CronPayload` | 任务内容：`kind` + `message`（消息文本）+ 目标 `channel` / `to` |

### 14.1.3 运行流程

```text
调度线程（每秒循环）
   │
   ▼
遍历所有 enabled=true 且 nextRunAtMs <= now 的 CronJob
   │
   ▼
调用 onJob handler（GatewayBootstrap 注入的复合分发器）
   ├── name 以 "__heartbeat__" 开头 → HeartbeatRunner.runOnceForJob(name)
   ├── name == "__memory_evolution__" → triggerMemoryEvolution()（带去重）
   └── 其余 → CronTool.executeJob(job)
              → AgentRuntime.processDirectWithChannel(...)
   │
   ▼
回写 lastRunAtMs / nextRunAtMs / runCount → CronStore.save(...)
   │
   ▼
追加 CronRunRecord 到 state.history（最新在前，上限 20 条）→ 落盘
```

### 14.1.4 CLI / 工具 / Web 入口

- **CLI**：`tinyclaw cron list|add|edit|remove|enable|disable ...`（list 含上次运行状态）
- **工具**：`cron` 工具（Agent 可自主创建任务）
- **Web**：`CronHandler`（REST + UI）：列表含 `lastStatus`/`history`；`POST /api/cron/{id}/run` 手动触发；`PUT /api/cron/{id}` 编辑任务；Cron 页面支持 Edit / Run / History

三路入口最终都落到同一个 `CronService` API。

### 14.1.5 消息回流

- 若 `payload.channel == null` → 回到原调用通道（工具调用的上下文）
- 否则 → 直接推送到指定通道（如定时发送飞书提醒给某用户）

---

## 14.2 心跳（HeartbeatRunner）

### 14.2.1 定位

心跳是"**周期性的被动觉察**"：按固定节奏读一遍 `HEARTBEAT.md` 清单，让 Agent 自查一轮，只在发现异常时冒泡告警。**不做**确定性周期任务（那是 Cron 的职责），**不再**内嵌记忆进化（已拆为独立 job）。

心跳没有独立线程：tick 由 `CronService` 的内置 job `__heartbeat__` 调度，`HeartbeatRunner` 是该 job 的执行体。

### 14.2.2 启用与配置

```json
{
  "agent": {
    "heartbeatEnabled": true,
    "heartbeat": {
      "enabled": true,
      "intervalSeconds": 1800,
      "timeoutSeconds": 0,
      "prompt": null,
      "model": null,
      "isolatedSession": true,
      "lightContext": false,
      "target": "none",
      "showOk": false,
      "showAlerts": true,
      "activeHours": { "start": "09:00", "end": "21:00", "timezone": null },
      "entries": null
    }
  }
}
```

| 字段 | 默认 | 说明 |
|------|------|------|
| `heartbeatEnabled` / `heartbeat.enabled` | `false` | 读写同一字段，兼容别名 |
| `intervalSeconds` | `1800` | 心跳间隔；`<=0` 视为禁用 |
| `timeoutSeconds` | `0` | 整轮超时；`0` 表示取 `min(interval, 600)` |
| `prompt` | `null` | 覆盖默认 prompt 指令体（清单始终附加） |
| `model` | `null` | 心跳专用模型；要求 `isolatedSession=true`，否则启动时 WARN 并忽略 |
| `isolatedSession` | `true` | 每轮用一次性 sessionKey，跑完即删，避免会话无限膨胀 |
| `lightContext` | `false` | `true` 时跳过 workspace bootstrap 文件注入 |
| `target` | `"none"` | 告警投递：`none`（仅日志）/ `last`（最近一次入站消息的 channel/chatId）/ 显式 channel 名 |
| `showOk` | `false` | 是否展示 HEARTBEAT_OK 轮次 |
| `showAlerts` | `true` | 是否处理告警；与 `showOk` 均关 → 整轮跳过 |
| `activeHours` | `null` | 活跃时段（HH:mm，`start==end` 为零宽窗口全部跳过）；时区缺省用系统时区 |
| `entries` | `null` | per-agent 配置：key=agent 名，见 14.2.7 |

只在 **gateway 模式**生效（CLI 直连不启动心跳）。

### 14.2.3 每轮门控与执行流程

```text
cron tick（__heartbeat__）
   │
   ▼
门控检查（按序，任一命中则跳过并记录 reason）：
   1. 心跳禁用              → SKIPPED_DISABLED
   2. showOk/showAlerts 均关 → SKIPPED_ALERTS_DISABLED
   3. activeHours 窗口外     → SKIPPED_HOURS
   4. Agent 忙（有任务在跑） → SKIPPED_BUSY
   5. HEARTBEAT.md 缺失或无实质内容 → SKIPPED_EMPTY
   │
   ▼
buildPrompt（默认指令 + 清单，清单 8 KiB 硬上限）
   │
   ▼
经单线程 ExecutorService 提交 AgentRuntime.processDirect(content, sessionKey, lightContext)
   future.get(timeoutSeconds)；超时 → cancel(true) + abortCurrentTask()，记 TIMEOUT
   │
   ▼
handleResult（HEARTBEAT_OK 契约）
```

**HEARTBEAT_OK 响应契约**：默认 prompt 要求 Agent 无事时回复 `HEARTBEAT_OK`。回复首/尾的 `HEARTBEAT_OK` 被剥离后，剩余内容为空或 ≤300 字符 → 静默丢弃；否则视为告警，按 `target` 投递（`none` 时仅记日志）。

**空清单判定**：去除空行、Markdown 标题、单行 HTML 注释、代码围栏、空 checklist stub（`- [ ]` 无内容）后无实质行 → 跳过整轮，不调 LLM。

**状态与日志**：每轮结果（时间、状态、reason、耗时）落盘 `workspace/memory/heartbeat-status.json`，日志统一走 `TinyClawLogger`（不再写 `memory/heartbeat.log`）。

### 14.2.4 HEARTBEAT.md 示例

```markdown
# 心跳清单

- 检查昨天的部署是否全部成功
- 看看有没有未回复的用户反馈
- 磁盘/配额等资源是否有告警迹象
```

此文件**完全由用户自定义**，但请保持精简——**8 KiB 硬上限**，超限内容会被截断并 WARN。"保持精简"不是约定而是约束：清单每轮都会注入 prompt。

### 14.2.5 记忆进化独立 job

记忆进化（`MemoryEvolver` / `PromptOptimizer` / `ReflectionEngine`）不再挂在心跳回调里，而是独立内置 job `__memory_evolution__`（EVERY，间隔与心跳同为 30 分钟起步；内部已有 24h 冷却 + 新记忆条数门控，天然幂等，且 `AtomicBoolean` 防止上一轮未完成时重复触发）。见 [12 · 自我进化](12-self-evolution.md)。

### 14.2.6 手动触发与状态查询

- **CLI**：`tinyclaw heartbeat now`（经 Web Console API 立即触发一轮，仍受 busy guard）、`tinyclaw heartbeat last`（显示上次心跳时间/结果/跳过原因）
- **Web**：`GET /api/heartbeat` 返回各 agent 的 lastRun 状态；`POST /api/heartbeat/now` 触发；Web Console 心跳面板可视化
- **CLI**：`tinyclaw status` 附带输出上次心跳结果

### 14.2.7 per-agent 心跳

`heartbeat.entries` 以 agent 名为 key 提供独立配置（可覆盖 prompt/model/interval/activeHours 等，未设置项回填基础配置）。任一 entry 存在时，只注册这些 agent 的心跳 job（`__heartbeat__:<agentId>`），不再注册默认 `__heartbeat__` job。

---

## 14.3 两者的关系

| 维度 | Cron | Heartbeat |
|------|------|-----------|
| 触发源 | 时间表（cron 表达式/固定间隔/单次） | 固定周期（默认 30 分钟） |
| 任务来源 | `CronJob.payload.message` | `HEARTBEAT.md` 清单 |
| 目标 | 确定性任务（提醒、日报、清理） | 周期性觉察与异常冒泡 |
| 回复处理 | 按 payload 通道投递 | HEARTBEAT_OK 契约：无事静默、有事告警 |
| 持久化 | `jobs.json` | `HEARTBEAT.md` + `heartbeat-status.json` |
| 管理界面 | CLI + Tool + Web | CLI `heartbeat` + Web 面板 |

心跳本质就是 CronService 中的一个**系统内置 job**，与用户 cron 任务共用调度器、共享 busy 语义。

---

## 14.4 消息路由特殊性

Cron 任务消息 `channel="system"`，由复合 onJob handler 经 `CronTool.executeJob` → `processDirectWithChannel` 执行：

- 指定了 `channel + to` → 回复直接推送到目标通道
- 未指定 → 回到原调用上下文

心跳告警（`target="last"`）投递到**最近一次入站消息**的 channel/chatId（由 `MessageRouter` 维护的 `LastContact`），经 `MessageBus.publishOutbound` 出站。

---

## 14.5 存储文件

```text
workspace/
├── cron/
│   └── jobs.json              ← Cron 任务列表（含系统内置 job）
└── memory/
    ├── HEARTBEAT.md           ← 心跳清单（用户自定义，≤8 KiB）
    └── heartbeat-status.json  ← 各 agent 上次心跳状态
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

---

## 14.7 故障处理

| 场景 | 行为 |
|------|------|
| 单次任务执行失败 | 记 `lastError`，保留任务继续按下次调度运行 |
| JSON 文件损坏 | 启动时告警，回退为空任务列表；原文件备份为 `.bak` |
| 心跳整轮超时 | `future.cancel(true)` + `abortCurrentTask()`，记 `TIMEOUT` |
| Agent 忙（任务进行中） | 心跳本轮跳过（`SKIPPED_BUSY`），下轮正常 |
| EVERY 任务 misfire | 启动重算时若 `lastRunAtMs + everyMs <= now`，下次运行时间设为 now（启动后补跑一次） |
| CRON 任务 misfire | 启动重算时若最近应执行点（`lastExecution(now)`）晚于 `lastRunAtMs`，启动后补跑一次；从未执行过的新任务不补跑 |
| AT 任务错过 | 不补跑（一次性任务过期即失效） |

---

## 14.8 最佳实践

| 建议 | 原因 |
|------|------|
| 心跳周期 ≥ 30 分钟 | 避免频繁 LLM 调用和上下文膨胀 |
| HEARTBEAT.md 保持精简 | 每轮注入 prompt，且有 8 KiB 硬上限 |
| 保持 `isolatedSession=true` | 避免心跳会话无限膨胀与 model bleed |
| 无事让 Agent 回 `HEARTBEAT_OK` | 默认 prompt 已包含该契约，勿删除 |
| 确定性任务写成 cron job | 心跳只做觉察，不做确定性周期任务 |
| Cron 任务消息写清楚上下文 | LLM 收到时没有历史，需自带背景 |
| 指定 `channel + to` | 确保回复正确派送 |
| 监控 `lastError` | Web 控制台或日志定期巡检 |

---

## 14.9 下一步

- 自我进化流程 → [12 · 自我进化](12-self-evolution.md)
- 消息路由细节 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
- 工具系统中的 `cron` 工具 → [09 · 工具系统](09-tools-system.md)
