# 04 · 配置指南

> 完整解释 `~/.tinyclaw/config.json` 中每一个字段，以及运行时覆盖方式。

---

## 4.1 总体结构

`Config` 类（`io.leavesfly.tinyclaw.config.Config`）是顶层聚合，字段与 JSON 键一一对应：

```json
{
  "agent":         { /* AgentConfig        */ },
  "models":        { /* ModelsConfig       */ },
  "providers":     { /* ProvidersConfig    */ },
  "channels":      { /* ChannelsConfig     */ },
  "tools":         { /* ToolsConfig        */ },
  "gateway":       { /* GatewayConfig      */ },
  "mcpServers":    { /* MCPServersConfig   */ },
  "socialNetwork": { /* SocialNetworkConfig*/ }
}
```

加载顺序（`ConfigLoader`）：

1. 若指定路径则从该路径读
2. 否则读 `~/.tinyclaw/config.json`
3. 敏感字段（`apiKey` 等）若为空，从环境变量补齐
4. 调用 `Config.validate()` 校验

## 4.2 agent — Agent 核心参数

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `workspace` | string | `~/.tinyclaw/workspace` | Agent 工作空间根目录，所有文件操作都被沙箱限制在此 |
| `model` | string | `qwen3.5-plus` | 使用的模型名，需与 `models` / `providers` 匹配 |
| `provider` | string | - | 指定 Provider；为空时由 `ModelsConfig` 反查 |
| `maxTokens` | int | 16384 | 单次请求最大 token |
| `temperature` | double | 0.7 | 随机性参数（0.0 – 1.0） |
| `maxToolIterations` | int | 20 | 工具迭代最大轮次，防止死循环 |
| `heartbeatEnabled` | bool | false | 是否启用心跳（周期性自主思考） |
| `restrictToWorkspace` | bool | true | 文件操作是否限定在 workspace 内（**强烈建议保持 true**） |
| `commandBlacklist` | string[] | `rm -rf`、`mkfs`、`sudo`、… | 禁止执行的命令；为空时用内置默认黑名单 |
| `evolution` | EvolutionConfig | 默认关 | 自我进化配置，见 [4.2.1](#421-evolution) |
| `collaboration` | CollaborationSettings | 默认开 | 多 Agent 协同配置，见 [4.2.2](#422-collaboration) |

### 4.2.1 evolution

```json
"evolution": {
  "enabled": false,
  "feedbackEnabled": true,
  "promptOptimizationEnabled": false,
  "strategy": "TEXTUAL_GRADIENT",
  "minFeedbackToTrigger": 10,
  "maxVariants": 5,
  "memoryEvolveEnabled": true
}
```

- `strategy` — `TEXTUAL_GRADIENT` / `OPRO` / `SELF_REFINE`
- 详见 [12 · 自我进化](12-self-evolution.md)

### 4.2.2 collaboration

```json
"collaboration": {
  "enabled": true,
  "defaultMaxRounds": 3,
  "defaultConsensusThreshold": 0.6,
  "timeoutMs": 0,
  "roleTemplates": {
    "debate": [
      {"name": "正方", "prompt": "…", "model": "gpt-4o"},
      {"name": "反方", "prompt": "…"}
    ]
  }
}
```

- `roleTemplates` 允许按协同模式预置角色模板，用户调 `collaborate` 工具时不用每次传角色
- 详见 [11 · 多 Agent 协同](11-multi-agent-collaboration.md)

## 4.3 models — 模型映射

```json
"models": {
  "default": "qwen3.5-plus",
  "aliases": {
    "fast":   "glm-4-flash",
    "strong": "claude-3.5-sonnet"
  },
  "contextWindow": {
    "qwen3.5-plus": 131072,
    "gpt-4o": 128000
  },
  "modelToProvider": {
    "qwen3.5-plus": "dashscope",
    "glm-4-flash":  "zhipu",
    "gpt-4o":       "openai"
  }
}
```

- `default` — Agent 的默认模型（与 `agent.model` 二选一，`agent.model` 优先）
- `aliases` — 命令行/配置中使用别名，内部解析为真实模型
- `contextWindow` — 每个模型的上下文窗口大小，用于 `SessionSummarizer` 判断是否需要摘要
- `modelToProvider` — 模型到 Provider 的映射，`ProviderManager` 根据此反查 Provider

## 4.4 providers — LLM 提供商

```json
"providers": {
  "openrouter": {"apiKey": "", "apiBase": "https://openrouter.ai/api/v1"},
  "openai":     {"apiKey": "", "apiBase": "https://api.openai.com/v1"},
  "anthropic":  {"apiKey": "", "apiBase": "https://api.anthropic.com/v1"},
  "zhipu":      {"apiKey": "", "apiBase": "https://open.bigmodel.cn/api/paas/v4"},
  "gemini":     {"apiKey": "", "apiBase": "https://generativelanguage.googleapis.com/v1beta"},
  "dashscope":  {"apiKey": "", "apiBase": "https://dashscope.aliyuncs.com/compatible-mode/v1"},
  "ollama":     {"apiKey": "",  "apiBase": "http://localhost:11434/v1"}
}
```

- **环境变量覆盖**：`OPENROUTER_API_KEY`、`OPENAI_API_KEY`、`ZHIPU_API_KEY`、`DASHSCOPE_API_KEY`、`GEMINI_API_KEY`、`ANTHROPIC_API_KEY` 等
- 所有 Provider 统一走 **OpenAI 兼容协议**（`/chat/completions`）
- Ollama 等本地模型不需要 `apiKey`，只需保证 `apiBase` 可达

详见 [08 · LLM 提供商](08-llm-providers.md)。

## 4.5 channels — 消息通道

每个通道都有通用字段 `enabled` 与 `allowFrom`（白名单），再加上各平台特有凭证。

### 4.5.1 Telegram

```json
"telegram": {
  "enabled": true,
  "token": "123456:ABC-DEF…",
  "allowFrom": ["123456789"]
}
```

### 4.5.2 Discord

```json
"discord": {
  "enabled": false,
  "token": "Bot-token",
  "allowFrom": ["userId1"]
}
```

### 4.5.3 飞书 Feishu

```json
"feishu": {
  "enabled": false,
  "appId": "cli_xxx",
  "appSecret": "xxx",
  "encryptKey": "",
  "verificationToken": "",
  "connectionMode": "websocket",
  "allowFrom": ["open_id:xxx"]
}
```

- `connectionMode` — `websocket`（长连）或 `webhook`（HTTP 回调）
- WebSocket 模式无需公网 IP，推荐；Webhook 模式需配合 `WebhookServer`

### 4.5.4 钉钉 DingTalk

```json
"dingtalk": {
  "enabled": false,
  "clientId": "xxx",
  "clientSecret": "xxx",
  "webhook": "",
  "connectionMode": "stream",
  "allowFrom": ["unionId:xxx"]
}
```

- `connectionMode` — `stream`（长连）或 `webhook`

### 4.5.5 WhatsApp

```json
"whatsapp": {
  "enabled": false,
  "bridgeUrl": "http://localhost:8080",
  "allowFrom": ["xxx@c.us"]
}
```

- 需配合外部 Bridge 服务（如 [whatsapp-bridge](https://github.com/…)）

### 4.5.6 QQ / MaixCam

```json
"qq": {
  "enabled": false, "appId": "", "appSecret": "", "allowFrom": []
},
"maixcam": {
  "enabled": false, "host": "192.168.1.100", "port": 8080, "allowFrom": []
}
```

详见 [07 · 消息总线与通道](07-message-bus-and-channels.md)。

## 4.6 tools — 工具配置

```json
"tools": {
  "braveSearch": {"apiKey": "BSA…"},
  "webFetch":    {"timeout": 15000, "maxBytes": 2000000}
}
```

- `braveSearch.apiKey` — `web_search` 工具使用的 Brave Search API Key
- `webFetch` — `web_fetch` 抓取的超时与字节上限

详见 [09 · 工具系统](09-tools-system.md)。

## 4.7 gateway — 网关服务

```json
"gateway": {
  "host": "0.0.0.0",
  "port": 18791,
  "webConsoleEnabled": true,
  "authToken": ""
}
```

- `authToken` — 非空时，Web 控制台需要 `Authorization: Bearer <token>`

详见 [17 · Web 控制台](17-web-console.md)。

## 4.8 mcpServers — MCP 服务器

```json
"mcpServers": {
  "filesystem": {
    "transport": "stdio",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
    "timeout": 30
  },
  "remote-api": {
    "transport": "sse",
    "endpoint": "https://mcp.example.com/sse",
    "headers": {"Authorization": "Bearer xxx"},
    "timeout": 30
  },
  "stream-api": {
    "transport": "streamable-http",
    "endpoint": "https://mcp.example.com/mcp",
    "timeout": 30
  }
}
```

- `transport` — `stdio` / `sse` / `streamable-http`（三选一）
- `MCPManager` 启动时会自动握手（`initialize` → `notifications/initialized` → `tools/list`），并把每个 MCP 工具注册为独立 `MCPTool` 到 `ToolRegistry`

详见 [10 · MCP 协议](10-mcp-integration.md)。

## 4.9 socialNetwork — Agent 社交网络

```json
"socialNetwork": {
  "enabled": false,
  "endpoint": "https://clawdchat.ai",
  "apiKey": "",
  "agentName": "my-tinyclaw"
}
```

- 接入 ClawdChat.ai 后，Agent 可通过 `social_network` 工具与其他 Agent 通信

## 4.10 workspace 下的 Markdown 文件

工作空间内的 Markdown 文件也是 Agent 行为的配置，会被 `ContextBuilder` 注入到系统提示：

| 文件 | 作用 | Section |
|------|------|---------|
| `AGENTS.md` | Agent 通用行为指令 | `IdentitySection` |
| `SOUL.md` | 个性、价值观、语气 | `IdentitySection` |
| `USER.md` | 用户画像（偏好、背景） | `IdentitySection` |
| `IDENTITY.md` | Agent 自我身份描述 | `IdentitySection` |
| `memory/MEMORY.md` | 长期记忆索引 | `MemorySection` |
| `memory/HEARTBEAT.md` | 心跳时读取的上下文 | `HeartbeatService` |
| `skills/{name}/SKILL.md` | 技能定义（YAML frontmatter + Markdown 正文） | `SkillsSection` |
| `PROFILE.md` | 配置快照摘要（`onboard` 生成） | 仅参考 |

## 4.11 Hooks 配置（可选）

`~/.tinyclaw/hooks.json`（或 `workspace/hooks.json`）：

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "exec",
        "handler": {"type": "command", "command": "python /path/to/audit.py"},
        "timeoutMs": 3000
      }
    ]
  }
}
```

详见 [19 · Hooks 钩子](19-hooks.md) 与 `docs/hooks-guide.md`。

## 4.12 环境变量一览

| 变量 | 覆盖字段 |
|------|----------|
| `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `ZHIPU_API_KEY` / `GEMINI_API_KEY` / `DASHSCOPE_API_KEY` | 对应 provider 的 `apiKey` |
| `TINYCLAW_WORKSPACE` | `agent.workspace` |
| `TINYCLAW_MODEL` | `agent.model` |
| `TINYCLAW_CONFIG` | 指定配置文件路径 |
| `BRAVE_SEARCH_API_KEY` | `tools.braveSearch.apiKey` |
| `tinyclaw.bus.inbound.queue.size` | JVM 系统属性：入站队列大小 |
| `tinyclaw.bus.outbound.queue.size` | JVM 系统属性：单通道出站队列大小 |

## 4.13 热重载

- **模型切换**：Web 控制台 Settings → Models 切换，`ProviderManager.reloadModel()` 会一次性替换 `ProviderComponents` 的所有派生组件
- **Hooks**：`HookConfigLoader` 支持手动调用 reload（或重启生效）
- **通道**：通道状态变更需重启 gateway
- **MCP 服务器**：`MCPManager.reconnect(name)` 可单独重连

## 4.14 配置验证

`Config.validate()` 返回 `Optional<String>`，为空表示通过。常见错误：

- `agent.model` 为空
- `agent.maxTokens <= 0` 或 `agent.maxToolIterations <= 0`
- 启用了通道但缺少必填凭证
- `mcpServers.{name}.transport` 取值非法

## 4.15 下一步

- 想知道如何在命令行中使用 → [05 · CLI 命令](05-cli-commands.md)
- 想了解每个通道的具体接入步骤 → `docs/feishu-guide.md` / `docs/dingtalk-guide.md`
- 想调整工具 / 协同 / 进化 → [09](09-tools-system.md) / [11](11-multi-agent-collaboration.md) / [12](12-self-evolution.md)
