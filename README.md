## 🦞 TinyClaw

**超轻量个人 AI 助手** — 用 Java 编写，支持多模型、多通道、多技能的一站式 AI Agent 框架。

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.x-blue)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![Version](https://img.shields.io/badge/Version-0.1.0-brightgreen)]()

---

### ✨ 特性一览

- **🤖 多模型支持** — 接入 OpenRouter、OpenAI、Anthropic、智谱 GLM、Gemini、Groq、vLLM 等主流 LLM 提供商
- **💬 多通道消息** — 同时连接 Telegram、Discord、WhatsApp、飞书、钉钉、QQ、MaixCam 等平台
- **🛠️ 丰富的内置工具** — 文件读写、Shell 执行、网络搜索、网页抓取、定时任务、子代理等
- **🧩 技能插件系统** — 通过 Markdown 定义技能，支持从 GitHub 安装，轻松扩展 Agent 能力
- **⏰ 定时任务引擎** — 支持 Cron 表达式和固定间隔，自动执行 Agent 任务
- **🧠 记忆与上下文** — 内置长期记忆存储和会话管理，Agent 能记住重要信息
- **💓 心跳服务** — 定期自主思考，让 Agent 保持"活跃"
- **🎤 语音转写** — 集成 Groq Whisper，支持 Telegram/Discord 语音消息自动转文字
- **🔒 安全沙箱** — 工作空间限制 + 命令黑名单，生产级安全防护
- **🌐 Agent 社交网络** — 支持接入 ClawdChat.ai，与其他 Agent 通信协作

---

### 📚 学习路径

TinyClaw 按难度分为三个学习层级，适合不同阶段的开发者：

| 层级 | 适合人群 | 学习目标 |
|------|----------|----------|
| **Level 1** | Java 初学者 | 跑通项目、理解主入口和配置 |
| **Level 2** | 后端工程师 | 扩展工具/通道、理解核心模块 |
| **Level 3** | 架构师 / AI 工程 | 改造 Agent 策略、设计生产级方案 |

👉 详细的实践任务与思考题请参阅 [docs/architecture.md - 第十章：动手实践与思考题](docs/architecture.md#十动手实践与思考题)

---

### 📦 项目架构

```
src/main/java/io/leavesfly/tinyclaw/
├── TinyClaw.java          # 应用入口，命令分发
├── agent/                 # Agent 核心引擎
│   ├── AgentLoop.java     #   Agent 主循环（工具调用、推理、响应）
│   ├── ContextBuilder.java#   上下文构建器（系统提示、记忆、技能注入）
│   └── MemoryStore.java   #   长期记忆存储
├── bus/                   # 消息总线
│   ├── MessageBus.java    #   发布/订阅消息中心
│   ├── InboundMessage.java#   入站消息（来自通道）
│   └── OutboundMessage.java#  出站消息（发往通道）
├── channels/              # 消息通道适配器
│   ├── Channel.java       #   通道接口
│   ├── TelegramChannel.java
│   ├── DiscordChannel.java
│   ├── WhatsAppChannel.java
│   ├── FeishuChannel.java #   飞书
│   ├── DingTalkChannel.java#  钉钉
│   ├── QQChannel.java
│   └── MaixCamChannel.java#   MaixCam 硬件设备
├── cli/                   # 命令行接口
│   ├── CliCommand.java    #   命令基类
│   ├── OnboardCommand.java#   初始化引导
│   ├── AgentCommand.java  #   Agent 交互
│   ├── GatewayCommand.java#   网关服务
│   ├── StatusCommand.java #   状态查看
│   ├── CronCommand.java   #   定时任务管理
│   └── SkillsCommand.java #   技能管理
├── config/                # 配置管理
├── cron/                  # 定时任务引擎
├── heartbeat/             # 心跳服务
├── logger/                # 日志系统
├── providers/             # LLM 提供商抽象
│   ├── LLMProvider.java   #   提供商接口
│   └── HTTPProvider.java  #   HTTP 通用实现
├── session/               # 会话管理
├── skills/                # 技能插件系统
├── tools/                 # Agent 工具集
│   ├── Tool.java          #   工具接口
│   ├── ReadFileTool.java  #   读取文件
│   ├── WriteFileTool.java #   写入文件
│   ├── EditFileTool.java  #   编辑文件（diff 模式）
│   ├── ExecTool.java      #   执行 Shell 命令
│   ├── WebSearchTool.java #   网络搜索（Brave API）
│   ├── WebFetchTool.java  #   网页内容抓取
│   ├── CronTool.java      #   定时任务操作
│   ├── MessageTool.java   #   跨通道消息发送
│   ├── SpawnTool.java     #   子代理生成
│   └── ...
├── util/                  # 工具类
└── voice/                 # 语音转写
```

---

### 🚀 快速开始

#### 环境要求

- **Java 17** 或更高版本
- **Maven 3.x**
- 至少一个 LLM API Key（推荐 [OpenRouter](https://openrouter.ai/keys) 或 [智谱 GLM](https://open.bigmodel.cn/)）

#### 1. 构建项目

```bash
git clone <repo-url>
cd TinyClaw
mvn clean package -DskipTests
```

构建完成后，可执行 JAR 位于 `target/tinyclaw-0.1.0.jar`。

#### 2. 初始化配置

```bash
java -jar target/tinyclaw-0.1.0.jar onboard
```

该命令会：
- 在 `~/.tinyclaw/config.json` 创建默认配置文件
- 在 `~/.tinyclaw/workspace/` 创建工作空间目录
- 生成模板文件（`AGENTS.md`、`SOUL.md`、`USER.md`、`IDENTITY.md`）

#### 3. 配置 API Key

编辑 `~/.tinyclaw/config.json`，填入你的 API Key：

```json
{
  "providers": {
    "openrouter": {
      "apiKey": "sk-or-v1-your-key-here",
      "apiBase": "https://openrouter.ai/api/v1"
    },
    "zhipu": {
      "apiKey": "your-zhipu-key-here",
      "apiBase": "https://open.bigmodel.cn/api/paas/v4"
    },
    "dashscope": {
      "apiKey": "sk-your-dashscope-key-here",
      "apiBase": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  }
}
```

#### 4. 开始对话

```bash
# 单条消息模式
java -jar target/tinyclaw-0.1.0.jar agent -m "你好，介绍一下你自己"

# 交互模式
java -jar target/tinyclaw-0.1.0.jar agent
```

### 🎬 5 分钟 Demo：如何演示 TinyClaw

- **Demo 1：本地 CLI 助手**
  - 前置：完成上文“快速开始”的构建、onboard 和 API Key 配置。
  - 在终端运行 `java -jar target/tinyclaw-0.1.0.jar agent`，随便问一个问题，一边看终端输出，一边可以对照 `TinyClaw.java` → `AgentCommand` → `AgentLoop.processDirect` 的调用链来讲解。
- **Demo 2：网关 + 单通道机器人**
  - 在配置中启用一个通道（例如 Telegram），填好 token 和 allowFrom。
  - 执行 `java -jar target/tinyclaw-0.1.0.jar gateway`，从 IM 客户端发消息，观察 MessageBus 进出站日志，即可演示“消息通道 → 消息总线 → Agent → 通道”的完整闭环。
- **Demo 3：定时任务播报**
  - 使用 `tinyclaw cron add --name "demo" --message "这是一条演示任务" --every 30` 创建一个每 30 秒执行的任务。
  - 保持 gateway 运行，等待定时任务触发并在通道中看到播报消息，可以用来说明 `CronService` 与 Agent 的集成路径。

---

### 📖 命令参考

| 命令 | 说明 | 示例 |
|------|------|------|
| `onboard` | 初始化配置和工作空间 | `tinyclaw onboard` |
| `agent` | 与 Agent 直接交互 | `tinyclaw agent -m "Hello"` |
| `gateway` | 启动网关服务（连接所有通道） | `tinyclaw gateway` |
| `status` | 查看系统状态和配置 | `tinyclaw status` |
| `cron` | 管理定时任务 | `tinyclaw cron list` |
| `skills` | 管理技能插件 | `tinyclaw skills list` |
| `version` | 显示版本信息 | `tinyclaw version` |

#### Agent 命令选项

```bash
tinyclaw agent [options]

  -m, --message <text>    发送单条消息并退出
  -s, --session <key>     指定会话键（默认：cli:default）
  -d, --debug             启用调试模式
```

#### Cron 命令选项

```bash
tinyclaw cron list                          # 列出所有定时任务
tinyclaw cron add --name "日报" \
  --message "生成今日工作总结" \
  --cron "0 18 * * *"                       # 每天 18:00 执行
tinyclaw cron add --name "心跳" \
  --message "检查系统状态" \
  --every 3600                              # 每小时执行
tinyclaw cron remove <job_id>               # 移除任务
tinyclaw cron enable <job_id>               # 启用任务
tinyclaw cron disable <job_id>              # 禁用任务
```

#### Skills 命令选项

```bash
tinyclaw skills list                        # 列出已安装技能
tinyclaw skills list-builtin                # 列出内置技能
tinyclaw skills install-builtin             # 安装所有内置技能
tinyclaw skills install owner/repo/skill    # 从 GitHub 安装
tinyclaw skills show <name>                 # 查看技能详情
tinyclaw skills remove <name>               # 移除技能
```

---

### 🔌 支持的 LLM 提供商

| 提供商 | 配置字段 | 说明 |
|--------|----------|------|
| [OpenRouter](https://openrouter.ai/) | `providers.openrouter` | 聚合多模型网关，推荐首选 |
| [OpenAI](https://platform.openai.com/) | `providers.openai` | GPT 系列模型 |
| [Anthropic](https://www.anthropic.com/) | `providers.anthropic` | Claude 系列模型 |
| [智谱 GLM](https://open.bigmodel.cn/) | `providers.zhipu` | GLM-4 系列，国内推荐 |
| [Google Gemini](https://ai.google.dev/) | `providers.gemini` | Gemini 系列模型 |
| [Groq](https://groq.com/) | `providers.groq` | 超快推理 + 语音转写 |
| [vLLM](https://docs.vllm.ai/) | `providers.vllm` | 本地部署模型 |
| [阿里云 DashScope](https://dashscope.aliyun.com/) | `providers.dashscope` | Qwen 系列模型（通义千问） |

所有提供商均通过统一的 `HTTPProvider` 适配 OpenAI 兼容 API 格式，切换模型只需修改配置。

---

### 💬 支持的消息通道

| 通道 | 配置字段 | 所需凭证 |
|------|----------|----------|
| Telegram | `channels.telegram` | Bot Token |
| Discord | `channels.discord` | Bot Token |
| WhatsApp | `channels.whatsapp` | Bridge URL |
| 飞书 | `channels.feishu` | App ID + App Secret |
| 钉钉 | `channels.dingtalk` | Client ID + Client Secret |
| QQ | `channels.qq` | App ID + App Secret |
| MaixCam | `channels.maixcam` | Host + Port |

每个通道都支持 `allowFrom` 白名单配置，确保只有授权用户可以与 Agent 交互。

#### 通道配置示例（Telegram）

```json
{
  "channels": {
    "telegram": {
      "enabled": true,
      "token": "your-telegram-bot-token",
      "allowFrom": ["your-telegram-user-id"]
    }
  }
}
```

---

### 🛠️ 内置工具

Agent 在对话中可以自主调用以下工具：

| 工具 | 说明 |
|------|------|
| `read_file` | 读取文件内容 |
| `write_file` | 写入文件（创建或覆盖） |
| `append_file` | 追加内容到文件 |
| `edit_file` | 基于 diff 的精确文件编辑 |
| `list_dir` | 列出目录内容 |
| `exec` | 执行 Shell 命令 |
| `web_search` | 网络搜索（基于 Brave Search API） |
| `web_fetch` | 抓取网页内容 |
| `message` | 向指定通道发送消息 |
| `cron` | 创建/管理定时任务 |
| `spawn` | 生成子代理执行独立任务 |
| `social_network` | 与其他 Agent 通信（ClawdChat.ai） |

---

### 🧩 技能系统

技能是通过 Markdown 文件定义的 Agent 能力扩展，存放在 `~/.tinyclaw/workspace/skills/` 目录下。

#### 内置技能

| 技能 | 说明 |
|------|------|
| `weather` | 天气查询 |
| `github` | GitHub 仓库和 Issue 操作 |
| `summarize` | 文本摘要 |
| `tmux` | tmux 会话管理 |
| `skill-creator` | 辅助创建新技能 |

#### 自定义技能

在 `~/.tinyclaw/workspace/skills/` 下创建目录，并添加 `SKILL.md` 文件：

```markdown
---
name: my-skill
description: "我的自定义技能"
---

# My Skill

当用户要求执行某某任务时，按照以下步骤操作：
1. ...
2. ...
```

Agent 会在构建上下文时自动加载所有可用技能。

---

### 🌐 网关模式

网关模式是 TinyClaw 的核心运行方式，它会同时启动所有已配置的通道，并在后台运行 Agent 循环：

```bash
java -jar target/tinyclaw-0.1.0.jar gateway
```

启动后，网关会：
1. 加载配置并初始化 LLM 提供商
2. 注册所有内置工具
3. 启动定时任务服务
4. 启动心跳服务（如已启用）
5. 连接所有已启用的消息通道
6. 在后台运行 Agent 消息处理循环

按 `Ctrl+C` 优雅关闭所有服务。

---

### 🗂️ 工作空间结构

初始化后，工作空间目录结构如下：

```
~/.tinyclaw/workspace/
├── AGENTS.md          # Agent 行为指令
├── SOUL.md            # Agent 个性定义
├── USER.md            # 用户信息和偏好
├── IDENTITY.md        # Agent 身份描述
├── memory/
│   └── MEMORY.md      # 长期记忆存储
├── skills/            # 技能插件目录
├── sessions/          # 会话数据
└── cron/
    └── jobs.json      # 定时任务持久化
```

你可以编辑这些 Markdown 文件来自定义 Agent 的行为、个性和记忆。

---

### ⚙️ 完整配置示例

`~/.tinyclaw/config.json`：

```json
{
  "agents": {
    "defaults": {
      "workspace": "~/.tinyclaw/workspace",
      "model": "glm-4.7",
      "maxTokens": 8192,
      "temperature": 0.7,
      "maxToolIterations": 20,
      "heartbeatEnabled": false,
      "restrictToWorkspace": true,
      "commandBlacklist": []
    }
  },
  "providers": {
    "openrouter": {
      "apiKey": "",
      "apiBase": "https://openrouter.ai/api/v1"
    },
    "openai": {
      "apiKey": "",
      "apiBase": ""
    },
    "anthropic": {
      "apiKey": "",
      "apiBase": ""
    },
    "zhipu": {
      "apiKey": "your-key",
      "apiBase": "https://open.bigmodel.cn/api/paas/v4"
    },
    "gemini": {
      "apiKey": "",
      "apiBase": ""
    },
    "groq": {
      "apiKey": "",
      "apiBase": ""
    },
    "vllm": {
      "apiKey": "",
      "apiBase": ""
    },
    "dashscope": {
      "apiKey": "",
      "apiBase": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  },
  "channels": {
    "telegram": {
      "enabled": false,
      "token": "",
      "allowFrom": []
    },
    "discord": {
      "enabled": false,
      "token": "",
      "allowFrom": []
    },
    "feishu": {
      "enabled": false,
      "appId": "",
      "appSecret": "",
      "allowFrom": []
    },
    "dingtalk": {
      "enabled": false,
      "clientId": "",
      "clientSecret": "",
      "allowFrom": []
    }
  },
  "gateway": {
    "host": "0.0.0.0",
    "port": 18790
  },
  "tools": {
    "web": {
      "search": {
        "maxResults": 5
      }
    }
  },
  "socialNetwork": {
    "enabled": false,
    "endpoint": "https://clawdchat.ai/api",
    "agentId": "",
    "apiKey": ""
  }
}
```

---

### 🧪 测试

项目使用 **JUnit 5** + **Mockito** 作为测试框架：

```bash
# 运行所有测试
mvn test

# 运行指定测试类
mvn test -Dtest=TinyClawTest
```

---

### 🛣️ 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven |
| HTTP 客户端 | OkHttp 4.12 |
| JSON 处理 | Jackson 2.17 |
| 日志 | SLF4J + Logback |
| 命令行 | JLine 3.25 |
| Telegram | telegrambots 6.8 |
| Discord | JDA 5.0 |
| 飞书 | oapi-sdk 2.3 |
| 钉钉 | dingtalk SDK 2.0 |
| 定时任务 | cron-utils 9.2 |
| 测试 | JUnit 5.10 + Mockito 5.10 |

---

### 📄 License

[MIT License](https://opensource.org/licenses/MIT) — 自由使用、修改和分发。
