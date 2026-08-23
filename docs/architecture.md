## TinyClaw 技术架构文档

> 版本：0.1.0 ｜ 最后更新：2026-08-22

---

## 一、项目概述

**TinyClaw** 是一个用 Java 编写的超轻量个人 AI 助手框架，提供多模型、多通道、多技能的一站式 AI Agent 能力。它以命令行工具和网关服务为入口，通过安全沙箱、工具系统、技能系统、MCP 协议集成、多 Agent 协同编排、自我进化引擎和 Web 控制台，把一个 LLM 封装成可在本地或服务器长期运行的「多通道智能体」。

### 1.1 核心设计理念

- **轻量化与可移植**：纯 Java 实现，无需 Spring 等重型框架，使用 Maven 构建，单 JAR 即可部署到任意支持 Java 17 的环境。
- **模块解耦**：入口 CLI、Agent 引擎、消息总线、通道适配、LLM Provider、工具系统、技能系统、MCP 集成、协同编排、进化引擎等通过清晰接口解耦，便于替换和扩展。
- **配置驱动**：使用 `config.json`、工作空间内 Markdown 文件（AGENTS / SOUL / USER / IDENTITY / SKILL）驱动 Agent 行为与个性。
- **工具优先**：围绕工具调用（function calling）设计，Agent 通过工具执行文件操作、Shell 命令、网络访问、定时任务、子代理、多 Agent 协同等复杂动作。
- **安全优先**：内置 **SecurityGuard**，对文件操作和命令执行实施工作空间沙箱与命令黑名单，适合长期运行与生产环境。
- **自我进化**：内置反馈收集、Prompt 自动优化和记忆进化机制，Agent 能持续改进自身表现。
- **可观测与可演示**：提供 Web 控制台（含 18 个 REST API Handler）、结构化日志体系以及 Demo 命令，方便现场演示和日常运维。

### 1.2 技术栈概览

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven |
| HTTP 客户端 | OkHttp 4.12 |
| JSON 处理 | Jackson 2.17 |
| 日志 | SLF4J + Logback |
| 命令行 | JLine 3.25 |
| Cron | cron-utils 9.2 |
| 环境变量 | dotenv-java 3.0 |
| 测试 | JUnit 5.10 + Mockito |

---

## 二、整体架构

### 2.1 架构总览

从上到下，可以分为六层：CLI / 网关入口层 → Agent 引擎层 → 消息总线与通道层 → LLM 提供商与工具系统 → 高级能力层（协同 / 进化 / MCP）→ 基础设施层。

```text
┌──────────────────────────────────────────────────────┐
│                 CLI & Gateway 入口层                   │
│  TinyClaw.java + CliCommand 子类                      │
│  onboard / agent / gateway / status / cron /          │
│  skills / mcp / demo / version                        │
└──────────────────────────┬───────────────────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
      ┌─────────────┐  ┌─────────┐  ┌────────────────┐
      │ Agent 引擎   │  │ 网关服务 │  │ Web 控制台      │
      │ AgentRuntime    │  │ Gateway  │  │ WebConsoleServer│
      │ MessageRouter│  │ Bootstrap│  │ + 18 Handlers  │
      │ ProviderMgr  │  └────┬────┘  └────┬───────────┘
      └──────┬───────┘       │            │
             │               │            │
             ▼               │            │
     ┌─────────────────────────────────────────────┐
     │             消息总线 MessageBus              │
     │   inboundQueue ◄───► outboundQueue          │
     └────────┬──────────────────────┬─────────────┘
              │                      │
              ▼                      ▼
     ┌─────────────────┐    ┌──────────────────────┐
     │ LLMProvider     │    │ 消息通道层 Channels   │
     │ HTTPProvider    │    │ Telegram / Discord /  │
     │ ProviderManager │    │ Feishu / DingTalk /   │
     └────────┬────────┘    │ WhatsApp / QQ /       │
              │             │ MaixCam               │
       ┌──────┴──────┐     └──────────┬────────────┘
       ▼             ▼                │
┌────────────┐ ┌───────────┐         │
│ 工具系统    │ │ MCP 集成   │         │
│ ToolRegistry│ │ MCPManager│         │
│ + 15 工具  │ │ + Clients │         │
└──────┬─────┘ └───────────┘         │
       │                              │
  ┌────┴──────────────────────────────┴──────┐
  │            高级能力层                      │
  ├──────────────┬──────────────┬─────────────┤
  │ 多Agent协同   │ 自我进化引擎  │ 技能系统     │
  │ Orchestrator  │ PromptOptim. │ SkillsLoader │
  │ + 6种策略     │ FeedbackMgr  │ SkillRegistry│
  │ + Workflow    │ MemoryEvolver│ SkillSearch  │
  │   Engine      │              │ SkillInstall │
  └──────────────┴──────────────┴─────────────┘
       │                │               │
       ▼                ▼               ▼
  ┌──────────────────────────────────────────┐
  │           基础设施层                      │
  │ Config / Session / Security / Logger /   │
  │ Cron / Heartbeat / Voice / Util          │
  └──────────────────────────────────────────┘
```

### 2.2 分层视角

| 层次 | 包路径 | 职责 |
|------|--------|------|
| **入口层** | `cli/`, `TinyClaw.java` | 命令行解析、命令分发、网关/Agent 启动 |
| **装配层** | `bootstrap/` | 组合根：集中构建对象图，保证有状态单例全局唯一 |
| **Agent 引擎层** | `agent/` | 生命周期、消息路由、Provider 管理、上下文构建、会话摘要 |
| **推理层** | `react/` | ReAct 循环（LLM 调用 + 工具迭代），被 agent / collaboration / subagent 共用 |
| **通信层** | `bus/`, `channels/`, `providers/` | 消息总线、7 种通道适配、LLM HTTP 调用与流式输出 |
| **工具与 MCP 层** | `tools/`, `mcp/`, `subagent/` | 内置工具、MCP 客户端（SSE / Stdio / Streamable HTTP）、子代理 |
| **高级能力层** | `collaboration/`, `evolution/`, `memory/`, `skills/`, `plugins/`, `hooks/` | 多 Agent 协同编排、Prompt 自动优化、工具级自我调试（Reflection 2.0）、记忆进化、技能与插件、生命周期 Hook |
| **基础设施层** | `config/`, `session/`, `security/`, `logger/`, `cron/`, `heartbeat/`, `voice/`, `util/`, `web/` | 配置管理、会话持久化、安全沙箱、结构化日志、定时任务、心跳、语音转写、Web 控制台 |

### 2.3 包依赖约束

包依赖关系保持**无循环**（无双向依赖、无传递环），依赖方向自上而下单向：

```text
cli → bootstrap → agent → react → tools / providers / session / hooks
                   ↘ collaboration ↗
                   ↘ subagent      ↗
```

几条关键约束（修改时请勿破坏）：

- **`config/` 不依赖业务包**：只允许向下用 `util/`。所有配置类（包括 `EvolutionConfig` /
  `ReflectionConfig` / `HeartbeatSettings` 等）都放在 `config/`，而不是各自的业务包里。
- **`react/` 不得依赖 `agent/`**：ReActExecutor 是被多方复用的底层循环，比 AgentRuntime 更底层。
- **`tools/` 只放真正的 Tool 实现与注册表**：需要回调上层引擎的工具放在它服务的包里
  （`CollaborateTool` → `collaboration/`、`SpawnTool` → `subagent/`、`MCPTool` → `mcp/`）。
- **跨层反向引用用窄接口倒置**：如 `ReflectionEngine` 依赖 `ToolDefinitionLookup` 而非
  `ToolRegistry`，适配在装配层（`ProviderManager`）完成。
- **`util/` 是叶包**：只允许依赖 `logger/`。内部状态落盘统一走 `util/JsonFileStore`，
  业务包不再各自实现原子写；但工具/命令向用户文件的写入不走它（见下文「文件持久化保证」）。

---

## 三、核心模块

### 3.1 应用入口 — TinyClaw

**位置**：`io.leavesfly.tinyclaw.TinyClaw`

- 使用 `LinkedHashMap<String, Supplier<CliCommand>>` 维护命令注册表。
- 已注册命令：`onboard`、`agent`、`gateway`、`status`、`cron`、`skills`、`mcp`、`demo`，以及内置的 `version`。
- `run(String[] args)` 负责：无参数时打印帮助；`version` / `--version` / `-v` 输出版本；其余命令从注册表查找并执行。
- 典型调用链：
  - CLI 交互模式：`TinyClaw.main` → `AgentCommand` → `GatewayBootstrap` / 直接创建 `AgentRuntime`
  - 网关模式：`TinyClaw.main` → `GatewayCommand` → 启动消息通道 + AgentRuntime + WebConsoleServer

### 3.2 Agent 引擎 — `agent/`

Agent 引擎是 TinyClaw 的核心，经过重构后采用**职责分离**设计，将原来集中在 AgentRuntime 中的逻辑拆分为多个专职组件：

| 组件 | 职责 |
|------|------|
| `AgentRuntime` | 生命周期管理、消息消费主循环、直连模式入口 |
| `MessageRouter` | 消息路由（用户消息 / 系统消息 / 指令消息）、流式输出选择 |
| `ProviderManager` | LLM Provider 初始化、热重载、模型路由、组件构建 |
| `ProviderComponents` | Provider 派生组件容器（Summarizer / Evolver / Orchestrator / Reflection 等） |
| `ContextBuilder` | 系统提示组装（分段式架构） |
| `SessionSummarizer` | 会话摘要与上下文压缩 |

> `ReActExecutor`（LLM 调用与工具迭代循环）位于独立的 `react/` 包。它被 AgentRuntime、
> 协同的 `RoleAgent` 和子代理的 `SubagentManager` 共同复用，因此层级低于 `agent/`。

#### AgentRuntime — 生命周期与消息消费

- 支持两类入口：
  - `run()`：网关模式，持续从 `MessageBus.consumeInbound()` 取消息
  - `processDirect(...)` / `processDirectStream(...)`：CLI / Web 控制台直连模式
- 初始化时创建 `ToolRegistry`、`SessionManager`、`ContextBuilder`、`MessageRouter`、`ProviderManager`
- 通过 `ProviderManager` 管理 LLM Provider 的生命周期，支持运行时热切换

#### MessageRouter — 消息路由

从 AgentRuntime 中抽取的消息路由器，负责：
- **用户消息**：构建上下文 → LLM 调用 → 持久化 → 发布回复
- **系统消息**（`channel=system`）：解析原始来源，路由回原始会话
- **指令消息**（如 `/new`）：执行指令逻辑（创建新会话等）
- **流式输出判断**：根据目标通道是否支持流式，选择对应的 LLM 执行路径

#### ProviderManager — Provider 管理

- **模型路由**：从 `ModelsConfig` 反查 model 对应的 provider，保证 api_base 与 model 始终来自同一绑定关系
- **热重载**：`reloadModel()` 支持运行时切换模型和 Provider，无需重启
- **组件构建**：`applyProvider()` 一次性构建所有派生组件（ReActExecutor、SessionSummarizer、MemoryEvolver、FeedbackManager、PromptOptimizer、AgentOrchestrator）
- 使用 `volatile` + `synchronized` 保证线程安全

#### ReActExecutor — LLM 迭代与工具调用

- 使用 `LLMProvider.chat` / `chatStream` 调用远端模型
- 工具调用循环：解析 tool_calls → `ToolRegistry.execute(...)` → 追加结果 → 再次调用 LLM
- 最多迭代 `maxIterations` 次防止无限循环
- 集成 `TokenUsageStore` 记录 Token 用量
- 集成 `FeedbackManager` 记录消息交换（用于进化系统）

#### ContextBuilder — 分段式上下文构建

采用 **ContextSection** 接口实现模块化的系统提示组装：

| Section | 职责 |
|---------|------|
| `IdentitySection` | Agent 身份（AGENTS.md / SOUL.md / USER.md / IDENTITY.md） |
| `BootstrapSection` | 基础行为指令、当前时间、通道信息 |
| `ToolsSection` | 工具摘要（来自 ToolRegistry） |
| `SkillsSection` | 技能摘要（来自 SkillsLoader），支持语义搜索匹配 |
| `MemorySection` | 长期记忆上下文（来自 MemoryStore） |

每个 Section 实现 `ContextSection` 接口的 `build(SectionContext)` 方法，`ContextBuilder` 按序组装各段内容。支持注入 `PromptOptimizer` 的优化结果覆盖默认身份提示。

#### SessionSummarizer — 会话摘要

- 根据消息数量与 Token 估算判断是否需要摘要
- 保留最近 N 条消息，对较早消息进行分批摘要
- 摘要在后台守护线程中异步执行
- 集成 `MemoryEvolver`：摘要完成后触发记忆进化，从对话中提取长期记忆

### 3.3 消息总线 — `bus/`

- `MessageBus` 提供统一的入站/出站队列：
  - `LinkedBlockingQueue<InboundMessage> inbound`（有界队列）
  - `LinkedBlockingQueue<OutboundMessage> outbound`（有界队列）
- 通道层只负责：收到平台消息 → 组装 `InboundMessage` → `publishInbound`
- Agent 只依赖 `consumeInbound` / `publishOutbound`，与各平台 SDK 完全解耦
- `InboundMessage` 支持指令消息（`isCommand()`）和多模态内容
- 队列满时丢弃消息并记录日志，防止级联故障
- `BusClosedException` 用于优雅关闭时的信号传递

### 3.4 消息通道层 — `channels/`

**核心接口**：`Channel`、`BaseChannel`、`ChannelManager`、`WebhookServer`、`Reconnector`

- `Channel` 定义统一能力：`name()` / `start()` / `stop()` / `send(OutboundMessage)` / `isAllowed(senderId)` / `supportsStreaming()`
- `BaseChannel` 封装通用逻辑（白名单校验、日志等）
- `ChannelManager`：
  - 根据 `ChannelsConfig` 初始化各通道
  - 管理所有通道的 `startAll` / `stopAll`
  - 后台线程从 MessageBus 出站队列消费并调度到对应 `Channel.send`
  - 支持动态通道注册和按名称查询
- 已实现 7 种通道：Telegram、Discord、Feishu（飞书）、DingTalk（钉钉）、WhatsApp、QQ、MaixCam
- `WebhookServer`：内置轻量 HTTP 服务器，为飞书、钉钉等通道提供 Webhook 回调入口
- 语音消息由各通道通过 `voice/Transcriber`（当前实现为 `AliyunTranscriber`）转换为文本

#### 长连接重连约束

按接收方式，通道分为三类，只有长连接类需要重连：

| 类型 | 通道 | 断线恢复方式 |
|------|------|------------|
| WebSocket 长连接 | 钉钉 Stream、飞书 WebSocket、Discord Gateway | 统一使用 `Reconnector` |
| 长轮询 | Telegram | 轮询循环内 catch + 固定间隔重试，自愈 |
| Webhook 被动接收 | WhatsApp、QQ、MaixCam、飞书/钉钉的 webhook 模式 | 无长连接，不适用 |

`Reconnector` 把重连策略收敛为唯一实现，关键约束：

| 约束 | 做法 | 防的问题 |
|------|------|----------|
| 断线必重连 | `onClosed` 与 `onFailure` 都调 `schedule()` | 对端主动关连（令牌轮换、LB 漂移、空闲超时）只触发 `onClosed`，只监听 `onFailure` 等于在最常见场景下不重连 |
| 退避不溢出 | 逐次翻倍并在触及上限时立即收敛 | 无限重连下 `initial << (attempt-1)` 会溢出成负数，退化为不带间隔的忙重连 |
| 不自行放弃 | 指数退避到 60s 后无限重试 | 固定次数上限下，一次稍长的网络抖动就让通道永久静默，只能重启进程 |
| 在途去重 | `pending` CAS，同一次断线只排一条重连链 | 多个回调各排一次导致重连链分叉增长 |
| 退避在“真正可用”后才重置 | `onConnected()` 在 `onOpen`（钉钉/飞书）或 `READY`（Discord）调用 | socket 接通不等于会话建立，过早重置会把退避持续清零 |
| 不可恢复错误不重试 | 调用方判定后调 `disable(reason)` | Discord 的 4004/4013/4014 等关闭码重连永不会成功，持续重试会冲击对端 |

**启动失败不进入重连**：三个通道的首次连接失败都直接抛 `ChannelException`。启动阶段失败绝大多数
是配置错误，应当当场报错而不是默默退避重试。

**Discord 不做 RESUME**：未保存 `session_id`，重连采用重新 IDENTIFY 开新 session，代价是丢失
断线期间的消息。新 session 序列号从头开始，因此重连前必须清空 `lastSequence`。

### 3.5 LLM 提供商 — `providers/`

**核心类**：`LLMProvider`、`HTTPProvider`、`Message`、`ToolCall`、`ToolDefinition`、`LLMResponse`、`StreamEvent`

- `LLMProvider` 抽象接口：
  - `chat(messages, tools, model, options)`：普通对话 + 工具调用
  - `chatStream(...)`：流式对话，支持 `StreamCallback` 和 `EnhancedStreamCallback`
- `HTTPProvider` 通过 **OpenAI 兼容接口** 访问各类 LLM：
  - `POST {apiBase}/chat/completions`
  - 解析文本内容与工具调用（包括流式增量 tool_calls）
- `StreamEvent`：流式事件模型，支持文本增量、工具调用开始/结束、协同开始/结束等事件类型
- 当前支持的 provider：`openrouter`、`openai`、`anthropic`、`zhipu`（智谱 GLM）、`gemini`（Google）、`dashscope`（阿里云通义）、`groq`、`ollama`（本地模型）、`vllm`

### 3.6 工具系统 — `tools/`

**核心接口**：`Tool`、`ToolRegistry`、`StreamAwareTool`、`ToolContextAware`

- `Tool`：定义 `name()` / `description()` / `parameters()` / `execute(args)`
- `StreamAwareTool`：扩展接口，允许工具接收流式回调（如 `CollaborateTool`）
- `ToolContextAware`：扩展接口，允许工具感知执行上下文
- `ToolRegistry`：线程安全的工具注册表，提供 `register` / `unregister` / `execute` / `getDefinitions` / `getSummaries`，记录调用时长与结果长度

**内置工具**：

| 工具 | 实现类所在包 | 说明 | 安全特性 |
|------|--------------|------|----------|
| `read_file` | `tools/` | 读取文件内容 | ✓ 工作空间沙箱 |
| `write_file` | `tools/` | 写入文件（创建或覆盖） | ✓ 工作空间沙箱 |
| `edit_file` | `tools/` | 基于 diff 的精确文件编辑 | ✓ 工作空间沙箱 |
| `list_dir` | `tools/` | 列出目录内容 | ✓ 工作空间沙箱 |
| `exec` | `tools/` | 执行 Shell 命令 | ✓ 命令黑名单 + 工作目录限制 |
| `web_search` | `tools/` | 网络搜索（Brave Search API） | 仅在配置了 API Key 时注册 |
| `web_fetch` | `tools/` | 抓取网页内容 | ✓ SSRF 防护 |
| `message` | `tools/` | 向指定通道发送消息 | - |
| `cron` | `tools/` | 创建/管理定时任务 | 共用全局单一 CronService |
| `skills` | `tools/` | 管理和查询技能插件 | ✓ 技能名路径穿越校验 |
| `token_usage` | `tools/` | 查询 Token 用量统计 | - |
| `social_network` | `tools/` | 与其他 Agent 通信（ClawdChat.ai） | 仅在启用时注册 |
| `spawn` | `subagent/` | 生成子代理执行独立任务 | - |
| `collaborate` | `collaboration/` | 启动多 Agent 协同 | - |

此外，`mcp/MCPTool` 作为 MCP 协议的桥接工具，将外部 MCP 服务器的工具动态注册到 ToolRegistry 中。

> 注：工具的注册统一在组合根 `bootstrap/RuntimeAssembly` 完成，而不是在 CLI 命令里。

### 3.7 MCP 协议集成 — `mcp/`

**核心类**：`MCPManager`、`MCPClient`、`SSEMCPClient`、`StdioMCPClient`、`StreamableHttpMCPClient`

TinyClaw 实现了完整的 **MCP（Model Context Protocol）** 客户端，支持三种传输方式：

| 传输方式 | 实现类 | 适用场景 |
|----------|--------|----------|
| SSE | `SSEMCPClient` | 远程 HTTP 服务器（Server-Sent Events） |
| Stdio | `StdioMCPClient` | 本地进程通信（标准输入/输出） |
| Streamable HTTP | `StreamableHttpMCPClient` | 远程 HTTP 服务器（流式 HTTP） |

`MCPManager` 负责：
- 根据 `MCPServersConfig` 初始化所有 MCP 服务器连接
- 执行 MCP 协议握手（`initialize` → `notifications/initialized` → `tools/list`）
- 将每个 MCP 工具注册为独立的 `MCPTool` 到 `ToolRegistry`，使 LLM 可直接调用
- 支持自动重连（`reconnect`）和优雅关闭（`shutdown`）
- 通过 `MCPMessage` 封装 JSON-RPC 2.0 请求/响应

### 3.8 多 Agent 协同编排 — `collaboration/`

这是 TinyClaw 的高级能力之一，支持多个 Agent 角色协同完成复杂任务。

#### 核心架构

```text
CollaborateTool (工具入口)
       │
       ▼
AgentOrchestrator (编排器)
       │
       ├── CollaborationConfig (协同配置：Mode + Style)
       ├── SharedContext (共享上下文)
       ├── RoleAgent (角色 Agent，内部复用 ReActExecutor)
       │
       ▼
strategyRegistry: Map<Mode, CollaborationStrategy>
       │
       ├── DiscussionStrategy → DISCUSS
       ├── TasksStrategy      → TASKS
       └── WorkflowStrategy   → WORKFLOW
```

#### 3 种模式 × 风格

协同能力经重构后收敛为 **3 个正交模式（Mode）+ 子风格（Style）** 的二级结构，
而不是平铺的多个策略类——风格只影响提示词与终止条件，不需要各开一个策略实现：

| Mode | Style | 策略类 | 说明 |
|------|-------|--------|------|
| `DISCUSS` | `DEBATE` | `DiscussionStrategy` | 正反方观点对决，可设裁判 |
| `DISCUSS` | `ROLEPLAY` | `DiscussionStrategy` | 多角色对话模拟，支持主动结束 |
| `DISCUSS` | `CONSENSUS` | `DiscussionStrategy` | 讨论后投票，达到阈值即结束 |
| `DISCUSS` | `DYNAMIC` | `DiscussionStrategy` | Router Agent 动态选择下一个发言者 |
| `TASKS` | `PARALLEL` | `TasksStrategy` | 任务按依赖图并行/串行执行 |
| `TASKS` | `HIERARCHY` | `TasksStrategy` | 金字塔式逐层汇报决策 |
| `WORKFLOW` | — | `WorkflowStrategy` | 基于 DAG 的流程编排，支持 LLM 动态生成 |

策略通过 `AgentOrchestrator.registerStrategy(Mode, CollaborationStrategy)` 可在运行时替换；
公共行为（轮次控制、超时、记录）上提到 `AbstractCollaborationStrategy`。

#### 工作流引擎 — `collaboration/workflow/`

- `WorkflowDefinition`：工作流定义（名称、描述、节点列表、输出表达式）
- `WorkflowNode`：工作流节点，支持 6 种类型：`SINGLE` / `PARALLEL` / `SEQUENTIAL` / `CONDITIONAL` / `LOOP` / `AGGREGATE`
- `WorkflowEngine`：执行引擎，支持依赖解析、条件分支、循环、聚合、超时、重试
- `WorkflowGenerator`：通过 LLM 动态生成工作流定义
- `WorkflowContext`：工作流执行上下文，管理变量和节点结果

#### 增强特性

- **Token 预算**：设置 Token 上限，超出后自动终止
- **优雅降级**：协同失败时自动降级为单 Agent 模式
- **自反馈循环**：Critic Agent 评估结果质量，不合格则改进重试
- **协同记录**：自动保存协同过程到 `workspace/collaboration/` 目录
- **结论回流**：协同结论自动回流到调用方的主会话历史
- **反馈集成**：协同结果可驱动 Agent 自我进化

### 3.9 自我进化引擎 — `evolution/` + `memory/`

TinyClaw 内置了完整的自我进化系统，使 Agent 能基于反馈持续改进。

#### 核心组件

| 组件 | 职责 |
|------|------|
| `FeedbackManager` | 收集和管理用户反馈（评分、评论、隐式信号） |
| `PromptOptimizer` | 基于反馈自动优化 System Prompt |
| `MemoryEvolver` | 从对话中提取和进化长期记忆 |
| `EvolutionConfig` | 进化功能配置（开关、策略、间隔等） |
| `MemoryStore` | 长期记忆存储（文件系统） |

#### Prompt 优化 — 3 种策略

| 策略 | 说明 |
|------|------|
| `TEXTUAL_GRADIENT` | 反馈驱动的文本梯度：分析反馈 → 生成优化建议 → 应用到 Prompt |
| `OPRO` | 历史轨迹引导优化：分析历史 Prompt 变体的评分趋势，生成更优版本 |
| `SELF_REFINE` | 自我反思优化：回顾会话记录 → 自我评估 → 生成改进建议 → 应用 |

Prompt 变体存储结构：
```text
{workspace}/evolution/prompts/
├── PROMPT_VARIANTS.json    # 所有 Prompt 变体及其评分
├── PROMPT_ACTIVE.md        # 当前活跃的优化 Prompt
└── PROMPT_HISTORY/         # 历史版本归档
```

#### 记忆进化

- `MemoryEvolver`：在会话摘要完成后，从对话中提取有价值的长期记忆
- `MemoryStore`：使用文件系统保存长期记忆（`workspace/memory/MEMORY.md`）
- `MemoryEntry`：记忆条目，包含内容、来源、时间戳、重要性等元信息

### 3.10 技能系统 — `skills/`

**核心类**：`SkillsLoader`、`SkillRegistry`、`SkillsSearcher`、`SkillsInstaller`、`SkillInfo`

- 技能以 Markdown 文件形式存在：`{workspace}/skills/{skill-name}/SKILL.md`，支持 YAML frontmatter
- `SkillsLoader`：从 workspace / global / builtin 三个目录加载技能，按优先级覆盖同名技能
- `SkillRegistry`：技能注册表，管理已加载技能的元信息
- `SkillsSearcher`：基于语义搜索匹配技能，使 ContextBuilder 能根据用户输入动态注入相关技能
- `SkillsInstaller`：支持从 GitHub 仓库下载和安装技能
- `SkillsTool`：将技能管理能力暴露给 Agent（`list` / `show` / `invoke` / `install` / `create` / `edit` / `remove`），使 Agent 可自我安装、创建和改进技能

### 3.11 定时任务引擎 — `cron/`

- `CronService`：调度线程每秒检查任务列表，支持三种调度方式：
  - Cron 表达式
  - 固定间隔 `EVERY`（含 misfire 补跑：重启时若错过一个周期则立即补跑一次）
  - 单次定时 `AT`
- 系统内置 job（`__heartbeat__` / `__memory_evolution__`）由复合 onJob handler 按名称分发
- 存储：`CronJob` + `CronSchedule` + `CronJobState` + `CronPayload` 持久化到 `workspace/cron/jobs.json`
- 使用 `CronStore` 接口抽象存储，`ReentrantReadWriteLock` 保证并发安全
- 到期任务通过回调构造消息，调用 `AgentRuntime.processDirectWithChannel`

### 3.12 会话管理 — `session/`

- `SessionManager`：使用 `ConcurrentHashMap<String, Session>` 作为内存缓存
- 会话标识形如 `channel:chatId`（CLI 默认为 `cli:default`）
- 会话 JSON 数据存储在 `workspace/sessions/{session-key}.json`
- `Session`：包含 `List<Message>` 历史、`summary`、创建/更新时间
- `ToolCallRecord`：记录工具调用的详细信息（名称、参数、结果、耗时）

### 3.13 心跳 — `heartbeat/`

- `HeartbeatRunner` 无独立线程，作为 CronService 内置 job `__heartbeat__` 的执行体周期性运行
- 门控链：禁用 → 可见性开关 → activeHours → busy guard → 空清单跳过
- 读取 `memory/HEARTBEAT.md` 清单（8 KiB 硬上限）构建 prompt，经 `processDirect` 交给 Agent 执行自检
- 响应契约：无事回 `HEARTBEAT_OK`（静默丢弃），异常内容按 `target` 投递告警
- 整轮超时保护（`future.get(timeout)` + `abortCurrentTask()`）；状态落盘 `memory/heartbeat-status.json`
- 记忆进化已拆分为独立内置 job `__memory_evolution__`

### 3.14 安全沙箱 — `security/`

- `SecurityGuard` 提供多层安全防护：
  - **工作空间沙箱**：所有文件操作限制在 workspace 目录内
  - **命令黑名单**：阻止危险命令（`rm -rf`、`mkfs`、`dd` 等）
  - **路径规范化**：防止路径遍历攻击
  - **自定义黑名单**：支持通过配置扩展命令黑名单

### 3.15 Web 控制台 — `web/`

**核心类**：`WebConsoleServer`、`SecurityMiddleware`、`WebUtils`、`handler/BaseHandler`

- `WebConsoleServer`：内置轻量 HTTP 服务器，提供 Web UI 和 REST API
- `SecurityMiddleware`：Web 安全中间件（认证、CORS 等）

#### Handler 层约束

所有 JSON API Handler 继承 `BaseHandler`，由基类的模板方法统一承担鉴权、404、500 与错误日志，
子类只实现 `route(exchange, path, method, corsOrigin)` 这一段纯业务分发：

| 约束 | 做法 | 防的问题 |
|------|------|----------|
| 鉴权是默认行为 | `handle` 声明为 `final`，内部无条件先调 `authorize()` | 新增 Handler 忘记调 `security.preCheck` 就等于裸奔，且无任何编译或运行期提示 |
| 例外必须显式登记 | 覆盖 `authorize()` 的类需登记进 `BaseHandlerTest.ALLOWED_AUTHORIZE_OVERRIDES` | 悄悄绕过标准鉴权的端点混进代码库 |
| 路由未命中统一 404 | `route` 返回 `false` 时由基类回 `sendNotFound` | 各处手写 404 文案不一致 |
| 异常统一 500 | 基类 catch 全部异常，记 `apiName + path` 后回 JSON | 异常穿透到 HttpServer 导致连接重置、栈信息回显给调用方 |

**`route` 的契约**：处理了请求返回 `true`，路径不认识返回 `false`。方法体内不允许出现裸 `return;`
—— 那会被基类当作"未命中"，在已经写过响应之后再补一个 404。

**两个已登记的鉴权例外**：

- `AuthHandler`：登录端点，要求已认证就永远登不进去，故仅处理 CORS 预检；同时覆盖 `errorMessage()`
  以免向未登录调用方回显异常细节
- `FilesHandler`：`<img src>` 一类浏览器直接请求无法携带 `Authorization` header，改用 `?token=` 查询参数；
  且有意不叠加限流（一个页面常一次性拉十几张图）

`StaticHandler` 不继承 `BaseHandler`：它不持有 `Config`/`SecurityMiddleware`，返回的也不是 JSON。

**18 个 REST API Handler**：

| Handler | 职责 |
|---------|------|
| `AuthHandler` | 认证与授权 |
| `ChatHandler` | 对话交互（支持流式 SSE） |
| `SessionsHandler` | 会话管理（列表、详情、删除） |
| `ConfigHandler` | 配置查看与修改 |
| `ModelsHandler` | 模型列表与切换 |
| `ProvidersHandler` | Provider 管理 |
| `ChannelsHandler` | 通道状态与管理 |
| `SkillsHandler` | 技能管理 |
| `CronHandler` | 定时任务管理 |
| `HeartbeatHandler` | 心跳状态查询与手动触发 |
| `FilesHandler` | 文件浏览与操作 |
| `UploadHandler` | 文件上传 |
| `WorkspaceHandler` | 工作空间管理 |
| `MCPHandler` | MCP 服务器管理 |
| `FeedbackHandler` | 用户反馈收集 |
| `TokenStatsHandler` | Token 用量统计 |
| `ReflectionHandler` | Reflection 2.0 工具健康与修复提案 |
| `StaticHandler` | 静态资源服务 |

### 3.16 日志系统 — `logger/`

- `TinyClawLogger`：结构化日志封装，支持 `Map<String, Object>` 格式的上下文字段
- 基于 SLF4J + Logback，支持按模块获取 logger 实例

### 3.17 语音转写 — `voice/`

- `Transcriber`：语音转写接口
- `AliyunTranscriber`：基于阿里云 DashScope Paraformer 的实现，支持 Telegram/Discord 语音消息自动转文字

---

## 四、数据流

### 4.1 网关模式消息流

```text
用户 ──► IM 平台 ──► Channel ──► MessageBus.inbound
                                        │
                                        ▼
                                   AgentRuntime.run()
                                        │
                                        ▼
                                   MessageRouter.route()
                                        │
                              ┌─────────┼─────────┐
                              ▼         ▼         ▼
                          routeUser  routeCmd  routeSystem
                              │
                              ▼
                     ContextBuilder.buildMessages()
                              │
                              ▼
                     ReActExecutor.execute()
                         │         ▲
                         ▼         │
                    LLM Provider ──┘
                         │
                    (tool_calls?)
                         │ Yes
                         ▼
                    ToolRegistry.execute()
                         │
                         ▼
                    (iterate until done)
                         │
                         ▼
                    MessageBus.outbound
                         │
                         ▼
                    ChannelManager ──► Channel ──► IM 平台 ──► 用户
```

### 4.2 多 Agent 协同流

```text
用户消息 ──► AgentRuntime ──► ReActExecutor
                               │
                          (tool_call: collaborate)
                               │
                               ▼
                        CollaborateTool.execute()
                               │
                               ▼
                        AgentOrchestrator.orchestrate()
                               │
                     ┌─────────┼─────────┐
                     ▼         ▼         ▼
               策略选择    创建Agents   共享上下文
                     │
                     ▼
              Strategy.execute()
                     │
              ┌──────┴──────┐
              ▼              ▼
         AgentExecutor   AgentExecutor
         (角色A)          (角色B)
              │              │
              ▼              ▼
         LLM 调用        LLM 调用
              │              │
              └──────┬───────┘
                     ▼
              结论汇总 + 记录保存
                     │
                     ▼
              回流到主会话
```

### 4.3 自我进化流

```text
用户对话 ──► FeedbackManager.recordMessageExchange()
                     │
                     ▼
              (累积足够反馈)
                     │
                     ▼
              PromptOptimizer.maybeOptimize()
                     │
              ┌──────┼──────┐
              ▼      ▼      ▼
          Textual  OPRO  Self-Refine
          Gradient
              │
              ▼
         生成优化 Prompt
              │
              ▼
         保存为候选变体
              │
              ▼
         ContextBuilder 注入优化 Prompt

会话摘要 ──► MemoryEvolver.evolve()
                     │
                     ▼
              提取长期记忆
                     │
                     ▼
              MemoryStore 持久化
```

---

## 五、配置体系

### 5.1 配置文件结构

```text
~/.tinyclaw/
├── config.json              # 主配置文件
├── workspace/               # 工作空间
│   ├── AGENTS.md            # Agent 行为定义
│   ├── SOUL.md              # Agent 灵魂/个性
│   ├── USER.md              # 用户信息
│   ├── IDENTITY.md          # Agent 身份
│   ├── memory/              # 长期记忆
│   │   ├── MEMORY.md
│   │   └── HEARTBEAT.md
│   ├── sessions/            # 会话持久化
│   ├── skills/              # 用户技能
│   ├── cron/                # 定时任务
│   ├── evolution/           # 进化数据
│   │   └── prompts/         # Prompt 变体
│   └── collaboration/       # 协同记录
```

### 文件持久化保证

所有落盘都经过 `util/JsonFileStore`，不直接调用 `Files.writeString` 覆盖写：

| 保证 | 做法 | 防的问题 |
|------|------|----------|
| 原子替换 | 唯一临时文件 → fsync → `ATOMIC_MOVE` → fsync 父目录 | 写入中途退出留下半截 JSON，整份数据不可读 |
| 并发安全 | 临时文件名含随机段 | 多写入方踩同一个 `.tmp`，互相截断或删掉对方待重命名的文件 |
| 完整写入 | 循环写至 buffer 耗尽 | `FileChannel.write` 短写导致内容截断 |
| 权限收敛 | 先以 600 创建再写 | 会话/记忆等敏感内容存在 644 可读窗口 |
| 损坏不销毁 | 解析失败把原文件移入同级 `corrupt/` | 上层拿到空值后覆盖写，原始数据被静默销毁 |

追加型日志（JSONL）走 `appendAndSync`：追加本身无破坏性，但仍 fsync 保证已返回的写入在掉电后存在。

**适用边界**：以上只适用于 Agent 自己的内部状态（会话、记忆、定时任务、进化产物、
配置、协同记录、心跳状态、token 计费）。`WriteFileTool` / `EditFileTool` / `SkillsTool` /
`OnboardCommand` 等向**用户文件**写入的路径仍用普通写：原子 rename 会换掉 inode，
破坏硬链接、符号链接目标和文件监听器，对“按指示改这个文件”的语义反而是倒退。

### 5.2 配置模型

| 配置类 | 职责 |
|--------|------|
| `Config` | 顶层配置容器 |
| `AgentConfig` | Agent 参数（模型、温度、心跳、进化配置等） |
| `ProvidersConfig` | LLM 提供商配置（API Key、API Base） |
| `ModelsConfig` | 模型别名、默认模型、上下文窗口 |
| `ChannelsConfig` | 通道配置（Token、白名单等） |
| `ToolsConfig` | 工具配置（安全选项等） |
| `GatewayConfig` | 网关配置 |
| `MCPServersConfig` | MCP 服务器配置（端点、传输方式、命令等） |
| `SocialNetworkConfig` | Agent 社交网络配置 |

---

## 六、项目结构

```text
src/main/java/io/leavesfly/tinyclaw/
├── TinyClaw.java                    # 应用入口，命令注册与分发
├── TinyClawException.java           # 统一异常基类
├── bootstrap/                       # 组合根（装配层）
│   └── RuntimeAssembly.java         #   集中构建对象图：总线 / AgentRuntime / CronService / 内置工具
├── agent/                           # Agent 引擎（编排与生命周期）
│   ├── AgentRuntime.java            #   生命周期管理与消息消费主循环
│   ├── MessageRouter.java           #   消息路由（用户/系统/指令）
│   ├── ProviderManager.java         #   LLM Provider 管理与热重载
│   ├── ProviderComponents.java      #   Provider 派生组件容器
│   ├── ContextBuilder.java          #   分段式上下文构建
│   ├── SessionSummarizer.java       #   会话摘要与上下文压缩
│   ├── AgentConstants.java          #   Agent 相关常量
│   └── context/                     #   上下文分段模块
│       ├── ContextSection.java      #     Section 接口
│       ├── SectionContext.java      #     Section 上下文数据
│       ├── IdentitySection.java     #     身份段
│       ├── BootstrapSection.java    #     基础行为段
│       ├── ToolsSection.java        #     工具摘要段
│       ├── SkillsSection.java       #     技能摘要段
│       └── MemorySection.java       #     记忆段
├── react/                           # ReAct 推理循环（被 agent / collaboration / subagent 共用）
│   └── ReActExecutor.java           #   LLM 调用与工具迭代循环
├── collaboration/                   # 多 Agent 协同编排
│   ├── AgentOrchestrator.java       #   协同编排器
│   ├── CollaborateTool.java         #   collaborate 工具入口
│   ├── RoleAgent.java               #   角色 Agent（内部复用 ReActExecutor）
│   ├── SharedContext.java           #   共享上下文
│   ├── AgentRole / AgentMessage / TeamTask / CollaborationRecord / ApprovalCallback
│   ├── CollaborationConfig / HierarchyConfig / ExecutionContext
│   ├── CollaborationExecutorPool.java  # 协同线程池
│   ├── strategy/                    #   协同策略（Abstract / Discussion / Tasks / Workflow）
│   └── workflow/                    #   工作流引擎（Engine / Definition / Node / Context / Generator +
│                                    #   NodeExecutor + executor/ 6 种节点执行器）
├── evolution/                       # 自我进化引擎
│   ├── PromptOptimizer.java         #   Prompt 自动优化（3 种策略）
│   ├── FeedbackManager.java         #   反馈收集与管理
│   ├── VariantManager.java          #   Prompt 变体管理
│   ├── EvaluationFeedback / FeedbackType / OptimizationResult
│   ├── strategy/                    #   优化策略（OptimizationStrategy / SelfReflection / Context）
│   └── reflection/                  #   Reflection 2.0：工具级自我调试
│       ├── ReflectionEngine.java    #     反思引擎（依赖 ToolDefinitionLookup 而非 ToolRegistry）
│       ├── ToolDefinitionLookup.java#     工具定义查询窄接口（避免反向依赖 tools）
│       ├── ToolCallRecorder / ToolCallLogStore / ToolCallEvent
│       ├── FailureDetector / PatternMiner / ErrorClassifier / ArgsFingerprinter
│       ├── ToolHealthAggregator / ToolHealthStat
│       └── RepairProposal / RepairApplier
├── memory/                          # 长期记忆
│   ├── MemoryStore.java             #   记忆存储（MEMORIES.json + MEMORY.md + topics/）
│   ├── MemoryEvolver.java           #   记忆进化
│   └── MemoryEntry.java             #   记忆条目
├── subagent/                        # 子代理
│   ├── SubagentManager.java         #   子代理执行管理（复用 ReActExecutor）
│   ├── SpawnTool.java               #   spawn 工具入口
│   ├── SubagentsLoader.java         #   动态子代理定义加载（workspace/agents/）
│   └── SubagentDefinition.java      #   子代理定义模型
├── hooks/                           # 生命周期 Hook
│   ├── HookDispatcher / HookRegistry / HookConfigLoader / HookMatcher
│   ├── HookEvent / HookContext / HookDecision / HookEntry
│   └── HookHandler / CommandHookHandler
├── plugins/                         # 插件系统（兼容 Claude Code / OpenClaw）
│   ├── PluginManager / PluginDiscovery / PluginInstaller / PluginRegistry
│   ├── PluginManifest / ManifestParser / VariableResolver
│   ├── MarketplaceManager / MarketplaceParser / MarketplaceManifest
│   └── AgentComponentAdapter / McpComponentAdapter / HookComponentAdapter
├── bus/                             # 消息总线
│   ├── MessageBus.java              #   发布/订阅消息中心
│   ├── InboundMessage / OutboundMessage
│   ├── LastContact.java             #   最近入站联系人（心跳 target=last 投递依据）
│   └── BusClosedException.java
├── channels/                        # 消息通道适配器（7 种）
│   ├── Channel / BaseChannel / ChannelManager / ChannelException
│   ├── WebhookServer / SharedHttpClient / TokenManager / ActiveSessionRegistry
│   └── Telegram / Discord / Feishu / DingTalk / WhatsApp / QQ / MaixCam Channel
├── cli/                             # 命令行接口
│   ├── CliCommand.java              #   基类：参数解析 / 配置加载 / Provider 创建
│   ├── GatewayBootstrap.java        #   网关服务编排（从 RuntimeAssembly 取用组件）
│   └── Onboard / Agent / Gateway / Status / Cron / Skills / Mcp / Plugins / Heartbeat / Demo Command
├── config/                          # 配置模型与加载（叶包，不依赖业务包）
│   ├── Config / ConfigLoader / ConfigException
│   ├── AgentConfig                  #   Agent 核心参数
│   ├── EvolutionConfig / ReflectionConfig      # 进化与反思配置
│   ├── CollaborationSettings / HeartbeatSettings / ActiveHours / RoleTemplate
│   └── ProvidersConfig / ModelsConfig / ChannelsConfig / ToolsConfig /
│       GatewayConfig / MCPServersConfig / SocialNetworkConfig / PluginsConfig
├── cron/                            # 定时任务引擎
│   └── CronService / CronJob / CronSchedule / CronJobState / CronPayload / CronStore
├── heartbeat/                       # 心跳运行器
│   └── HeartbeatRunner.java
├── mcp/                             # MCP 协议集成
│   ├── MCPManager / MCPClient / MCPTool / MCPMessage / MCPServerInfo
│   └── SSEMCPClient / StdioMCPClient / StreamableHttpMCPClient
├── providers/                       # LLM 调用抽象
│   ├── LLMProvider / HTTPProvider / LLMRequestBuilder / StreamResponseParser
│   └── Message / ToolCall / ToolDefinition / LLMResponse / StreamEvent / LLMException
├── tools/                           # Agent 工具集与注册表
│   ├── Tool / ToolRegistry / StreamAwareTool / ToolContextAware / ToolException
│   ├── ReadFile / WriteFile / EditFile / ListDir / Exec Tool
│   ├── WebSearch / WebFetch / Message / Cron / Skills / SocialNetwork Tool
│   └── TokenUsageTool / TokenUsageStore
├── session/                         # 会话管理
│   └── SessionManager / Session / SessionStore / JsonlSessionStore / SessionMeta / ToolCallRecord
├── skills/                          # 技能系统
│   └── SkillsLoader / SkillRegistry / SkillsSearcher / SkillsInstaller / SkillInfo
├── security/                        # 安全沙箱
│   └── SecurityGuard.java
├── logger/                          # 结构化日志
│   └── TinyClawLogger.java
├── util/                            # 工具类
│   └── StringUtils / SSLUtils / MediaPaths / JsonFileStore
├── voice/                           # 语音转写
│   └── Transcriber / AliyunTranscriber
└── web/                             # Web 控制台
    ├── WebConsoleServer / SecurityMiddleware / WebUtils
    └── handler/                     # 18 个 REST API Handler
        ├── Auth / Chat / Sessions / Config / Models / Providers / Channels /
        │   Skills / Cron / Heartbeat / Files / Upload / Workspace / MCP /
        │   Feedback / TokenStats / Reflection / Static Handler
```


## 七、扩展指南

### 7.1 添加新的消息通道

1. 创建 `XxxChannel extends BaseChannel`
2. 实现 `start()` / `stop()` / `send(OutboundMessage)` / `isAllowed(senderId)`
3. 在 `ChannelsConfig` 中添加对应配置模型
4. 在 `ChannelManager` 中注册新通道

### 7.2 添加新的工具

1. 创建 `XxxTool implements Tool`，实现 `name()` / `description()` / `parameters()` / `execute(args)`
2. 在 `bootstrap/RuntimeAssembly#registerBuiltinTools` 中注册——这是唯一的内置工具注册点，
   不要在 CLI 命令或 `GatewayBootstrap` 里另建一份（有状态组件重复构建曾导致定时任务丢失）
3. 如需流式输出支持，额外实现 `StreamAwareTool`；如需感知会话上下文，实现 `ToolContextAware`
4. **放置位置**：纯叶子工具放 `tools/`；需要回调上层引擎（如 ReActExecutor、Orchestrator）的
   工具放到它服务的那个包里，避免 `tools/` 反向依赖上层形成包级循环

### 7.3 添加新的协同策略

优先考虑能否用现有 Mode 的**新 Style** 表达（只需改提示词与终止条件），而不是新增一个策略类。
确实需要新模式时：

1. 创建 `XxxStrategy extends AbstractCollaborationStrategy`（复用轮次控制/超时/记录等公共行为）
2. 在 `CollaborationConfig.Mode` 中添加新模式
3. 在 `AgentOrchestrator` 的策略注册表中登记，或运行时调用 `registerStrategy(Mode, strategy)`

### 7.4 添加新的 LLM 提供商

所有提供商均通过 `HTTPProvider` 适配 OpenAI 兼容 API 格式：
1. 在 `ProvidersConfig` 中添加 provider 配置
2. 在 `ModelsConfig` 中定义模型到 provider 的映射
3. 修改配置文件即可，无需编写代码

### 7.5 接入新的 MCP 服务器

在 `config.json` 的 `mcpServers` 中添加配置即可：
```json
{
  "mcpServers": {
    "my-server": {
      "endpoint": "https://my-mcp-server.com/sse",
      "apiKey": "your-api-key",
      "timeout": 30
    }
  }
}
```
`MCPManager` 会自动初始化连接并将工具注册到 `ToolRegistry`。
