## TinyClaw 技术架构文档

> 版本：0.1.0 ｜ 最后更新：2026-02-16

---

## 一、项目概述

**TinyClaw** 是一个用 Java 编写的超轻量个人 AI 助手框架，提供多模型、多通道、多技能的一站式 AI Agent 能力。它以命令行工具和网关服务为入口，通过安全沙箱、工具系统、技能系统和 Web 控制台，把一个 LLM 封装成可在本地或服务器长期运行的「多通道智能体」。

### 1.1 核心设计理念

- **轻量化与可移植**：纯 Java 实现，无需 Spring 等重型框架，使用 Maven 构建，单 JAR 即可部署到任意支持 Java 17 的环境。
- **模块解耦**：入口 CLI、Agent 引擎、消息总线、通道适配、LLM Provider、工具系统、技能系统等通过清晰接口解耦，便于替换和扩展。
- **配置驱动**：使用 `config.json`、工作空间内 Markdown 文件（AGENTS / SOUL / USER / IDENTITY / SKILL）驱动 Agent 行为与个性。
- **工具优先**：围绕工具调用（function calling）设计，Agent 通过工具执行文件操作、Shell 命令、网络访问、定时任务、子代理等复杂动作。
- **安全优先**：内置 **SecurityGuard**，对文件操作和命令执行实施工作空间沙箱与命令黑名单，适合长期运行与生产环境。
- **可观测与可演示**：提供 Web 控制台、日志体系以及 Demo 命令，方便现场演示和日常运维。

### 1.2 技术栈概览

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven |
| HTTP 客户端 | OkHttp 4.12 |
| JSON 处理 | Jackson 2.17 |
| 日志 | SLF4J + Logback |
| 命令行 | JLine 3.25 |
| Telegram | telegrambots 6.8 |
| Discord | JDA 5.x |
| 飞书 | oapi-sdk 2.3 |
| 钉钉 | dingtalk SDK 2.0 |
| Cron | cron-utils 9.2 |
| 测试 | JUnit 5.10 + Mockito |

---

## 二、整体架构

### 2.1 架构总览

从上到下，可以粗略分为：CLI / 网关入口层 → Agent 引擎 → 消息总线与通道层 → LLM 提供商与工具系统 → 配置、会话、技能、安全等基础设施层。

```text
┌────────────────────────────────────────────┐
│               CLI & Gateway 入口层          │
│  TinyClaw.java + CliCommand 子类            │
│  onboard / agent / gateway / status / ...  │
└──────────────────────────┬─────────────────┘
                           │
                ┌──────────┼───────────┐
                ▼          ▼           ▼
        ┌────────────┐  ┌─────────┐  ┌────────────┐
        │ Agent 引擎 │  │ 网关服务 │  │ Web 控制台 │
        │ AgentLoop  │  │ Gateway  │  │ WebConsole │
        └─────┬──────┘  └────┬────┘  └────┬───────┘
              │              │            │
              ▼              │            │
      ┌────────────────────────────────────────┐
      │            消息总线 MessageBus         │
      │  inboundQueue ◄───► outboundQueue     │
      └───────┬──────────────────────┬────────┘
              │                      │
              ▼                      ▼
     ┌────────────────┐     ┌─────────────────────┐
     │ LLMProvider    │     │ 消息通道层 Channels │
     │ HTTPProvider   │     │ Telegram/Discord/...│
     └────────────────┘     └─────────┬───────────┘
              │                        │
       ┌──────┴────────┐              │
       ▼               ▼              ▼
┌─────────────┐  ┌─────────────┐  ┌──────────────┐
│ 工具系统    │  │ 会话/记忆    │  │ 定时/心跳    │
│ ToolRegistry│  │ Session/     │  │ Cron/Heartbeat│
│ + 各类 Tool │  │ MemoryStore  │  └──────────────┘
└─────────────┘  └─────────────┘
       │                 │
       ▼                 ▼
┌─────────────┐   ┌─────────────┐
│ 技能系统    │   │ 安全沙箱    │
│ SkillsLoader│   │ SecurityGuard│
│ SkillsTool  │   └─────────────┘
└─────────────┘
```

### 2.2 分层视角

| 层次 | 包路径 | 职责 |
|------|--------|------|
| **入口层** | `cli/`, `TinyClaw.java` | 命令行解析、命令分发、网关/Agent 启动 |
| **Agent 与业务层** | `agent/`, `cron/`, `heartbeat/`, `web/` | 推理循环、工具调用、会话摘要、定时任务、心跳、自带 Web 控制台 |
| **通信层** | `bus/`, `channels/`, `providers/` | 消息总线、通道适配、LLM HTTP 调用 |
| **基础设施层** | `config/`, `session/`, `skills/`, `tools/`, `security/`, `logger/`, `util/`, `voice/` | 配置管理、会话持久化、技能加载和管理、工具实现、安全守卫、日志、语音转写等 |

---

## 三、核心模块

### 3.1 应用入口 — TinyClaw

**位置**：`io.leavesfly.tinyclaw.TinyClaw`

- 使用 `LinkedHashMap<String, Supplier<CliCommand>>` 维护命令注册表，命令如：`onboard`、`agent`、`gateway`、`status`、`cron`、`skills`、`demo`、`version` 等。
- `run(String[] args)` 负责：
  - 无参数时打印帮助信息；
  - `version` / `--version` / `-v` 直接输出版本；
  - 其余命令从注册表中查找并执行对应 `CliCommand` 实例。
- 典型调用链：
  - CLI 交互模式：`TinyClaw.main` → `AgentCommand` → `GatewayBootstrap` / 直接创建 `AgentLoop`；
  - 网关模式：`TinyClaw.main` → `GatewayCommand` → 启动消息通道 + AgentLoop + WebConsoleServer。

### 3.2 Agent 引擎 — `agent/`

**核心类**：`AgentLoop`、`ContextBuilder`、`LLMExecutor`、`SessionSummarizer`、`MemoryStore`、`AgentConstants`

#### AgentLoop — Agent 主循环

- 负责协调 **消息总线、会话管理、上下文构建、LLM 调用、工具执行、摘要触发**。
- 支持两类入口：
  - `run()`：网关模式下，持续从 `MessageBus.consumeInbound()` 取消息；
  - `processDirect(...)` / `processDirectStream(...)`：CLI / Web 控制台直连模式。
- 初始化时：
  - 创建 `ToolRegistry`、`SessionManager`、`ContextBuilder`；
  - 根据 `Config` 中的 `agent.model` / `agent.maxTokens` / `agent.maxToolIterations`，构造 `LLMExecutor` 与 `SessionSummarizer`；
  - 允许在启动后通过 `setProvider(LLMProvider)` 动态注入 LLM Provider。
- 对每条用户消息：
  1. 根据 `sessionKey` 从 `SessionManager` 读取历史与摘要；
  2. 通过 `ContextBuilder.buildMessages(...)` 生成系统提示 + 历史 + 当前消息；
  3. 调用 `LLMExecutor.execute(...)` 或 `executeStream(...)`；
  4. 将用户与助手消息写回会话并保存；
  5. 调用 `SessionSummarizer.maybeSummarize(sessionKey)` 做按需摘要。
- 当消息来自 `channel=system` 时，会被视为后台任务结果，路由回原始会话并通过 `MessageBus.publishOutbound` 回复用户。

#### LLMExecutor — LLM 迭代与工具调用

- 输入：`List<Message>` 历史 + 系统提示，`sessionKey`。
- 行为：
  - 使用 `LLMProvider.chat` / `chatStream` 调用远端模型；
  - 如果无工具调用请求，直接返回文本并记录日志；
  - 如果有工具调用：
    - 将含 tool_calls 的助手消息写入历史与会话；
    - 依次调用 `ToolRegistry.execute(...)`，将每次工具结果以 `Message.tool(...)` 追加到历史；
    - 最多迭代 `maxIterations` 次（防止无限循环）。
- 默认 LLM 选项从 `AgentConstants` 读取：`DEFAULT_MAX_TOKENS=8192`、`DEFAULT_TEMPERATURE=0.7`。

#### SessionSummarizer — 会话摘要与上下文压缩

- 根据 **消息数量** 与 **Token 估算** 判断是否需要摘要：
  - `SUMMARIZE_MESSAGE_THRESHOLD`：历史消息数超过该阈值；
  - 或总 Token 数占上下文窗口超过 `SUMMARIZE_TOKEN_PERCENTAGE%`。
- 策略：
  1. 保留最近 `RECENT_MESSAGES_TO_KEEP` 条消息；
  2. 对较早的 user/assistant 消息进行摘要，必要时采用分批摘要；
  3. 使用同一 LLM Provider 生成摘要，并与已有摘要 merge；
  4. 只保留摘要 + 最近消息，大幅降低上下文长度。
- 摘要在后台守护线程中异步执行，不阻塞主消息处理。

#### ContextBuilder & MemoryStore

- `ContextBuilder` 负责组装完整系统提示：
  - AGENT 身份与行为（AGENTS.md / SOUL.md / USER.md / IDENTITY.md 等）；
  - 工具摘要（来自 `ToolRegistry.getSummaries()`）；
  - 技能摘要（来自 `SkillsLoader`）；
  - 记忆上下文（通过 `MemoryStore` 加载）；
  - 当前通道与会话信息；
  - 现有会话摘要与最近历史。
- `MemoryStore` 使用文件系统保存长期记忆：`workspace/memory/MEMORY.md` 等，Agent 可以通过工具主动写入记忆。

### 3.3 消息总线 — `bus/`

**位置**：`io.leavesfly.tinyclaw.bus`

- `MessageBus` 提供统一的入站/出站队列：
  - `LinkedBlockingQueue<InboundMessage> inbound`（默认容量 100）；
  - `LinkedBlockingQueue<OutboundMessage> outbound`（默认容量 100）。
- 通道层只负责：
  - 收到平台消息 → 组装 `InboundMessage` → `publishInbound`；
  - 订阅 `OutboundMessage` 并根据 `channel` 转发。
- Agent 只依赖 `consumeInbound` / `publishOutbound`，与各平台 SDK 完全解耦。

### 3.4 消息通道层 — `channels/`

**核心接口**：`Channel`、`BaseChannel`、`ChannelManager`、`WebhookServer`

- `Channel` 定义统一能力：`name()` / `start()` / `stop()` / `send(OutboundMessage)` / `isAllowed(senderId)`。
- `BaseChannel` 封装了通用逻辑（白名单校验、日志等），具体通道只需关注各自 SDK 调用。
- `ChannelManager`：
  - 根据 `ChannelsConfig` 初始化各通道；
  - 管理所有通道的 `startAll` / `stopAll`；
  - 后台线程从 MessageBus 出站队列消费并调度到对应 `Channel.send`。
- 已实现通道：Telegram、Discord、Feishu、DingTalk、WhatsApp、QQ、MaixCam 等。
- `WebhookServer`：内置轻量 HTTP 服务器，为飞书、钉钉等通道提供 Webhook 回调入口。
- 语音消息由各通道通过 `voice/Transcriber`（当前实现为 `AliyunTranscriber`）转换为文本后再交给 Agent。

### 3.5 LLM 提供商与模型路由 — `providers/`

**核心接口**：`LLMProvider`、`HTTPProvider`、`Message`、`ToolCall`、`ToolDefinition`、`LLMResponse`

- `LLMProvider` 抽象：
  - `chat(messages, tools, model, options)`：普通对话 + 工具调用；
  - `chatStream(...)`：流式对话，配合 Web 控制台 SSE 使用；
  - `getDefaultModel()`：可选默认模型描述。
- `HTTPProvider` 是当前唯一实现，通过 **OpenAI 兼容接口** 访问各类 LLM：
  - `POST {apiBase}/chat/completions`，请求体包含 `model`、`messages`、`tools`、`tool_choice` 等字段；
  - 解析文本内容与工具调用（包括流式增量 tool_calls）。
- **模型路由**：
  - `Config.getModels().getDefinitions()` 中维护模型到 provider 的映射及上下文窗口；
  - `HTTPProvider.createProvider(config)`：
    1. 按 `agent.model` 名称查找模型定义；
    2. 根据模型上的 `provider` 字段选择对应 provider 配置；
    3. 构造统一的 `HTTPProvider(apiKey, apiBase)` 实例。
- 当前支持的 provider 名称（由 `ModelsConfig + ProvidersConfig` 驱动）：
  - `openrouter`（多模型网关）；
  - `openai`；
  - `anthropic`；
  - `zhipu`（智谱 GLM）；
  - `gemini`（Google）；
  - `dashscope`（阿里云通义）— 同时为语音转写提供 API Key；
  - `ollama`（本地模型，默认 `http://localhost:11434/v1`）。

### 3.6 工具系统 — `tools/`

**核心接口**：`Tool`、`ToolRegistry`

- `Tool` 抽象了一个可被 LLM 调用的功能：
  - `name()`：唯一名称；
  - `description()`：人类可读描述；
  - `parameters()`：JSON Schema 风格的参数定义；
  - `execute(Map<String, Object> args)`：执行并返回字符串结果。
- `ToolRegistry`：
  - 线程安全存储所有注册工具；
  - 提供 `register` / `unregister` / `execute` / `getDefinitions` / `getSummaries` 等能力；
  - 记录调用时长与结果长度，便于诊断。

**内置工具（节选）**：

- 文件与执行相关：`ReadFileTool`、`WriteFileTool`、`AppendFileTool`、`EditFileTool`、`ListDirTool`、`ExecTool`；
- 网络相关：`WebSearchTool`（Brave 搜索）、`WebFetchTool`（抓取网页内容）；
- Agent 运行相关：`MessageTool`（向通道发消息）、`CronTool`、`SpawnTool`（子 Agent）、`SkillsTool`（技能管理）、`SocialNetworkTool`（Agent 社交网络）；
- 所有文件与命令相关工具在内部都会通过 `SecurityGuard` 做沙箱校验。

### 3.7 技能系统 — `skills/` + `SkillsTool`

- 技能以 Markdown 文件形式存在：`{workspace}/skills/{skill-name}/SKILL.md`，支持 YAML frontmatter 声明 `name` 与 `description` 等元信息。
- `SkillsLoader` 负责：
  - 从 workspace / global / builtin 三个目录加载技能；
  - 构建技能摘要，供 `ContextBuilder` 注入系统提示时使用；
  - 按优先级覆盖同名技能（workspace > global > builtin）。
- `SkillsInstaller` 支持从 GitHub 仓库下载技能，便于复用社区能力。
- `SkillsTool` 将技能管理能力暴露给 Agent：
  - `list` / `show` / `invoke` / `install` / `create` / `edit` / `remove`；
  - `invoke` 返回符合「Claude Code Skills」标准的响应，包含技能 base-path，方便结合 `exec` 执行脚本型技能；
  - 这使得 Agent 可以在对话中 **自我安装、自我创建、自我改进技能**。

### 3.8 定时任务引擎 — `cron/`

- `CronService`：守护线程，每秒检查一次任务列表，支持三种调度方式：
  - Cron 表达式；
  - 固定间隔 `EVERY`；
  - 单次定时 `AT`。
- 存储：
  - 任务数据 `CronJob` + 调度配置 `CronSchedule` + 运行状态 `CronJobState` 持久化到 `workspace/cron/jobs.json`；
  - 使用 `ReentrantReadWriteLock` 保证任务表读写并发安全。
- 与 Agent 集成：
  - 到期任务通过 `CronTool` / 内部回调构造消息，调用 `AgentLoop.processDirectWithChannel`；
  - 如配置 `deliver=true`，再通过 `MessageTool` 把结果发送到指定通道/用户。

### 3.9 会话管理 — `session/`

- `SessionManager`：
  - 使用 `ConcurrentHashMap<String, Session>` 作为内存缓存；
  - 会话标识形如 `channel:chatId`（CLI 默认为 `cli:default`）；
  - 会话 JSON 数据存储在 `workspace/sessions/{session-key}.json`；
  - 提供历史列表、摘要字段、截断历史等方法，供 AgentLoop 和 SessionSummarizer 使用。
- `Session`：
  - 包含 `List<Message>` 历史、`summary`、创建/更新时间等，作为单个会话的持久化单元。

### 3.10 心跳服务 — `heartbeat/`

- `HeartbeatService` 在守护线程中周期性运行（间隔由配置控制）：
  - 读取 `memory/HEARTBEAT.md` 作为心跳上下文；
  - 组合当前时间等信息生成提示词；
  - 通过指定回调把心跳提示交给 Agent，让其执行自检、整理待办、刷新外部数据等。

### 3.11 安全沙箱 — `security/SecurityGuard`

- 提供两大安全能力：
  - **工作空间限制**：
    - 所有文件操作工具在执行前调用 `checkFilePath` / `checkWorkingDir`；
    - 只允许访问工作空间目录及子目录，阻止对 `/etc`、`/tmp` 等系统路径的访问；
  - **命令黑名单**：
    - `checkCommand` 根据一组正则模式阻止 `rm -rf`、磁盘格式化、关机重启、curl|wget + 管道执行脚本、sudo 提权等高危命令。
- 支持通过配置传入自定义黑名单，覆盖默认策略。

> 更详细的配置和模式说明可以参考 `docs/security-and-social-network.md`。

### 3.12 Web 控制台 — `web/WebConsoleServer`

- 内置基于 `com.sun.net.httpserver.HttpServer` 的轻量 Web 服务器，默认端口可在 `GatewayConfig` 中配置。
- 提供若干 REST API：
  - `/api/chat` / `/api/chat/stream`：与 Agent 对话（非流式 / SSE 流式）；
  - `/api/channels` / `/api/channels/{name}`：查看与修改通道配置；
  - `/api/sessions`：查看会话列表与详情；
  - `/api/cron`：管理定时任务；
  - `/api/workspace`：浏览与编辑工作空间文件；
  - `/api/skills`：技能列表与加载信息；
  - `/api/providers`、`/api/models`、`/api/config`：模型与 Provider 配置的读取和更新。
- 前端静态资源位于 `src/main/resources/web/`，通过根路径 `/` 提供简单 Web UI。

### 3.13 Agent 社交网络 — `SocialNetworkTool` + `SocialNetworkConfig`

- `SocialNetworkTool` 将 Agent 接入外部 Agent Social Network（例如 `ClawdChat.ai`）：
  - 支持 `send`（私信指定 Agent）、`broadcast`（频道广播）、`query`（搜索 Agent 目录）、`status`（查询网络状态）；
  - 所有请求通过 OkHttp 调用远端 HTTP API；
  - 内置消息长度限制（默认 10000 字符）以防滥用。
- `SocialNetworkConfig` 在 `config/` 中定义相关配置：
  - `enabled`、`endpoint`、`agentId`、`apiKey`、`agentName`、`agentDescription` 等。

---

## 四、典型数据流

### 4.1 CLI 直接对话

```text
用户输入
  │
  ▼
TinyClaw.main → AgentCommand
  │
  ▼
创建 Config + HTTPProvider + AgentLoop + 工具
  │
  ▼
AgentLoop.processDirect / processDirectStream
  │
  ├─ SessionManager.getOrCreate(sessionKey)
  ├─ ContextBuilder.buildMessages(...)
  ├─ LLMExecutor.execute(含工具迭代)
  ├─ SessionSummarizer.maybeSummarize
  └─ 输出最终回复
```

### 4.2 网关多通道模式

```text
外部平台消息 (Telegram / Discord / Feishu / ...)
  │
  ▼
对应 Channel.onMessage
  │  ├─ isAllowed(senderId)
  │  ├─ 语音消息 → AliyunTranscriber.transcribe()
  │  └─ 封装为 InboundMessage
  ▼
MessageBus.publishInbound
  ▼
AgentLoop.run 主循环
  │  ├─ consumeInbound
  │  ├─ buildContext + LLMExecutor + 工具迭代
  │  └─ publishOutbound
  ▼
ChannelManager.dispatchThread
  ▼
Channel.send → 发回到对应平台
```

### 4.3 Web 控制台对话

```text
浏览器 Web UI
  │
  ▼
POST /api/chat 或 /api/chat/stream
  │
  ▼
WebConsoleServer.handleChat / handleChatStream
  │
  └─ 调用 AgentLoop.processDirect / processDirectStream(sessionKey=web:...)
  ▼
返回 JSON 或 SSE 流，前端实时展示响应
```

### 4.4 定时任务触发

```text
CronService 后台线程
  │
  ├─ 检查 jobs.json 中任务是否到期
  └─ 到期任务 → 构造消息
      │
      ▼
AgentLoop.processDirectWithChannel
  │
  └─ 如配置 deliver=true → MessageTool 发送结果
```

### 4.5 Agent 社交网络调用

```text
用户在对话中要求与其他 Agent 通信
  │
  ▼
LLM 选择调用 social_network 工具
  │
  ├─ SocialNetworkTool.send / broadcast / query / status
  │
  └─ 通过 HTTP 调用社交网络服务
  ▼
工具返回结果 → 作为上下文再次交给 LLM 生成最终回复
```

---

## 五、工作空间与配置结构

### 5.1 工作空间目录

默认工作空间位于 `~/.tinyclaw/workspace/`，典型结构：

```text
~/.tinyclaw/workspace/
├─ AGENTS.md           # Agent 行为指令
├─ SOUL.md             # Agent 个性设定
├─ USER.md             # 用户信息与偏好
├─ IDENTITY.md         # Agent 身份描述
├─ memory/
│  ├─ MEMORY.md        # 长期记忆
│  ├─ HEARTBEAT.md     # 心跳上下文
│  └─ heartbeat.log    # 心跳日志
├─ skills/
│  └─ {skill-name}/
│       └─ SKILL.md    # 技能定义
├─ sessions/
│  └─ {session-key}.json
└─ cron/
   └─ jobs.json        # 定时任务配置与状态
```

### 5.2 配置模型

`Config` 是顶层配置入口，字段大致包括：

- `agents`：Agent 默认参数（workspace、model、maxTokens、maxToolIterations、heartbeatEnabled、restrictToWorkspace、commandBlacklist 等）；
- `providers`：各 Provider 的 `apiKey` 与 `apiBase`（openrouter/openai/anthropic/zhipu/gemini/dashscope/ollama 等）；
- `channels`：各消息通道的 `enabled`、凭证与 `allowFrom` 白名单；
- `gateway`：Web 控制台与网关监听地址/端口；
- `tools`：工具相关参数（例如 Web 搜索结果数量等）；
- `models`：模型定义与别名，指向具体 Provider；
- `socialNetwork`：Agent 社交网络配置。

---

## 六、线程模型

```text
主进程 main
  │
  ├─ AgentLoop 消息处理线程（网关模式）
  ├─ ChannelManager.dispatchThread 出站分发线程
  ├─ 各通道内部线程（由外部 SDK 管理，Telegram/Discord 等）
  ├─ CronService 调度线程（daemon）
  ├─ HeartbeatService 心跳线程（daemon）
  ├─ SessionSummarizer 异步摘要线程（按需创建，daemon）
  └─ WebConsoleServer 线程池（处理 HTTP 请求）
```

并发安全主要依赖：

- `ConcurrentHashMap`：工具注册表、会话缓存、摘要中会话集合等；
- `LinkedBlockingQueue`：消息总线入站/出站队列；
- `ReentrantReadWriteLock`：定时任务读写控制；
- `volatile` 与内置锁对象：控制 Provider、运行状态等可见性。

---

## 七、关键设计模式小结

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| **命令模式** | `CliCommand` 及其子类 | 封装各 CLI 子命令逻辑 |
| **策略模式** | `LLMProvider` / 不同 Provider 配置 | 支持多家 LLM 服务商与本地推理 |
| **适配器模式** | `Channel` 及各平台通道实现 | 统一封装 Telegram/Discord/Feishu 等 SDK 差异 |
| **发布-订阅** | `MessageBus` | 解耦通道层与 Agent 层 |
| **工厂方法** | `HTTPProvider.createProvider` | 根据模型定义选择 Provider 和 API Base |
| **注册表模式** | `ToolRegistry` | 集中管理 Agent 工具并向 LLM 暴露工具元数据 |
| **模板方法** | `BaseChannel` | 复用通道生命周期与消息处理模板逻辑 |
| **观察者模式** | `ChannelManager.dispatchThread` | 监听 out 队列并推送到各通道 |

这份文档反映了当前版本 TinyClaw 的实际代码结构与功能模块，可作为阅读源码、扩展通道/工具/技能或集成新 LLM Provider 时的参考蓝本。