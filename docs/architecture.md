## TinyClaw 技术架构文档

> 版本：0.1.0 ｜ 最后更新：2026-03-24

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
- **可观测与可演示**：提供 Web 控制台（含 16 个 REST API）、结构化日志体系以及 Demo 命令，方便现场演示和日常运维。

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
      │ AgentLoop    │  │ Gateway  │  │ WebConsoleServer│
      │ MessageRouter│  │ Bootstrap│  │ + 16 Handlers  │
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
| **Agent 引擎层** | `agent/` | 推理循环、消息路由、Provider 管理、上下文构建、会话摘要 |
| **通信层** | `bus/`, `channels/`, `providers/` | 消息总线、7 种通道适配、LLM HTTP 调用与流式输出 |
| **工具与 MCP 层** | `tools/`, `mcp/` | 15 个内置工具、MCP 协议客户端（SSE / Stdio / Streamable HTTP） |
| **高级能力层** | `agent/collaboration/`, `agent/evolution/`, `skills/` | 多 Agent 协同编排（7 种模式）、Prompt 自动优化（3 种策略）、记忆进化、技能管理 |
| **基础设施层** | `config/`, `session/`, `security/`, `logger/`, `cron/`, `heartbeat/`, `voice/`, `util/`, `web/` | 配置管理、会话持久化、安全沙箱、结构化日志、定时任务、心跳、语音转写、Web 控制台 |

---

## 三、核心模块

### 3.1 应用入口 — TinyClaw

**位置**：`io.leavesfly.tinyclaw.TinyClaw`

- 使用 `LinkedHashMap<String, Supplier<CliCommand>>` 维护命令注册表。
- 已注册命令：`onboard`、`agent`、`gateway`、`status`、`cron`、`skills`、`mcp`、`demo`，以及内置的 `version`。
- `run(String[] args)` 负责：无参数时打印帮助；`version` / `--version` / `-v` 输出版本；其余命令从注册表查找并执行。
- 典型调用链：
  - CLI 交互模式：`TinyClaw.main` → `AgentCommand` → `GatewayBootstrap` / 直接创建 `AgentLoop`
  - 网关模式：`TinyClaw.main` → `GatewayCommand` → 启动消息通道 + AgentLoop + WebConsoleServer

### 3.2 Agent 引擎 — `agent/`

Agent 引擎是 TinyClaw 的核心，经过重构后采用**职责分离**设计，将原来集中在 AgentLoop 中的逻辑拆分为多个专职组件：

| 组件 | 职责 |
|------|------|
| `AgentLoop` | 生命周期管理、消息消费主循环、直连模式入口 |
| `MessageRouter` | 消息路由（用户消息 / 系统消息 / 指令消息）、流式输出选择 |
| `ProviderManager` | LLM Provider 初始化、热重载、模型路由、组件构建 |
| `ProviderComponents` | Provider 派生组件容器（LLMExecutor / Summarizer / Evolver / Orchestrator 等） |
| `LLMExecutor` | LLM 调用与工具迭代循环 |
| `ContextBuilder` | 系统提示组装（分段式架构） |
| `SessionSummarizer` | 会话摘要与上下文压缩 |

#### AgentLoop — 生命周期与消息消费

- 支持两类入口：
  - `run()`：网关模式，持续从 `MessageBus.consumeInbound()` 取消息
  - `processDirect(...)` / `processDirectStream(...)`：CLI / Web 控制台直连模式
- 初始化时创建 `ToolRegistry`、`SessionManager`、`ContextBuilder`、`MessageRouter`、`ProviderManager`
- 通过 `ProviderManager` 管理 LLM Provider 的生命周期，支持运行时热切换

#### MessageRouter — 消息路由

从 AgentLoop 中抽取的消息路由器，负责：
- **用户消息**：构建上下文 → LLM 调用 → 持久化 → 发布回复
- **系统消息**（`channel=system`）：解析原始来源，路由回原始会话
- **指令消息**（如 `/new`）：执行指令逻辑（创建新会话等）
- **流式输出判断**：根据目标通道是否支持流式，选择对应的 LLM 执行路径

#### ProviderManager — Provider 管理

- **模型路由**：从 `ModelsConfig` 反查 model 对应的 provider，保证 api_base 与 model 始终来自同一绑定关系
- **热重载**：`reloadModel()` 支持运行时切换模型和 Provider，无需重启
- **组件构建**：`applyProvider()` 一次性构建所有派生组件（LLMExecutor、SessionSummarizer、MemoryEvolver、FeedbackManager、PromptOptimizer、AgentOrchestrator）
- 使用 `volatile` + `synchronized` 保证线程安全

#### LLMExecutor — LLM 迭代与工具调用

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

**核心接口**：`Channel`、`BaseChannel`、`ChannelManager`、`WebhookServer`

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

**内置工具（15 个）**：

| 工具 | 说明 | 安全特性 |
|------|------|----------|
| `read_file` | 读取文件内容 | ✓ 工作空间沙箱 |
| `write_file` | 写入文件（创建或覆盖） | ✓ 工作空间沙箱 |
| `append_file` | 追加内容到文件 | ✓ 工作空间沙箱 |
| `edit_file` | 基于 diff 的精确文件编辑 | ✓ 工作空间沙箱 |
| `list_dir` | 列出目录内容 | ✓ 工作空间沙箱 |
| `exec` | 执行 Shell 命令 | ✓ 命令黑名单 + 工作目录限制 |
| `web_search` | 网络搜索（Brave Search API） | - |
| `web_fetch` | 抓取网页内容 | - |
| `message` | 向指定通道发送消息 | - |
| `cron` | 创建/管理定时任务 | - |
| `spawn` | 生成子代理执行独立任务 | - |
| `collaborate` | 启动多 Agent 协同（7 种模式） | - |
| `social_network` | 与其他 Agent 通信（ClawdChat.ai） | - |
| `skills` | 管理和查询技能插件 | - |
| `token_usage` | 查询 Token 用量统计 | - |

此外，`MCPTool` 作为 MCP 协议的桥接工具，将外部 MCP 服务器的工具动态注册到 ToolRegistry 中。

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

### 3.8 多 Agent 协同编排 — `agent/collaboration/`

这是 TinyClaw 的高级能力之一，支持多个 Agent 角色协同完成复杂任务。

#### 核心架构

```text
CollaborateTool (工具入口)
       │
       ▼
AgentOrchestrator (编排器)
       │
       ├── CollaborationConfig (协同配置)
       ├── SharedContext (共享上下文)
       ├── AgentExecutor (Agent 执行器)
       │
       ▼
CollaborationStrategy (策略接口)
       │
       ├── DiscussionStrategy   → debate / roleplay / consensus
       ├── TeamWorkStrategy     → team（并行/串行子任务）
       ├── HierarchyStrategy    → hierarchy（层级汇报）
       ├── WorkflowStrategy     → workflow（工作流引擎）
       └── DynamicRoutingStrategy → dynamic（动态路由）
```

#### 7 种协同模式

| 模式 | 策略类 | 说明 |
|------|--------|------|
| `debate` | `DiscussionStrategy` | 正反方观点对决，适合利弊权衡 |
| `roleplay` | `DiscussionStrategy` | 多角色对话模拟、场景演练 |
| `consensus` | `DiscussionStrategy` | 多方讨论后投票达成共识 |
| `team` | `TeamWorkStrategy` | 任务分解为子任务并行/串行执行 |
| `hierarchy` | `HierarchyStrategy` | 层级汇报式决策，逐层汇总 |
| `workflow` | `WorkflowStrategy` | 多步骤工作流，支持 LLM 动态生成 |
| `dynamic` | `DynamicRoutingStrategy` | Router Agent 动态选择下一个发言者 |

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

### 3.9 自我进化引擎 — `agent/evolution/`

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

- `CronService`：守护线程，每秒检查任务列表，支持三种调度方式：
  - Cron 表达式
  - 固定间隔 `EVERY`
  - 单次定时 `AT`
- 存储：`CronJob` + `CronSchedule` + `CronJobState` + `CronPayload` 持久化到 `workspace/cron/jobs.json`
- 使用 `CronStore` 接口抽象存储，`ReentrantReadWriteLock` 保证并发安全
- 到期任务通过回调构造消息，调用 `AgentLoop.processDirectWithChannel`

### 3.12 会话管理 — `session/`

- `SessionManager`：使用 `ConcurrentHashMap<String, Session>` 作为内存缓存
- 会话标识形如 `channel:chatId`（CLI 默认为 `cli:default`）
- 会话 JSON 数据存储在 `workspace/sessions/{session-key}.json`
- `Session`：包含 `List<Message>` 历史、`summary`、创建/更新时间
- `ToolCallRecord`：记录工具调用的详细信息（名称、参数、结果、耗时）

### 3.13 心跳服务 — `heartbeat/`

- `HeartbeatService` 在守护线程中周期性运行
- 读取 `memory/HEARTBEAT.md` 作为心跳上下文
- 通过回调把心跳提示交给 Agent，让其执行自检、整理待办等

### 3.14 安全沙箱 — `security/`

- `SecurityGuard` 提供多层安全防护：
  - **工作空间沙箱**：所有文件操作限制在 workspace 目录内
  - **命令黑名单**：阻止危险命令（`rm -rf`、`mkfs`、`dd` 等）
  - **路径规范化**：防止路径遍历攻击
  - **自定义黑名单**：支持通过配置扩展命令黑名单

### 3.15 Web 控制台 — `web/`

**核心类**：`WebConsoleServer`、`SecurityMiddleware`、`WebUtils`

- `WebConsoleServer`：内置轻量 HTTP 服务器，提供 Web UI 和 REST API
- `SecurityMiddleware`：Web 安全中间件（认证、CORS 等）

**16 个 REST API Handler**：

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
| `FilesHandler` | 文件浏览与操作 |
| `UploadHandler` | 文件上传 |
| `WorkspaceHandler` | 工作空间管理 |
| `MCPHandler` | MCP 服务器管理 |
| `FeedbackHandler` | 用户反馈收集 |
| `TokenStatsHandler` | Token 用量统计 |
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
                                   AgentLoop.run()
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
                     LLMExecutor.execute()
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
用户消息 ──► AgentLoop ──► LLMExecutor
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
├── agent/                           # Agent 核心引擎
│   ├── AgentLoop.java               #   生命周期管理与消息消费主循环
│   ├── MessageRouter.java           #   消息路由（用户/系统/指令）
│   ├── ProviderManager.java         #   LLM Provider 管理与热重载
│   ├── ProviderComponents.java      #   Provider 派生组件容器
│   ├── LLMExecutor.java             #   LLM 调用与工具迭代循环
│   ├── ContextBuilder.java          #   分段式上下文构建
│   ├── SessionSummarizer.java       #   会话摘要与上下文压缩
│   ├── AgentConstants.java          #   Agent 相关常量
│   ├── context/                     #   上下文分段模块
│   │   ├── ContextSection.java      #     Section 接口
│   │   ├── SectionContext.java      #     Section 上下文数据
│   │   ├── IdentitySection.java     #     身份段
│   │   ├── BootstrapSection.java    #     基础行为段
│   │   ├── ToolsSection.java        #     工具摘要段
│   │   ├── SkillsSection.java       #     技能摘要段
│   │   └── MemorySection.java       #     记忆段
│   ├── evolution/                   #   自我进化引擎
│   │   ├── PromptOptimizer.java     #     Prompt 自动优化（3 种策略）
│   │   ├── FeedbackManager.java     #     反馈收集与管理
│   │   ├── MemoryEvolver.java       #     记忆进化
│   │   ├── MemoryStore.java         #     长期记忆存储
│   │   ├── MemoryEntry.java         #     记忆条目
│   │   ├── EvolutionConfig.java     #     进化配置
│   │   ├── EvaluationFeedback.java  #     评估反馈模型
│   │   ├── FeedbackType.java        #     反馈类型枚举
│   │   └── OptimizationResult.java  #     优化结果
│   └── collaboration/               #   多 Agent 协同编排
│       ├── AgentOrchestrator.java   #     协同编排器
│       ├── CollaborationConfig.java #     协同配置（7 种模式）
│       ├── SharedContext.java       #     共享上下文
│       ├── AgentExecutor.java       #     Agent 执行器
│       ├── AgentRole.java           #     角色定义
│       ├── AgentMessage.java        #     Agent 间消息
│       ├── Artifact.java            #     协同产物
│       ├── TeamTask.java            #     团队任务
│       ├── CollaborationRecord.java #     协同记录
│       ├── HierarchyConfig.java     #     层级配置
│       ├── ApprovalCallback.java    #     审批回调
│       ├── ExecutionContext.java     #     执行上下文
│       ├── CollaborationExecutorPool.java # 协同线程池
│       ├── strategy/                #     协同策略
│       │   ├── CollaborationStrategy.java  # 策略接口
│       │   ├── DiscussionStrategy.java     # 讨论策略
│       │   ├── TeamWorkStrategy.java       # 团队策略
│       │   ├── HierarchyStrategy.java      # 层级策略
│       │   ├── WorkflowStrategy.java       # 工作流策略
│       │   └── DynamicRoutingStrategy.java # 动态路由策略
│       └── workflow/                #     工作流引擎
│           ├── WorkflowEngine.java  #       执行引擎
│           ├── WorkflowDefinition.java #    工作流定义
│           ├── WorkflowNode.java    #       节点（6 种类型）
│           ├── WorkflowContext.java #       执行上下文
│           ├── WorkflowGenerator.java #     LLM 动态生成
│           ├── NodeResult.java      #       节点结果
│           ├── NodeExecutor.java    #       节点执行器接口
│           └── executor/            #       节点执行器实现
├── bus/                             # 消息总线
│   ├── MessageBus.java              #   发布/订阅消息中心
│   ├── InboundMessage.java          #   入站消息模型
│   ├── OutboundMessage.java         #   出站消息模型
│   └── BusClosedException.java      #   总线关闭异常
├── channels/                        # 消息通道适配器（7 种）
│   ├── Channel.java / BaseChannel.java / ChannelManager.java
│   ├── WebhookServer.java / ChannelException.java
│   └── TelegramChannel / DiscordChannel / FeishuChannel /
│       DingTalkChannel / WhatsAppChannel / QQChannel / MaixCamChannel
├── cli/                             # 命令行接口（8 个命令）
│   ├── CliCommand.java / OnboardCommand / AgentCommand /
│   │   GatewayCommand / GatewayBootstrap / StatusCommand /
│   │   CronCommand / SkillsCommand / McpCommand / DemoCommand
├── config/                          # 配置模型与加载（11 个类）
│   ├── Config / ConfigLoader / AgentConfig / ProvidersConfig /
│   │   ModelsConfig / ChannelsConfig / ToolsConfig / GatewayConfig /
│   │   MCPServersConfig / SocialNetworkConfig / ConfigException
├── cron/                            # 定时任务引擎
│   ├── CronService / CronJob / CronSchedule / CronJobState /
│   │   CronPayload / CronStore
├── heartbeat/                       # 心跳服务
│   └── HeartbeatService.java
├── logger/                          # 结构化日志
│   └── TinyClawLogger.java
├── mcp/                             # MCP 协议集成
│   ├── MCPManager / MCPClient / SSEMCPClient /
│   │   StdioMCPClient / StreamableHttpMCPClient /
│   │   MCPMessage / MCPServerInfo
├── providers/                       # LLM 调用抽象
│   ├── LLMProvider / HTTPProvider / Message / ToolCall /
│   │   ToolDefinition / LLMResponse / StreamEvent / LLMException
├── security/                        # 安全沙箱
│   └── SecurityGuard.java
├── session/                         # 会话管理
│   ├── SessionManager / Session / ToolCallRecord
├── skills/                          # 技能系统
│   ├── SkillsLoader / SkillRegistry / SkillsSearcher /
│   │   SkillsInstaller / SkillInfo
├── tools/                           # Agent 工具集（23 个类）
│   ├── Tool / ToolRegistry / StreamAwareTool / ToolContextAware /
│   │   ToolException / SubagentManager / MCPTool /
│   │   TokenUsageTool / TokenUsageStore / CollaborateTool /
│   │   ReadFileTool / WriteFileTool / AppendFileTool / EditFileTool /
│   │   ListDirTool / ExecTool / WebSearchTool / WebFetchTool /
│   │   MessageTool / CronTool / SpawnTool / SkillsTool /
│   │   SocialNetworkTool
├── util/                            # 工具类
│   ├── StringUtils.java / SSLUtils.java
├── voice/                           # 语音转写
│   ├── Transcriber.java / AliyunTranscriber.java
└── web/                             # Web 控制台
    ├── WebConsoleServer.java / SecurityMiddleware.java / WebUtils.java
    └── handler/                     # 16 个 REST API Handler
        ├── AuthHandler / ChatHandler / SessionsHandler /
        │   ConfigHandler / ModelsHandler / ProvidersHandler /
        │   ChannelsHandler / SkillsHandler / CronHandler /
        │   FilesHandler / UploadHandler / WorkspaceHandler /
        │   MCPHandler / FeedbackHandler / TokenStatsHandler /
        │   StaticHandler
```

---

## 七、扩展指南

### 7.1 添加新的消息通道

1. 创建 `XxxChannel extends BaseChannel`
2. 实现 `start()` / `stop()` / `send(OutboundMessage)` / `isAllowed(senderId)`
3. 在 `ChannelsConfig` 中添加对应配置模型
4. 在 `ChannelManager` 中注册新通道

### 7.2 添加新的工具

1. 创建 `XxxTool implements Tool`
2. 实现 `name()` / `description()` / `parameters()` / `execute(args)`
3. 在 `AgentLoop` 或 `ToolRegistry` 中注册
4. 如需流式输出支持，额外实现 `StreamAwareTool`

### 7.3 添加新的协同策略

1. 创建 `XxxStrategy implements CollaborationStrategy`
2. 实现 `execute(SharedContext, List<AgentExecutor>, CollaborationConfig)`
3. 在 `AgentOrchestrator.initStrategies()` 中注册
4. 在 `CollaborationConfig.Mode` 中添加新模式

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
