<div align="center">

# 🦞 TinyClaw

**Ultra-lightweight Personal AI Assistant** — Built with Java, featuring multi-model, multi-channel, multi-agent collaboration, and self-evolution capabilities

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.x-blue)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![Version](https://img.shields.io/badge/Version-0.1.0-brightgreen)]()

[中文 README](./README.md)

</div>

---

### ✨ Features

- **🤖 Multi-Model Support** — Connect to OpenRouter, OpenAI, Anthropic, Zhipu GLM, Gemini, Aliyun DashScope, Groq, Ollama and more
- **💬 Multi-Channel Messaging** — Simultaneously connect to Telegram, Discord, WhatsApp, Feishu, DingTalk, QQ, MaixCam and more
- **🤝 Multi-Agent Collaboration** — 7 collaboration modes (debate/team/roleplay/consensus/hierarchy/workflow/dynamic) with built-in workflow engine
- **🧬 Self-Evolution** — 3 automatic Prompt optimization strategies (Textual Gradient/OPRO/Self-Refine) + memory evolution + feedback collection
- **🔌 MCP Protocol** — Full MCP client supporting SSE, Stdio, and Streamable HTTP transports
- **🛠️ Rich Built-in Tools** — File I/O, shell execution, web search, web fetch, cron jobs, sub-agents, token stats and 15 tools total
- **🧩 Skill Plugin System** — Define skills in Markdown, semantic search matching, install from GitHub, agent can self-create and improve skills
- **⏰ Cron Task Engine** — Supports cron expressions, fixed intervals, and one-time scheduling
- **🧠 Memory & Context** — Long-term memory storage, session summarization, modular context building
- **💓 Heartbeat Service** — Periodic autonomous thinking to keep the agent "alive"
- **🎤 Voice Transcription** — Aliyun DashScope Paraformer integration for automatic speech-to-text
- **🔒 Security Sandbox** — Workspace restriction + command blacklist + web security middleware for production-grade protection
- **🌐 Agent Social Network** — Connect to ClawdChat.ai for inter-agent communication
- **🖥️ Web Console** — Built-in Web UI with 16 REST APIs for managing agent status, sessions, models, skills and more
- **🎬 Demo Mode** — One-click demonstration of core features

![TinyClaw Logo](src/main/resources/tinyclaw.png)

---

### 📦 Project Architecture

```
src/main/java/io/leavesfly/tinyclaw/
├── TinyClaw.java                    # Application entry, command registration & dispatch
├── agent/                           # Agent core engine
│   ├── AgentLoop.java               #   Lifecycle management & message consumption loop
│   ├── MessageRouter.java           #   Message routing (user/system/command)
│   ├── ProviderManager.java         #   LLM Provider management & hot-reload
│   ├── LLMExecutor.java             #   LLM invocation & tool iteration loop
│   ├── ContextBuilder.java          #   Modular context building
│   ├── SessionSummarizer.java       #   Session summarization & context compression
│   ├── context/                     #   Context sections (Identity/Bootstrap/Tools/Skills/Memory)
│   ├── evolution/                   #   Self-evolution engine (PromptOptimizer/FeedbackManager/MemoryEvolver)
│   └── collaboration/               #   Multi-agent orchestration (7 modes + workflow engine)
├── bus/                             # Message bus (pub/sub, inbound/outbound queues)
├── channels/                        # Channel adapters (7 platforms)
├── cli/                             # CLI interface (8 commands)
├── config/                          # Configuration models & loader (11 config classes)
├── cron/                            # Cron task engine
├── heartbeat/                       # Heartbeat service
├── logger/                          # Structured logging wrapper
├── mcp/                             # MCP protocol integration (3 transport types)
├── providers/                       # LLM invocation abstraction (HTTPProvider + StreamEvent)
├── security/                        # Security sandbox (SecurityGuard)
├── session/                         # Session management & persistence
├── skills/                          # Skill system (loader/registry/searcher/installer)
├── tools/                           # Agent toolset (15 built-in tools + MCP bridge)
├── util/                            # Utility classes
├── voice/                           # Voice transcription (AliyunTranscriber)
└── web/                             # Web console (16 REST API handlers)
```

---

### 🚀 Getting Started

#### Requirements

- **Java 17** or later
- **Maven 3.x**
- At least one LLM API key (recommended: [OpenRouter](https://openrouter.ai/keys) or [Zhipu GLM](https://open.bigmodel.cn/))

#### 1. Build the project

```bash
git clone <repo-url>
cd TinyClaw
mvn clean package -DskipTests
```

After the build, the executable JAR will be at `target/tinyclaw-0.1.0.jar`.

#### 2. Initialize configuration

```bash
java -jar target/tinyclaw-0.1.0.jar onboard
```

This command will:
- Create the default config file at `~/.tinyclaw/config.json`
- Create the workspace directory at `~/.tinyclaw/workspace/`
- Generate template files (`AGENTS.md`, `SOUL.md`, `USER.md`, `IDENTITY.md`)

#### 3. Configure API keys

Edit `~/.tinyclaw/config.json` and fill in your API keys:

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

#### 4. Start chatting

```bash
# Single message mode
java -jar target/tinyclaw-0.1.0.jar agent -m "Hi, introduce yourself."

# Interactive mode
java -jar target/tinyclaw-0.1.0.jar agent
```

---

### 📖 Command Reference

| Command | Description | Example |
|---------|-------------|----------|
| `onboard` | Initialize config and workspace | `tinyclaw onboard` |
| `agent` | Interact with the agent directly | `tinyclaw agent -m "Hello"` |
| `gateway` | Start gateway service (connect all channels) | `tinyclaw gateway` |
| `status` | Show system status and configuration | `tinyclaw status` |
| `cron` | Manage cron jobs | `tinyclaw cron list` |
| `skills` | Manage skill plugins | `tinyclaw skills list` |
| `mcp` | Manage MCP servers | `tinyclaw mcp list` |
| `demo` | Run built-in demo flows | `tinyclaw demo agent-basic` |
| `version` | Show version info | `tinyclaw version` |

#### Agent command options

```bash
tinyclaw agent [options]

  -m, --message <text>    Send a single message and exit
  -s, --session <key>     Specify session key (default: cli:default)
  -d, --debug             Enable debug mode
```

#### Cron command options

```bash
tinyclaw cron list                          # List all cron jobs
tinyclaw cron add --name "Daily Report" \
  --message "Generate today's work summary" \
  --cron "0 18 * * *"                       # Every day at 18:00
tinyclaw cron add --name "Heartbeat" \
  --message "Check system status" \
  --every 3600                              # Every hour
tinyclaw cron remove <job_id>               # Remove a job
tinyclaw cron enable <job_id>               # Enable a job
tinyclaw cron disable <job_id>              # Disable a job
```

#### Skills command options

```bash
tinyclaw skills list                        # List installed skills
tinyclaw skills list-builtin                # List built-in skills
tinyclaw skills install-builtin             # Install all built-in skills
tinyclaw skills install owner/repo/skill    # Install from GitHub
tinyclaw skills show <name>                 # Show skill details
tinyclaw skills remove <name>               # Remove a skill
```

---

### 🔌 Supported LLM Providers

| Provider | Config key | Description |
|----------|------------|-------------|
| [OpenRouter](https://openrouter.ai/) | `providers.openrouter` | Aggregated multi-model gateway, recommended |
| [OpenAI](https://platform.openai.com/) | `providers.openai` | GPT series models |
| [Anthropic](https://www.anthropic.com/) | `providers.anthropic` | Claude series models |
| [Zhipu GLM](https://open.bigmodel.cn/) | `providers.zhipu` | GLM-4 series, China-friendly |
| [Google Gemini](https://ai.google.dev/) | `providers.gemini` | Gemini series models |
| [Groq](https://groq.com/) | `providers.groq` | Ultra-fast inference |
| [Ollama](https://ollama.ai/) | `providers.ollama` | Self-hosted open-source models |
| [Aliyun DashScope](https://dashscope.aliyun.com/) | `providers.dashscope` | Qwen (Tongyi Qianwen) models |

All providers are adapted via a unified `HTTPProvider` using OpenAI-compatible API format. Switching models only requires updating configuration.

---

### 💬 Supported Channels

| Channel | Config key | Credentials |
|---------|------------|-------------|
| Telegram | `channels.telegram` | Bot Token |
| Discord | `channels.discord` | Bot Token |
| WhatsApp | `channels.whatsapp` | Bridge URL |
| Feishu | `channels.feishu` | App ID + App Secret |
| DingTalk | `channels.dingtalk` | Client ID + Client Secret |
| QQ | `channels.qq` | App ID + App Secret |
| MaixCam | `channels.maixcam` | Host + Port |

Each channel supports an `allowFrom` whitelist so that only authorized users can interact with the agent.

---

### 🛠️ Built-in Tools

The agent can autonomously call the following tools during conversations:

| Tool | Description | Security |
|------|-------------|----------|
| `read_file` | Read file content | ✓ Workspace restriction |
| `write_file` | Write file (create or overwrite) | ✓ Workspace restriction |
| `append_file` | Append content to file | ✓ Workspace restriction |
| `edit_file` | Precise file edit based on diff | ✓ Workspace restriction |
| `list_dir` | List directory content | ✓ Workspace restriction |
| `exec` | Execute shell command | ✓ Command blacklist + workspace restriction |
| `web_search` | Web search (Brave Search API) | - |
| `web_fetch` | Fetch web page content | - |
| `message` | Send messages to specific channels | - |
| `cron` | Create/manage cron jobs | - |
| `spawn` | Spawn sub-agents for isolated tasks | - |
| `collaborate` | Launch multi-agent collaboration (7 modes) | - |
| `social_network` | Communicate with other agents (ClawdChat.ai) | - |
| `skills` | Manage and query skill plugins | - |
| `token_usage` | Query token usage statistics | - |

Additionally, tools from MCP servers are automatically registered and directly callable by the LLM.

---

### 🤝 Multi-Agent Collaboration

TinyClaw includes a complete multi-agent orchestration system, triggered via the `collaborate` tool:

| Mode | Description |
|------|-------------|
| `debate` | Pro/con argumentation for trade-off analysis |
| `team` | Task decomposition with parallel/sequential execution |
| `roleplay` | Multi-role dialogue simulation and scenario rehearsal |
| `consensus` | Discussion followed by voting to reach consensus |
| `hierarchy` | Hierarchical reporting and layered decision-making |
| `workflow` | Multi-step workflows with LLM-generated workflow definitions |
| `dynamic` | Router Agent dynamically selects the next speaker |

The workflow engine supports 6 node types: SINGLE / PARALLEL / SEQUENTIAL / CONDITIONAL / LOOP / AGGREGATE.

---

### 🧬 Self-Evolution

TinyClaw includes a self-evolution engine that enables the agent to continuously learn and improve:

- **Automatic Prompt Optimization**: 3 strategies (Textual Gradient / OPRO / Self-Refine) to automatically improve system prompts
- **Memory Evolution**: Extract long-term memories from conversations, retaining important information across sessions
- **Feedback Collection**: Supports explicit ratings, text feedback, and implicit signals

---

### 🔌 MCP Protocol Integration

TinyClaw implements a complete MCP (Model Context Protocol) client:

| Transport | Use Case |
|-----------|----------|
| SSE | Remote HTTP servers (Server-Sent Events) |
| Stdio | Local process communication (stdin/stdout) |
| Streamable HTTP | Remote HTTP servers (streaming HTTP) |

MCP server tools are automatically registered into the tool system. Configuration example:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
      "timeout": 30
    }
  }
}
```

---

### 🔒 Security

TinyClaw provides multi-layer security protection via **SecurityGuard**:

- **Workspace Sandbox**: All file operations restricted to the workspace directory
- **Command Blacklist**: Blocks dangerous commands like `rm -rf`, `mkfs`, `sudo`
- **Channel Whitelist**: Each channel supports `allowFrom` configuration
- **Web Security Middleware**: Authentication and CORS protection
- **Path Normalization**: Prevents path traversal attacks

---

### 🖥️ Web Console

In gateway mode, visit `http://localhost:18791` to access the web console:

- Real-time chat (SSE streaming support)
- Session management and history
- Model switching (runtime hot-reload)
- Provider management
- Channel status monitoring
- Skill management
- Cron job management
- MCP server management
- File browsing and upload
- Token usage statistics
- User feedback collection

---

### 🧩 Skill System

Skills are agent capability extensions defined in Markdown:

```markdown
---
name: my-skill
description: "My custom skill"
---

# My Skill

When the user asks to perform a certain task, follow these steps:
1. ...
2. ...
```

- Load from workspace / global / builtin directories
- Install community skills from GitHub
- **Semantic search matching** — only inject relevant skills based on user input
- Agent can self-create, edit, and manage skills via the `skills` tool

---

### 🌐 Gateway Mode

```bash
java -jar target/tinyclaw-0.1.0.jar gateway
```

Once started, the gateway will:
1. Load configuration and initialize LLM providers
2. Initialize security protection (SecurityGuard)
3. Register all built-in tools
4. Initialize MCP server connections
5. Start the cron service
6. Start the heartbeat service (if enabled)
7. Connect all enabled channels
8. Start the web console
9. Run the agent message-processing loop in the background

Press `Ctrl+C` to gracefully shut down all services.

---

### 🗂️ Workspace Layout

```
~/.tinyclaw/
├── config.json              # Main configuration file
├── workspace/
│   ├── AGENTS.md            # Agent behavior instructions
│   ├── SOUL.md              # Agent personality and values
│   ├── USER.md              # User profile and preferences
│   ├── IDENTITY.md          # Agent identity description
│   ├── memory/              # Long-term memory
│   │   ├── MEMORY.md
│   │   └── HEARTBEAT.md
│   ├── sessions/            # Session persistence
│   ├── skills/              # User skills
│   ├── cron/                # Cron jobs
│   │   └── jobs.json
│   ├── evolution/           # Evolution data
│   │   └── prompts/         # Prompt variants
│   └── collaboration/       # Collaboration records
```

---

### 🎬 Demo

```bash
# One-click demo mode
tinyclaw demo agent-basic

# Local CLI assistant
tinyclaw agent

# Gateway + channel bot
tinyclaw gateway

# Web console
open http://localhost:18791

# MCP server management
tinyclaw mcp list
```

---

### 🧪 Tests

```bash
mvn test                        # Run all tests
mvn test -Dtest=TinyClawTest    # Run a specific test class
```

---

### 🛣️ Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 17 |
| Build | Maven |
| HTTP Client | OkHttp 4.12 |
| JSON Processing | Jackson 2.17 |
| Logging | SLF4J + Logback |
| CLI | JLine 3.25 |
| Cron | cron-utils 9.2 |
| Environment | dotenv-java 3.0 |
| Testing | JUnit 5.10 + Mockito 5.10 |

---

### 📄 License

[MIT License](https://opensource.org/licenses/MIT) — Free to use, modify and distribute.
