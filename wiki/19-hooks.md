# 19 · Hooks 钩子系统

> `hooks/` 包：在 Agent 生命周期的关键时机插入自定义逻辑，不改代码即可扩展行为。

---

## 19.1 为什么需要 Hooks

很多"**横切关注点**"不适合硬编码到核心流程：

- 审计 / 合规：记录每次工具调用、每轮消息
- 访问控制：对特定工具做细粒度拦截
- 内容改写：敏感词替换、格式规范化
- 上下文注入：启动会话时自动追加团队约定
- 外部通知：某些事件触发时通知企业微信 / Slack

TinyClaw 对齐 **Claude Code** 的 hook 设计，6 个核心时机，配置驱动。

---

## 19.2 6 个生命周期事件

| 事件（`HookEvent`） | WireName | 触发时机 | 可做什么 |
|---------------------|----------|----------|----------|
| `SESSION_START` | `SessionStart` | 会话首次创建时 | 注入初始上下文 |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | 用户消息到达但未进 LLM 前 | 改写 / 拒绝 prompt |
| `PRE_TOOL_USE` | `PreToolUse` | 工具执行前 | 改写参数 / deny |
| `POST_TOOL_USE` | `PostToolUse` | 工具执行后 | 改写结果 / 追加上下文 |
| `STOP` | `Stop` | 本轮回复完成 | 归档 / 通知 / 统计 |
| `SESSION_END` | `SessionEnd` | AgentRuntime 停止 | 收尾（flush 日志 / 关连接） |

`wireName` 是配置文件和外部 handler 协议使用的字符串名，与 Claude Code 完全一致。

---

## 19.3 核心组件

```text
hooks/
├── HookEvent.java            # 生命周期事件枚举
├── HookContext.java          # 事件上下文（event, sessionKey, prompt, toolName, toolInput, toolOutput, extra）
├── HookDecision.java         # handler 返回的决策（ALLOW/DENY + modify*）
├── HookHandler.java          # handler 接口：HookDecision invoke(HookContext)
├── HookMatcher.java          # 工具名匹配器（Java 正则，full match）
├── HookEntry.java            # 一个 matcher + 一组 handlers
├── HookRegistry.java         # 事件 → HookEntry[] 不可变索引
├── HookDispatcher.java       # 统一门面，按顺序调用并聚合决策
├── HookConfigLoader.java     # 从 ~/.tinyclaw/hooks.json 加载
└── CommandHookHandler.java   # 内置实现：调外部 shell 命令作为 handler
```

**HookMatcher 语义**（来自 `HookMatcher.of(pattern)`）：

- `null` / 空串 / `"*"` → 匹配所有（含非工具类事件）
- 其他 → 一律当 **Java 正则**，对工具名做 **full match**（`Matcher.matches()`）
- 对于 `SESSION_START` / `USER_PROMPT_SUBMIT` / `STOP` / `SESSION_END` 这类 `toolName=null` 的事件，**只有 `"*"` 或空 matcher 会命中**

典型正则例子（注意：这里是正则而非 glob）：

- `exec` — 精确匹配
- `exec|write_file|edit_file` — 多个精确名的或
- `web_.*` — 所有 `web_` 开头的工具
- `.*` — 匹配所有（等价于 `"*"`）

---

## 19.4 HookDecision — 决策类型

一个 handler 返回 `HookDecision`：

| 工厂方法 | 语义 |
|----------|------|
| `HookDecision.cont()` / `allow()` | 放行，无副作用 |
| `HookDecision.deny(reason)` | 拒绝，reason 会回灌给 LLM 或返回给用户 |
| `HookDecision.modifyInput(newInput)` | 改写工具入参（`PRE_TOOL_USE`） |
| `HookDecision.modifyOutput(newOutput)` | 改写工具结果（`POST_TOOL_USE`） |
| `HookDecision.modifyPrompt(newPrompt)` | 改写用户 prompt（`USER_PROMPT_SUBMIT`） |
| `HookDecision.addContext(text)` | 追加额外上下文（任意事件） |

---

## 19.5 HookDispatcher — 聚合规则

同一事件可配置多条 `HookEntry`，每条含多个 handler。`HookDispatcher.fire(event, ctx)` 的聚合规则：

1. **空注册表短路**：无任何 hook 时直接返回 `cont()`，零开销
2. **按配置顺序**执行所有命中 matcher 的 handler
3. **DENY 短路**：任一 handler 返回 `deny(reason)`，立即停止，reason 回灌
4. **modify 累积**：后一个 handler 看到的 input/prompt/output 是前一个修改后的结果
5. **additionalContext 累加**：多个 handler 的追加文本用 `\n\n` 拼接
6. **fail-open**：handler 抛异常时**放行**（记 warn 日志），不影响主流程

线程安全：`HookRegistry` 构造后不可变，`HookDispatcher` 无可变状态，可并发调用。

---

## 19.6 配置文件

### 19.6.1 路径

- **固定**：`~/.tinyclaw/hooks.json`（由 `HookConfigLoader.defaultPath()` 返回）
- 文件不存在 → 返回 `HookRegistry.EMPTY`，零开销
- 解析失败（坏 JSON）→ 返回 `EMPTY` + error 日志；不拖垮启动
- 局部错误（未知事件名、非法 matcher 正则、缺 `command` 字段、`type != "command"`）→ 跳过该条 + warn 日志

### 19.6.2 格式

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "python3 /etc/tinyclaw/hooks/inject_context.py",
            "timeoutMs": 5000
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "exec|write_file|edit_file",
        "hooks": [
          {
            "type": "command",
            "command": "/etc/tinyclaw/hooks/audit.sh",
            "timeoutMs": 2000,
            "workingDir": "/var/tinyclaw",
            "env": {"TC_ENV": "prod"}
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "web_fetch",
        "hooks": [
          {
            "type": "command",
            "command": "python3 /etc/tinyclaw/hooks/sanitize_html.py"
          }
        ]
      }
    ]
  }
}
```

### 19.6.3 字段说明（以源码 `HookConfigLoader` 为准）

| 字段 | 类型 | 说明 |
|------|------|------|
| `matcher` | string | 工具名正则；空 / `"*"` 匹配所有；非法正则会跳过该条 |
| `hooks[]` | array | 该 matcher 下的 handler 列表，**至少一项** |
| `hooks[].type` | string | 目前仅支持 `"command"`，其他值跳过 |
| `hooks[].command` | **string** | Shell 命令行（`sh -c` 执行，Windows `cmd /c`）；缺失则跳过 |
| `hooks[].timeoutMs` | long | 超时毫秒；不传或 ≤ 0 时用默认 `30_000` |
| `hooks[].workingDir` | string? | 子进程工作目录；可选 |
| `hooks[].env` | object? | 额外环境变量 `{KEY: "value"}`；可选 |

> **要点**：`command` 是**字符串**（不是数组），由 `sh -c` 解释，因此支持管道、重定向、变量替换等复杂写法。

---

## 19.7 CommandHookHandler — 外部进程协议

`CommandHookHandler` 允许你用**任意语言**写 hook（Python / Shell / Go / …）。协议严格对齐 Claude Code。

### 19.7.1 进程启动

- Unix：`sh -c "<command>"`
- Windows：`cmd /c "<command>"`
- 可选 `workingDir`、`env`
- stdin / stdout / stderr 全部 pipe，由 Java 侧读写

### 19.7.2 stdin 输入（来自 `HookContext.toPayload()`）

写入一次 UTF-8 JSON 后立即关闭 stdin。字段使用 **Claude Code 约定的命名**（注意 snake_case）：

```json
{
  "hookEventName": "PreToolUse",
  "sessionKey":    "telegram:123456",
  "prompt":        "用户原始 prompt，若事件相关",
  "tool_name":     "exec",
  "tool_input":    {"command": "rm -rf /tmp/x"},
  "tool_output":   "（仅 PostToolUse 有）",
  "...extra":      "HookContext.extra 的键会被 flatten 到顶层"
}
```

未设置的字段不会出现在 JSON 中（`toPayload()` 只放非空字段）。

### 19.7.3 stdout 输出 + exit code 决策语义

最终决策由 **exit code + stdout** 联合决定：

| exit code | 行为 |
|-----------|------|
| `0` | 解析 stdout JSON（见下）；stdout 为空 → `allow` |
| `2` | **强制 deny**，reason = stderr（为空则 `"Blocked by hook (exit 2)"`） |
| 其他非零 | 记 warn + stderr 预览，**fail-open**（放行） |

`exit 0` 时的 stdout JSON 结构（字段名也对齐 Claude Code）：

```json
{
  "hookSpecificOutput": {
    "permissionDecision": "allow" | "deny",
    "permissionDecisionReason": "...",
    "modifiedInput":      { "...": "..." },
    "modifiedOutput":     "...",
    "modifiedPrompt":     "...",
    "additionalContext":  "..."
  }
}
```

解析优先级（命中任一字段即返回对应决策）：

1. `permissionDecision == "deny"` → `HookDecision.deny(reason)`
2. `modifiedInput` 是 object → `modifyInput(map)`
3. `modifiedOutput` 非空文本 → `modifyOutput(text)`
4. `modifiedPrompt` 非空文本 → `modifyPrompt(text)`
5. `additionalContext` 非空文本 → `addContext(text)`
6. 都没有 → `cont()`

非法 JSON / 缺字段 / 解析异常 → **fail-open**（放行，记 warn）。

### 19.7.4 Python Hook 示例

`/etc/tinyclaw/hooks/audit.py`：

```python
#!/usr/bin/env python3
"""记录审计并拦截生产路径写操作。"""
import json, sys, time, pathlib

ctx = json.loads(sys.stdin.read())

# 审计落盘
audit_file = pathlib.Path("/var/log/tinyclaw/audit.log")
audit_file.parent.mkdir(parents=True, exist_ok=True)
with audit_file.open("a") as f:
    f.write(json.dumps({
        "ts": time.time(),
        "event":   ctx.get("hookEventName"),
        "session": ctx.get("sessionKey"),
        "tool":    ctx.get("tool_name"),
        "input":   ctx.get("tool_input"),
    }) + "\n")

# 拦截生产写操作
if ctx.get("tool_name") == "write_file":
    path = (ctx.get("tool_input") or {}).get("path", "")
    if "/production/" in path:
        print(json.dumps({
            "hookSpecificOutput": {
                "permissionDecision": "deny",
                "permissionDecisionReason": "production path is read-only from AI"
            }
        }))
        sys.exit(0)

# 默认放行：stdout 留空 + exit 0 即可
sys.exit(0)
```

另一种更"Unix 风格"的写法：**用 exit code 2 表示 deny**，stderr 写 reason：

```bash
#!/bin/sh
# 遇到 rm -rf 一律拦
if echo "$(cat)" | grep -q '"command":".*rm -rf'; then
  echo "refuse to rm -rf" 1>&2
  exit 2
fi
exit 0
```

### 19.7.5 失败语义（fail-open）

**所有**非预期异常都不会阻塞 Agent 主流程（`HookDispatcher` 的设计决策）：

| 场景 | 行为 |
|------|------|
| 超时（默认 30s） | `destroyForcibly()` 杀进程 + warn + `cont()` |
| 进程启动失败（`IOException`） | warn + `cont()` |
| stdout 非法 JSON | warn + `cont()` |
| 其他 exit code（非 0、非 2） | warn + stderr 预览 + `cont()` |

这是刻意选择：hook 脚本是用户自定义代码，其故障不应拖垮 Agent。

---

## 19.8 与 SecurityGuard 的关系

| 维度 | SecurityGuard | Hooks |
|------|---------------|-------|
| 触发 | 工具内部手动检查 | Dispatcher 自动在切点触发 |
| 粒度 | 路径 / 命令模式 | 事件 × 工具名 matcher |
| 扩展 | 修改 Java 代码或 `security.yaml` | **配置 + 外部脚本**，完全运行时 |
| 适用 | 通用安全底座 | 业务定制：审计、合规、工作流约束 |

两者**叠加生效**：hook 通过后还要过 SecurityGuard，反之亦然。

---

## 19.9 常见 Hook 模式

### 审计日志

所有 `PRE_TOOL_USE` / `POST_TOOL_USE` 写入 SIEM / ELK / 数据库，满足合规要求。

### 敏感词过滤

`USER_PROMPT_SUBMIT` 调外部 DLP 服务，命中就 `deny` 或改写。

### 工作流约束

`PRE_TOOL_USE` 检查当前用户角色，限制 `exec` 仅限管理员。

### 上下文注入

`SESSION_START` 读取企业知识库或用户档案，通过 `addContext` 注入。

### 结果打标

`POST_TOOL_USE` 对 `web_fetch` 返回内容做 XSS 清洗，防止污染 LLM 上下文。

### 通知

`STOP` 回调飞书 / 钉钉机器人，告知"一轮对话完成 + 用量 + 耗时"。

---

## 19.10 调试

| 方法 | 说明 |
|------|------|
| 开 DEBUG 日志 | `logger.level=DEBUG`，`hooks` 命名日志会输出每次 fire 与决策 |
| 本地 dry-run | 写一个 hook 脚本只 echo 输入，确认协议对接 |
| 验证 matcher | 改 matcher 为 `*` 看是否触发，排除匹配规则问题 |
| 时间统计 | 在 hook 脚本内部打点，定位慢 hook |

---

## 19.11 性能注意

- Hook 执行**同步阻塞**主流程，慢 hook 会拖慢 Agent 响应
- 建议：
  - 单个 hook 执行时间 < 100ms
  - 重型逻辑（落盘、调云服务）异步化：hook 内只投递队列，后台 worker 处理
  - 用 `matcher` 精确匹配，避免每个工具都触发

---

## 19.12 Java 原生 Handler

若 `CommandHookHandler`（外部进程）性能不够，可在代码层实现 Java 原生 handler：

```java
public class JavaAuditHook implements HookHandler {
    @Override
    public HookDecision invoke(HookContext ctx) {
        AuditService.record(ctx);
        return HookDecision.cont();
    }
}
```

**注意**：当前版本的 `HookConfigLoader.parseHandler()` 仅识别 `type = "command"`，其他 `type` 会被跳过并打 warn。若想让 Java 原生 handler 参与配置驱动，需要扩展 `HookConfigLoader`（见下一节）。

---

## 19.13 扩展 HookConfigLoader 支持新 handler 类型

当前 loader 的 handler 解析在 `parseHandler(...)` 中硬编码了 `"command"`。要新增类型，需在源码层：

1. 新建实现类（如 `WebhookHookHandler implements HookHandler`）
2. 修改 `HookConfigLoader.parseHandler(event, handlerNode)`，在 `type` 分支增加对应 case
3. 按新 type 所需字段从 `handlerNode` 读取参数并构造

这不是纯配置扩展，改完需重新编译打包。更多指导见 [20 · 扩展开发](20-extending.md)。

---

## 19.14 下一步

- 钩子使用实战指南 → `docs/hooks-guide.md`
- 底层安全护栏 → [16 · 安全沙箱](16-security-sandbox.md)
- 工具系统 → [09 · 工具系统](09-tools-system.md)
- 扩展开发 → [20 · 扩展开发](20-extending.md)
