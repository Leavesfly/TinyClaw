# 06 · Agent 引擎

> TinyClaw 的核心大脑：`agent/` 包的设计与协作。

---

## 6.1 组件全景

Agent 引擎经过重构后采用**职责分离**设计，避免「上帝类」：

| 类 | 角色 |
|----|------|
| `AgentRuntime` | 生命周期 + 主循环 + 直连入口 + 对外 API |
| `MessageRouter` | 路由用户/系统/指令消息 |
| `ProviderManager` | LLM Provider 初始化、热重载 |
| `ProviderComponents` | Provider 派生组件容器 |
| `ReActExecutor` | LLM 调用与工具迭代（Reason-Act 循环） |
| `ContextBuilder` | 按 `ContextSection` 拼装系统提示 |
| `SessionSummarizer` | 会话摘要 + 触发 MemoryEvolver |
| `AgentConstants` | 常量（默认上下文窗口、默认值等） |
| `context/*` | 5 个 `ContextSection` 实现 |

依赖关系：

```text
        AgentRuntime
       ┌──┬──┬──┬──┬────────┐
       │  │  │  │  │        │
       ▼  ▼  ▼  ▼  ▼        ▼
   MsgRouter ProviderMgr   HookDispatcher
       │      │
       │      ▼
       │  ProviderComponents
       │  ├─ ReActExecutor ◄──── ToolRegistry
       │  ├─ SessionSummarizer
       │  ├─ MemoryEvolver
       │  ├─ FeedbackManager
       │  ├─ PromptOptimizer
       │  ├─ AgentOrchestrator
       │  ├─ ReflectionEngine
       │  ├─ ToolCallRecorder
       │  ├─ ToolHealthAggregator
       │  └─ RepairApplier
       │
       └────► ContextBuilder
                 └─ 5 ContextSection
```

## 6.2 AgentRuntime — 生命周期与入口

### 6.2.1 构造

```java
AgentRuntime(Config config, MessageBus bus, LLMProvider provider)
```

构造时会：

1. 校验 workspace 目录
2. 创建 `ToolRegistry` / `SessionManager` / `ContextBuilder`
3. 加载 `hooks.json` → 创建 `HookDispatcher`
4. 创建 `ProviderManager` 与 `MessageRouter`，注入 `HookDispatcher`
5. 若 provider 非空，调 `providerManager.setProvider(...)` 构建 `ProviderComponents`

### 6.2.2 两类入口

**网关模式**：`run()` 阻塞循环

```java
while (running) {
    InboundMessage msg = bus.consumeInbound();
    messageRouter.route(msg);
}
```

**直连模式**：`processDirect*` 家族

| 方法 | 用途 |
|------|------|
| `processDirect(content, sessionKey)` | CLI 单次问答，返回完整文本 |
| `processDirectStream(content, sessionKey, cb)` | 流式回调（Web UI 用） |
| `processDirectStream(content, images, sessionKey, cb)` | 多模态（含图片） |
| `processDirectWithChannel(content, sessionKey, channel, chatId)` | 指定通道上下文（Cron 触发 → 送回对应通道） |

所有直连方法都绕过 `MessageBus.inbound`，直接调 `MessageRouter.routeUser(...)` 的内部实现。

### 6.2.3 运行时控制

- `abortCurrentTask()` — 中断当前 LLM 调用与工具循环（`ReActExecutor.abort()`）
- `isTaskRunning()` — 是否有进行中的任务
- `reloadModel()` — 热切换模型，内部委托给 `ProviderManager.reloadModel()`
- `stop()` — 优雅停止主循环 + 关闭 MCP + 触发 `SESSION_END` hook

### 6.2.4 暴露的 getter

供 Web 控制台、工具层、心跳服务等外部组件使用：

- `getToolRegistry()` / `getSessionManager()` / `getSkillsLoader()` / `getMemoryStore()`
- `getOrchestrator()` / `getMemoryEvolver()` / `getFeedbackManager()` / `getPromptOptimizer()`
- `getReflectionEngine()` / `getToolHealthAggregator()` / `getRepairApplier()`
- `getTokenUsageStore()`

## 6.3 MessageRouter — 消息路由

```java
String route(InboundMessage msg) throws Exception {
    logIncoming(msg);
    if (msg.isCommand()) return routeCommand(msg);
    if ("system".equals(msg.getChannel())) return routeSystem(msg);
    return routeUser(msg);
}
```

### 6.3.1 routeUser 的核心步骤

```text
1. 检查 Provider 是否配置，否则返回 PROVIDER_NOT_CONFIGURED_MSG
2. 会话首次出现 → 触发 HookEvent.SESSION_START
3. 触发 HookEvent.USER_PROMPT_SUBMIT
   - Hook 返回 DENY → 跳过 LLM 调用，返回 hook 消息
   - Hook 返回 MODIFY → 使用改写后的内容
4. 读取/创建 Session，拼接 history + summary
5. ContextBuilder.buildMessages(...)
6. 判断目标通道是否 supportsStreaming()：
   - 支持流式 → ReActExecutor.executeStream(...)
   - 否则     → ReActExecutor.execute(...)
7. SessionManager 持久化最新历史
8. 触发 HookEvent.STOP
9. 封装 OutboundMessage → bus.publishOutbound(...)
```

### 6.3.2 指令消息

- `command=new_session` → 清空 session 历史，可选指定新 sessionKey

### 6.3.3 系统消息

- `channel=system` 的消息由 Cron / Heartbeat / Spawn 子代理产生，Router 解析其 metadata 中的原始通道，把回复送回原会话。

## 6.4 ProviderManager — Provider 热重载

### 6.4.1 职责

- **初始化**：`setProvider(LLMProvider)` 一次性创建 `ProviderComponents`
- **热重载**：`reloadModel()` 从 `Config.getModels()` 重新解析 model→provider 映射，构造新的 `HTTPProvider`，替换 `components`
- **线程安全**：`volatile components` + `synchronized(providerLock)`

### 6.4.2 reloadModel() 关键逻辑

```text
1. 从 Config.agent.model 读取模型名
2. 从 Config.models.modelToProvider 反查 provider
3. 从 Config.providers.{provider} 读取 apiKey / apiBase
4. 构造新的 HTTPProvider
5. applyProvider(newProvider)
   ├─ 构造 ReActExecutor（含 TokenUsageStore / FeedbackManager / HookDispatcher）
   ├─ 构造 SessionSummarizer（含 MemoryEvolver）
   ├─ 构造 PromptOptimizer / FeedbackManager
   ├─ 构造 AgentOrchestrator
   ├─ 构造 Reflection 2.0 组件（ReflectionEngine / Recorder / Aggregator / Applier）
   └─ 打包为 ProviderComponents，原子替换
```

## 6.5 ReActExecutor — 推理与工具循环

### 6.5.1 核心 API

```java
String execute(List<Message> messages, String sessionKey);
String executeStream(List<Message> messages, String sessionKey, StreamCallback cb);
```

### 6.5.2 工具循环逻辑

```text
for iter in 0..maxIterations:
    if aborted: break

    resp = provider.chat(messages, tool_defs, model, options)
    if tokenUsageStore: 记录用量
    messages.add(resp.message)  // 带 tool_calls 的 assistant

    if !resp.hasToolCalls(): return resp.content

    for tc in resp.tool_calls:
        Hook: PRE_TOOL_USE  ← 可 deny / 改参
        result = tools.execute(tc.name, tc.args)
        Hook: POST_TOOL_USE ← 可改结果
        sessions.appendToolCallRecord(...)
        messages.add(tool_result_msg)

        if feedbackManager: 记录消息交换

return finalContent
```

### 6.5.3 空响应降级

- 若 LLM 连续返回空内容，最多重试 `MAX_EMPTY_RESPONSE_RETRIES=2` 次
- 仍为空则返回 `EMPTY_RESPONSE_FALLBACK`（友好提示）

### 6.5.4 流式模式

- 把 `StreamCallback` 封装为 `EnhancedStreamCallback`，支持：
  - 文本增量（`onChunk`）
  - 工具调用开始/结束（`onToolCallStart` / `onToolCallEnd`）
  - 协同开始/结束（供 `CollaborateTool` 使用）
- `currentEnhancedCallback` 会传递给 `StreamAwareTool` 与 `SubagentManager`

## 6.6 ContextBuilder — 系统提示组装

### 6.6.1 分段式架构

```java
interface ContextSection {
    String build(SectionContext ctx);
}
```

默认注册 5 个 Section（按顺序）：

| Section | 内容来源 |
|---------|----------|
| `IdentitySection` | `AGENTS.md` + `SOUL.md` + `USER.md` + `IDENTITY.md`（支持 `PromptOptimizer` 覆盖） |
| `BootstrapSection` | 当前时间、运行环境、workspace 路径、channel 标识 |
| `ToolsSection` | `ToolRegistry.getSummaries()` |
| `SkillsSection` | `SkillsSearcher` 基于当前用户消息做语义匹配，只注入相关技能的摘要 |
| `MemorySection` | `MemoryStore.readMemoryContext()`（长期记忆） |

各 Section 之间用 `\n\n---\n\n` 分隔。

### 6.6.2 关键方法

```java
String buildSystemPrompt();
String buildSystemPrompt(String currentMessage);  // 带当前消息，用于 SkillsSection 语义搜索

List<Message> buildMessages(
    List<Message> history, String summary, String currentMessage,
    String channel, String chatId);

// 多模态版本
List<Message> buildMessages(
    List<Message> history, String summary, String currentMessage,
    List<String> images, String channel, String chatId);
```

产出的消息列表结构：

```text
[ system: 分段式系统提示 ]
[ system: 会话摘要（如有） ]
[ history ... ]
[ user: currentMessage ]
```

### 6.6.3 Prompt 优化注入

```java
contextBuilder.setPromptOptimizer(optimizer);
```

当 `PromptOptimizer` 有激活变体时，`IdentitySection` 会用优化后的 Prompt 替换默认身份段。

## 6.7 SessionSummarizer — 会话摘要

### 6.7.1 触发条件

- 消息数量超过阈值（默认 50 条）
- 或 Token 估算超过 `contextWindow` 的 70%

### 6.7.2 流程

```text
1. 后台守护线程异步执行，避免阻塞主对话
2. 保留最近 N 条消息（默认 10 条）
3. 对较早消息分批送给 LLM 生成摘要
4. 更新 Session.summary
5. 触发 MemoryEvolver.evolveFromSummary(...)
   └─ 提取长期记忆，追加到 memory/MEMORY.md
```

### 6.7.3 集成点

- `ReActExecutor.execute()` 完成后会检查是否需要触发摘要
- 摘要完成后通过 `SessionManager` 持久化
- 心跳服务也会周期性调用 `runEvolutionCycle()`（含摘要/记忆进化/Prompt 优化）

## 6.8 HookDispatcher 集成

6 种 `HookEvent` 在引擎中的触发点：

| 事件 | 触发位置 | 可影响 |
|------|----------|--------|
| `SESSION_START` | `MessageRouter.routeUser` 首次会话 | 注入上下文 |
| `USER_PROMPT_SUBMIT` | `MessageRouter.routeUser` 在调 LLM 之前 | deny / 改写用户消息 |
| `PRE_TOOL_USE` | `ReActExecutor` 工具调用之前 | deny / 改写参数 |
| `POST_TOOL_USE` | `ReActExecutor` 工具调用之后 | 改写结果 / 追加上下文 |
| `STOP` | `MessageRouter.routeUser` 本轮结束 | 通知、归档 |
| `SESSION_END` | `AgentRuntime.stop()` | 收尾通知 |

Hook 默认 `noop`，未配置时零开销。详见 [19 · Hooks](19-hooks.md)。

## 6.9 线程模型

- 主循环（`run()`）单线程从 bus 取消息，**同步**处理
- 工具执行在主线程，但 `SubagentManager` / `AgentOrchestrator` 会启动独立子线程
- `SessionSummarizer` 使用后台守护线程
- `HeartbeatService` / `CronService` 各自守护线程
- `ChannelManager` 为每个通道起独立线程消费出站队列
- LLM 的流式 HTTP 由 OkHttp 的线程池处理

## 6.10 扩展点

- **新增 ContextSection**：`contextBuilder.addSection(new MySection())`
- **新增 Tool**：`runtime.registerTool(new MyTool())`
- **新增 Hook**：写一个 `HookHandler` + 注册到 `hooks.json` 的 matcher
- **自定义 Provider**：`runtime.setProvider(myProvider)`（需实现 `LLMProvider`）

详见 [20 · 扩展开发](20-extending.md)。

## 6.11 下一步

- 消息如何流转 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
- LLM 细节 → [08 · LLM 提供商](08-llm-providers.md)
- 工具调用细节 → [09 · 工具系统](09-tools-system.md)
- 会话与记忆 → [15 · 会话与记忆](15-session-memory.md)
