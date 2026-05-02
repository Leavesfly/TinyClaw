# TinyClaw Hooks 使用手册

TinyClaw Hooks 借鉴 Claude Code 的设计，让你用**外部进程 / HTTP Webhook** 在 Agent 生命周期的关键切点上动态干预——拦截高危工具调用、改写用户 Prompt、注入上下文、审计回复，而**不需要改一行 TinyClaw 源码**。

---

## 1. 快速开始

在 `~/.tinyclaw/hooks.json` 写入：

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "exec",
        "hooks": [
          { "type": "command", "command": "~/.tinyclaw/guards/block-rm.sh", "timeoutMs": 3000 }
        ]
      }
    ]
  }
}
```

创建 `~/.tinyclaw/guards/block-rm.sh`：

```bash
#!/usr/bin/env bash
INPUT=$(cat)
if echo "$INPUT" | grep -qE '"command"\s*:\s*"[^"]*rm +-rf'; then
  echo "Refusing to run rm -rf via Agent" 1>&2
  exit 2
fi
exit 0
```

`chmod +x` 后启动 TinyClaw，任何命中 `exec` 工具且参数包含 `rm -rf` 的调用都会被阻断，Agent 会收到 `Error: blocked by hook: ...` 并正常继续对话。

---

## 2. 生命周期事件

| 事件 | 触发时机 | 支持的响应动作 |
|---|---|---|
| `SessionStart` | 新 `sessionKey` 首次出现 | `additionalContext`（写入 session summary） |
| `UserPromptSubmit` | 用户消息入路由，写入 session 前 | `deny` / `modifyPrompt` / `additionalContext` |
| `PreToolUse` | 工具真正执行前 | `deny` / `modifyInput` |
| `PostToolUse` | 工具执行后，结果回注 LLM 前 | `deny`（替换结果为错误信息）/ `modifyOutput` |
| `Stop` | LLM 生成完整回复后，返回用户前 | `deny` / `modifyOutput` |
| `SessionEnd` | `AgentRuntime.stop()` 时 | 仅通知，忽略决策 |

---

## 3. 配置格式

完整 schema：

```jsonc
{
  "hooks": {
    "<EventName>": [
      {
        "matcher": "<regex>",          // 工具名正则；"*" 或省略表示匹配所有。仅对 *ToolUse 生效
        "hooks": [
          {
            "type": "command",
            "command": "<shell command>",
            "timeoutMs": 5000,          // 可选，默认 30000
            "workdir": "/abs/path"      // 可选，进程工作目录
          },
          {
            "type": "http",
            "url": "https://example.com/hook",
            "timeoutMs": 5000,          // 可选，默认 5000（连/读/写各自）
            "headers": {                // 可选
              "Authorization": "Bearer xxx"
            }
          }
        ]
      }
    ]
  }
}
```

**解析容错**：未知事件名、非法 matcher 正则、不支持的 handler type、缺字段的 handler 都会被**单条跳过**并写 WARN 日志，**不会让整个配置失效**。

---

## 4. Hook 协议（Command / HTTP 通用）

### 4.1 输入（stdin / request body）

一段 JSON，字段如下：

```jsonc
{
  "hookEventName": "PreToolUse",
  "session_key": "cli:default",
  "tool_name": "exec",           // 仅 *ToolUse 事件
  "tool_input": { "command": "ls -la" },
  "tool_output": "...",          // 仅 PostToolUse / Stop
  "prompt": "用户原始消息",       // 仅 UserPromptSubmit / Stop
  "cwd": "/current/workdir"
}
```

### 4.2 输出

两种等价通道，**HTTP 只能用 JSON 通道**：

#### 通道 1：Exit Code（仅 command handler）

| Exit Code | 含义 |
|---|---|
| `0` | Allow（若 stdout 是合法 JSON 则解析 JSON 决策） |
| `2` | Deny，stderr 内容作为 reason 返回给 Agent |
| 其他 | **Fail-open**：忽略 hook，记 WARN，主流程继续 |

#### 通道 2：Stdout / Response Body JSON

```jsonc
{
  "hookSpecificOutput": {
    "permissionDecision": "deny",           // 可选："deny" | "allow"
    "permissionDecisionReason": "...",      // deny 时用作错误消息
    "modifiedInput":  { "command": "ls" },  // 可选，PreToolUse 改写工具入参
    "modifiedOutput": "sanitized",          // 可选，PostToolUse / Stop 改写输出
    "modifiedPrompt": "rewritten prompt",   // 可选，UserPromptSubmit 改写提示词
    "additionalContext": "extra note"       // 可选，SessionStart / UserPromptSubmit 注入到 session summary
  }
}
```

多 handler 累积规则：
- **DENY 立即短路**，后续 handler 不再执行
- `modifyInput` / `modifyOutput` / `modifyPrompt`：**后写覆盖前写**，且后续 handler 看到的 `tool_input` 等字段是前一个 handler 改写后的值
- `additionalContext`：**按出现顺序用空行拼接**

### 4.3 Fail-open 语义

**Hook 永远不应该让 Agent 宕机。** 下列情况全部视为"hook 放行"并写 WARN 日志：

- Command：超时、exit code 非 0 非 2、stdout 不是合法 JSON、handler 抛异常
- HTTP：连接/读写超时、非 2xx 响应、响应体非 JSON、网络异常

---

## 5. 常见食谱

### 5.1 审计所有工具调用到日志文件

```json
{ "hooks": { "PostToolUse": [
  { "matcher": "*", "hooks": [
    { "type": "command",
      "command": "jq -c '{ts: now, tool: .tool_name, out: .tool_output | tostring | .[0:200]}' >> ~/.tinyclaw/audit.ndjson" }
  ]}
]}}
```

### 5.2 敏感词自动脱敏（改写工具输出）

```bash
#!/usr/bin/env bash
python3 -c '
import json, sys, re
p = json.load(sys.stdin)
out = p.get("tool_output") or ""
out = re.sub(r"\b\d{17}[\dXx]\b", "***REDACTED-ID***", out)
print(json.dumps({"hookSpecificOutput": {"modifiedOutput": out}}))
'
```

### 5.3 企业 IM 审计（HTTP）

```json
{ "hooks": { "Stop": [
  { "matcher": "*", "hooks": [
    { "type": "http",
      "url": "https://im.example.com/tinyclaw/audit",
      "headers": { "X-Token": "secret" } }
  ]}
]}}
```

### 5.4 新会话自动注入项目背景

```json
{ "hooks": { "SessionStart": [
  { "matcher": "*", "hooks": [
    { "type": "command", "command": "cat ~/my-project/BRIEF.md && printf '%s' '{}'" }
  ]}
]}}
```

*(注：此例利用 stdout 纯文本非 JSON → fail-open，所以实际上不会注入；真正要注入请返回 `{"hookSpecificOutput":{"additionalContext":"..."}}`)*

---

## 6. 排错

| 现象 | 排查方向 |
|---|---|
| Hook 似乎没触发 | 1) 确认 `~/.tinyclaw/hooks.json` 存在且 JSON 合法 2) 启动日志里应出现 `hooks_enabled=true`  3) `tinyclaw hookctl list` 检查是否被加载 |
| DENY 没生效 | exit code 必须严格为 `2`；其他非 0 值都会 fail-open。`tinyclaw hookctl test PreToolUse --tool exec --input '{"command":"rm -rf /"}'` 可在不真实调用 Agent 的情况下验证 |
| 改写后还是用旧值 | `modifiedInput` / `modifiedOutput` / `modifiedPrompt` 要放在 `hookSpecificOutput` 下，且字段名区分大小写 |
| 整个配置无效 | 顶级结构必须是 `{"hooks": {...}}`，且 value 是对象，不是数组 |
| 超时不起作用 | `timeoutMs` 是毫秒；<= 0 会回退到 30000ms 默认值 |

### 相关日志

所有 hook 日志写到 `[tinyclaw.hooks]` logger，可用：

```
grep '\[tinyclaw.hooks\]' ~/.tinyclaw/logs/*.log
```

---

## 7. CLI 工具

```
tinyclaw hookctl list                   # 列出已加载的 hook 及 matcher
tinyclaw hookctl validate [path]        # 仅校验配置，不启动 Agent
tinyclaw hookctl test <event> [--tool X] [--input JSON] [--output STR] [--prompt STR]
                                        # 本地触发一次 hook 链路，打印决策
```

详见 `tinyclaw hookctl --help`。
