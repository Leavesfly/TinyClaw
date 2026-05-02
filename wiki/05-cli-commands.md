# 05 · CLI 命令参考

> `TinyClaw.java` 注册了 8 个子命令 + `version`。本章逐一说明用法。

---

## 5.1 命令总览

| 命令 | 类 | 作用 |
|------|----|------|
| `onboard` | `OnboardCommand` | 初始化配置与工作空间 |
| `agent` | `AgentCommand` | CLI 直接与 Agent 对话 |
| `gateway` | `GatewayCommand` | 启动网关（通道 + Web 控制台 + Cron） |
| `status` | `StatusCommand` | 查看系统状态与配置 |
| `cron` | `CronCommand` | 管理定时任务 |
| `skills` | `SkillsCommand` | 管理技能插件 |
| `mcp` | `McpCommand` | 管理 MCP 服务器连接 |
| `demo` | `DemoCommand` | 运行内置演示流程 |
| `version` / `-v` / `--version` | 内置 | 显示版本号 |

统一入口：

```bash
java -jar target/tinyclaw-0.1.0.jar <command> [args...]
```

下文所有示例默认已将 JAR 路径别名为 `tinyclaw`。

---

## 5.2 `onboard` — 初始化

```bash
tinyclaw onboard
```

**做了什么**：

- 在 `~/.tinyclaw/config.json` 写入默认 `Config`
- 在 `~/.tinyclaw/workspace/` 下创建：
  - 子目录：`memory/topics/`、`skills/`、`sessions/`、`cron/`
  - 模板文件：`AGENTS.md`、`SOUL.md`、`USER.md`、`IDENTITY.md`、`PROFILE.md`、`memory/MEMORY.md`、`memory/HEARTBEAT.md`

**交互**：若文件已存在会提示是否覆盖，输入 `y` 覆盖，其他取消。

---

## 5.3 `agent` — CLI 对话

```bash
tinyclaw agent [options]
```

| 选项 | 缩写 | 说明 |
|------|------|------|
| `--message <text>` | `-m` | 发送单条消息并退出 |
| `--session <key>` | `-s` | 会话键，默认 `cli:default` |
| `--debug` | `-d` | 启用调试输出 |
| `--no-stream` | - | 关闭流式输出（默认开启） |

**单条模式**：

```bash
tinyclaw agent -m "今天天气如何？请帮我查一下杭州"
```

**交互模式**：

```bash
tinyclaw agent -s cli:work
你: 帮我总结今天的待办
Agent: 我来看看……
你: /new     # 开启新会话
你: exit     # 退出
```

交互模式下：

- 输入 `/new` → 触发 `InboundMessage.COMMAND_NEW_SESSION`，清空当前会话历史
- 输入 `exit` / `quit` 或按 `Ctrl+D` → 退出
- 默认流式输出，可用 `--no-stream` 关闭

---

## 5.4 `gateway` — 启动网关

```bash
tinyclaw gateway [--debug]
```

**启动流程**（由 `GatewayBootstrap` 引导）：

1. 加载 `Config`
2. 初始化 `SecurityGuard`
3. 创建 `MessageBus` + `AgentRuntime`
4. 注册 15 个内置工具
5. 初始化 `MCPManager` 并连接所有已配置的 MCP 服务器
6. 启动 `CronService`
7. 启动 `HeartbeatService`（若启用）
8. 启动 `ChannelManager`（连接所有 `enabled=true` 的通道）
9. 启动 `WebConsoleServer`（默认 `0.0.0.0:18791`）
10. 进入 `AgentRuntime.run()` 主循环

**优雅停机**：按 `Ctrl+C`，会 `drainAndClose` 消息总线、`stopAll` 通道、关闭所有后台服务。

> ℹ️ 即使未配置 LLM Provider，`gateway` 也可以启动，方便通过 Web Console 完成首次配置。

---

## 5.5 `status` — 系统状态

```bash
tinyclaw status
```

输出内容：

- 配置文件路径（存在/缺失）
- 工作空间路径与存在状态
- 当前模型名
- 每个 Provider 的 API Key 配置状态（`✓ 已设置` / `✗ 未设置`）
- Ollama 默认端点

典型输出：

```text
🦞 tinyclaw 状态

配置: ~/.tinyclaw/config.json ✓
工作空间: ~/.tinyclaw/workspace ✓
模型: qwen3.5-plus

API 密钥:
  OpenRouter API: ✗ 未设置
  DashScope API:  ✓ 已设置
  Zhipu API:      ✗ 未设置
  Ollama:         未设置 (默认 http://localhost:11434)
```

---

## 5.6 `cron` — 定时任务

```bash
tinyclaw cron <list|add|remove|enable|disable> [args]
```

### 5.6.1 列出任务

```bash
tinyclaw cron list
```

### 5.6.2 添加任务

```bash
# Cron 表达式（5 字段：分 时 日 月 周）
tinyclaw cron add \
  --name "每日早报" \
  --message "生成今日新闻摘要并发给我" \
  --cron "0 9 * * *"

# 固定间隔（秒）
tinyclaw cron add \
  --name "每小时心跳" \
  --message "检查系统状态" \
  --every 3600

# 指定推送通道（不指定时默认回到触发上下文）
tinyclaw cron add \
  --name "每日日报" \
  --message "总结今天工作" \
  --cron "0 18 * * *" \
  --channel feishu \
  --to "open_id:xxx"
```

### 5.6.3 启用/禁用/删除

```bash
tinyclaw cron enable  <job_id>
tinyclaw cron disable <job_id>
tinyclaw cron remove  <job_id>
```

**存储位置**：`~/.tinyclaw/workspace/cron/jobs.json`（`CronStore` 统一持久化）。

更多细节见 [14 · 定时任务与心跳](14-cron-heartbeat.md)。

---

## 5.7 `skills` — 技能管理

```bash
tinyclaw skills <subcommand> [args]
```

| 子命令 | 作用 |
|--------|------|
| `list` | 列出当前 workspace 中已安装的技能 |
| `list-builtin` | 列出可安装的内置技能 |
| `install-builtin` | 一次性安装所有内置技能到 workspace |
| `install <owner/repo[/path]>` | 从 GitHub 仓库安装技能 |
| `remove <name>` / `uninstall <name>` | 卸载指定技能 |
| `show <name>` | 查看某个技能的 `SKILL.md` 完整内容 |

示例：

```bash
tinyclaw skills list
tinyclaw skills install-builtin
tinyclaw skills install leavesfly/tinyclaw-skills/weather
tinyclaw skills show weather
tinyclaw skills remove weather
```

详见 [13 · 技能系统](13-skills-system.md)。

---

## 5.8 `mcp` — MCP 服务器

```bash
tinyclaw mcp <list|test|tools> [server-name]
```

| 子命令 | 作用 |
|--------|------|
| `list` | 列出 `config.json` 中已配置的 MCP 服务器 |
| `test <name>` | 连接指定服务器，执行 `initialize` 握手并报告 |
| `tools <name>` | 连接指定服务器，列出其 `tools/list` 返回的工具 |

示例：

```bash
tinyclaw mcp list
tinyclaw mcp test filesystem
tinyclaw mcp tools filesystem
```

详见 [10 · MCP 协议集成](10-mcp-integration.md)。

---

## 5.9 `demo` — 一键演示

```bash
tinyclaw demo <mode>
```

| 模式 | 说明 |
|------|------|
| `agent-basic` | 加载配置 → 自动挑选第一个可用 Provider → 发一条固定问题跑完整链路 |

示例：

```bash
tinyclaw demo agent-basic
```

---

## 5.10 `version` / `-v` / `--version`

```bash
tinyclaw version
tinyclaw -v
tinyclaw --version
```

输出：`🦞 tinyclaw v0.1.0`。

---

## 5.11 退出码约定

| 退出码 | 含义 |
|--------|------|
| 0 | 成功 |
| 1 | 参数错误、配置缺失、运行时异常 |

`TinyClaw.main()` 会捕获所有异常，打印到 stderr 并以 1 退出。日志同时写入 `~/.tinyclaw/logs/tinyclaw.log`（Logback 配置）。

---

## 5.12 新增子命令

见 [20 · 扩展开发](20-extending.md)：
1. 继承 `CliCommand`
2. 实现 `name() / description() / execute(args) / printHelp()`
3. 在 `TinyClaw` 静态代码块中 `COMMAND_REGISTRY.put("xxx", XxxCommand::new)`（或运行时 `TinyClaw.registerCommand`）

---

## 5.13 下一步

- 深入 Agent 引擎 → [06 · Agent 引擎](06-agent-engine.md)
- 想了解网关引导流程 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
