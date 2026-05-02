# 02 · 整体架构

> 从上到下讲清 TinyClaw 的分层、数据流与关键抽象。

---

## 2.1 架构总览

TinyClaw 采用**分层 + 消息总线**的经典结构，自上而下分为六层：

```text
┌────────────────────────────────────────────────────────────────┐
│                    CLI & Gateway 入口层                        │
│  TinyClaw.java  +  CliCommand × 8                              │
│  onboard / agent / gateway / status / cron / skills / mcp /   │
│  demo / version                                                │
└─────────────────────────────┬──────────────────────────────────┘
                              │
            ┌─────────────────┼─────────────────┐
            ▼                 ▼                 ▼
   ┌─────────────────┐  ┌───────────┐  ┌────────────────────┐
   │   Agent 引擎层   │  │ 网关引导   │  │   Web 控制台        │
   │  AgentRuntime    │  │GatewayBoot│  │ WebConsoleServer   │
   │  MessageRouter   │  │ -strap    │  │ + 17 Handlers      │
   │  ReActExecutor   │  └─────┬─────┘  └─────┬──────────────┘
   │  ContextBuilder  │        │              │
   │  ProviderManager │        │              │
   │  SessionSummariz.│        │              │
   └────────┬─────────┘        │              │
            │                  │              │
            ▼                  ▼              ▼
   ┌───────────────────────────────────────────────────┐
   │              消息总线 MessageBus                   │
   │    inbound ──►  AgentRuntime  ──► outboundByChannel│
   └─────────┬────────────────────────────────┬────────┘
             │                                │
             ▼                                ▼
   ┌──────────────────┐       ┌─────────────────────────────┐
   │ LLM Provider     │       │   Channels（7 种平台适配）    │
   │ HTTPProvider     │       │  Telegram / Discord /        │
   │ ProviderManager  │       │  Feishu / DingTalk /         │
   └────────┬─────────┘       │  WhatsApp / QQ / MaixCam     │
            │                 └────────────┬────────────────┘
   ┌────────┴────────┐                     │
   ▼                 ▼                     │
 ┌─────────────┐ ┌─────────────┐          │
 │ 工具系统     │ │ MCP 客户端   │          │
 │ ToolRegistry │ │ MCPManager  │          │
 │ + 15 Tool    │ │ + 3 Client  │          │
 └──────┬──────┘ └─────────────┘          │
        │                                  │
  ┌─────┴───────────────────────────────────┴──────┐
  │                 高级能力层                      │
  ├─────────────┬──────────────┬───────────────────┤
  │ 多Agent协同 │ 自我进化引擎 │ 技能系统          │
  │ Orchestrator│ Prompt Opt.  │ SkillsLoader      │
  │ + 4 策略    │ MemoryEvolver│ SkillsSearcher    │
  │ + Workflow  │ Reflection   │ SkillsInstaller   │
  │   Engine    │ Engine       │                   │
  └─────────────┴──────────────┴───────────────────┘
        │             │              │
        ▼             ▼              ▼
  ┌───────────────────────────────────────────────┐
  │                基础设施层                      │
  │ Config / Session / Security / Logger /        │
  │ Cron / Heartbeat / Voice / Hooks / Util       │
  └───────────────────────────────────────────────┘
```

## 2.2 分层视角

| 层次 | 主要包 | 职责 |
|------|--------|------|
| **入口层** | `TinyClaw`、`cli/` | 解析命令 / 分发 / 启动 Agent 或 Gateway |
| **Agent 引擎层** | `agent/`、`agent/context/` | 推理循环、消息路由、上下文构建、Provider 管理、会话摘要 |
| **通信层** | `bus/`、`channels/`、`providers/` | 消息总线、7 种通道、9 种 LLM Provider |
| **工具与 MCP 层** | `tools/`、`mcp/` | 15 个内置工具、3 种 MCP 传输 |
| **高级能力层** | `collaboration/`、`evolution/`、`memory/`、`skills/` | 协同编排、Prompt 优化、记忆进化、工具反思、技能管理 |
| **基础设施层** | `config/`、`session/`、`security/`、`logger/`、`cron/`、`heartbeat/`、`voice/`、`hooks/`、`util/`、`web/` | 支持性服务 |

## 2.3 核心抽象

| 抽象 | 所在类 | 作用 |
|------|--------|------|
| **命令** | `CliCommand` | 命令行子命令统一接口（`execute(args): int`） |
| **消息总线** | `MessageBus` | 解耦通道与 Agent：`publishInbound` / `consumeInbound` / `publishOutbound` / `subscribeOutbound` |
| **消息模型** | `InboundMessage` / `OutboundMessage` | 入站/出站消息的统一结构，含 `channel` + `chatId` + `senderId` + `sessionKey` |
| **通道** | `Channel` / `BaseChannel` | 每个 IM 平台实现一个 Channel，统一 `start/stop/send` |
| **LLM Provider** | `LLMProvider` / `HTTPProvider` | 使用 OpenAI 兼容协议统一封装所有 LLM |
| **工具** | `Tool` / `ToolRegistry` | function calling 调用的原子能力 |
| **上下文分段** | `ContextSection` | 将系统提示拆成 `Identity` / `Bootstrap` / `Tools` / `Skills` / `Memory` 等段 |
| **协同策略** | `CollaborationStrategy` | 多 Agent 协同的具体玩法 |
| **进化策略** | `OptimizationStrategy` | Prompt 优化具体实现 |
| **Hook** | `HookEvent` / `HookHandler` / `HookDispatcher` | 生命周期切点的命令式插件 |

## 2.4 两种运行模式

### 2.4.1 直连模式（CLI / Web）

```text
User ──► AgentCommand / Web ChatHandler
              │
              ▼
     AgentRuntime.processDirect(content, sessionKey)
              │
              ▼
     MessageRouter.routeUser(...)   ← 内部路径
              │
       同 Gateway 模式
```

- 不经过 `MessageBus.inbound`，直接调用内部 `processDirect*` 系列方法。
- 适合单次问答、Web UI 流式对话。
- 支持中断（`abortCurrentTask()`）。

### 2.4.2 网关模式（Gateway）

```text
User ─► IM 平台 ─► Channel ─► MessageBus.publishInbound()
                                      │
                                      ▼
                          AgentRuntime.run() 主循环
                                      │
                                      ▼
                          consumeInbound() → MessageRouter.route()
                                      │
                              ┌───────┼────────┐
                              ▼       ▼        ▼
                        routeUser routeCmd routeSystem
                              │
                              ▼
                    ContextBuilder.buildMessages()
                              │
                              ▼
                    ReActExecutor.execute() / executeStream()
                         │         ▲
                         ▼         │
                  LLM Provider ────┘   ◄──(tool_calls 循环)
                         │
                         ▼
                    ToolRegistry.execute()
                         │
                         ▼
                MessageBus.publishOutbound()
                         │
                         ▼
              ChannelManager ──► 对应 Channel ──► IM 平台
```

- 启动后由 `GatewayBootstrap` 统一引导：加载配置 → 初始化 SecurityGuard → 注册工具 → 初始化 MCP → 启动 Cron / Heartbeat → 启动 ChannelManager / WebConsoleServer → 启动 AgentRuntime.run()
- `Ctrl+C` 触发 `drainAndClose`，等待队列排空后优雅退出。

## 2.5 关键数据流

### 2.5.1 用户消息处理

```text
InboundMessage
    ↓
MessageRouter.route(msg)
    ↓  (isCommand?)
  ├─ Yes → routeCommand(msg)
  └─ No  → routeUser(msg)
                ↓
          Hook: SessionStart (首次)
                ↓
          Hook: UserPromptSubmit  ← 可 deny / 改写
                ↓
          ContextBuilder.buildMessages(history, summary, content, channel, chatId)
                ↓
          ReActExecutor.execute() / executeStream()
                ↓
           for iter in 0..maxIterations:
               LLMProvider.chat(messages, tools)
                ↓
               if no tool_calls: break
               else:
                  Hook: PreToolUse  ← 可 deny / 改写参数
                  ToolRegistry.execute(name, args)
                  Hook: PostToolUse ← 可改写结果
                  append tool result → messages
                ↓
          SessionManager 持久化
                ↓
          Hook: Stop
                ↓
          MessageBus.publishOutbound(OutboundMessage)
                ↓
          ChannelManager 派发
```

### 2.5.2 多 Agent 协同流

```text
用户消息 ─► ReActExecutor ─► (tool_call: collaborate)
                                  ↓
                         CollaborateTool.execute()
                                  ↓
                         AgentOrchestrator.orchestrate()
                                  ↓
                     选择 CollaborationStrategy
                                  ↓
                  ┌──────────────┼───────────────┬──────────────┐
                  ▼              ▼               ▼              ▼
           DiscussionStr.  TasksStrategy  WorkflowStrategy  Dynamic
           (debate /       (team /        (workflow)        Routing
            roleplay /     hierarchy)                       (dynamic)
            consensus)
                  │
                  ▼
           RoleAgent × N 并行/串行执行
                  │
                  ▼
           SharedContext 汇总
                  │
                  ▼
           CollaborationRecord 落盘
                  │
                  ▼
           回流主会话
```

### 2.5.3 自我进化流

```text
每次用户对话 ─► FeedbackManager.recordMessageExchange()
                        ↓
                  (累积足够反馈？)
                        ↓ Yes
                  PromptOptimizer.maybeOptimize()
                        ↓
         ┌──────────────┼──────────────┐
         ▼              ▼              ▼
  TextualGradient     OPRO     SelfReflection
         ↓              ↓              ↓
         └────── VariantManager ───────┘
                        ↓
             保存到 workspace/evolution/prompts/
                        ↓
             ContextBuilder 注入激活变体


每次会话摘要 ─► MemoryEvolver.evolve()
                        ↓
                  提取长期记忆
                        ↓
                  MemoryStore 持久化 (memory/MEMORY.md)


每次工具调用 ─► ToolCallRecorder
                        ↓
                  ToolCallLogStore 落盘
                        ↓
              （定期）ReflectionEngine
                        ↓
          FailureDetector + PatternMiner
                        ↓
              RepairProposal → RepairApplier
                        ↓
          触发 Prompt 优化或工具参数修正
```

## 2.6 职责分离后的 Agent 引擎

AgentRuntime 经过重构后不再是一个「上帝类」，而是按职责分离：

| 组件 | 职责 |
|------|------|
| `AgentRuntime` | 生命周期管理、`run()` 主循环、`processDirect*` 直连入口、对外 API |
| `MessageRouter` | 路由用户/系统/指令消息，调用 Hook |
| `ProviderManager` | 管理 LLM Provider，热重载，构建 `ProviderComponents` |
| `ProviderComponents` | `ReActExecutor` / `SessionSummarizer` / `MemoryEvolver` / `FeedbackManager` / `PromptOptimizer` / `AgentOrchestrator` 的容器 |
| `ReActExecutor` | LLM 调用 + 工具迭代循环（Reason-Act 循环） |
| `ContextBuilder` | 按 `ContextSection` 拼装系统提示 |
| `SessionSummarizer` | 会话摘要 + 触发 MemoryEvolver |
| `HookDispatcher` | 生命周期钩子分发 |

## 2.7 配置体系

```text
~/.tinyclaw/
├── config.json              # 主配置文件（Config 对象 JSON 序列化）
├── hooks.json               # Hooks 配置（可选）
└── workspace/
    ├── AGENTS.md            # Agent 行为定义
    ├── SOUL.md              # Agent 个性与价值观
    ├── USER.md              # 用户画像
    ├── IDENTITY.md          # Agent 身份
    ├── memory/
    │   ├── MEMORY.md
    │   └── HEARTBEAT.md
    ├── sessions/            # 会话 JSON
    ├── skills/              # 用户技能
    ├── cron/jobs.json       # 定时任务
    ├── evolution/prompts/   # Prompt 变体
    ├── collaboration/       # 协同记录
    └── reflection/          # 工具调用日志
```

配置类（`config/` 包）：

| 类 | 对应 JSON 字段 |
|----|----------------|
| `Config` | 根 |
| `AgentConfig` | `agent.*` |
| `ProvidersConfig` | `providers.*` |
| `ModelsConfig` | `models.*` |
| `ChannelsConfig` | `channels.*` |
| `ToolsConfig` | `tools.*` |
| `GatewayConfig` | `gateway.*` |
| `MCPServersConfig` | `mcpServers.*` |
| `SocialNetworkConfig` | `socialNetwork.*` |

详见 [04 · 配置指南](04-configuration.md)。

## 2.8 关键设计决策

| 决策 | 理由 |
|------|------|
| 用 **消息总线** 而非 RPC | 彻底解耦通道与 Agent，便于并发与多通道共享一个 Agent |
| **Per-channel 出站队列** | 防止某个通道阻塞影响其他通道 |
| **OpenAI 兼容协议** 统一所有 Provider | 接一个新 LLM 只需配置，不用写代码 |
| **ContextSection** 分段 | 便于独立测试、替换、注入优化 Prompt |
| **ProviderComponents** 聚合 | Provider 热重载时一次性替换所有派生组件，避免中间态 |
| **Hook 默认 noop** | 未配置 Hook 时零开销，主流程无侵入 |
| **SecurityGuard 作为所有工具的前置检查** | 所有文件 / 命令工具统一过安全关 |
| **工作空间沙箱** | 所有副作用限定在 `workspace/`，防止误操作用户主目录 |

## 2.9 下一步阅读

- 想跑起来 → [03 · 快速开始](03-getting-started.md)
- 深入 Agent 引擎 → [06 · Agent 引擎](06-agent-engine.md)
- 了解消息总线 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
- 了解高级能力 → [11 · 多 Agent 协同](11-multi-agent-collaboration.md) / [12 · 自我进化](12-self-evolution.md)
