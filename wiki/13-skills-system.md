# 13 · 技能系统

> `skills/` 包：用 Markdown 定义 Agent 能力扩展，支持语义搜索、GitHub 安装与自我管理。

---

## 13.1 什么是技能

**技能（Skill）**是用 Markdown 文件描述的一段"操作步骤"，形如：

```markdown
---
name: weather
description: 查询某个城市的天气并以表格方式回复
---

# Weather Skill

当用户询问天气时：

1. 使用 `web_search` 工具搜索「{city} 今日天气」
2. 从结果中提取温度、天气状况、湿度
3. 按以下格式回复：
   | 城市 | 温度 | 天气 | 湿度 |
   |------|------|------|------|
   | …    | …    | …    | …    |
```

它和**内置工具**的区别：

| 维度 | 工具（Tool） | 技能（Skill） |
|------|--------------|---------------|
| 形式 | Java 代码 | Markdown 文件 |
| 能力 | 一个原子操作 | 多步骤流程 / 行为模式 |
| 加载 | 启动时注册 | 运行时按**语义搜索**匹配注入 |
| 对 LLM | 作为 function | 作为 System Prompt 片段 |

技能是**对 LLM 说的"做事的套路"**，工具是**它调用的"工具箱"**。技能里通常会引导 LLM 调用多个工具。

---

## 13.2 目录布局

技能按优先级从三个位置加载，同名覆盖：

```text
1. workspace/skills/{name}/SKILL.md     ← 最高优先级（用户自定义）
2. ~/.tinyclaw/skills/{name}/SKILL.md   ← 全局（可选）
3. 内置技能目录（资源内嵌）              ← 最低
```

每个技能是 `skills/` 下的一个**子目录**，必含 `SKILL.md`；可附带资源（提示片段、示例文件、脚本等）。

---

## 13.3 SKILL.md 格式

YAML frontmatter + Markdown 正文：

```markdown
---
name: daily-report
description: 生成每日工作日报，聚合 Cron 任务与会话摘要
tags: [report, daily, workflow]
triggers:
  - "日报"
  - "今天干了什么"
  - "总结一下今天"
model: qwen-max   # 可选：该技能触发时强制使用某模型
---

# Daily Report Skill

…（步骤说明）…
```

关键字段：

| 字段 | 说明 |
|------|------|
| `name` | 技能唯一标识（必填，匹配目录名） |
| `description` | 一句话描述（必填，参与语义搜索） |
| `tags` | 标签（可选，用于分组展示） |
| `triggers` | 关键词列表（可选，匹配时直接注入） |
| `model` | 运行该技能时的模型（可选） |

---

## 13.4 核心组件

### 13.4.1 SkillsLoader

- 启动时扫描 3 个目录
- 解析 frontmatter + 正文
- 同名优先级覆盖
- 产出 `List<SkillInfo>` → 注入 `SkillRegistry`

### 13.4.2 SkillInfo

```java
class SkillInfo {
    String name;
    String description;
    String content;     // Markdown 正文
    List<String> tags;
    List<String> triggers;
    String model;
    Path sourcePath;
    SkillOrigin origin; // WORKSPACE / GLOBAL / BUILTIN
}
```

### 13.4.3 SkillRegistry

- 线程安全的技能索引
- 支持 `get(name)` / `list()` / `refresh()`
- 供 `SkillsTool` 和 `SkillsSearcher` 查询

### 13.4.4 SkillsSearcher

**关键**：负责基于用户消息**动态选出相关技能**注入到系统提示。核心思路：

```text
1. 每个 SkillInfo 的 (name + description + tags + triggers) 预计算关键词
2. 用户消息到来 → 分词、归一化
3. 打分：trigger 命中 +权重；tag 命中 +权重；description 关键词命中 +权重
4. 返回 top-N 技能（默认 3-5 个）
5. ContextBuilder.SkillsSection 把 top-N 的摘要 + 内容片段注入系统提示
```

避免了一次性把所有技能塞进上下文，显著节省 token。

### 13.4.5 SkillsInstaller

- **从 GitHub 安装**：解析 `owner/repo[/path]` 格式 → 下载 ZIP → 提取 `SKILL.md` → 拷贝到 `workspace/skills/`
- **安装内置技能**：一次性把内嵌资源展开到 workspace
- 校验：`name` 合法、`SKILL.md` 存在、frontmatter 合规

---

## 13.5 `skills` 工具（Agent 自治）

技能系统的关键特性之一：**Agent 可以自己管理技能**。

`SkillsTool` 提供以下子动作（作为 `action` 参数）：

| action | 作用 |
|--------|------|
| `list` | 列出已安装技能 |
| `list_builtin` | 列出可安装的内置技能 |
| `show` | 查看某个技能的完整 SKILL.md |
| `invoke` | 显式调用技能（把技能内容作为 hint 加到下一轮） |
| `create` | 创建新技能（Agent 根据需要自己编写） |
| `edit` | 修改已有技能 |
| `remove` | 删除技能 |
| `install` | 从 GitHub 安装 |
| `install_builtin` | 安装所有内置技能 |

这让 Agent 具备了"**在工作中沉淀技能**"的能力：遇到反复出现的任务模式，Agent 可以自主 `create` 技能，下次自动命中。

---

## 13.6 注入链路

```text
User 消息
    │
    ▼
MessageRouter.routeUser
    │
    ▼
ContextBuilder.buildMessages(..., currentMessage=User消息)
    │
    ▼
SkillsSection.build(ctx)
    ├── SkillsSearcher.search(currentMessage)  → top-N
    ├── 拼装摘要（短）或完整内容（长技能会摘要）
    └── 追加到 system prompt 的技能段
    │
    ▼
LLM 看到相关技能的提示，按步骤执行
```

---

## 13.7 CLI 命令

见 [05 · CLI 命令](05-cli-commands.md#57-skills--技能管理)：

```bash
tinyclaw skills list
tinyclaw skills list-builtin
tinyclaw skills install-builtin
tinyclaw skills install leavesfly/tinyclaw-skills/weather
tinyclaw skills show weather
tinyclaw skills remove weather
```

---

## 13.8 Web 控制台集成

`web/handler/SkillsHandler` 提供：

- `GET /api/skills` — 列表
- `GET /api/skills/{name}` — 详情
- `POST /api/skills` — 创建
- `PUT /api/skills/{name}` — 修改
- `DELETE /api/skills/{name}` — 删除
- `POST /api/skills/install` — 从 GitHub 安装
- `POST /api/skills/install-builtin` — 安装内置

Web UI 提供所见即所得的 Markdown 编辑器。

---

## 13.9 最佳实践

| 建议 | 原因 |
|------|------|
| 技能粒度适中 | 过小不如内置工具，过大容易占用上下文 |
| `description` 写清楚触发条件 | 语义搜索命中率 = 描述质量 |
| 配置 `triggers` 兜底 | 保证关键词能精确命中 |
| 技能内部明确写"先做什么、后做什么、调用哪个工具" | 减少 LLM 自由发挥带来的波动 |
| 常用技能 `install-builtin` 一键装 | 开箱即用 |
| 生产环境定期 review `workspace/skills/` | 防止 Agent 创建的技能失控增长 |

---

## 13.10 扩展：添加新内置技能

1. 在 `src/main/resources/skills/{name}/SKILL.md` 新建
2. 重新打包 JAR
3. 用户运行 `tinyclaw skills install-builtin` 即可安装

或直接在 `workspace/skills/` 手动写一个，零代码。

---

## 13.11 下一步

- 工具系统基础 → [09 · 工具系统](09-tools-system.md)
- 上下文如何注入 → [06 · Agent 引擎 §6.6 ContextBuilder](06-agent-engine.md)
- 让 Agent 自主进化技能 → [12 · 自我进化](12-self-evolution.md)
