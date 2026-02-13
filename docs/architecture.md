# TinyClaw 技术架构文档

> 版本：0.1.0 | 最后更新：2026-02-12

---

## 一、项目概述

**TinyClaw** 是一个用 Java 编写的超轻量个人 AI 助手框架，提供多模型、多通道、多技能的一站式 AI Agent 能力。它以命令行工具为入口，支持通过网关模式同时连接多个消息平台（Telegram、Discord、飞书、钉钉等），并通过工具调用和技能插件系统扩展 Agent 的能力边界。

### 核心设计理念

- **轻量化**：纯 Java 实现，无需 Spring 等重型框架，单 JAR 即可运行
- **可插拔**：LLM 提供商、消息通道、工具、技能均为接口驱动，支持灵活扩展
- **事件驱动**：基于消息总线的发布/订阅模式，实现组件间松耦合
- **文件即配置**：通过 JSON 配置文件和 Markdown 文件定义 Agent 行为与个性

### 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 构建工具 | Maven | 3.x |
| HTTP 客户端 | OkHttp | 4.12 |
| JSON 处理 | Jackson | 2.17 |
| 日志 | SLF4J + Logback | - |
| 命令行交互 | JLine | 3.25 |
| Telegram SDK | telegrambots | 6.8 |
| Discord SDK | JDA | 5.0 |
| 飞书 SDK | oapi-sdk | 2.3 |
| 钉钉 SDK | dingtalk SDK | 2.0 |
| 定时任务 | cron-utils | 9.2 |
| 测试 | JUnit 5 + Mockito | 5.10 |

---

## 二、整体架构

### 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLI 入口层                                │
│  TinyClaw.java → 命令注册表 → CliCommand 子类                    │
│  (onboard / agent / gateway / status / cron / skills)           │
└──────────────────────────┬──────────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  Agent 引擎  │  │  网关服务     │  │  管理命令     │
│  AgentLoop   │  │  Gateway     │  │  Status/Cron │
│  + Context   │  │  Command     │  │  /Skills     │
└──────┬───────┘  └──────┬───────┘  └──────────────┘
       │                 │
       │    ┌────────────┘
       ▼    ▼
┌──────────────────────────────────────────────────────────────┐
│                      消息总线 (MessageBus)                     │
│          InboundQueue ◄──── 通道层 ────► OutboundQueue        │
└──────┬───────────────────────────────────────────┬───────────┘
       │                                           │
       ▼                                           ▼
┌──────────────┐                          ┌──────────────────┐
│  LLM 提供商  │                          │   消息通道层       │
│  HTTPProvider│                          │  ChannelManager   │
│  (OpenAI     │                          │  ┌─────────────┐ │
│   兼容 API)  │                          │  │ Telegram     │ │
└──────────────┘                          │  │ Discord      │ │
                                          │  │ 飞书 / 钉钉   │ │
┌──────────────┐                          │  │ QQ / WhatsApp│ │
│  工具注册表   │                          │  │ MaixCam      │ │
│  ToolRegistry│                          │  └─────────────┘ │
│  ┌─────────┐ │                          └──────────────────┘
│  │read_file│ │
│  │exec     │ │    ┌──────────────┐    ┌──────────────┐
│  │web_*    │ │    │  定时任务引擎  │    │  心跳服务     │
│  │cron     │ │    │  CronService  │    │  Heartbeat   │
│  │spawn    │ │    └──────────────┘    └──────────────┘
│  │...      │ │
│  └─────────┘ │    ┌──────────────┐    ┌──────────────┐
└──────────────┘    │  技能系统     │    │  会话管理     │
                    │  SkillsLoader│    │  Session     │
                    └──────────────┘    │  Manager     │
                                       └──────────────┘
```

### 分层架构

项目采用**四层架构**设计：

| 层次 | 包路径 | 职责 |
|------|--------|------|
| **入口层** | `cli/` | 解析命令行参数，分发到对应的命令处理器 |
| **业务层** | `agent/`, `cron/`, `heartbeat/`, `skills/` | 核心业务逻辑：Agent 推理循环、定时调度、心跳、技能管理 |
| **通信层** | `bus/`, `channels/`, `providers/` | 消息路由、通道适配、LLM API 调用 |
| **基础层** | `config/`, `session/`, `tools/`, `logger/`, `util/`, `voice/` | 配置加载、会话持久化、工具执行、日志、语音转写 |

---

## 三、核心模块详解

### 3.1 应用入口 — `TinyClaw.java`

**包路径**：`io.leavesfly.tinyclaw`

应用程序的主入口类，采用**命令注册表模式**管理所有 CLI 命令：

```
TinyClaw.main(args)
  └─► run(args)
        ├─► 版本查询 → 输出版本号
        ├─► 从 COMMAND_REGISTRY 查找命令
        └─► commandSupplier.get().execute(subArgs)
```

- 使用 `LinkedHashMap<String, Supplier<CliCommand>>` 作为命令注册表
- 支持 `registerCommand()` 方法动态注册命令（便于测试）
- 所有命令实现 `CliCommand` 接口，统一 `execute(String[] args)` 入口

### 3.2 Agent 核心引擎 — `agent/`

**包路径**：`io.leavesfly.tinyclaw.agent`

这是整个系统的核心，包含三个关键类：

#### AgentLoop — Agent 主循环

Agent 的核心执行引擎，负责完整的对话处理流程：

```
消息到达 (MessageBus)
  │
  ▼
构建上下文 (ContextBuilder)
  │  ├─ 系统提示词（身份 + 引导文件 + 工具说明 + 技能摘要 + 记忆）
  │  ├─ 历史消息 + 摘要
  │  └─ 当前用户消息
  │
  ▼
调用 LLM (LLMProvider.chat())
  │
  ├─► 纯文本响应 → 返回给用户
  │
  └─► 工具调用请求 → 执行工具 (ToolRegistry.execute())
        │                    │
        │                    ▼
        │              工具执行结果
        │                    │
        └────────────────────┘
              ▼
        再次调用 LLM（携带工具结果）
              │
              ▼
        循环直到无工具调用或达到最大迭代次数
              │
              ▼
        保存会话历史 + 触发自动摘要（如需要）
```

**关键参数**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `maxIterations` | 20 | 单次对话最大工具调用迭代次数 |
| `SUMMARIZE_MESSAGE_THRESHOLD` | 20 | 触发自动摘要的消息数阈值 |
| `SUMMARIZE_TOKEN_PERCENTAGE` | 75% | 触发摘要的上下文窗口占比 |
| `RECENT_MESSAGES_TO_KEEP` | 4 | 摘要时保留的最近消息数 |

**运行模式**：
- **直接模式** (`processDirect`)：CLI 单条消息处理，同步返回结果
- **总线模式** (`run`)：网关模式下持续监听 MessageBus，异步处理消息

#### ContextBuilder — 上下文构建器

负责组装发送给 LLM 的完整上下文，采用**模块化组装**策略：

```
系统提示词 = 身份信息
           + 引导文件 (AGENTS.md / SOUL.md / USER.md / IDENTITY.md)
           + 工具说明 (ToolRegistry.getSummaries())
           + 技能摘要 (SkillsLoader.buildSkillsSummary())
           + 记忆上下文 (MemoryStore.getMemoryContext())
           + 当前会话信息 (channel + chatId)
           + 历史对话摘要
```

各部分之间使用 `---` 分隔符连接，便于 LLM 理解结构层次。

#### MemoryStore — 长期记忆存储

基于文件系统的记忆管理，存储路径为 `workspace/memory/MEMORY.md`。Agent 可通过工具调用主动写入记忆，ContextBuilder 在构建上下文时自动加载。

### 3.3 消息总线 — `bus/`

**包路径**：`io.leavesfly.tinyclaw.bus`

采用**发布/订阅模式**的消息中枢，实现通道层与 Agent 层的完全解耦：

```
通道层                    消息总线                    Agent层
  │                         │                         │
  │  publishInbound()       │                         │
  ├────────────────────────►│                         │
  │                         │  consumeInbound()       │
  │                         ├────────────────────────►│
  │                         │                         │
  │                         │  publishOutbound()      │
  │                         │◄────────────────────────┤
  │  subscribeOutbound()    │                         │
  │◄────────────────────────┤                         │
```

**核心设计**：

| 组件 | 类型 | 容量 | 说明 |
|------|------|------|------|
| `inbound` | `LinkedBlockingQueue<InboundMessage>` | 100 | 入站消息队列 |
| `outbound` | `LinkedBlockingQueue<OutboundMessage>` | 100 | 出站消息队列 |
| `handlers` | `ConcurrentHashMap` | - | 通道处理器注册表 |

**消息模型**：
- **InboundMessage**：包含 `channel`（来源通道）、`chatId`（聊天标识）、`content`（消息内容）、`senderId`（发送者）等字段
- **OutboundMessage**：包含 `channel`（目标通道）、`chatId`（聊天标识）、`content`（响应内容）等字段

**流量控制**：队列满时丢弃新消息并记录警告日志，防止内存溢出。

### 3.4 消息通道层 — `channels/`

**包路径**：`io.leavesfly.tinyclaw.channels`

#### Channel 接口

所有消息通道的统一抽象，定义了五个核心方法：

| 方法 | 说明 |
|------|------|
| `name()` | 返回通道名称标识 |
| `start()` | 启动通道连接 |
| `stop()` | 停止通道 |
| `send(OutboundMessage)` | 通过该通道发送消息 |
| `isAllowed(String senderId)` | 检查发送者是否在白名单中 |

#### ChannelManager — 通道管理器

统一管理所有通道的生命周期和消息路由：

```
ChannelManager
  ├─ initChannels()        ← 根据配置初始化各通道
  │    ├─ TelegramChannel
  │    ├─ DiscordChannel
  │    ├─ WhatsAppChannel
  │    ├─ FeishuChannel
  │    ├─ DingTalkChannel
  │    ├─ QQChannel
  │    └─ MaixCamChannel
  │
  ├─ startAll()            ← 启动所有已启用通道
  ├─ stopAll()             ← 停止所有通道
  └─ dispatchThread        ← 出站消息分发线程
       └─ 从 MessageBus 消费 OutboundMessage
            └─ 路由到对应 Channel.send()
```

**错误隔离**：单个通道的初始化或运行故障不会影响其他通道。

#### 已实现的通道

| 通道 | 类名 | 协议/SDK | 特殊能力 |
|------|------|----------|----------|
| Telegram | `TelegramChannel` | telegrambots SDK | 语音消息转写、长消息分片 |
| Discord | `DiscordChannel` | JDA SDK | 语音消息转写 |
| WhatsApp | `WhatsAppChannel` | HTTP Bridge | - |
| 飞书 | `FeishuChannel` | oapi-sdk | Webhook 回调 |
| 钉钉 | `DingTalkChannel` | dingtalk SDK | Webhook 回调 |
| QQ | `QQChannel` | HTTP API | - |
| MaixCam | `MaixCamChannel` | TCP Socket | 硬件设备通信 |

#### WebhookServer

内嵌的轻量 HTTP 服务器，为飞书、钉钉等需要 Webhook 回调的通道提供 HTTP 端点支持。

### 3.5 LLM 提供商层 — `providers/`

**包路径**：`io.leavesfly.tinyclaw.providers`

#### LLMProvider 接口

LLM 调用的统一抽象：

```java
public interface LLMProvider {
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools,
                     String model, Map<String, Object> options) throws Exception;
    String getDefaultModel();
}
```

#### HTTPProvider — 通用 HTTP 实现

所有 LLM 提供商均通过 **OpenAI 兼容 API** 格式统一适配，核心流程：

```
HTTPProvider.chat()
  ├─ 构建 JSON 请求体
  │    ├─ model
  │    ├─ messages[]     ← 支持 system / user / assistant / tool 角色
  │    ├─ tools[]        ← OpenAI 格式的工具定义
  │    └─ options        ← temperature, max_tokens 等
  │
  ├─ 发送 HTTP POST 到 {apiBase}/chat/completions
  │    ├─ Authorization: Bearer {apiKey}
  │    └─ Content-Type: application/json
  │
  └─ 解析响应
       ├─ content        ← 文本响应
       ├─ tool_calls[]   ← 工具调用请求
       ├─ finish_reason  ← 结束原因
       └─ usage          ← Token 使用统计
```

**智能路由**：`createProvider()` 工厂方法根据模型名称自动匹配对应的 API 提供商：

| 模型名称模式 | 匹配提供商 | 默认 API Base |
|-------------|-----------|---------------|
| `openrouter/*`, `anthropic/*`, `meta-llama/*` | OpenRouter | `https://openrouter.ai/api/v1` |
| `*claude*` | Anthropic | `https://api.anthropic.com/v1` |
| `*gpt*` | OpenAI | `https://api.openai.com/v1` |
| `*glm*`, `*zhipu*` | 智谱 | `https://open.bigmodel.cn/api/paas/v4` |
| `*qwen*` | DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `*gemini*` | Google | `https://generativelanguage.googleapis.com/v1beta` |
| `*groq*` | Groq | `https://api.groq.com/openai/v1` |
| 其他 | vLLM（本地） | 用户自定义 |

**HTTP 超时配置**：连接 30s / 读取 120s / 写入 30s。

#### 数据模型

| 类 | 说明 |
|----|------|
| `Message` | 对话消息，包含 role、content、toolCalls、toolCallId |
| `ToolCall` | 工具调用请求，包含 id、name、arguments |
| `ToolDefinition` | 工具定义（OpenAI 格式），包含 name、description、parameters |
| `LLMResponse` | LLM 响应，包含 content、toolCalls、finishReason、usage |

### 3.6 工具系统 — `tools/`

**包路径**：`io.leavesfly.tinyclaw.tools`

#### Tool 接口

所有工具的统一抽象，遵循 **OpenAI Function Calling** 规范：

```java
public interface Tool {
    String name();                          // 工具名称
    String description();                   // 工具描述
    Map<String, Object> parameters();       // JSON Schema 格式的参数定义
    String execute(Map<String, Object> args); // 执行工具
}
```

#### ToolRegistry — 工具注册表

工具的集中管理组件，基于 `ConcurrentHashMap` 实现线程安全：

- **注册/注销**：`register(Tool)` / `unregister(String)`
- **执行**：`execute(String name, Map args)` — 带性能监控（记录执行时间和结果长度）
- **元数据导出**：`getDefinitions()` 生成 OpenAI 格式工具定义，`getSummaries()` 生成人类可读摘要

#### 内置工具清单

| 工具 | 类名 | 功能 |
|------|------|------|
| `read_file` | `ReadFileTool` | 读取文件内容 |
| `write_file` | `WriteFileTool` | 写入文件（创建或覆盖） |
| `append_file` | `AppendFileTool` | 追加内容到文件 |
| `edit_file` | `EditFileTool` | 基于 diff 的精确文件编辑 |
| `list_dir` | `ListDirTool` | 列出目录内容 |
| `exec` | `ExecTool` | 执行 Shell 命令 |
| `web_search` | `WebSearchTool` | 网络搜索（Brave Search API） |
| `web_fetch` | `WebFetchTool` | 抓取网页内容 |
| `message` | `MessageTool` | 向指定通道发送消息 |
| `cron` | `CronTool` | 创建/管理定时任务 |
| `spawn` | `SpawnTool` | 生成子代理执行独立任务 |

#### SubagentManager — 子代理管理

管理通过 `spawn` 工具创建的子代理实例，支持 Agent 将复杂任务分解为独立的子任务并行处理。

### 3.7 技能插件系统 — `skills/`

**包路径**：`io.leavesfly.tinyclaw.skills`

#### 技能加载机制

技能以 Markdown 文件定义，支持三级优先级加载：

```
优先级：workspace > global > builtin

workspace/skills/{skill-name}/SKILL.md    ← 最高优先级
~/.tinyclaw/skills/{skill-name}/SKILL.md  ← 全局
内置技能目录                                ← 最低优先级
```

同名技能按优先级覆盖，高优先级的技能会屏蔽低优先级的同名技能。

#### SKILL.md 文件格式

```markdown
---
name: "技能名称"
description: "技能描述"
---

# 技能内容

当用户要求执行某某任务时，按照以下步骤操作：
1. ...
```

#### 核心类

| 类 | 说明 |
|----|------|
| `SkillsLoader` | 技能加载器，负责发现、加载、管理技能 |
| `SkillsInstaller` | 技能安装器，支持从 GitHub 安装技能 |
| `SkillInfo` | 技能信息 VO（name、path、source、description） |
| `SkillMetadata` | 技能元数据（从 YAML frontmatter 解析） |

#### 渐进式披露策略

ContextBuilder 在构建系统提示词时，只注入技能的**摘要信息**（名称、描述、位置），而非完整内容。Agent 需要使用某个技能时，通过 `read_file` 工具读取完整的 SKILL.md 内容。这样可以显著减少 Token 消耗。

### 3.8 定时任务引擎 — `cron/`

**包路径**：`io.leavesfly.tinyclaw.cron`

#### CronService — 调度核心

独立线程运行的定时任务调度器，支持三种调度模式：

| 模式 | 枚举值 | 说明 | 示例 |
|------|--------|------|------|
| Cron 表达式 | `CRON` | 标准 UNIX Cron 表达式 | `0 18 * * *`（每天 18:00） |
| 固定间隔 | `EVERY` | 固定毫秒间隔重复执行 | `3600000`（每小时） |
| 定时触发 | `AT` | 指定时间点一次性执行 | 某个时间戳 |

**核心流程**：

```
CronService.start()
  └─ runnerThread (daemon)
       └─ 每秒检查一次 (checkJobs)
            ├─ 遍历所有已启用任务
            ├─ 比较 nextRunAtMs 与当前时间
            ├─ 到期任务 → executeJob()
            │    └─ onJob.handle(job) → Agent 处理
            └─ 更新 nextRunAtMs + 持久化到 jobs.json
```

**并发控制**：使用 `ReentrantReadWriteLock` 保护任务列表的读写操作。

**持久化**：任务配置和状态以 JSON 格式存储在 `workspace/cron/jobs.json`。

#### 数据模型

| 类 | 说明 |
|----|------|
| `CronJob` | 任务定义（id、name、schedule、payload、state、enabled） |
| `CronSchedule` | 调度配置（kind、expr、everyMs、atMs） |
| `CronPayload` | 任务载荷（message、deliver、channel、to） |
| `CronJobState` | 任务运行状态（nextRunAtMs、lastRunAtMs、lastStatus、lastError） |

### 3.9 会话管理 — `session/`

**包路径**：`io.leavesfly.tinyclaw.session`

#### SessionManager

管理用户与 Agent 之间的对话会话：

- **会话标识**：使用 `channel:chatId` 格式作为会话键（如 `telegram:123456`）
- **内存缓存**：`ConcurrentHashMap<String, Session>` 提供高性能并发访问
- **磁盘持久化**：每个会话序列化为独立的 JSON 文件，存储在 `workspace/sessions/` 目录
- **懒加载**：启动时从磁盘批量加载已有会话，运行时按需创建新会话
- **摘要管理**：维护会话摘要，用于长对话的上下文压缩

#### Session

单个会话的数据容器，包含：
- 消息历史列表 (`List<Message>`)
- 会话摘要 (`String summary`)
- 创建/更新时间戳

### 3.10 心跳服务 — `heartbeat/`

**包路径**：`io.leavesfly.tinyclaw.heartbeat`

#### HeartbeatService

可配置的定时心跳机制，让 Agent 保持"活跃"状态：

```
HeartbeatService.start()
  └─ heartbeatThread (daemon)
       └─ 每 intervalSeconds 秒执行一次
            ├─ 构建心跳提示 (buildPrompt)
            │    ├─ 当前时间
            │    └─ 读取 memory/HEARTBEAT.md 上下文
            └─ 调用 onHeartbeat 回调
                 └─ Agent 自主思考并执行必要操作
```

心跳服务使 Agent 能够定期自主检查任务、发现问题并采取行动，而不仅仅是被动响应用户消息。

### 3.11 配置管理 — `config/`

**包路径**：`io.leavesfly.tinyclaw.config`

#### 配置层次结构

```
Config (根配置)
  ├─ AgentsConfig          ← Agent 参数（model、maxTokens、temperature 等）
  ├─ ChannelsConfig        ← 通道配置（各平台的 token、appId 等）
  ├─ ProvidersConfig       ← LLM 提供商配置（apiKey、apiBase）
  ├─ GatewayConfig         ← 网关配置（host、port）
  └─ ToolsConfig           ← 工具配置（搜索 API 等）
```

#### ConfigLoader

配置加载器，支持：
- 从 `~/.tinyclaw/config.json` 加载 JSON 配置
- 路径中的 `~` 自动展开为用户主目录
- 提供合理的默认值（`Config.defaultConfig()`）

### 3.12 语音转写 — `voice/`

**包路径**：`io.leavesfly.tinyclaw.voice`

#### GroqTranscriber

集成 Groq Whisper API 的语音转文字服务，被 Telegram 和 Discord 通道调用，自动将语音消息转写为文本后交给 Agent 处理。

### 3.13 日志系统 — `logger/`

**包路径**：`io.leavesfly.tinyclaw.logger`

#### TinyClawLogger

自定义日志封装，基于 SLF4J + Logback：
- 支持结构化日志（`Map<String, Object>` 参数）
- 按模块命名（如 `agent`、`bus`、`channels`、`cron`）
- 配置文件：`src/main/resources/logback.xml`

---

## 四、核心数据流

### 4.1 CLI 直接对话模式

```
用户输入
  │
  ▼
TinyClaw.main() → AgentCommand.execute()
  │
  ▼
AgentLoop.processDirect(content, sessionKey)
  │
  ├─ SessionManager.getOrCreate(sessionKey)
  ├─ ContextBuilder.buildMessages(history, summary, content)
  ├─ LLMProvider.chat(messages, tools, model, options)
  │    │
  │    ├─► 文本响应 → 返回
  │    └─► 工具调用 → ToolRegistry.execute() → 再次调用 LLM → 循环
  │
  ├─ SessionManager.save(session)
  └─ 返回最终响应文本
```

> **演示建议**：在本机完成 README 中的“快速开始”后，运行 `java -jar target/tinyclaw-0.1.0.jar agent`，输入一个问题，同时在另一终端观察日志，按上图顺序讲解从 `TinyClaw.main` 到 `AgentLoop.processDirect` 的处理流程。


### 4.2 网关多通道模式

```
外部消息 (Telegram/Discord/飞书/...)
  │
  ▼
Channel.onMessage()
  │
  ├─ isAllowed(senderId) → 白名单校验
  ├─ 语音消息 → GroqTranscriber.transcribe() → 文本
  │
  ▼
MessageBus.publishInbound(InboundMessage)
  │
  ▼
AgentLoop.run() [主循环]
  │
  ├─ MessageBus.consumeInbound() [阻塞等待]
  ├─ 构建上下文 + 调用 LLM + 工具循环
  │
  ▼
MessageBus.publishOutbound(OutboundMessage)
  │
  ▼
ChannelManager.dispatchThread
  │
  ├─ MessageBus.consumeOutbound()
  └─ Channel.send(OutboundMessage) → 发送到对应平台
```

> **演示建议**：在配置中启用一个 IM 通道（如 Telegram）并填好凭证后，运行 `java -jar target/tinyclaw-0.1.0.jar gateway`，从客户端发送消息，对照日志说明 `Channel.onMessage` → `MessageBus` → `AgentLoop.run` → `ChannelManager.dispatchThread` 的流转过程。


### 4.3 定时任务触发流程

```
CronService.runLoop() [每秒检查]
  │
  ├─ 任务到期 → CronJob
  │
  ▼
JobHandler.handle(job)
  │
  ├─ 构建消息 → AgentLoop.processDirect()
  │    └─ Agent 处理任务消息
  │
  └─ 如果 payload.deliver = true
       └─ MessageTool → 发送结果到指定通道
```

> **演示建议**：在终端通过 `tinyclaw cron add --name "demo" --message "这是一条演示任务" --every 30` 创建一个短周期任务，保持 gateway 运行，等任务触发时讲解从 `CronService.runLoop` 到 `AgentLoop.processDirect` 再到 `MessageTool` 的调用路径。


---

## 五、工作空间结构

```
~/.tinyclaw/
  └─ config.json              ← 全局配置文件

~/.tinyclaw/workspace/
  ├─ AGENTS.md                ← Agent 行为指令
  ├─ SOUL.md                  ← Agent 个性定义
  ├─ USER.md                  ← 用户信息和偏好
  ├─ IDENTITY.md              ← Agent 身份描述
  ├─ memory/
  │    ├─ MEMORY.md           ← 长期记忆存储
  │    ├─ HEARTBEAT.md        ← 心跳上下文
  │    ├─ heartbeat.log       ← 心跳日志
  │    └─ YYYYMM/
  │         └─ YYYYMMDD.md    ← 每日笔记
  ├─ skills/                  ← 技能插件目录
  │    └─ {skill-name}/
  │         └─ SKILL.md
  ├─ sessions/                ← 会话持久化
  │    └─ {session-key}.json
  └─ cron/
       └─ jobs.json           ← 定时任务持久化
```

---

## 六、源码目录结构

```
src/main/java/io/leavesfly/tinyclaw/
├── TinyClaw.java                    # 应用入口，命令分发
│
├── agent/                           # Agent 核心引擎
│   ├── AgentLoop.java               #   Agent 主循环（推理 + 工具调用迭代）
│   ├── ContextBuilder.java          #   上下文构建器（系统提示词组装）
│   └── MemoryStore.java             #   长期记忆存储
│
├── bus/                             # 消息总线
│   ├── MessageBus.java              #   发布/订阅消息中心
│   ├── InboundMessage.java          #   入站消息模型
│   └── OutboundMessage.java         #   出站消息模型
│
├── channels/                        # 消息通道适配器
│   ├── Channel.java                 #   通道接口
│   ├── BaseChannel.java             #   通道基类
│   ├── ChannelManager.java          #   通道管理器
│   ├── WebhookServer.java           #   内嵌 Webhook HTTP 服务器
│   ├── TelegramChannel.java         #   Telegram 通道
│   ├── DiscordChannel.java          #   Discord 通道
│   ├── WhatsAppChannel.java         #   WhatsApp 通道
│   ├── FeishuChannel.java           #   飞书通道
│   ├── DingTalkChannel.java         #   钉钉通道
│   ├── QQChannel.java               #   QQ 通道
│   └── MaixCamChannel.java          #   MaixCam 硬件设备通道
│
├── cli/                             # 命令行接口
│   ├── CliCommand.java              #   命令基类/接口
│   ├── OnboardCommand.java          #   初始化引导命令
│   ├── AgentCommand.java            #   Agent 交互命令
│   ├── GatewayCommand.java          #   网关服务命令
│   ├── StatusCommand.java           #   状态查看命令
│   ├── CronCommand.java             #   定时任务管理命令
│   └── SkillsCommand.java           #   技能管理命令
│
├── config/                          # 配置管理
│   ├── Config.java                  #   根配置类
│   ├── ConfigLoader.java            #   配置加载器
│   ├── AgentsConfig.java            #   Agent 配置
│   ├── ChannelsConfig.java          #   通道配置
│   ├── ProvidersConfig.java         #   LLM 提供商配置
│   ├── GatewayConfig.java           #   网关配置
│   └── ToolsConfig.java             #   工具配置
│
├── cron/                            # 定时任务引擎
│   ├── CronService.java             #   调度服务核心
│   ├── CronJob.java                 #   任务定义
│   ├── CronSchedule.java            #   调度配置
│   ├── CronPayload.java             #   任务载荷
│   ├── CronJobState.java            #   任务运行状态
│   └── CronStore.java               #   任务持久化存储
│
├── heartbeat/                       # 心跳服务
│   └── HeartbeatService.java        #   定期自主思考服务
│
├── logger/                          # 日志系统
│   └── TinyClawLogger.java          #   结构化日志封装
│
├── providers/                       # LLM 提供商抽象
│   ├── LLMProvider.java             #   提供商接口
│   ├── HTTPProvider.java            #   HTTP 通用实现（OpenAI 兼容）
│   ├── Message.java                 #   对话消息模型
│   ├── ToolCall.java                #   工具调用模型
│   ├── ToolDefinition.java          #   工具定义模型
│   └── LLMResponse.java             #   LLM 响应模型
│
├── session/                         # 会话管理
│   ├── SessionManager.java          #   会话管理器
│   └── Session.java                 #   会话数据容器
│
├── skills/                          # 技能插件系统
│   ├── SkillsLoader.java            #   技能加载器
│   ├── SkillsInstaller.java         #   技能安装器（GitHub）
│   ├── SkillInfo.java               #   技能信息 VO
│   └── SkillMetadata.java           #   技能元数据
│
├── tools/                           # Agent 工具集
│   ├── Tool.java                    #   工具接口
│   ├── ToolRegistry.java            #   工具注册表
│   ├── SubagentManager.java         #   子代理管理器
│   ├── ReadFileTool.java            #   读取文件
│   ├── WriteFileTool.java           #   写入文件
│   ├── AppendFileTool.java          #   追加文件
│   ├── EditFileTool.java            #   编辑文件（diff 模式）
│   ├── ListDirTool.java             #   列出目录
│   ├── ExecTool.java                #   执行 Shell 命令
│   ├── WebSearchTool.java           #   网络搜索
│   ├── WebFetchTool.java            #   网页抓取
│   ├── MessageTool.java             #   跨通道消息发送
│   ├── CronTool.java                #   定时任务操作
│   └── SpawnTool.java               #   子代理生成
│
├── util/                            # 工具类
│   └── StringUtils.java             #   字符串工具
│
└── voice/                           # 语音转写
    └── GroqTranscriber.java         #   Groq Whisper 语音转文字

src/main/resources/
└── logback.xml                      # 日志配置

src/test/java/                       # 测试代码（JUnit 5 + Mockito）
```

---

## 七、关键设计模式

| 模式 | 应用场景 | 说明 |
|------|----------|------|
| **命令模式** | CLI 命令分发 | `CliCommand` 接口 + 命令注册表 |
| **策略模式** | LLM 提供商 | `LLMProvider` 接口 + `HTTPProvider` 实现 |
| **适配器模式** | 消息通道 | `Channel` 接口统一适配不同平台 SDK |
| **发布/订阅** | 消息路由 | `MessageBus` 解耦通道层与 Agent 层 |
| **工厂方法** | 提供商创建 | `HTTPProvider.createProvider()` 根据配置创建实例 |
| **注册表模式** | 工具管理 | `ToolRegistry` 集中管理工具的注册与执行 |
| **模板方法** | 通道基类 | `BaseChannel` 提供通道的通用实现 |
| **观察者模式** | 出站消息分发 | `ChannelManager.dispatchThread` 监听出站队列 |

---

## 八、线程模型

```
主线程 (main)
  │
  ├─ AgentLoop 线程          ← 消息处理主循环
  │
  ├─ CronService 线程        ← 定时任务调度（daemon）
  │
  ├─ HeartbeatService 线程   ← 心跳检查（daemon）
  │
  ├─ ChannelManager
  │    ├─ dispatchThread     ← 出站消息分发（daemon）
  │    └─ 各通道内部线程      ← SDK 自管理
  │
  └─ SubagentManager         ← 子代理执行线程
```

**线程安全保障**：
- `ConcurrentHashMap`：用于工具注册表、通道管理、会话缓存
- `LinkedBlockingQueue`：用于消息总线的入站/出站队列
- `ReentrantReadWriteLock`：用于定时任务的并发控制
- `ReentrantLock`：用于心跳服务的启停控制
- `volatile`：用于各服务的运行状态标志

---

## 九、扩展指南

### 添加新的 LLM 提供商

1. 如果提供商兼容 OpenAI API 格式，只需在 `config.json` 中添加配置即可
2. 如果需要自定义协议，实现 `LLMProvider` 接口并在 `HTTPProvider.createProvider()` 中添加路由逻辑

### 添加新的消息通道

1. 实现 `Channel` 接口（或继承 `BaseChannel`）
2. 在 `ChannelsConfig` 中添加对应的配置类
3. 在 `ChannelManager.initChannels()` 中添加初始化逻辑

### 添加新的工具

1. 实现 `Tool` 接口，定义 `name()`、`description()`、`parameters()`、`execute()`
2. 在 `AgentLoop` 或 `GatewayCommand` 中通过 `ToolRegistry.register()` 注册

### 添加新的技能

1. 在 `workspace/skills/{skill-name}/` 下创建 `SKILL.md`
2. 使用 YAML frontmatter 定义元数据
3. Agent 会在下次构建上下文时自动发现并加载

---

## 十、动手实践与思考题

> 以下任务按难度分为三个层级，适合不同阶段的开发者循序渐进学习。
> 完成每个任务后，建议对照源码和架构图回顾知识点。

---

### Level 1 — Java 基础（入门）

适合刚学完 Java 基础语法、想用真实项目练手的开发者。

#### 实践任务

| # | 任务 | 学习目标 | 提示 |
|---|------|----------|------|
| L1-1 | **运行 CLI 助手** | 熟悉 Maven 构建和 JAR 运行 | 参照 README 的"快速开始"完成 `mvn package`，执行 `agent` 命令 |
| L1-2 | **修改日志输出格式** | 理解 SLF4J + Logback 日志配置 | 编辑 `src/main/resources/logback.xml`，修改 `pattern` 字段 |
| L1-3 | **在 TinyClaw.java 中添加一条启动提示** | 理解主入口结构 | 在 `run()` 方法开头添加一行 `System.out.println(...)` |
| L1-4 | **阅读 CliCommand 接口** | 理解 Java 接口与实现类 | 阅读 `CliCommand.java`，浏览它的所有实现类 |

#### 思考题

1. `COMMAND_REGISTRY` 为什么使用 `LinkedHashMap` 而不是普通 `HashMap`？
2. 为什么 `CliCommand.execute()` 的返回类型是 `int` 而非 `void`？这个值有什么用途？
3. 如果想在日志中显示线程名，应该修改 `logback.xml` 的哪个部分？

---

### Level 2 — 后端工程师（进阶）

适合有一定 Java 后端开发经验，想深入理解系统设计的开发者。

#### 实践任务

| # | 任务 | 学习目标 | 提示 |
|---|------|----------|------|
| L2-1 | **实现 EchoTool** | 理解 Tool 接口和 ToolRegistry | 参考 `ReadFileTool.java`，实现一个原样返回输入内容的工具 |
| L2-2 | **添加本地文件搜索工具** | 综合运用 IO、正则、工具注册 | 工具接收 `directory` 和 `pattern` 参数，返回匹配文件列表 |
| L2-3 | **启用第二个 IM 通道** | 理解 ChannelManager 的多通道管理 | 在 `config.json` 启用 Discord/飞书，观察日志验证启动 |
| L2-4 | **为 AgentLoop 添加执行耗时日志** | 理解 Agent 主循环 | 在 `processDirect()` 入口和出口记录时间差 |
| L2-5 | **扩展 StatusCommand 显示更多信息** | 理解命令模式与状态查询 | 在输出中添加当前已注册工具数量 |

#### 思考题

1. `MessageBus` 使用 `LinkedBlockingQueue` 而非 `ConcurrentLinkedQueue`，二者在阻塞/非阻塞语义上有何区别？哪些场景更适合使用阻塞队列？
2. `ToolRegistry.execute()` 方法为什么要用 `synchronized` 或 `ConcurrentHashMap`？如果不做并发保护会出现什么问题？
3. 假如你需要给每条入站消息打一个唯一 ID，应该修改哪个类、在什么位置生成？
4. 为什么 `HTTPProvider` 将连接超时和读取超时分开设置？各自的典型场景是什么？

---

### Level 3 — 架构师 / AI 工程（高级）

适合想深度理解 AI Agent 架构、掌握生产级设计模式的高级开发者。

#### 实践任务

| # | 任务 | 学习目标 | 提示 |
|---|------|----------|------|
| L3-1 | **改造 HTTPProvider，实现多模型路由策略** | 理解 LLM 抽象与工厂模式 | 根据任务类型（如"代码生成"、"文本摘要"）动态选择不同模型 |
| L3-2 | **重构 AgentLoop 的工具调用策略** | 理解 Agent 推理循环 | 实现"并行工具调用"：一次 LLM 返回多个 tool_calls 时并发执行 |
| L3-3 | **设计流式响应输出** | 理解 SSE / Streaming | 在 CLI 模式下实现 LLM 响应的逐 token 输出，而非一次性返回 |
| L3-4 | **为 MessageBus 增加优先级队列** | 理解消息调度 | 让定时任务触发的消息优先于普通用户消息处理 |
| L3-5 | **实现简单的 RAG 工具** | 理解检索增强生成 | 基于 `read_file` 和简单向量相似度，让 Agent 能从文档库检索上下文 |
| L3-6 | **设计并实现 Agent 自我反思机制** | 理解 ReAct / Reflection | 当工具连续失败 N 次时，让 Agent 生成反思并调整策略 |

#### 思考题

1. 当前 `AgentLoop` 采用同步调用 LLM，如果要支持"思考中..."动态反馈，需要改造哪些模块？请画出改造后的时序图。
2. `ContextBuilder` 的上下文窗口管理（摘要、截断）对于长对话非常关键。如果不做摘要，Token 超限时 LLM 会如何表现？除了摘要还有哪些常见的上下文管理策略？
3. 假设要支持多租户（不同用户隔离配置和会话），你会如何改造 `SessionManager` 和 `ConfigLoader`？
4. 在生产环境中部署 TinyClaw，你会在哪些地方添加指标监控（Metrics）？列举至少 5 个关键指标。
5. 如果要将 TinyClaw 从单机扩展到分布式集群（多实例共享会话和任务），需要重构哪些模块？消息总线应该换成什么？

---

### 学习路径建议

```
Level 1                    Level 2                    Level 3
  │                          │                          │
  │ 完成 L1-1~L1-4           │ 完成 L2-1~L2-5           │ 完成 L3-1~L3-6
  │ 回答思考题 1~3           │ 回答思考题 1~4           │ 回答思考题 1~5
  │                          │                          │
  ▼                          ▼                          ▼
掌握项目结构              理解核心模块              具备架构改造能力
能跑通 CLI/Gateway        能扩展工具/通道           能设计生产级 Agent
```

> **提示**：每完成一个实践任务，建议在 `docs/` 下用简短笔记记录你的思路和遇到的问题，这也是很好的学习习惯。

---

### 测试入口

项目的测试代码也是很好的学习材料，建议按以下顺序阅读和运行：

| 文件 | 学习重点 | 运行命令 |
|------|----------|----------|
| `TinyClawTest.java` | JUnit 5 基本用法、Mockito 三件套、命令行测试思路 | `mvn test -Dtest=TinyClawTest` |

**学习建议**：
1. 先阅读测试代码中的 Javadoc 注释，了解每个测试的目的和学习点
2. 运行测试观察输出，然后尝试修改断言条件让测试失败，理解断言的作用
3. 尝试为 Level 2 的"实现 EchoTool"任务编写对应的单元测试
