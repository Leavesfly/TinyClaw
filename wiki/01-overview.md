# 01 · 项目概览

> TinyClaw 是什么、为谁而造、能做什么。

---

## 1.1 一句话定义

**TinyClaw** 是一个用 **Java 17** 编写的超轻量个人 AI 助手框架，把一个 LLM 封装成可在本地或服务器上长期运行的「多通道智能体」，开箱即用地提供多模型接入、多 IM 通道、多 Agent 协同、工具调用、MCP 协议、自我进化、定时任务、Web 控制台等完整能力，**以单一 JAR（无 Spring、无 Web 容器）即可部署**。

## 1.2 设计目标

| 目标 | 实现方式 |
|------|----------|
| **轻量可移植** | 纯 Java 实现，无重型框架，`maven-shade-plugin` 打出单一可执行 JAR，约 15MB |
| **模块解耦** | CLI / Agent / 通道 / Provider / 工具 / 技能 / MCP / 协同 / 进化分层清晰，通过接口隔离 |
| **配置驱动** | `config.json` + Markdown 文件（`AGENTS.md`、`SOUL.md`、`USER.md`、`IDENTITY.md`）驱动行为与个性 |
| **工具优先** | 围绕 function calling 设计，Agent 所有「动手」能力通过 `Tool` 实现 |
| **安全优先** | 内置 `SecurityGuard`，所有文件操作限定在 workspace 沙箱内，命令黑名单拦截危险操作 |
| **自我进化** | 反馈收集 + Prompt 自动优化（3 策略）+ 记忆进化 + 工具失败反思 |
| **可观测** | 结构化日志 + Web 控制台（16 个 REST API）+ Token 用量统计 |

## 1.3 核心特性一览

- **🤖 多模型支持** — OpenRouter / OpenAI / Anthropic / 智谱 GLM / Gemini / 阿里云 DashScope / Groq / Ollama / vLLM
- **💬 多通道消息** — Telegram / Discord / WhatsApp / 飞书 / 钉钉 / QQ / MaixCam
- **🤝 多 Agent 协同** — 7 种模式（debate / team / roleplay / consensus / hierarchy / workflow / dynamic）+ 工作流引擎
- **🧬 自我进化** — 3 种 Prompt 优化策略（TEXTUAL_GRADIENT / OPRO / SELF_REFINE）+ 记忆进化 + 工具反思
- **🔌 MCP 协议** — 完整 MCP 客户端（SSE / Stdio / Streamable HTTP 三种传输）
- **🛠️ 丰富的内置工具** — 15 个内置工具（文件、Shell、搜索、抓取、消息、cron、子代理、协同等）
- **🧩 技能插件系统** — Markdown 定义，语义搜索匹配，支持从 GitHub 安装
- **⏰ 定时任务引擎** — Cron / 固定间隔 / 单次定时
- **🧠 长期记忆与会话摘要** — `MemoryStore` + `SessionSummarizer`
- **💓 心跳服务** — 周期性自主思考
- **🎤 语音转写** — 阿里云 DashScope Paraformer
- **🔒 安全沙箱** — `SecurityGuard`
- **🌐 Agent 社交网络** — 接入 ClawdChat.ai
- **🖥️ Web 控制台** — 内置 Web UI + 16 个 REST API
- **🪝 Hooks 钩子** — 6 种生命周期切点（SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / Stop / SessionEnd）
- **🎬 Demo 模式** — 一键演示核心功能

## 1.4 典型使用场景

| 场景 | 说明 |
|------|------|
| **个人助理** | 在终端或 IM 中聊天，让 Agent 管理待办、写作、搜索 |
| **多通道机器人** | 飞书 / 钉钉 / Telegram 同时部署，跨平台接受指令 |
| **定时助手** | 每日早报、周报生成、监控告警回复 |
| **自动化研究** | 多 Agent 协同辩论 / 工作流执行一份调研任务 |
| **企业知识助手** | 通过 MCP 接入内部系统，让 Agent 调用业务 API |
| **边缘设备 AI** | MaixCam 通道 + 轻量本地模型（Ollama） |

## 1.5 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 构建 | Maven | 3.x |
| HTTP 客户端 | OkHttp | 4.12.0（含 `okhttp-sse`） |
| JSON | Jackson | 2.17.0（含 `jsr310`） |
| 日志 | SLF4J + Logback | 2.0.11 / 1.5.0 |
| 交互式命令行 | JLine | 3.25.0 |
| 定时任务 | cron-utils | 9.2.1 |
| 环境变量 | dotenv-java | 3.0.0 |
| 测试 | JUnit 5 + Mockito | 5.10.0 / 5.10.0 |

## 1.6 与同类项目的差异

| 维度 | TinyClaw | 典型 Python Agent 框架 | 重型企业方案 |
|------|----------|------------------------|--------------|
| 语言 | Java 17 | Python | Java/Go + Spring |
| 运行时 | 单 JAR | 虚拟环境 | Web 容器 + 多服务 |
| 通道 | 内置 7 种 | 需额外集成 | 需额外集成 |
| MCP | 内置三种传输 | 多数支持 | 少数支持 |
| 进化 | 内置 Prompt 优化 + 记忆进化 + 工具反思 | 外部依赖 | 通常缺失 |
| 部署 | 本地/服务器单 JAR | 需打镜像 | 需 K8s 等 |

## 1.7 项目目录速览

```text
TinyClaw/
├── pom.xml
├── README.md / README.en.md
├── docs/                            # 现有技术文档（将与 wiki/ 互补）
├── wiki/                            # 本 Wiki
└── src/
    ├── main/java/io/leavesfly/tinyclaw/
    │   ├── TinyClaw.java            # 主入口
    │   ├── agent/                   # Agent 引擎
    │   ├── bus/                     # 消息总线
    │   ├── channels/                # 7 种通道
    │   ├── cli/                     # 8 个 CLI 命令
    │   ├── collaboration/           # 多 Agent 协同
    │   ├── config/                  # 配置模型
    │   ├── cron/                    # 定时任务
    │   ├── evolution/               # 自我进化（含 reflection 子包）
    │   ├── heartbeat/               # 心跳
    │   ├── hooks/                   # Hooks 钩子
    │   ├── logger/                  # 结构化日志
    │   ├── mcp/                     # MCP 协议
    │   ├── memory/                  # 长期记忆
    │   ├── providers/               # LLM 提供商
    │   ├── security/                # 安全沙箱
    │   ├── session/                 # 会话管理
    │   ├── skills/                  # 技能系统
    │   ├── tools/                   # 工具系统
    │   ├── util/                    # 工具类
    │   ├── voice/                   # 语音转写
    │   └── web/                     # Web 控制台
    └── test/java/…                  # 单元测试
```

## 1.8 下一步阅读

- 想了解系统如何组装 → [02 · 整体架构](02-architecture.md)
- 想立刻跑起来 → [03 · 快速开始](03-getting-started.md)
- 想知道所有配置项 → [04 · 配置指南](04-configuration.md)
