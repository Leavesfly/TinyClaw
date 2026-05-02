# 21 · FAQ & 故障排查

> 使用中最常见的问题与定位思路。

---

## 21.1 安装与启动

### Q1. `tinyclaw` 命令找不到

- JAR 用法：`java -jar tinyclaw-*.jar <cmd>` 可用即 OK
- 脚本别名：建议在 `~/.zshrc` 加：
  ```bash
  alias tinyclaw='java -jar /absolute/path/tinyclaw-*.jar'
  ```
  然后 `source ~/.zshrc`
- 验证 Java 版本：`java -version`，需要 **JDK 17+**（`pom.xml` 的 `maven.compiler.source`）

### Q2. `tinyclaw onboard` 卡住 / 不给自动创建 workspace

- 默认配置与 workspace 位置：`~/.tinyclaw/`
- 权限不足：检查 `~/.tinyclaw/` 目录是否可写
- 手动兜底：自行 `mkdir -p ~/.tinyclaw/workspace`，再运行 `tinyclaw onboard`

### Q3. `tinyclaw gateway` 启动失败，端口被占用

默认端口 **18790**（`GatewayConfig` 默认值）：

```bash
# macOS / Linux 查端口占用
lsof -i :18790
# 替换端口
# 修改 ~/.tinyclaw/config.json 的 gateway.port
```

---

## 21.2 对话与 Agent 行为

### Q4. 回复直接返回空 / 报错 `API key not configured`

1. 检查 `~/.tinyclaw/config.json` 中对应 Provider 的 `apiKey` 是否已填
2. 检查 `models.defaultModel` 是否指向一个在 `models.modelProviders` 中有映射的模型
3. 查日志：`agent` 与 `providers` 命名空间

### Q5. Agent 不调用工具，直接给空洞回答

- **LLM 能力**：确保用的是支持 function calling 的模型（如 `qwen-plus`、`gpt-4o`、`claude-*`）
- **工具描述**：`description()` 写清楚**何时**调用（"当用户询问..."）
- **ReAct 迭代**：检查 `agent.maxIterations`（默认 10），太小会提前截断

### Q6. 长对话越来越慢 / Token 消耗暴涨

- 开启会话摘要：保证 `SessionSummarizer` 被启用（默认即开启）
- 调低 `agent.memoryWindow`（见 [04 配置](04-configuration.md)）
- 开启 Memory：`agent.evolution.memoryEvolveEnabled=true` 把事实沉淀到长期记忆
- 定期清理 `workspace/sessions/*.json` 中的僵尸会话（Web 控制台 Sessions 页可批量删）

### Q7. 工具调用一直失败（`Blocked: ...`）

- `SecurityGuard` 拦截。排查：
  - 路径是否在 workspace 内？（见 [16 · 安全沙箱](16-security-sandbox.md)）
  - 命令是否命中黑名单？日志里会写 `matched="<正则>"`
- 若确实是误拦，扩展 `tools.exec.blacklistExtras` 白名单模式或调整为允许规则

### Q8. 工具被 hook deny

- 日志：`hooks  Hook denied action  event=... tool=... reason=...`
- 检查 `~/.tinyclaw/hooks.json` 是否存在预期外的 `PreToolUse` 配置
- 逐个排查 matcher，可临时设 `matcher: "__never__"` 禁用某条

---

## 21.3 通道相关

### Q9. Telegram 通道不工作

- 是否启用：`channels.telegram.enabled = true`
- Token 有效性：用 `curl https://api.telegram.org/bot<TOKEN>/getMe`
- 白名单：`channels.telegram.allowList` 非空时**只有列表内用户可用**
- 网络：Telegram API 在某些地区需要代理，检查 `httpClient` 的代理配置

### Q10. 飞书 / 钉钉机器人收不到消息

- Webhook URL 是否与网关一致？需要公网可达，或用内网穿透（ngrok / frp）
- 签名校验：确认机器人**事件加密**、**签名 key** 和配置一致
- 查 `agent` 或 `channel` 日志，看消息是否到达 `InboundMessage`

### Q11. 语音消息不被识别

- 仅 **Telegram / Discord** 通道接入了转写（见 [18 · 语音](18-voice.md)）
- `providers.dashscope.apiKey` 必须配置，否则网关启动会打印 "Voice transcription disabled"
- 大文件：`AliyunTranscriber` 轮询最多 60 秒，更长音频会超时

---

## 21.4 Web 控制台

### Q12. 打开 `http://localhost:18790` 要求登录但不知道密码

默认凭据：**`admin / tinyclaw`**（`GatewayConfig` 内置默认）。**生产环境务必修改** `gateway.username` / `password`。

### Q13. 前端页面刷新后 404

`StaticHandler` **不做 SPA history 回退**，非 `/` 的前端路径刷新会 404。解决：

- 始终通过 `/` 入口进入
- 或在反向代理层配置 fallback 到 `index.html`

### Q14. Web 调 `/api/chat` 跨域被拦

- 默认 `gateway.corsOrigin = "*"`，应允许所有来源
- 若改为具体域名，确保**精确匹配**（含协议、端口）
- 预检失败查 Network 面板的 OPTIONS 响应

---

## 21.5 MCP

### Q15. MCP 服务器启动日志显示 FAILED

- 查 `mcp` 日志里的 `error` 字段：常见是可执行文件不存在、API endpoint 不可达、握手超时
- 手动调试：`tinyclaw mcp test <name>` 单独重跑握手
- 单服务器失败**不影响**其他；修好后可通过 Web UI "reconnect" 不用重启整个网关

### Q16. MCP 工具名在多服务器间冲突

MCP 工具注册时会加前缀（实现见 `MCPManager.initializeServer`）。若仍有冲突：

- 把冲突服务器中之一 `enabled: false`
- 或在 MCP 服务器侧自己规划命名

---

## 21.6 进化与反馈

### Q17. Prompt 进化好像没发生

- `agent.evolution.enabled = true` 没开？
- `agent.evolution.feedbackEnabled = true` 没开？Prompt 优化依赖反馈积累
- 反馈数量 < `minFeedbackToTrigger`（默认 10）—— 需要积累足够数据
- 心跳未启动：进化由 `runEvolutionCycle()` 触发，它跑在 gateway 心跳线程

### Q18. 记忆越写越多，无关信息也沉淀进去了

- 降低 `agent.evolution.memoryEvolveEnabled` 频率，或临时关闭
- 手动维护：编辑 `workspace/memory/topics/*.md` 删除无关内容
- 归档：Web UI 把低重要度条目归档到 `MEMORIES_ARCHIVE.json`

---

## 21.7 日志与调试

### Q19. 怎么定位"某次工具调用为什么失败"

TinyClaw 的日志按**命名空间**分组：

| 命名空间 | 对应模块 |
|----------|----------|
| `agent` | AgentRuntime / ReActExecutor / ContextBuilder |
| `providers` | LLM 调用 |
| `tools` | 工具注册与执行 |
| `mcp` | MCP 客户端 |
| `hooks` | Hook 触发 |
| `security` | SecurityGuard 拦截 |
| `channel` / `web` | 通道 / Web 控制台 |
| `cron` / `heartbeat` | 定时任务 / 心跳 |
| `voice` | 语音转写 |
| `session` | 会话存取 |
| `memory` | 记忆存取 |

调日志级别：在配置或启动参数中把对应命名空间设为 `DEBUG`，然后看详细输出。

### Q20. 启动太慢 / 网关启动耗时长

常见原因（按概率排序）：
1. **MCP 服务器握手**：每个 MCP 都要 initialize + tools/list；改成 `enabled: false` 试一下
2. **首次 `onboard`** 下载内置技能
3. **冷启动 JIT**：第一次响应慢，后续会快

排查：看启动日志里的耗时点。

---

## 21.8 数据与隐私

### Q21. 数据存在哪？

| 数据 | 位置 |
|------|------|
| 配置 | `~/.tinyclaw/config.json` |
| 工作空间 | `~/.tinyclaw/workspace/`（可自定义 `workspace` 字段） |
| 会话 | `workspace/sessions/*.json` |
| 记忆 | `workspace/memory/*.md` + `MEMORIES.json` |
| 定时任务 | `workspace/cron/jobs.json` |
| Token 用量 | `workspace/token_usage.json` |
| 反馈 | `workspace/evolution/feedback.jsonl` |
| Prompt 变体 | `workspace/evolution/prompts/` |
| 工具反思 | `workspace/reflection/tool_calls.jsonl` |
| 协同记录 | `workspace/collaboration/` |
| 上传文件 | `workspace/uploads/` |
| Hook 配置 | `~/.tinyclaw/hooks.json` |

### Q22. 会把我的对话上传到第三方吗？

TinyClaw **本身**不上传数据，但：

- 所有 LLM 调用都发往你配置的 Provider（OpenAI / DashScope / ...）
- MCP 工具调用会把参数发给对应的 MCP Server
- 如需离线，全部用本地模型（`ollama` Provider）+ 本地 MCP Server

---

## 21.9 性能与规模

### Q23. 能同时服务多少并发对话？

参考量级（本机 M1/M2 Mac，8 线程池）：
- Web：~8 并发 HTTP 请求（`WebConsoleServer` 线程池）
- 主瓶颈是 LLM 调用的延迟与 Provider 限流
- 通道侧：每个通道独立处理，互不阻塞

生产扩展：
- 调大 `WebConsoleServer` 线程池
- 把心跳、Cron、Collaboration 线程池参数化
- 前置 nginx 做连接复用与限流

### Q24. 会话数多了以后 `sessions/` 目录会爆吗？

`Session` 是懒加载 + 内存缓存，不会一次性加载全部到内存。但目录下文件数量不会自动清理：

- 定期通过 `SessionManager.delete(key)` 或 Web UI "Sessions 批量删除" 清理过期会话
- 长期方案：加一个后台任务按 `updated` 时间淘汰

---

## 21.10 开发与集成

### Q25. 想二次开发，从哪下手？

- 阅读 [02 架构](02-architecture.md) 理解分层
- 阅读 [06 Agent 引擎](06-agent-engine.md) 看主循环
- 按扩展类型参考 [20 扩展开发](20-extending.md)
- 单元测试：`src/test/java` 下已有大量示例

### Q26. 如何给 Agent 加一段"无论何时都要记住"的系统提示？

编辑 workspace 下的 Markdown 文件（见 [04 配置 §4.3](04-configuration.md)）：

| 文件 | 作用 |
|------|------|
| `AGENTS.md` | 全局人设 / 约束 |
| `IDENTITY.md` | Agent 身份介绍 |
| `PROFILE.md` | 用户画像 |
| `USER.md` | 用户偏好 |
| `SOUL.md` | 灵魂 / 核心价值观 |
| `HEARTBEAT.md` | 心跳自省指令 |

这些文件会被 `ContextBuilder` 对应的 section 注入系统提示。

### Q27. 如何在 CI 里跑 TinyClaw 做自动化任务？

推荐 `tinyclaw agent` 子命令（非交互）：

```bash
echo "帮我分析这份报告" | java -jar tinyclaw.jar agent --no-tty
```

配合 Cron 任务用于定时报告、异常告警等无人值守场景。

---

## 21.11 提交 Issue 前的 Checklist

1. 版本号：`java -jar tinyclaw.jar version`
2. 操作系统 + JDK 版本
3. 复现步骤（最小化）
4. 相关配置（**务必脱敏 API Key**）
5. 对应命名空间的 DEBUG 日志片段
6. 期望行为 vs 实际行为

---

## 21.12 还没解决？

- 查阅 `docs/` 目录下的专题指南：
  - `docs/architecture.md`、`docs/hooks-guide.md`
  - `docs/dingtalk-guide.md`、`docs/feishu-guide.md`
  - `docs/security-and-social-network.md`
  - `docs/tech-sharing-tinyclaw.md`
- 提 Issue：[github.com/leavesfly/tinyclaw](https://github.com/leavesfly/tinyclaw)（以实际仓库地址为准）
