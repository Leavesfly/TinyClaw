# 03 · 快速开始

> 5 分钟跑通第一个 TinyClaw Agent。

---

## 3.1 环境要求

| 项目 | 要求 |
|------|------|
| Java | **17 或更高版本**（Maven 打包开启 `--enable-preview`） |
| Maven | 3.x |
| 磁盘 | 约 100MB（含 Maven 依赖） |
| 至少一个 LLM API Key | 推荐 [OpenRouter](https://openrouter.ai/keys)、[智谱 GLM](https://open.bigmodel.cn/) 或 [阿里云 DashScope](https://dashscope.aliyun.com/) |
| 网络 | 能访问对应 LLM Provider 的 API Base |

## 3.2 构建项目

```bash
git clone <repo-url>
cd TinyClaw
mvn clean package -DskipTests
```

产物：`target/tinyclaw-0.1.0.jar`（约 15MB，单 JAR 含所有依赖）。

> 💡 如果本地 Maven 仓库较慢，可配置阿里云镜像 `~/.m2/settings.xml`。

## 3.3 初始化配置（onboard）

```bash
java -jar target/tinyclaw-0.1.0.jar onboard
```

该命令会：

1. 创建主配置 `~/.tinyclaw/config.json`
2. 创建工作空间 `~/.tinyclaw/workspace/`
3. 生成模板文件：
   - `AGENTS.md` — Agent 行为指令
   - `SOUL.md` — Agent 灵魂/个性
   - `USER.md` — 用户画像
   - `IDENTITY.md` — Agent 身份
   - `PROFILE.md` — 配置摘要
   - `memory/MEMORY.md` — 长期记忆索引
   - `memory/HEARTBEAT.md` — 心跳上下文
4. 创建子目录：`memory/topics/`、`skills/`、`sessions/`、`cron/`

> ℹ️ 如果 `config.json` 已存在，命令会提示是否覆盖（输入 `y` 覆盖，其他中止）。

## 3.4 配置 API Key

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
  },
  "agent": {
    "model": "qwen3.5-plus",
    "provider": "dashscope"
  }
}
```

**关键字段解读**：

- `providers.{name}.apiKey` — 对应提供商的密钥；环境变量优先（如 `DASHSCOPE_API_KEY`）
- `providers.{name}.apiBase` — API 地址，一般保持默认
- `agent.model` — 使用的模型名（如 `qwen3.5-plus`、`glm-4`、`gpt-4o`）
- `agent.provider` — 指定 Provider（与 model 对应）；也可由 `ModelsConfig` 反查

更多字段见 [04 · 配置指南](04-configuration.md)。

## 3.5 发起第一次对话

### 方式一：单条消息

```bash
java -jar target/tinyclaw-0.1.0.jar agent -m "你好，介绍一下你自己"
```

### 方式二：交互式

```bash
java -jar target/tinyclaw-0.1.0.jar agent
```

进入交互界面后，输入消息回车即可。内置快捷命令：

| 输入 | 含义 |
|------|------|
| `/new` | 开启一个新会话（清空历史） |
| `exit` / `quit` / Ctrl+D | 退出 |

### 方式三：指定 session key

```bash
java -jar target/tinyclaw-0.1.0.jar agent -s cli:work -m "总结一下今天的工作"
```

同一 `session key` 的对话会被合并到一个会话历史中，重启后也能恢复。

## 3.6 启动网关模式（多通道 + Web 控制台）

```bash
java -jar target/tinyclaw-0.1.0.jar gateway
```

网关启动后会：

1. 加载 `config.json` + 工作空间
2. 初始化 `SecurityGuard`（工作空间沙箱 + 命令黑名单）
3. 注册 15 个内置工具
4. 初始化 MCP 服务器连接（如已配置）
5. 启动定时任务服务 `CronService`
6. 启动心跳服务 `HeartbeatService`（若 `agent.heartbeatEnabled=true`）
7. 连接所有 `enabled=true` 的消息通道
8. 启动 Web 控制台（默认 `http://localhost:18791`）
9. 运行 `AgentRuntime.run()` 主循环

停止：按 `Ctrl+C`，系统会 `drainAndClose` 消息总线，优雅关闭所有服务。

访问 Web 控制台：浏览器打开 `http://localhost:18791`。

## 3.7 Demo 模式（一键演示）

不想自己配通道也可以直接跑内置演示：

```bash
java -jar target/tinyclaw-0.1.0.jar demo agent-basic
```

其他 demo：

```bash
java -jar target/tinyclaw-0.1.0.jar demo               # 列出所有 demo
java -jar target/tinyclaw-0.1.0.jar demo collaborate   # 多 Agent 协同演示
java -jar target/tinyclaw-0.1.0.jar demo workflow      # 工作流演示
```

## 3.8 验证环境

```bash
java -jar target/tinyclaw-0.1.0.jar status
```

会输出：

- 当前配置文件路径
- Agent 当前模型、provider、workspace
- 已启用的通道列表
- MCP 服务器状态
- Cron 任务数量
- 技能数量

## 3.9 常见起步问题

| 现象 | 解决方案 |
|------|----------|
| `⚠️ LLM Provider 未配置` | 在 `config.json` 的 `providers.{name}.apiKey` 填入密钥，或设置环境变量 |
| `java.lang.UnsupportedClassVersionError` | JDK 版本低于 17，请升级 |
| 交互式模式卡住 | 确认终端支持 ANSI；或改用 `-m` 一次性消息模式 |
| 通道连接失败 | 检查 `channels.{name}.enabled` / `token` / `allowFrom`，并查看 `~/.tinyclaw/logs/tinyclaw.log` |
| `/new` 不生效 | 确认你是在交互模式输入；API 调用需设置 `command=new_session` |

更多排查见 [21 · FAQ](21-faq-troubleshooting.md)。

## 3.10 下一步

- 想了解每个配置字段 → [04 · 配置指南](04-configuration.md)
- 想知道所有 CLI 命令 → [05 · CLI 命令](05-cli-commands.md)
- 想让 Agent 接入飞书/钉钉 → [07 · 消息总线与通道](07-message-bus-and-channels.md) + `docs/feishu-guide.md` / `docs/dingtalk-guide.md`
- 想玩多 Agent 协同 → [11 · 多 Agent 协同](11-multi-agent-collaboration.md)
