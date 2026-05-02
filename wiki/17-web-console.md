# 17 · Web 控制台

> `web/` 包：基于 JDK 内置 `HttpServer` 的轻量 REST + 静态资源服务，用于可视化管理 TinyClaw。

---

## 17.1 定位

Web 控制台是**网关模式**的伴随组件：

- 仅在 `tinyclaw gateway` 启动时运行
- 默认端口 `18790`（`GatewayConfig.port` 默认值），在 `config.json.gateway.port` 中可改
- 默认 host `0.0.0.0`，监听所有网卡
- 定位为**本地 / 内网运维工具**，不是生产级多租户门户
- 零外部依赖：JDK `com.sun.net.httpserver.HttpServer` + 原生静态资源

---

## 17.2 启动与生命周期

`WebConsoleServer` 由 `GatewayCommand` 在网关启动时拉起：

```java
WebConsoleServer web = new WebConsoleServer(
    host, port, config, agentRuntime,
    sessionManager, cronService, skillsLoader);
web.start();
```

关键细节：

- 线程池：`Executors.newFixedThreadPool(8)` — HTTP 请求并发上限
- 停止：`web.stop()` 优雅停机，宽限 2 秒（`SERVER_STOP_DELAY`）
- 日志：所有启停事件输出到 `web` 命名日志

---

## 17.3 组件结构

```text
web/
├── WebConsoleServer.java     # HTTP 服务生命周期 + 路由注册
├── SecurityMiddleware.java   # CORS + Basic Auth + 速率限制
├── WebUtils.java             # 通用工具（响应、JSON、路径常量、密钥掩码）
└── handler/                  # 业务 Handler
```

每个 Handler 构造时注入 `SecurityMiddleware`，在 `handle(HttpExchange)` 入口先走 `preCheck(exchange)`（CORS 预检 → 认证 → 速率限制）。

---

## 17.4 Handler 与 API 路径总览

下表严格以 `WebUtils` 中定义的 `API_*` 常量与 `WebConsoleServer.registerApiEndpoints` 的实际注册为准：

| Handler | 注册路径前缀 | 实际端点 | 职责 |
|---------|--------------|----------|------|
| `AuthHandler` | `/api/auth` | `GET /api/auth/check`、`POST /api/auth/login` | 检查/登录（返回 Basic Token） |
| `ChatHandler` | `/api/chat`, `/api/chat/abort`, `/api/chat/status` | `POST /api/chat`（非流）、`POST /api/chat/stream`（SSE）、`POST /api/chat/abort`、`GET /api/chat/status` | 对话、流式、中断、运行状态 |
| `ChannelsHandler` | `/api/channels` | CRUD | 通道启停与凭据管理 |
| `SessionsHandler` | `/api/sessions` | CRUD + 详情 | 会话列表/详情/删除/回放 |
| `CronHandler` | `/api/cron` | CRUD | 定时任务 CRUD + 启停 |
| `WorkspaceHandler` | `/api/workspace` + `/api/workspace/files` | 文件树 / 读写 | workspace Markdown 编辑 |
| `SkillsHandler` | `/api/skills` | CRUD + 安装 | 技能 CRUD + GitHub 安装 |
| `ProvidersHandler` | `/api/providers` | CRUD | LLM Provider 配置 + 热重载 |
| `ModelsHandler` | `/api/models` | - | 模型列表与 model→provider 映射 |
| `ConfigHandler` | `/api/config` + `/api/config/model` + `/api/config/agent` | - | 通用配置读写（触发热重建） |
| `FeedbackHandler` | `/api/feedback` | - | 提交/查询反馈 |
| `MCPHandler` | `/api/mcp` | - | MCP 服务器状态、重连、工具列表 |
| `UploadHandler` | `/api/upload` | `POST` | 图片 Base64 上传，单文件 ≤ 10MB |
| `FilesHandler` | `/api/files` | `GET` | 上传后的静态文件访问 |
| `TokenStatsHandler` | `/api/token-stats` | - | Token 用量聚合 |
| `ReflectionHandler` | `/api/reflection` | - | 工具健康墙、修复建议审批（HITL） |
| `StaticHandler` | `/` | 全路径兜底 | 前端静态资源（classpath `web/`） |

注：`ReflectionHandler` 仅在 `AgentRuntime.getToolHealthAggregator() != null` 时才完整注入组件，否则相关操作会返回错误。

---

## 17.5 SecurityMiddleware — CORS + 认证 + 速率限制

`SecurityMiddleware.preCheck(exchange)` 的固定顺序：

1. **CORS 预检**：`OPTIONS` 请求直接返回 204，带上 `Access-Control-Allow-*` 头
2. **Basic Auth 认证**：若启用则校验 `Authorization: Basic <base64>`
3. **速率限制**：若启用则按每分钟滑动窗口计数

三步任一拦截（已写响应）返回 `false`，Handler 直接 return。

### 17.5.1 认证模式

TinyClaw 只支持 **HTTP Basic Auth**（无 token/none 之分）：

- 启用条件：`GatewayConfig.isAuthEnabled() = username 非空 && password 非空`
- 未启用：`preCheck` 直接放行（本地开发场景）
- 默认凭据：`username=admin`、`password=tinyclaw`（`GatewayConfig` 构造函数内置）
- 失败：返回 `401 {"error":"Authentication required"}`（**不带** `WWW-Authenticate` 头，避免浏览器弹原生对话框）

### 17.5.2 登录流程

`POST /api/auth/login` 入参：

```json
{"username": "admin", "password": "tinyclaw"}
```

匹配成功后返回：

```json
{"success": true, "token": "YWRtaW46dGlueWNsYXc="}
```

其中 `token = Base64(username + ":" + password)` — 即标准 Basic Auth 的头值。前端后续请求只需：

```
Authorization: Basic YWRtaW46dGlueWNsYXc=
```

### 17.5.3 CORS

- 配置项：`gateway.corsOrigin`（单字符串，默认 `"*"`）
- 响应头：`Access-Control-Allow-Origin: <corsOrigin>`
- 允许方法：`GET, POST, PUT, DELETE, OPTIONS`
- 允许头：`Content-Type, Authorization`

### 17.5.4 速率限制

- 配置：`gateway.rateLimitPerMinute`（默认 `0` 表示不限）
- 实现：进程内 `AtomicInteger` + 每分钟滑动窗口
- 超限：返回 `429 {"error":"Rate limit exceeded. Try again later."}`

---

## 17.6 流式对话（SSE）

流式与非流式是**两条独立路径**，不靠 `Accept` 协商：

| 场景 | 路径 | 方法 |
|------|------|------|
| 一次性响应 | `POST /api/chat` | 返回完整 `{response, sessionId}` |
| 流式 SSE | `POST /api/chat/stream` | 返回 `text/event-stream` |
| 中断 | `POST /api/chat/abort` | 调 `agentRuntime.abortCurrentTask()` |
| 状态查询 | `GET /api/chat/status` | 返回 `{running: boolean}` |

### 17.6.1 请求体

```json
{
  "message": "帮我总结一下这篇文章",
  "sessionId": "web:default",
  "images": ["uploads/20260501/abc.jpg"]
}
```

- `sessionId` 缺省时用 `WebUtils.DEFAULT_SESSION_ID = "web:default"`
- `images` 为可选的多模态图片路径数组

### 17.6.2 SSE 事件格式

每个事件由 `StreamEvent.toJson()` 序列化为单行 JSON，包装为 `data: <json>\n\n`。

实际事件类型（对应 `StreamEvent.EventType` 枚举）：

| 事件类型 | 触发时机 |
|----------|----------|
| `CONTENT` | 主 Agent 的普通文本块 |
| `TOOL_START` | 工具调用开始（带 `tool`、`args`） |
| `TOOL_END` | 工具调用结束（带 `tool`、`success`） |
| `SUBAGENT_START` / `SUBAGENT_CONTENT` / `SUBAGENT_END` | 子代理（`spawn` 工具）生命周期 |
| `COLLABORATE_START` / `COLLABORATE_AGENT` / `COLLABORATE_AGENT_CHUNK` / `COLLABORATE_END` | 多 Agent 协同生命周期 |
| `THINKING` | 可选的思考/推理过程 |

### 17.6.3 结束与错误标志

- 正常结束：裸字符串 `data: [DONE]\n\n`（**不是** JSON）
- 异常：`data: [ERROR] <escaped message>\n\n`

### 17.6.4 中断

`POST /api/chat/abort` → 调 `agentRuntime.abortCurrentTask()`，返回 `{success, message}`。ReActExecutor 会在工具循环空隙检查终止标志，及时退出。

---

## 17.7 配置热重载

部分配置项支持**不重启**生效：

| 修改项 | Handler | 生效方式 |
|--------|---------|----------|
| `providers.*` | `ProvidersHandler` | `ProviderManager.reload()` 重建 provider 实例 |
| `models.*` | `ModelsHandler` / `ConfigHandler` | 下一次请求按新映射选 Provider |
| `agent.evolution.*` | `ConfigHandler` | 下一次 `runEvolutionCycle()` 生效 |
| `mcpServers.*` | `MCPHandler` | 单服务器 reconnect |
| `channels.*` | `ChannelsHandler` | 单通道 stop → start |
| 其他 | - | 需重启网关 |

---

## 17.8 静态资源

`StaticHandler` 服务前端资源：

- 资源目录：classpath `web/`（打包进 JAR，常量 `WebUtils.RESOURCE_PREFIX`）
- 根路径 `/` 与空路径被规范化为 `/index.html`（`WebUtils.PATH_INDEX`）
- 路径包含 `..` 直接返回 **403**（路径穿越防护）
- 资源缺失返回 **404**（**不做** SPA history 回退）
- MIME：由 `WebUtils.getContentType(path)` 按扩展名识别：`html/css/js/json/png/svg/ico`，其他一律 `application/octet-stream`

---

## 17.9 多模态文件上传

`UploadHandler` 接收 JSON Body（**不是** multipart）：

```json
{
  "images": [
    {"data": "data:image/jpeg;base64,...", "name": "photo.jpg"}
  ]
}
```

关键约束：

- 单文件上限 `MAX_FILE_SIZE = 10 * 1024 * 1024 = 10MB`（源码常量）
- 只接受 MIME 前缀为 `image/` 的图片
- 存储目录：`workspace/uploads/`，文件名随机 UUID
- 响应：`{"files": ["uploads/<uuid>.<ext>", ...]}`
- 访问：`GET /api/files/<uuid>.<ext>`

安全：`FilesHandler` 只读 `workspace/uploads/` 子目录，防止越权读其他文件。

---

## 17.10 Token 统计面板

`/api/token-stats` 基于 `workspace/token_usage.json`：

- 按 `provider` / `model` / `day` / `sessionKey` 聚合
- 前端绘制趋势图、热门模型排行
- 数据写入由 `ReActExecutor` 在每次 LLM 调用后完成

---

## 17.11 前端功能页（典型）

虽然前端不在本文讨论范围，但 UI 常见页面与 Handler 对应如下：

| 页面 | 主要 Handler |
|------|---------------|
| Chat（对话） | `ChatHandler`, `UploadHandler`, `FilesHandler` |
| Sessions（会话历史） | `SessionsHandler` |
| Cron（定时任务） | `CronHandler` |
| Workspace（工作空间） | `WorkspaceHandler` |
| Skills（技能） | `SkillsHandler` |
| Providers（模型提供商） | `ProvidersHandler`, `ModelsHandler` |
| Channels（通道管理） | `ChannelsHandler` |
| MCP（外部工具） | `MCPHandler` |
| Feedback（反馈） | `FeedbackHandler` |
| Token Usage（费用统计） | `TokenStatsHandler` |
| Tools Health（Reflection 2.0） | `ReflectionHandler` |

---

## 17.12 安全加固建议

- 生产环境务必修改默认凭据 `admin/tinyclaw`（`gateway.username` / `password`）
- 启用速率限制：`gateway.rateLimitPerMinute > 0`
- 避免把 Web 端口 `18790` 暴露公网；如需远程访问走 SSH 隧道 / VPN
- 反向代理层加 HTTPS；Basic Auth 明文传输必须走 TLS
- 若仅做只读监控，可在反向代理层屏蔽 `POST/PUT/DELETE` 方法
- 注意响应中的 API Key 会被 `WebUtils.maskSecret()` 掩码（首尾各 4 位 + `****`）

---

## 17.13 扩展：添加新 Handler

1. 在 `web/handler/` 新建类，构造接收 `Config` + `SecurityMiddleware`，提供 `handle(HttpExchange)`
2. 在 `WebUtils` 增加路径常量（如 `API_XXX`）
3. 在 `WebConsoleServer.registerApiEndpoints` 注册 `httpServer.createContext(...)`
4. （可选）在前端加对应页面

详见 [20 · 扩展开发](20-extending.md)。

---

## 17.14 下一步

- 部署到网关模式 → [03 · 快速开始 §3.6](03-getting-started.md)
- 鉴权策略详解 → [16 · 安全沙箱](16-security-sandbox.md)
- 通道凭据在 UI 的配置 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
