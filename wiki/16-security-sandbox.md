# 16 · 安全沙箱

> `security/` 包：让 Agent 不会把电脑搞坏。

---

## 16.1 总体思路

Agent 主动调用文件和 Shell 工具是"**超能力**"，但也是"**灾难源**"。TinyClaw 用 `SecurityGuard` 做前置拦截，核心三层：

1. **工作空间沙箱**：所有文件操作限制在 workspace 内，防越权
2. **受保护路径**：无论是否启用 workspace 限制，特定路径（SSH 密钥、密码文件等）始终阻止
3. **命令黑名单**：执行 Shell 前正则匹配危险命令

并辅以 `SecurityPolicyLoader`（工具级细粒度策略）和钩子 `PRE_TOOL_USE`（自定义拦截）。

---

## 16.2 SecurityGuard — 核心守卫

### 16.2.1 构造参数

```java
new SecurityGuard(workspace, restrictToWorkspace);
new SecurityGuard(workspace, restrictToWorkspace, customBlacklist);
```

- `workspace`：工作空间绝对路径（会被 `toRealPath` 规范化）
- `restrictToWorkspace`：是否开启沙箱（生产**必须**开启）
- `customBlacklist`：可选，扩展或覆盖命令黑名单模式

日志在启动时打印：

```
SecurityGuard initialized workspace=... restrictToWorkspace=true blacklistRules=32 protectedPaths=8
```

### 16.2.2 核心 API

| 方法 | 作用 | 返回 |
|------|------|------|
| `checkFilePath(String path)` | 校验文件路径 | `null`=放行，非空=错误消息 |
| `checkCommand(String command)` | 校验 Shell 命令 | 同上 |
| `checkWorkingDir(String cwd)` | 校验 `exec` 工具的 `cwd` | 同上 |
| `getWorkspace()` | 获取 workspace | - |
| `isRestrictToWorkspace()` | 查询沙箱状态 | - |
| `getBlacklistPatterns()` | 获取当前黑名单 | - |

设计原则：**返回错误消息**而不是抛异常，让调用方决定是拒绝工具调用还是降级处理。

---

## 16.3 工作空间沙箱

### 16.3.1 路径解析

`resolveRealPath(Path)` 使用 `Files.toRealPath()`，会：

1. 展开符号链接（防止 `ln -s /etc/passwd ws/a` 绕过）
2. 规范化 `..` 与 `.`
3. 若文件不存在，回退到父目录解析并拼接文件名

### 16.3.2 判定逻辑

```text
input path
   │
   ▼
resolvedPath = resolveRealPath(path)
   │
   ▼
1. 先检查 protectedPaths（即使 restrictToWorkspace=false 也拦）
   ├── 命中 → 拒绝
   └── 否则继续
   │
   ▼
2. 若 restrictToWorkspace=false → 放行
   │
   ▼
3. resolvedPath.startsWith(workspaceRealPath) ?
   ├── 是 → 放行
   └── 否 → 拒绝："Access denied: Path ... is outside workspace ..."
```

### 16.3.3 为什么用 realPath 而不是字符串前缀

以下都会被正确拦截：

```text
workspace/../etc/passwd                 ← .. 解析
workspace/link -> /etc/passwd           ← 符号链接
/tmp/../Users/.ssh/id_rsa               ← 协议相对
```

纯字符串前缀无法防上述攻击向量。

---

## 16.4 受保护路径（Protected Paths）

无论是否开启 workspace 限制，以下路径一律拒绝写入（读取也会告警）：

| 类别 | 路径样例 |
|------|----------|
| SSH | `~/.ssh/`、`~/.ssh/id_rsa*`、`~/.ssh/authorized_keys` |
| 凭据 | `~/.aws/credentials`、`~/.gnupg/`、`~/.kube/config` |
| 系统 | `/etc/passwd`、`/etc/shadow`、`/etc/sudoers` |
| 敏感 | `~/.bash_history`、`~/.zsh_history` |

由 `buildDefaultProtectedPaths()` 构造，支持通过 `SecurityPolicyLoader` 在 `security.yaml` 扩展。

---

## 16.5 命令黑名单

### 16.5.1 默认规则（节选）

使用正则模式匹配完整命令行：

```text
^\s*rm\s+.*-[rRf]            # rm -rf
^\s*sudo(\s|$)               # sudo 任何命令
^\s*mkfs\b                   # 格式化
^\s*dd\s+.*of=\s*/dev/       # dd 写块设备
^\s*:(){.*:|:&};:            # fork bomb
^\s*chmod\s+.*777            # 777 权限
^\s*curl\s+.*\|\s*(sh|bash)  # curl | sh 管道执行
^\s*wget\s+.*\|\s*(sh|bash)
.*(>\s*/dev/sda|>>\s*/dev/sda)
^\s*shutdown\b
^\s*reboot\b
# ...
```

### 16.5.2 扩展方式

在 `config.json`：

```json
{
  "tools": {
    "exec": {
      "blacklistExtras": [
        "^\\s*git\\s+push.*--force",
        "^\\s*docker\\s+system\\s+prune"
      ]
    }
  }
}
```

`AgentRuntime` 构造时把列表传给 `SecurityGuard`，与默认规则合并。

### 16.5.3 白名单？

**有意不提供**。安全策略原则：默认拒绝的白名单等价于把风险决策外包给用户，容易出错。推荐做法：

- 想限制更严：扩展黑名单 + 降低 `exec` 权限
- 想精细控制：用 **SecurityPolicyLoader** 做"允许工具 × 允许模式"的组合策略

---

## 16.6 受限工作目录

`exec` 工具允许传 `cwd`，`SecurityGuard.checkWorkingDir(cwd)` 的判定：

- `cwd == null` → 默认为 workspace 根
- 否则 → 同 `checkFilePath`，必须在 workspace 内

拒绝示例：`cwd="/"`、`cwd="/etc"`、`cwd="~/"`（若不在 workspace 内）。

---

## 16.7 SecurityPolicyLoader — 工具级策略

位置：`security/SecurityPolicyLoader.java`（示意）

支持的策略文件（可选，`workspace/security.yaml`）：

```yaml
tools:
  exec:
    mode: restricted          # off / default / restricted / deny
    allowCommands:
      - "^\\s*ls\\b"
      - "^\\s*pwd\\b"
      - "^\\s*cat\\s+[^/]"
    denyCommands:
      - "^\\s*npm\\s+publish"
  write_file:
    mode: default
    denyPaths:
      - ".*\\.env$"
```

- `restricted` 模式：**只允许** `allowCommands` 命中的命令
- `default`：默认规则 + 额外 `denyCommands`
- `deny`：直接禁用该工具

通过钩子 `PRE_TOOL_USE` 生效，见 [19 · Hooks](19-hooks.md)。

---

## 16.8 Agent 调用视角

```text
Agent: 我调用 exec, command="rm -rf /tmp/cache"
       │
       ▼
ReActExecutor
       │
       ▼
Hook PRE_TOOL_USE (optional policy check)
       │
       ▼
ToolRegistry.execute("exec", args)
       │
       ▼
ExecTool.execute
       ├── SecurityGuard.checkCommand("rm -rf /tmp/cache")
       │      └── 命中 ^\s*rm\s+.*-[rRf] → 返回 "Blocked: dangerous rm -rf"
       ├── 抛 ToolException("Blocked: ...")
       └── 记录日志
       │
       ▼
tool_result 消息（错误）回传给 LLM
       │
       ▼
LLM 收到 "Blocked" 提示，尝试换一种做法或告知用户
```

错误消息**回传给 LLM**，让它知道为什么被拒，有机会自我修正。

---

## 16.9 日志与审计

所有安全拦截事件都写入 `security` 命名日志：

```
WARN  security  File path blocked (outside workspace) path=/etc/passwd resolved=/etc/passwd workspace=/Users/xx/workspace
WARN  security  Command blocked command="rm -rf ..." matched="^\\s*rm\\s+.*-[rRf]"
```

Web 控制台 `LogsHandler` 页面可过滤 `security` 日志查看。

---

## 16.10 注意事项与已知局限

| 项 | 说明 |
|----|------|
| **非 OS 级沙箱** | TinyClaw 是进程内 Java 拦截，若攻击者能直接运行 JVM 外命令，拦不住；要做容器化部署 |
| **正则黑名单不完备** | 命令变形（`r\m -rf`、环境变量）可能绕过；对抗恶意用户请用允许列表模式 |
| **MCP 工具不受控** | `SecurityGuard` 不作用于远端 MCP 服务器；MCP 的安全由服务器方负责 |
| **Java 反编译工具** | 若你把 TinyClaw 包含进别的应用，校验也要随之启用 |

---

## 16.11 最佳实践

| 建议 | 原因 |
|------|------|
| `restrictToWorkspace=true` 永远开 | 核心护栏 |
| 生产环境容器化运行 | 即使被突破，爆炸半径受限 |
| 按最小权限开工具 | 例如客服机器人只开 `web_search`、`message`，关掉 `exec` |
| 用 `tools.*.allowCommands` 模式 | 比黑名单稳 |
| 定期审查 `security.yaml` | 策略会随业务变化 |
| MCP 服务器自带鉴权 | 尤其公网部署 |

---

## 16.12 下一步

- 工具系统整体 → [09 · 工具系统](09-tools-system.md)
- Hooks 做更灵活拦截 → [19 · Hooks](19-hooks.md)
- 部署与运维 → [03 · 快速开始](03-getting-started.md)
