# 🦞 TinyClaw Wiki

> **TinyClaw** — 一个用 Java 17 编写的超轻量个人 AI 助手框架，支持多模型、多通道、多 Agent 协同与自我进化。

欢迎来到 TinyClaw 技术 Wiki。本 Wiki 按模块组织，覆盖从快速上手到深入二次开发的全部内容。

---

## 📚 文档地图

### 入门篇

| # | 文档 | 适合读者 |
|---|------|----------|
| 01 | [项目概览](01-overview.md) | 所有读者：了解 TinyClaw 是什么、能做什么 |
| 02 | [整体架构](02-architecture.md) | 架构师 / 高级开发：系统分层、数据流、关键组件 |
| 03 | [快速开始](03-getting-started.md) | 新用户：5 分钟跑通第一个 Agent |
| 04 | [配置指南](04-configuration.md) | 运维 / 用户：`config.json` 所有字段详解 |
| 05 | [CLI 命令参考](05-cli-commands.md) | 所有用户：8 个子命令用法与示例 |

### 核心引擎篇

| # | 文档 | 内容 |
|---|------|------|
| 06 | [Agent 引擎](06-agent-engine.md) | `AgentRuntime` / `ReActExecutor` / `MessageRouter` / `ContextBuilder` / `ProviderManager` / `SessionSummarizer` |
| 07 | [消息总线与通道](07-message-bus-and-channels.md) | `MessageBus` + 7 种消息通道（Telegram、Discord、飞书、钉钉、WhatsApp、QQ、MaixCam） |
| 08 | [LLM 提供商](08-llm-providers.md) | `LLMProvider` / `HTTPProvider` / 流式输出 / 9 种 provider 接入 |
| 09 | [工具系统](09-tools-system.md) | `Tool` 接口 / 15 个内置工具 / `ToolRegistry` / Token 统计 |

### 高级能力篇

| # | 文档 | 内容 |
|---|------|------|
| 10 | [MCP 协议集成](10-mcp-integration.md) | 三种传输（SSE / Stdio / Streamable HTTP）+ 工具桥接 |
| 11 | [多 Agent 协同](11-multi-agent-collaboration.md) | 7 种协同模式 + 工作流引擎（6 种节点） |
| 12 | [自我进化引擎](12-self-evolution.md) | Prompt 优化（3 策略）+ 记忆进化 + 工具反思 |
| 13 | [技能系统](13-skills-system.md) | Markdown 技能定义 / 语义搜索 / GitHub 安装 |

### 基础设施篇

| # | 文档 | 内容 |
|---|------|------|
| 14 | [定时任务与心跳](14-cron-heartbeat.md) | `CronService` + `HeartbeatService` |
| 15 | [会话与记忆](15-session-memory.md) | `SessionManager` / `MemoryStore` / `SessionSummarizer` |
| 16 | [安全沙箱](16-security-sandbox.md) | `SecurityGuard`：工作空间沙箱 + 命令黑名单 |
| 17 | [Web 控制台](17-web-console.md) | `WebConsoleServer` + 16 个 REST API Handler |
| 18 | [语音转写](18-voice.md) | `Transcriber` 接口 / 阿里云 DashScope Paraformer |
| 19 | [Hooks 钩子系统](19-hooks.md) | 6 种生命周期事件 + 命令式 handler |

### 扩展与排错

| # | 文档 | 内容 |
|---|------|------|
| 20 | [扩展开发指南](20-extending.md) | 如何新增通道 / 工具 / 策略 / Provider / MCP 服务器 |
| 21 | [FAQ 与故障排查](21-faq-troubleshooting.md) | 常见问题与诊断步骤 |

---

## 🚀 60 秒上手

```bash
# 1) 构建
mvn clean package -DskipTests

# 2) 初始化（生成 ~/.tinyclaw/config.json 与工作空间）
java -jar target/tinyclaw-0.1.0.jar onboard

# 3) 填入 API Key 后开始对话
java -jar target/tinyclaw-0.1.0.jar agent -m "你好，介绍一下你自己"
```

详细步骤见 [03-快速开始](03-getting-started.md)。

---

## 🗂️ 代码仓库导航

| 目录 | 内容 | 对应文档 |
|------|------|----------|
| `TinyClaw.java` | 主入口，命令注册与分发 | [05](05-cli-commands.md) |
| `agent/` | Agent 核心引擎 | [06](06-agent-engine.md) |
| `bus/` + `channels/` | 消息总线 + 通道适配 | [07](07-message-bus-and-channels.md) |
| `providers/` | LLM HTTP 客户端 | [08](08-llm-providers.md) |
| `tools/` | 15 个内置工具 + `MCPTool` 桥接 | [09](09-tools-system.md) |
| `mcp/` | MCP 协议客户端 | [10](10-mcp-integration.md) |
| `collaboration/` | 多 Agent 协同 + 工作流 | [11](11-multi-agent-collaboration.md) |
| `evolution/` + `memory/` | 进化引擎 + 长期记忆 | [12](12-self-evolution.md) |
| `skills/` | 技能系统 | [13](13-skills-system.md) |
| `cron/` + `heartbeat/` | 定时任务 + 心跳 | [14](14-cron-heartbeat.md) |
| `session/` | 会话管理 | [15](15-session-memory.md) |
| `security/` | 安全沙箱 | [16](16-security-sandbox.md) |
| `web/` | Web 控制台 | [17](17-web-console.md) |
| `voice/` | 语音转写 | [18](18-voice.md) |
| `hooks/` | Hook 钩子 | [19](19-hooks.md) |
| `config/` | 配置模型 | [04](04-configuration.md) |
| `cli/` | 命令行入口 | [05](05-cli-commands.md) |

---

## 🔖 版本信息

- **项目版本**：0.1.0
- **Java 版本**：17
- **License**：MIT
- **Wiki 最后更新**：2026-05-02

> 本 Wiki 基于当前代码库（`/Users/yefei.yf/QoderCLI/TinyClaw`）生成，如代码结构发生变化请及时同步。
