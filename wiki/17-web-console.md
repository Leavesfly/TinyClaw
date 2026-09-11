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
| `RunsHandler` | `/api/runs` | `GET /api/runs?sessionId=&clientRequestId=`、`GET /api/runs/{id}`、`GET /api/runs/{id}/interactions`、`GET /api/runs/pending?sessionId=` | 执行记录查询、刷新恢复与待确认交互重建（P1） |
| `AttachmentsHandler` | `/api/attachments` | `POST /api/attachments?sessionId=&name=`（二进制）、`GET /api/attachments/{id}`、`GET /{id}/content`、`GET /{id}/download` | 通用附件上传/解析状态/文本/原始下载（P2） |
| `ChannelsHandler` | `/api/channels` | CRUD | 通道启停与凭据管理 |
| `SessionsHandler` | `/api/sessions` | CRUD + 详情 | 会话列表/详情/删除/回放 |
| `CronHandler` | `/api/cron` | CRUD | 定时任务 CRUD + 启停 |
| `HeartbeatHandler` | `/api/heartbeat` | `GET /api/heartbeat`、`POST /api/heartbeat/now` | 心跳状态查询 + 手动触发 |
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
  "images": ["uploads/20260501/abc.jpg"],
  "clientRequestId": "可选幂等键（P1）"
}
```

- `sessionId` 缺省时用 `WebUtils.DEFAULT_SESSION_ID = "web:default"`
- `images` 为可选的多模态图片路径数组
- `clientRequestId`（P1，可选）：幂等键——同一 id 重复提交返回既有执行（SSE 首事件 `RUN_STARTED` 带 `duplicated=true`），不重跑工具；双击发送/断线重发不会产生重复副作用。每会话同时最多一个活动 Web 根执行，超出返回 `409 {error, busy:true, runId}`
- `attachmentIds`（P2，可选）：通用附件 ID 数组（最多 5 个）——就绪附件的解析文本以带来源 ID 的块追加到消息正文，随会话持久化可追溯；缺失/未就绪附件在消息中明确标注状态

SSE 首个结构化事件为 `RUN_STARTED`（P1）：`{"type":"RUN_STARTED","runId":"run-xxx","clientRequestId":"...","duplicated":false}`。前端刷新后凭 `GET /api/runs?sessionId=` 恢复运行状态，凭 `GET /api/runs/pending?sessionId=` 重建待审批卡。执行状态机：CREATED → RUNNING → WAITING_USER → RUNNING → … → COMPLETED/FAILED/CANCELLED；停止先进入 CANCELLING 并唤醒待审批 Future（按拒绝处理）；JVM 重启后非终态记录标记 INTERRUPTED，不自动重试。执行记录存 `workspace/runs/<runId>.json`（仅摘要，会话转录仍是消息事实源）。

### 17.6.2 SSE 事件格式

每个事件由 `StreamEvent.toJson()` 序列化为单行 JSON，包装为 `data: <json>\n\n`。

实际事件类型（对应 `StreamEvent.EventType` 枚举）：

| 事件类型 | 触发时机 |
|----------|----------|
| `CONTENT` | 主 Agent 的普通文本块 |
| `TOOL_START` | 工具调用开始（带 `tool`、`args`） |
| `TOOL_END` | 工具调用结束（带 `tool`、`success`） |
| `SUBAGENT_START` / `SUBAGENT_CONTENT` / `SUBAGENT_END` | 子代理（`spawn` 工具）生命周期 |
| `SUBAGENT_THINKING` | 子代理的思考/推理过程（卡片内折叠展示） |
| `COLLABORATE_START` / `COLLABORATE_AGENT` / `COLLABORATE_AGENT_CHUNK` / `COLLABORATE_END` | 多 Agent 协同生命周期 |
| `COLLABORATE_AGENT_THINKING` | 协同 Agent 的思考/推理过程（发言块内折叠展示） |
| `THINKING` | 可选的思考/推理过程 |

协同发言类事件（`COLLABORATE_AGENT_CHUNK` / `COLLABORATE_AGENT_THINKING`）带 `agent` 与 `turn`：`turn` 标识
一次发言（由 `RoleAgent.speakStream` 生成），前端按 `turn` 而非「当前块」建发言块索引。并行协同
（Tasks 模式 / 并行工作流节点）下多个 Agent 的事件交错到达时各归各块，顺序型多轮发言则因 `turn`
不同自然分块。

嵌套执行的工具调用（子代理 / 协同角色内部的工具）仍用 `TOOL_START` / `TOOL_END`，但额外带归属字段
`taskId`（子代理）或 `agent` + `turn`（协同角色），前端据此把工具卡片渲染进对应的子代理卡片或
本次发言块内；无这些字段时属于主 Agent，渲染在顶层消息容器。

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
| Memory（长期记忆） | `MemoryHandler` |
| Projects（项目空间） | `ProjectsHandler`（P4） |

---

## 17.11a 前端资源与体验基线（P0）

静态资源全部随 JAR 打包（classpath `web/`），**无任何运行时 CDN 依赖**，断网可用：

| 资源 | 职责 |
|------|------|
| `web/js/markdown.js` | 本地零依赖 Markdown 渲染器：白名单标签 + 全量转义（含引号）+ URL 协议白名单（http/https/站内/锚点/data:image），天然免疫 XSS；暴露 `window.marked` 兼容对象与 `window.tinyMarkdown` 独立入口 |
| `web/js/app.js` | 单控制器页面协调器 |
| `web/css/style.css` | 全部页面样式（含移动端抽屉） |

聊天页 P0 体验能力（均在 `app.js` 内，无新增 API）：

- **草稿**：按会话隔离存 localStorage（键 `tinyclaw_draft:<sessionKey>`），仅存正文；发送前保存、服务端确认接收后清除；发送/上传失败自动恢复到输入框，不自动重发
- **阅读位置**：流式输出仅在用户处于消息区底部时自动滚动；上滑时显示“回到最新”按钮与新消息计数徽标
- **移动端抽屉**：≤768px 会话侧栏改为滑出抽屉（☰ 入口），Escape 关闭、焦点返回触发按钮、会话项支持 Enter 键导航
- **输入区模型信息**：展示当前全局模型与思考配置（读 `/api/config/model` 与 `/api/config/agent`），点击跳转 Models 设置页；作用范围为全局，非会话级
- **请求隔离**：会话历史加载带序号守卫，切换会话后旧异步响应不写入新会话 DOM；后台任务轮询绑定会话快照，切走后自动停止

回归防护：`WebBaselineResourcesTest`（Maven）锁定本地渲染器接入、无 CDN 残留与安全关键点，避免退化。

---

## 17.11b 通用附件（P2）

输入区新增 📄 按钮：TXT/Markdown/UTF-8 代码文本/CSV/文本型 PDF/DOCX 逐文件二进制上传（图片仍走旧 📎 通道）。附件存 `workspace/attachments/`（`<id>.bin` 原始字节 + `<id>.json` 元信息 + `<id>.txt` 解析缓存）。

关键行为：
- **状态机** UPLOADING → PARSING → READY/FAILED；解析在独立有界池（并发 2、队列 16）执行，队列满明确报忙碌；chip 显示实时状态与截断标记，单份失败不清空其他
- **预算** 单附件 ≤ 10 MiB、每轮 ≤ 5 个、解析输出 ≤ 200,000 字符（超限截断带标记）、PDF ≤ 200 页；读取请求体时即限制大小
- **安全** 附件 ID 为 UUID 短串（文件名仅展示，不参与寻址）；文件头复核扩展名（拒绝伪造 MIME / 旧版 Office 宏文档 / 压缩包）；下载路径 realpath 校验防遍历
- **上下文** 就绪附件解析文本以 `[附件「名」，来源ID id]` 块追加进消息正文——随会话持久化，历史回放/fork 均可追溯；扫描件 PDF 报「未提取到文本」不伪造成功
- **依赖** commons-csv 1.11 / pdfbox 3.0.3 / poi-ooxml 5.3.0（定向解析，不引入全量 Tika）

回归防护：`AttachmentStoreTest`（11 用例）覆盖各格式样本、损坏/伪造文件拒绝、超限截断、来源 ID 注入与非法 ID 校验。

---

## 17.11c 持久化成果工作台（P3）

成果登记从「前端 TOOL_START 意图追踪」升级为服务端登记：write_file / edit_file **在实际写盘成功后**经 `ArtifactRecorder` 回调登记（含规范化路径、SHA-256 hash、递增 revision），每次成功写入保存版本快照。存 `workspace/artifacts/`（`<id>.json` 登记 + `versions/<id>/<rev>.snap` 快照）。

端点（`ArtifactsHandler`，`/api/artifacts`）：`?sessionId=` 列表、`/{id}` 详情（含存在性）、`/{id}/versions`（hash 与外部改动检测）、`/{id}/content?revision=` 文本预览、`/{id}/download?revision=` 原始字节下载。

关键行为：
- **登记可靠**：同会话同文件多次写复用同一成果条目并递增 revision；切会话/刷新/重启后均可找回（前端流结束与切会话时同步 `loadSessionArtifacts`）
- **版本回放**：任意历史版本可预览与下载；原文件删除后登记仍在（显示「原文件已移除」），快照仍可读；外部改动通过 hash 差异暴露，不冒充登记版本
- **快照预算**：单版本 > 2 MiB 不存快照（登记与当前文件下载不受影响）
- **继续修改**：面板提供「继续修改」按钮，填入文件路径与当前版本号的请求模板，走正常聊天链路（提交前校验由下一轮工具调用自然承担）
- **安全**：成果 ID 为 UUID 短串（路径不参与寻址）；读取范围仅限登记过的路径；二进制（图片/PDF）内容预览拒绝并引导下载

回归防护：`ArtifactStoreTest`（8 用例）覆盖版本递增/快照回放/会话隔离/外部改动检测/删除后可追溯/重启恢复/非法 ID。

---

## 17.11d 会话整理（P4）

会话侧栏条目新增 ⋯ 整理菜单：重命名、置顶/取消置顶、归档/取消归档、查看/隐藏归档会话。

- **端点**：`PATCH /api/sessions/{key}/flags`（请求体 `{displayTitle?, pinned?, archived?}`，可选字段仅更新出现的字段）；列表接口合并返回 `displayTitle/pinned/archived`
- **存储**：`workspace/session-flags.json`（独立可重建的偏好元信息，不混入不可变转录；损坏按空处理）；归档不删除任何资源；会话删除时同步清理标记
- **排序**：置顶优先 > 时间戳降序；归档会话默认隐藏（菜单可切换）
- **标题优先级**：自定义标题（服务端可重建） > localStorage 首条消息缓存 > 后端摘要标题 > 时间降级
- **CORS**：`Access-Control-Allow-Methods` 增加 PATCH

回归防护：`SessionFlagsStoreTest`（5 用例）覆盖标题设置/清除、开关独立、持久化重建、删除清理、损坏容错。

注：P4 的项目空间已在后续批次交付（见 17.11g）；会话侧剩余事项（fork 导航增强、导出、游标分页）待后续批次。

---

## 17.11e 可控记忆：筛选、分页与来源会话（P5）

记忆页从「全量列表」升级为「服务端筛选 + 分页 + 可回溯来源」：

- **列表筛选**：`GET /api/memory` 支持可选查询参数 `q`（关键词，匹配内容与标签，大小写不敏感）、`scope`（归属域精确匹配）、`tag`（命中标签之一）、`source`（来源标识）；响应新增 `total`（过滤后总数）/ `offset` / `limit`，`count` 为当前页条数
- **分页**：`limit` 默认 50、上限 200（超限钳制），`offset` 超界钳制为空页；前端提供关键词输入、scope/source 下拉（选项从当前页数据动态收集）、每页条数与上一页/下一页分页条
- **来源会话**：`POST /api/memory` 接受可选 `sourceSessionKey`；`MemoryEntry` 新增同名字段（旧数据缺失为空，前端显示“历史来源未记录”）。新增记忆表单默认带出当前聊天会话，编辑时只读
- **来源导航**：列表条目展示可点击的来源会话徽标；点击后先经服务端 `GET /api/sessions/{key}` 确认会话存在再切换到聊天页（404 时提示“来源会话已不存在”）——不接受任意 URL/路径跳转
- **安全**：`sourceSessionKey` 仅放行 `[A-Za-z0-9:_-]{1,64}`（冒号用于 Web 会话 key 形如 `web:1725968000000`）；路径分隔符、URL 保留字符、空格、点号等一律 400 拒绝且不入库

回归防护：`MemoryHandlerFilterTest`（9 用例）覆盖关键词/归属域/标签/来源筛选、分页与钳制、来源会话持久化与非法值拒绝；`WebBaselineResourcesTest` 增加 P5 前端锚点。

---

## 17.11f 可控记忆：memoryMode、聊天内纠正与上下文透明化（P5 续）

P5 剩余切片：会话级记忆模式、同源选中清单、聊天内纠正/删除、本轮上下文构成摘要。

- **memoryMode（每会话 DEFAULT / OFF）**：`PATCH /api/sessions/{key}/flags` 请求体新增可选 `memoryMode` 字段（非法值 400）；存储于 `session-flags.json`（旧数据缺失为 DEFAULT）；会话列表合并返回 `memoryMode`。OFF 同时关闭该会话的长期记忆自动检索（读路径）与摘要后自动提取写入（写路径），聊天历史仍正常保存——UI 命名为「不使用长期记忆」，不称为无痕模式；手动在记忆管理页新增不受影响
- **门机制贯穿**：`ContextBuilder.setMemoryGate(Predicate<String>)` 由 GatewayBootstrap 注入（实时读 flagsStore，切换立即生效）；读路径在 `buildSystemPromptWithSession` 判断，写路径在 `SessionSummarizer.applyCompaction` 判断（跳过时仅记日志）；ProviderManager 传稳定委托 `contextBuilder::isMemoryEnabled`，热切换 Provider 重建 Summarizer 不会固化旧门
- **同源选中清单**：`MemoryStore.buildMemorySelection(...)` 返回 `MemorySelection`（text + entries + topics + estimatedTokens + disabled），与 `getMemoryContext` 同一函数体产出——「本轮使用」与真实模型输入来自同一次计算，不做访问次数推断；`ContextBuilder` 每次带 sessionKey 构建时登记 `lastSelectionBySession`（容量 256 超限整体重置）
- **查询接口**：`GET /api/memory/context-used?sessionId=` 返回最近一次选择（available/disabled/memoryCount/topicCount/estimatedTokens/entries[]/topics[]）；无登记时 available=false。entries 携带完整字段（id/content/scope/source/importance/tags）供聊天内直接编辑
- **聊天页本轮上下文条**（输入区下方 `contextUsedBar`）：默认展示「本轮使用记忆 N 条 · 主题 M 个 · ≈X tokens（估算）」，OFF 会话展示「不使用长期记忆 · 本轮未注入任何长期记忆」；展开后列出实际使用的记忆，每条可「纠正」（复用记忆编辑弹窗，PUT 全字段回填）或「删除」（确认文案明示仅影响后续轮次，已发送上下文不被追溯抹除）；流结束、切会话、编辑/删除记忆后自动刷新
- **上下文构成摘要**（事项 7）：展开区顶部展示模型 / 待发附件数 / 是否使用压缩摘要 / 记忆部分 token 估算值——估算值与 Provider 返回的实际 Token 用量分开标注（斜体 + title 提示）
- **兼容性**：`buildMessages` 新九参重载携带 sessionKey；旧八参/五参入口保持旧行为（记忆启用、不登记）；sessionKey 为 null 时视为会话未知，记忆照常注入；`MemorySection` 优先使用 `SectionContext.presetMemoryContext`（非 null 时不再重算，空串表示已计算无内容）

回归防护：`MemoryStoreTest` 新增 4 用例（同源保证、选中收集含 importance/tags、空选择、disabled 语义）；`ContextBuilderTest` 新增 4 用例（OFF 会话不注入 Memory 段且登记 disabled、DEFAULT 会话注入且选中清单与提示词文本一致、旧入口/无 sessionKey 保持旧行为、门实时切换生效）；`WebBaselineResourcesTest` 新增上下文透明化锚点（含禁止无痕命名断言）。

注：P5 计划中的 sourceMessageId（逐消息回溯）已随 P4 项目空间批次补齐（POST /api/memory 接受可选 sourceMessageId，同套安全字符集校验，列表回显）；项目记忆域见 17.11g。

---

## 17.11g 项目空间（P4：项目指令、资料引用与会话归属）

项目 = 名称 + 指令 + 显式共享的资料引用，定位长期工作组织容器；项目页不替代全局核心文件编辑器。

- **存储**：`workspace/projects.json`（单 JSON 原子写，损坏按空处理）；`revision` 全局单调递增且重启不回退，供执行时记录使用的项目版本；名称 ≤100 字符，指令 ≤8000 字符
- **REST**（`ProjectsHandler`）：`GET/POST /api/projects`、`GET/PUT/PATCH/DELETE /api/projects/{id}`、`POST /api/projects/{id}/resources`、`DELETE /api/projects/{id}/resources/{attachmentId}`；删除必须 `?confirm=true`，响应报告解除的引用清单
- **项目指令注入**：新增 `ProjectSection`（位于 Identity 与 Bootstrap 之后）；固定附带「与全局安全限制冲突时以全局安全限制为准」——项目指令不可覆盖全局安全限制。注入时仅带名称与指令，不合并项目内聊天历史
- **项目记忆域**：`MemoryScope.projectDomain(projectId)` 新增 `p:<projectId>` 域（id 安全字符化，路径分隔符替换为下划线防拼接越权）；归属会话的可见域集合 = 全局 + 用户 + 聊天 + 本项目域，不默认注入其他项目域；解析器异常时安全退化（无 Project 段，不中断构建）
- **会话归属**：`PATCH /api/sessions/{key}/flags` 接受可选 `projectId`（不存在 404）；一旦产生消息归属固定（有消息时改归属返 409，提示 fork 新会话）；列表接口合并 `projectId` 并支持 `?projectId=<id|none>` 过滤；项目删除后残留标记视为无项目（指令不再注入）
- **删除语义**：只移除项目本体与资料引用——附件物理文件保留（其他项目/会话可能仍引用），会话转录不动；前端删除前先查归属会话数展示影响
- **装配**：`GatewayBootstrap` 创建 `ProjectStore` 并注入 `setProjectResolver`（实时读 flagsStore → ProjectStore，项目修改/删除下一轮立即生效）；`WebConsoleServer` 新 13 参构造仅在 projectStore 非 null 时注册 /api/projects
- **前端**：导航新增 Projects 页（卡片列表：指令折叠展示/归档/归属会话查看与切换）；会话菜单第 6 项设置/清除项目归属（提示归属固定约束）

回归防护：`ProjectStoreTest`（10 用例）覆盖 CRUD 往返/重建、revision 单调、引用幂等与上限、删除返回解除引用、名称指令钳制、副本隔离、损坏容错、projectDomain 安全化；`ContextBuilderTest` 新增 3 用例（项目指令注入+项目域可见、无归属/未装配无注入、解析器异常退化）；`WebBaselineResourcesTest` 新增 P4 锚点（删除影响确认与全局优先声明）。

注：P4 其余会话侧事项（fork 导航增强、导出 Markdown/JSON、游标分页消息页）未在本批交付，待后续批次。

---

## 17.11h 自动化闭环（P6：从会话创建任务、调度预览与执行追溯）

Cron 不再只是「填表建任务」：从聊天内容一键转周期任务、调度在创建前可预览、每次执行可跳转查看过程与产物。

- **CronPayload 扩展**：`runMode`（`NEW_SESSION` 默认：每次运行新建专用会话，不复制聊天历史，只携带确认的任务指令与勾选资料；`CONTINUE_SESSION`：继续同一专用会话，忙碌时跳过本轮非失败）、`projectId`（项目归属标记）、`sourceSessionKey`（来源会话回溯）、`attachmentIds`（资料引用，12 位十六进制上限 20）、`outputTarget`（输出要求，随任务注入）；旧 JSON 无这些字段时安全回退默认值
- **执行编排**（`GatewayBootstrap.executeUserCronJob`）：会话 key 决策（`cron-<jobId>` 或 `cron-<jobId>-<hex ts>`）→ 项目归属标记（flagsStore）→ 任务指令拼接资料块与输出要求 → `CronTool.executeJobInSession` → 执行前后成果差集记入 `CronRunEnvelope` 侧表 → `RunDetailCollector` 回传合入执行历史（runId/sessionKey/artifactIds/deliveryStatus）
- **调度预览**：`POST /api/cron/preview`（body 为 {kind,cron|everySeconds|atMs,tz}）返回未来 5 次执行点——复用服务端同一套调度计算（含时区），前端不另写 Cron 解释器，预览即真实；表单内嵌模板（每日 9 点/每周一/工作日）与时区输入（默认浏览器时区）
- **时区修复**：`computeCronNextRun`/`isCronJobMisfired` 此前忽略 `schedule.getTz()` 硬用服务器默认时区，本批改用 `resolveZone`（非法回退系统默认并告警）
- **非失败性跳过**：JobHandler 返回 `[SKIPPED] ` 前缀 → 历史记录 `STATUS_SKIPPED`（不触发误告警）；[生成成功≠消息已送达]——`deliveryStatus`（generated/skipped）单独标注，投递由通道异步承担
- **执行追溯**：历史行会话 key 可点击跳转（cron 专用会话不在聊天侧栏，以 Trace 时间线弹窗展示；已清理时明确提示）；成果 id 可点击预览（不在当前会话缓存时从 `GET /api/artifacts/{id}` 拉取兜底）；更新任务走 `persistJob` 保字段不丢
- **前端入口**：聊天输入区 ⏰ 按钮「把本轮任务设为周期任务」——预填本轮用户消息、勾选当前会话项目资料（仅勾选的随任务复用）、确认前不创建调度；Cron 页表单新建/编辑/从会话创建共用同一构建器（模板/类型切换/预览/执行模式/输出要求）

回归防护：`CronServiceP6Test`（10 用例）覆盖时区计算（东京 vs 洛杉矶同一时刻产出不同本地 8 点）、预览递进、[SKIPPED] 语义、RunDetailCollector 合入与 null 行为、payload 旧 JSON 兼容与往返、扩展字段重启保留、旧五参调用兼容、模板表达式；`WebBaselineResourcesTest` 新增 P6 锚点（入口按钮/不复制聊天历史明示/服务端同源预览/执行会话跳转/兜底拉取/生成≠送达）。

注：P6 计划中的首次引导部分（首次进入最短路径、模板三类、统一能力查询接口、修复入口）未在本批交付，待后续批次。

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
