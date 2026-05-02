# 10 · MCP 协议集成

> Model Context Protocol：让 TinyClaw 的 Agent 能调用外部工具服务器。

---

## 10.1 MCP 是什么

**MCP（Model Context Protocol）** 是 Anthropic 推出的开放协议，用 JSON-RPC 2.0 定义 LLM 与工具服务器之间的通信，支持：

- 工具列表发现（`tools/list`）与调用（`tools/call`）
- Resource 资源列表与读取（`resources/*`）
- Prompt 模板（`prompts/*`）

TinyClaw 目前主要消费 **tools** 能力，把每个 MCP 工具注册到 `ToolRegistry`，让 LLM 像调内置工具一样调用。

---

## 10.2 包结构

```text
mcp/
├── MCPClient.java              # 客户端接口
├── AbstractMCPClient.java      # 通用握手与 JSON-RPC 封装
├── SSEMCPClient.java           # HTTP + SSE 实现
├── StdioMCPClient.java         # 子进程 stdio 实现
├── StreamableHttpMCPClient.java# 流式 HTTP 实现
├── MCPMessage.java             # JSON-RPC 请求/响应模型
├── MCPServerInfo.java          # 握手后的服务器元信息
└── MCPManager.java             # 服务器连接生命周期管理 + 工具桥接
```

桥接工具位于 `tools/MCPTool.java`。

---

## 10.3 三种传输

| 传输 | 实现 | 适用场景 |
|------|------|----------|
| **Stdio** | `StdioMCPClient` | 本地子进程（最常见，MCP 官方标准用法） |
| **SSE** | `SSEMCPClient` | 远程 HTTP，基于 Server-Sent Events 的双通道 |
| **Streamable HTTP** | `StreamableHttpMCPClient` | 远程 HTTP，基于分块流式 POST |

`MCPManager` 根据 `MCPServersConfig.MCPServerConfig.transport` 字段选择实现。

---

## 10.4 配置

### 10.4.1 Stdio（本地子进程）

```json
{
  "mcpServers": {
    "filesystem": {
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
      "env": {},
      "timeout": 30,
      "enabled": true
    }
  }
}
```

- `command` + `args` → 启动子进程
- `env` → 额外环境变量
- `timeout` → 单次 RPC 的超时（秒）

### 10.4.2 SSE

```json
{
  "mcpServers": {
    "remote-search": {
      "transport": "sse",
      "endpoint": "https://mcp.example.com/sse",
      "headers": {"Authorization": "Bearer xxx"},
      "timeout": 30,
      "enabled": true
    }
  }
}
```

### 10.4.3 Streamable HTTP

```json
{
  "mcpServers": {
    "stream-api": {
      "transport": "streamable-http",
      "endpoint": "https://mcp.example.com/mcp",
      "headers": {"Authorization": "Bearer xxx"},
      "timeout": 60,
      "enabled": true
    }
  }
}
```

---

## 10.5 握手与初始化

`AbstractMCPClient` 封装了标准握手序列：

```text
1. initialize
   request:  {"method":"initialize","params":{"protocolVersion":"2024-11-05",...}}
   response: {"result":{"serverInfo":{...},"capabilities":{...}}}

2. notifications/initialized
   one-way notification

3. tools/list
   response: {"result":{"tools":[{name, description, inputSchema}, ...]}}
```

拿到工具列表后，`MCPManager.initializeServer(...)` 会：

1. 为每个工具创建一个 `MCPTool` 实例
2. 调 `toolRegistry.register(mcpTool)`
3. 保存 `MCPServerInfo`（供 Web 控制台与 `mcp` 命令查询）

---

## 10.6 MCPTool 桥接

`MCPTool` 是 `Tool` 接口的外部化实现：

```java
public class MCPTool implements Tool {
    private final String serverName;
    private final String originalName;
    private final String description;
    private final Map<String, Object> schema;
    private final MCPClient client;

    public String name() { return originalName; }       // 或 "mcp_<server>_<name>"
    public String description() { return description; }
    public Map<String, Object> parameters() { return schema; }

    public String execute(Map<String, Object> args) throws ToolException {
        MCPMessage req = MCPMessage.request("tools/call", Map.of(
            "name", originalName,
            "arguments", args
        ));
        MCPMessage resp = client.sendRequest(req, timeoutMs);
        return resp.getResult() 转字符串;
    }
}
```

LLM 完全不知道它是外部工具。

---

## 10.7 MCPManager 生命周期

| 方法 | 作用 |
|------|------|
| `initialize()` | 启动时批量连接所有 `enabled=true` 的服务器，执行握手与工具注册 |
| `reconnect(String name)` | 单服务器重连（Web 控制台按钮触发） |
| `shutdown()` | 网关停机时逐个关闭 stdio 子进程 / HTTP 连接 |
| `getConnectedCount()` | 已连接服务器数 |
| `getServerInfos()` | 返回所有服务器元信息（Web 控制台展示用） |
| `getClient(String name)` | 获取具体客户端 |
| `isServerConnected(String name)` | 单服务器连通性 |

连接失败不会阻断网关启动，只记 `error` 日志，其他服务器照常工作。

---

## 10.8 CLI 子命令

```bash
tinyclaw mcp list              # 列出所有配置的 MCP 服务器
tinyclaw mcp test <name>       # 对指定服务器执行 initialize 握手
tinyclaw mcp tools <name>      # 连接并列出服务器提供的工具
```

用于调试配置是否正确，不会影响运行中的 gateway。

---

## 10.9 Web 控制台

`web/handler/MCPHandler` 提供 REST API：

- `GET /api/mcp/servers` — 服务器列表与状态
- `POST /api/mcp/servers/{name}/reconnect` — 单服务器重连
- `GET /api/mcp/servers/{name}/tools` — 单服务器工具列表
- `POST /api/mcp/servers/{name}/tools/{tool}` — 手动调用测试（可选）

Web UI 上可直接看到每个 MCP 服务器的连接状态与暴露的工具。

---

## 10.10 错误处理

| 场景 | 行为 |
|------|------|
| 单服务器启动失败 | 记 `error` 日志，其他服务器照常；`MCPServerInfo.status=FAILED` |
| stdio 子进程崩溃 | 客户端探测到读 EOF，标记断开，下次调用抛 `ToolException`，可 `reconnect` |
| 握手超时 | 抛异常，进入 FAILED 状态 |
| `tools/call` 超时 | 按服务器 `timeout` 配置超时，抛 `ToolException` |
| JSON-RPC error | 转成 `ToolException`，`ReActExecutor` 会把错误喂回 LLM 让其修复 |

---

## 10.11 典型 MCP 服务器

| 服务器 | 能力 |
|--------|------|
| `@modelcontextprotocol/server-filesystem` | 文件读写（可指定目录） |
| `@modelcontextprotocol/server-github` | GitHub 操作 |
| `@modelcontextprotocol/server-slack` | Slack 消息与频道操作 |
| `@modelcontextprotocol/server-postgres` | PostgreSQL 只读查询 |
| `@modelcontextprotocol/server-brave-search` | Brave 搜索（与内置 `web_search` 可替代） |
| 自研 | 接入企业内部系统 |

---

## 10.12 与内置工具的关系

| 维度 | 内置工具 | MCP 工具 |
|------|----------|----------|
| 部署 | Java 代码，与主程序一体 | 独立进程/服务 |
| 重载 | 需重启（注册表支持 `unregister`） | `reconnect` 即可热切 |
| 开发成本 | 写 Java | 任意语言（Node/Python/Go/Rust） |
| 性能 | 进程内调用 | IPC/HTTP，有开销 |
| 安全 | 受 `SecurityGuard` 保护 | 由 MCP 服务器自己负责；TinyClaw 仅转发参数 |

建议：高频通用能力用内置工具，企业集成用 MCP。

---

## 10.13 扩展：接入新 MCP 服务器

**100% 配置驱动**：

1. 在 `~/.tinyclaw/config.json` 的 `mcpServers` 追加一项
2. 指定 `transport` 与对应字段（`command/args` 或 `endpoint`）
3. 重启 gateway 或通过 Web 控制台 `reconnect`

---

## 10.14 下一步

- 工具系统基础 → [09 · 工具系统](09-tools-system.md)
- Web 控制台使用 → [17 · Web 控制台](17-web-console.md)
- 自定义 MCP 服务器：见 [MCP 官方文档](https://modelcontextprotocol.io/)
