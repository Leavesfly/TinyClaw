# 09 · 工具系统

> `tools/` 包：Agent 的「动手」能力，支撑 function calling 的所有原子操作。

---

## 9.1 设计思路

TinyClaw 把 Agent 的所有副作用（读文件、跑命令、发消息、调 Web API 等）都抽象成 **Tool**。核心原则：

- **统一接口**：`Tool` 一个接口就是全部
- **JSON Schema 描述**：每个工具自己声明参数，`ToolRegistry` 把它转成 OpenAI 兼容的 `ToolDefinition`
- **安全优先**：所有文件/命令工具在执行前都经过 `SecurityGuard`
- **可插拔**：`ToolRegistry` 运行时可增删
- **可观测**：每次调用自动记录耗时、结果长度，并可选挂接 Reflection 2.0

---

## 9.2 Tool 接口

```java
public interface Tool {
    String name();
    String description();
    Map<String, Object> parameters();   // JSON Schema
    String execute(Map<String, Object> args) throws ToolException;
}
```

### 扩展接口

| 接口 | 作用 |
|------|------|
| `StreamAwareTool` | 声明本工具能接收流式回调（`setStreamCallback(EnhancedStreamCallback)`），用于协同、子代理等长耗时工具实时推送中间结果 |
| `ToolContextAware` | 声明本工具需要 `channel` + `chatId` 上下文（如 `message`、`cron` 等把回复指向特定会话的工具） |

---

## 9.3 ToolRegistry

### 关键字段

- `Map<String, Tool> tools`（`ConcurrentHashMap`，线程安全）
- `ToolCallRecorder recorder`（可选，Reflection 2.0 事件落盘）
- `RepairApplier repairApplier`（可选，执行前参数校验与描述覆写）

### 核心 API

| 方法 | 说明 |
|------|------|
| `register(Tool)` | 注册；同名覆盖 |
| `unregister(String name)` | 注销 |
| `get(String name)` | 可选 |
| `hasTool(String name)` | 存在性 |
| `execute(String name, Map<String,Object> args)` | 执行工具；记录耗时、结果长度；若挂了 recorder 会落 `ToolCallEvent` |
| `getDefinitions()` | 转成 `List<ToolDefinition>`，供 `HTTPProvider` 发送给 LLM |
| `getSummaries()` | 工具名 + 描述摘要，供 `ToolsSection` 注入系统提示 |
| `filter(List<String> allowed)` | 派生一个只包含允许工具的子注册表（`SubagentManager` 给子代理受限工具集时使用） |
| `getFewShotExamples()` | 收集各工具的示例，用于提示工程 |

---

## 9.4 15 个内置工具

### 9.4.1 文件族（5）

| 工具 | 主要参数 | 说明 | 沙箱 |
|------|----------|------|------|
| `read_file` | `path` | 读取文件 | ✅ |
| `write_file` | `path`, `content` | 创建或覆盖写 | ✅ |
| `append_file` | `path`, `content` | 追加 | ✅ |
| `edit_file` | `path`, `old_string`, `new_string`, `replace_all?` | 基于精确匹配的 diff 式编辑 | ✅ |
| `list_dir` | `path`, `max_depth?` | 列目录（树状） | ✅ |

所有路径都会经过 `SecurityGuard.resolve(path)`：
- 规范化（消除 `..`）
- 校验是否位于 workspace 之下
- 若 `restrictToWorkspace=false` 才允许跳出（不推荐）

### 9.4.2 Shell 执行

| 工具 | 主要参数 | 说明 |
|------|----------|------|
| `exec` | `command`, `cwd?`, `timeout?` | 执行 Shell 命令 |

防护：
- `SecurityGuard.checkCommand(command)` 匹配黑名单（`rm -rf`、`mkfs`、`sudo`、`dd`、`chmod 777` 等）
- `cwd` 默认为 workspace 根，不允许超出
- 超时默认 30 秒，最大 5 分钟

### 9.4.3 网络

| 工具 | 主要参数 | 说明 |
|------|----------|------|
| `web_search` | `query`, `count?` | 调 Brave Search API，返回结果列表 |
| `web_fetch` | `url`, `format?` | 抓取网页，支持 `text` / `markdown` / `html` |

`web_fetch` 限制：默认 15 秒超时、2MB 上限，可在 `tools.webFetch.*` 调整。

### 9.4.4 消息与通道

| 工具 | 说明 |
|------|------|
| `message` | 向指定 `channel` + `chatId` 发送一条消息（`ToolContextAware`） |

使用场景：让 Agent 在 Cron 任务中主动给某用户推送消息，或跨会话通知。

### 9.4.5 定时任务

| 工具 | 说明 |
|------|------|
| `cron` | 创建 / 列出 / 启用 / 禁用 / 删除定时任务 |

Agent 可自主通过 `cron` 工具持久化调度需求，例如："每天早上 9 点提醒我喝水"。

### 9.4.6 子代理与协同

| 工具 | 实现 | 说明 |
|------|------|------|
| `spawn` | `SpawnTool` / `SubagentManager` | 创建子代理执行独立任务；子代理有受限工具集与独立会话 |
| `collaborate` | `CollaborateTool` | 启动多 Agent 协同（7 种模式） |
| `social_network` | `SocialNetworkTool` | 与 ClawdChat.ai 上的其他 Agent 通信 |

`CollaborateTool` 实现 `StreamAwareTool`，协同过程中会通过 `COLLAB_START` / `COLLAB_END` / `TEXT_DELTA` 事件向上游流式输出进度。

### 9.4.7 技能与统计

| 工具 | 说明 |
|------|------|
| `skills` | 管理技能（`list` / `show` / `invoke` / `install` / `create` / `edit` / `remove`） |
| `token_usage` | 查询 Token 用量（按 provider / model / 日期 / 会话） |

---

## 9.5 SubagentManager — 子代理

`spawn` 工具的幕后：

- 为子代理构造**独立** `AgentRuntime`（复用同一 Provider）
- 默认限制工具集（禁掉 `spawn` / `collaborate` 防止递归爆炸）
- 子代理有独立 `sessionKey`，其历史不污染主会话
- 使用独立线程执行，主线程等待结果 or 超时

---

## 9.6 TokenUsageStore

- 存储：`workspace/token_usage.json`
- 维度：`provider` / `model` / `date` / `sessionKey` / `toolName`
- 每次 LLM 调用后由 `ReActExecutor` 写入
- 查询：`token_usage` 工具 + Web 控制台 Token Stats 页

---

## 9.7 工具调用生命周期

以 `exec` 为例：

```text
LLM 输出:  tool_call{id:"c1", name:"exec", arguments:"{\"command\":\"ls /\"}"}
        │
        ▼
ReActExecutor 解析 tool_call
        │
        ▼
Hook: PRE_TOOL_USE
  ├─ allow:   放行
  ├─ modify:  改写参数（如加超时）
  └─ deny:    跳过，返回 deny 原因
        │
        ▼
ToolRegistry.execute("exec", {command:"ls /"})
  ├─ （可选）RepairApplier.preCheck(...)
  ├─ ExecTool.execute(args)
  │   ├─ SecurityGuard.checkCommand(...)
  │   ├─ 启动子进程，读 stdout/stderr
  │   └─ 超时/异常处理
  ├─ 记录耗时、结果长度
  └─ （可选）ToolCallRecorder.record(...)
        │
        ▼
Hook: POST_TOOL_USE  ← 可改写结果
        │
        ▼
SessionManager 追加 ToolCallRecord
        │
        ▼
追加 tool_result 到 messages，继续下一轮 LLM
```

---

## 9.8 并发与线程安全

- `ToolRegistry` 内部用 `ConcurrentHashMap`，注册/注销线程安全
- 单次 `execute` 在 `ReActExecutor` 的调用线程上运行（主线程或直连请求线程）
- 长耗时工具（`collaborate` / `spawn`）内部启子线程
- 流式回调可能来自多个线程，`StreamAwareTool` 实现需自行处理

---

## 9.9 Reflection 2.0：工具反思

（自我进化引擎的一部分，详见 [12 · 自我进化](12-self-evolution.md)）

- `ToolCallRecorder` → 异步写入 `workspace/reflection/tool_calls.jsonl`
- `ErrorClassifier` → 分类错误（超时/参数错误/权限/业务异常）
- `FailureDetector` + `PatternMiner` → 挖掘失败模式
- `RepairProposal` → 生成修复建议（改描述、改默认参数、加前置校验）
- `RepairApplier` → 应用修复（运行时覆写工具描述或参数预检）

---

## 9.10 MCPTool — 外部工具桥接

`mcp/` 加载的每个远端工具都会被包装成 `MCPTool` 注册到 `ToolRegistry`：

- `name()` = MCP 工具名（可能加 `mcp_{server}_` 前缀避免冲突）
- `description()` = MCP 工具 description
- `parameters()` = MCP 工具 `inputSchema`
- `execute(args)` = 通过 `MCPClient.callTool(...)` 转发

LLM 完全感觉不到它是外部工具。详见 [10 · MCP 协议](10-mcp-integration.md)。

---

## 9.11 扩展：新增工具

最小示例：

```java
public class NowTool implements Tool {
    public String name() { return "now"; }
    public String description() { return "返回当前时间（ISO-8601）"; }
    public Map<String,Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of()
        );
    }
    public String execute(Map<String,Object> args) {
        return java.time.Instant.now().toString();
    }
}
```

注册：

```java
runtime.registerTool(new NowTool());
```

需要流式 / 上下文时，额外实现 `StreamAwareTool` / `ToolContextAware`。

更多例子见 [20 · 扩展开发](20-extending.md)。

---

## 9.12 下一步

- MCP 外部工具 → [10 · MCP 协议集成](10-mcp-integration.md)
- 协同工具的幕后 → [11 · 多 Agent 协同](11-multi-agent-collaboration.md)
- 工具反思机制 → [12 · 自我进化](12-self-evolution.md)
- 安全沙箱细节 → [16 · 安全沙箱](16-security-sandbox.md)
