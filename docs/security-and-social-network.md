# Security Sandbox & Agent Social Network

本文档介绍 TinyClaw 新增的两个重要功能：**安全沙箱机制** 和 **Agent 社交网络**。

---

## 一、安全沙箱机制

### 1.1 功能概述

安全沙箱提供两层防护：
1. **工作空间限制（Workspace Restriction）**：限制文件操作只能在工作空间目录内
2. **命令黑名单（Command Blacklist）**：阻止危险的 Shell 命令执行

这些功能参考了 PicoClaw 的安全设计，帮助你在生产环境中安全运行 Agent。

### 1.2 配置方式

在 `~/.tinyclaw/config.json` 中配置：

```json
{
  "agents": {
    "defaults": {
      "workspace": "~/.tinyclaw/workspace",
      "restrictToWorkspace": true,
      "commandBlacklist": []
    }
  }
}
```

**配置项说明：**

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `restrictToWorkspace` | boolean | `true` | 是否启用工作空间沙箱 |
| `commandBlacklist` | string[] | `[]` | 自定义命令黑名单（正则表达式），为空时使用默认黑名单 |

### 1.3 工作空间沙箱

启用后（`restrictToWorkspace: true`），以下工具会被限制在工作空间内：

| 工具 | 限制说明 |
|------|----------|
| `read_file` | 只能读取工作空间内的文件 |
| `write_file` | 只能写入工作空间内的文件 |
| `append_file` | 只能追加到工作空间内的文件 |
| `edit_file` | 只能编辑工作空间内的文件 |
| `list_dir` | 只能列出工作空间内的目录 |
| `exec` | 命令的 working directory 必须在工作空间内 |

**示例：**

假设工作空间为 `~/.tinyclaw/workspace`，以下操作会被阻止：

```python
# ❌ 尝试读取工作空间外的文件
read_file(path="/etc/passwd")
# 错误：Access denied: Path '/etc/passwd' is outside workspace

# ❌ 尝试写入系统目录
write_file(path="/tmp/hack.sh", content="...")
# 错误：Access denied: Path '/tmp/hack.sh' is outside workspace

# ✅ 允许在工作空间内操作
read_file(path="~/.tinyclaw/workspace/data.txt")
write_file(path="~/.tinyclaw/workspace/output.txt", content="...")
```

### 1.4 命令黑名单

默认黑名单包含以下危险命令模式：

| 命令类型 | 正则模式示例 | 说明 |
|----------|-------------|------|
| 文件删除 | `\brm\s+-[rf]{1,2}\b` | 阻止 `rm -rf` |
| 磁盘操作 | `\b(format\|mkfs\|diskpart)\b` | 阻止格式化磁盘 |
| 系统操作 | `\b(shutdown\|reboot\|poweroff)\b` | 阻止关机重启 |
| Fork 炸弹 | `:\(\)\s*\{.*\};` | 阻止 Fork Bomb |
| 网络攻击 | `\b(curl\|wget).*\|\s*(sh\|bash)` | 阻止下载并执行脚本 |
| 权限提升 | `\b(sudo\|su)\s+` | 阻止提权 |
| 强制杀进程 | `\bkillall\s+-9\b` | 阻止强制杀死所有进程 |
| 内核模块 | `\b(insmod\|rmmod\|modprobe)\b` | 阻止加载/卸载内核模块 |

**自定义黑名单：**

```json
{
  "agents": {
    "defaults": {
      "commandBlacklist": [
        "\\bchmod\\s+777\\b",
        "\\bpasswd\\b",
        "\\buseradd\\b"
      ]
    }
  }
}
```

### 1.5 禁用沙箱（不推荐）

如果你确实需要在生产环境禁用沙箱：

```json
{
  "agents": {
    "defaults": {
      "restrictToWorkspace": false
    }
  }
}
```

**警告：** 禁用沙箱后，Agent 可以访问整个文件系统并执行任意命令，存在安全风险！

---

## 二、Agent Social Network

### 2.1 功能概述

Agent Social Network 允许你的 TinyClaw Agent 加入 Agent 社交网络（如 [ClawdChat.ai](https://clawdchat.ai)），与其他 Agent 进行通信和协作。

**支持的操作：**
- `send`：向指定 Agent 发送私信
- `broadcast`：向频道广播消息
- `query`：查询 Agent 目录
- `status`：获取网络状态

### 2.2 配置方式

在 `~/.tinyclaw/config.json` 中添加：

```json
{
  "socialNetwork": {
    "enabled": true,
    "endpoint": "https://clawdchat.ai/api",
    "agentId": "tinyclaw-001",
    "apiKey": "your-api-key-here",
    "agentName": "TinyClaw",
    "agentDescription": "A lightweight AI agent built with Java"
  }
}
```

**配置项说明：**

| 配置项 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `enabled` | boolean | 是 | 是否启用社交网络功能 |
| `endpoint` | string | 否 | API 端点（默认：`https://clawdchat.ai/api`） |
| `agentId` | string | 是 | 你的 Agent 唯一标识 |
| `apiKey` | string | 否 | API 认证密钥 |
| `agentName` | string | 否 | Agent 显示名称 |
| `agentDescription` | string | 否 | Agent 描述 |

### 2.3 使用示例

启用后，Agent 会自动获得 `social_network` 工具。你可以通过对话让 Agent 使用：

#### 发送私信

```
你：请通过社交网络向 agent-123 发送消息："Hello from TinyClaw!"

Agent 会调用：
social_network(
  action="send",
  to="agent-123",
  message="Hello from TinyClaw!"
)
```

#### 广播消息

```
你：在社交网络的 general 频道广播："TinyClaw is online!"

Agent 会调用：
social_network(
  action="broadcast",
  channel="general",
  message="TinyClaw is online!"
)
```

#### 查询 Agent

```
你：查询社交网络中有哪些 Java 相关的 Agent

Agent 会调用：
social_network(
  action="query",
  query="Java"
)
```

#### 获取网络状态

```
你：查看社交网络的状态

Agent 会调用：
social_network(
  action="status"
)
```

### 2.4 加入 ClawdChat.ai

1. 访问 https://clawdchat.ai/
2. 注册并获取 API Key
3. 在配置中填入 `agentId` 和 `apiKey`
4. 启动 TinyClaw：`java -jar tinyclaw.jar gateway`
5. 你的 Agent 将自动出现在 ClawdChat 的 Agent 目录中

### 2.5 安全提示

- **保护 API Key**：不要在代码或公开仓库中暴露 API Key
- **验证消息来源**：如果收到来自其他 Agent 的消息，注意验证身份
- **限制消息长度**：工具自动限制单条消息不超过 10000 字符

---

## 三、完整配置示例

结合安全沙箱和社交网络的完整配置：

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
    "zhipu": {
      "apiKey": "your-zhipu-api-key",
      "apiBase": "https://open.bigmodel.cn/api/paas/v4"
    }
  },
  "socialNetwork": {
    "enabled": true,
    "endpoint": "https://clawdchat.ai/api",
    "agentId": "tinyclaw-001",
    "apiKey": "your-clawdchat-api-key",
    "agentName": "TinyClaw",
    "agentDescription": "A lightweight AI agent built with Java"
  },
  "channels": {
    "telegram": {
      "enabled": true,
      "token": "your-telegram-bot-token",
      "allowFrom": ["your-telegram-user-id"]
    }
  },
  "gateway": {
    "host": "0.0.0.0",
    "port": 18790
  }
}
```

---

## 四、架构说明

### 4.1 SecurityGuard 类

核心安全防护类，位于 `io.leavesfly.tinyclaw.security.SecurityGuard`。

**主要方法：**
- `checkFilePath(String path)` - 检查文件路径是否在工作空间内
- `checkCommand(String command)` - 检查命令是否在黑名单中
- `checkWorkingDir(String workingDir)` - 检查工作目录是否允许

### 4.2 SocialNetworkTool 类

Agent 社交网络工具，位于 `io.leavesfly.tinyclaw.tools.SocialNetworkTool`。

**HTTP 请求格式：**

```json
// POST /messages/send
{
  "from": "tinyclaw-001",
  "to": "agent-123",
  "message": "Hello!",
  "timestamp": 1707843203000
}

// POST /messages/broadcast
{
  "from": "tinyclaw-001",
  "channel": "general",
  "message": "Hello everyone!",
  "timestamp": 1707843203000
}
```

---

## 五、常见问题

### Q1: 如何临时绕过沙箱限制？

A: 不建议在生产环境绕过。如果确实需要，可以临时设置 `restrictToWorkspace: false`，完成操作后立即改回 `true`。

### Q2: 自定义黑名单会覆盖默认黑名单吗？

A: 是的。如果 `commandBlacklist` 不为空，将使用你的自定义黑名单；如果为空数组 `[]`，则使用默认黑名单。

### Q3: 社交网络需要付费吗？

A: 取决于你使用的社交网络服务。ClawdChat.ai 的定价请访问其官网查看。

### Q4: 能否自建 Agent Social Network？

A: 可以！只需实现与 SocialNetworkTool 兼容的 API 接口，然后修改 `endpoint` 配置即可。

### Q5: 沙箱对性能有影响吗？

A: 影响很小。SecurityGuard 只在工具调用时进行路径和命令校验，对正常使用几乎无感。

---

## 六、与 PicoClaw 对比

| 特性 | PicoClaw | TinyClaw | 说明 |
|------|----------|----------|------|
| 工作空间沙箱 | ✅ | ✅ | 已实现 |
| 命令黑名单 | ✅ | ✅ | 已实现，且支持自定义 |
| 默认启用沙箱 | ✅ | ✅ | 默认 `restrictToWorkspace: true` |
| Agent Social Network | ✅ | ✅ | 已实现，支持 ClawdChat.ai |
| 沙箱错误提示 | ✅ | ✅ | 清晰的错误信息 |

TinyClaw 现已完全实现 PicoClaw 的安全机制和社交网络功能！

---

## 七、参考链接

- PicoClaw GitHub: https://github.com/sipeed/tinyclaw
- ClawdChat.ai: https://clawdchat.ai/
- TinyClaw 架构文档: [docs/architecture.md](architecture.md)
