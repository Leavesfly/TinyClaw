# 15 · 会话与记忆

> `session/` + `memory/`：短期对话上下文与长期知识沉淀。

---

## 15.1 两种"记忆"的分工

| 维度 | Session（短期） | Memory（长期） |
|------|------------------|----------------|
| 承载 | 当前对话的完整消息列表 | 跨会话的知识、偏好、事实 |
| 键 | `sessionKey = "{channel}:{chatId}"` | 全局（面向当前 Agent） |
| 生命周期 | 活跃期 + 滚动摘要 | 持久（除非显式归档） |
| 存储 | `workspace/sessions/*.json` | `workspace/memory/` |
| 查询 | 整段追加进上下文 | 按相关性 + Token 预算选择性注入 |

两者配合完成 Agent 的"**此时此刻在说什么**"和"**过去记得什么**"。

---

## 15.2 Session — 短期会话

### 15.2.1 数据结构

```java
class Session {
    String key;                           // "telegram:123456"
    List<Message> messages;               // 完整对话历史
    String summary;                       // 滚动摘要（由 SessionSummarizer 写入）
    Instant created;
    Instant updated;
    List<ToolCallRecord> toolCallRecords; // 工具调用历史（UI 回放用）
}
```

`Message` 遵循 OpenAI 规范：`role` ∈ `system|user|assistant|tool`，`content`、`tool_calls`、`tool_call_id`。

### 15.2.2 SessionManager

职责：

- **懒加载**：`getOrCreate(key)` 命中内存 → 未命中则从 JSON 文件加载 → 都没有则新建
- **持久化**：关键写入后异步落盘 `workspace/sessions/{key}.json`（`:` 转义为安全字符）
- **线程安全**：`ConcurrentHashMap<String, Session>`
- **时间戳管理**：`created` / `updated` 自动维护
- **批量操作**：`listKeys()`, `list()`, `delete(key)`, `clearAll()`

构造时指定 `storagePath`，若为空则只内存不持久化（测试场景）。

### 15.2.3 truncateHistory — 智能截断

当 `keepLast` 小于 `messages.size()` 时，直接按尾部截取会破坏 `assistant(tool_calls)` ↔ `tool(result)` 的**配对关系**，导致 OpenAI 兼容协议报错。

TinyClaw 的策略：

```text
起点落在 tool 消息上 → 向前查找最近的 assistant(tool_calls)：
  找到 → 起点调到那条 assistant
  找不到 → 向后跳过所有孤立 tool 消息
```

保证无论如何截断，历史都是合法序列。

### 15.2.4 ToolCallRecord

专供 UI 回放的"事件流"记录：

- 工具名、参数（脱敏后）
- 执行时长、结果长度
- 状态（成功 / 失败 / 超时 / 拒绝）
- `afterAssistantIndex`：挂在第几条 assistant 消息之后

Web 控制台的会话时间线由此渲染。

### 15.2.5 SessionSummarizer

当 `messages.size()` 超过阈值（默认 30）时，由 `AgentRuntime.maybeSummarize()` 触发：

```text
1. 选取旧消息（保留最近 N 条）
2. 调 LLM：生成「要点摘要 + 用户偏好 + 待办事项」
3. summary 更新到 Session.summary
4. truncateHistory(keepLast) 截断保留尾部
5. ContextBuilder.SummarySection 后续会把 summary 注入上下文开头
```

摘要一次仅占几百 token，显著降低长会话成本。

### 15.2.6 会话键格式

- Telegram: `telegram:123456789`
- 飞书:   `feishu:oc_xxxxx`
- CLI:    `cli:user`
- Cron:   `cron:{jobId}`
- 心跳:   `system:heartbeat`

格式由 `MessageRouter` 统一组装，确保跨通道不冲突。

---

## 15.3 Memory — 长期记忆（两层架构）

### 15.3.1 为什么分层

单文件记忆在数量增长后会爆 token。TinyClaw 的解法：

| 层 | 文件 | 大小 | 注入策略 |
|----|------|------|----------|
| **索引层** | `memory/MEMORY.md` | ~200 token | **每次都注入**，让 Agent "知道自己记得什么" |
| **内容层 - 主题** | `memory/topics/*.md` | 可变 | **按相关性**选择 top-N 注入 |
| **内容层 - 结构化** | `memory/MEMORIES.json` | 多条 `MemoryEntry` | **按评分**选取注入 |
| 归档 | `memory/MEMORIES_ARCHIVE.json` | 任意 | 不注入，仅保留以便审计 |

索引层就像书的**目录**，内容层是**章节**。Agent 先看目录决定要不要翻章节。

### 15.3.2 MemoryStore 核心字段

```java
class MemoryStore {
    String workspace;
    String memoryDir;                            // workspace/memory
    String indexFile;                            // MEMORY.md
    String topicsDir;                            // topics/
    String memoriesJsonFile;                     // MEMORIES.json
    String archiveJsonFile;                      // MEMORIES_ARCHIVE.json

    ReentrantLock writeLock;                     // 写原子性
    CopyOnWriteArrayList<MemoryEntry> entries;   // 结构化记忆内存缓存
}
```

### 15.3.3 Token 预算分配

`DEFAULT_MEMORY_TOKEN_BUDGET = 2048`，分配比例：

- 主题文件：**50%**（~1024 token）
- 结构化记忆：**50%**（~1024 token）
- 索引层不计预算，始终注入

每条记忆 ≤ `MAX_SINGLE_ENTRY_TOKENS = 256`。

### 15.3.4 getMemoryContext(currentMessage, budget)

注入决策流程：

```text
1. 读 MEMORY.md 索引层（固定注入，不进预算）
2. 基于 currentMessage 分词
3. 扫描 topics/*.md：关键词命中 → 打分 → top-K
4. 扫描 MEMORIES.json 结构化条目：
   - 基础分 = importance * 时间衰减因子 * (1 + log(accessCount))
   - 相关性命中 → × RELEVANCE_BOOST_MULTIPLIER(2.0)
5. 按分数降序，依次装入预算直到耗尽
6. 拼成 Markdown 注入到 system prompt 的 memory 段
```

被选中的条目会自动更新 `accessCount` 与 `lastAccessedAt`，后续更容易被再次选中（强化学习思路）。

### 15.3.5 MemoryEntry

```java
class MemoryEntry {
    String id;              // 内容哈希，去重用
    String content;
    double importance;      // 0.0-1.0
    List<String> tags;
    String source;          // "session:xxx" / "manual" / "evolver"
    Instant createdAt;
    Instant updatedAt;
    int accessCount;
    Instant lastAccessedAt;
}
```

### 15.3.6 主题文件

`memory/topics/{topicName}.md` 由两种渠道维护：

- **MemoryEvolver** 自动整合（详见 [12 · 自我进化](12-self-evolution.md)）
- **Agent 手动写入**：通过内置 `write_file` 工具直接编辑（技能系统常用）
- **用户手动编辑**：任何编辑器打开即可

TinyClaw 对主题文件格式无强要求，Markdown 自由书写。

---

## 15.4 索引重建：rebuildIndex()

随着主题文件 / 条目的增删，`MEMORY.md` 会被重建：

```markdown
# Memory Index

## Topics
- **preferences**: 用户语言、称呼、时区等偏好（12 KB，更新于 2026-05-01）
- **projects**: 当前参与的项目列表（8 KB）
- ...

## Recent Entries (Top 10 by importance)
- 用户倾向使用中文回复（importance=0.9）
- 工作日 9:30-18:00 在北京办公（importance=0.85）
- ...
```

触发时机：
- 新增 / 删除 topic
- 结构化条目批量替换
- 手动 `rebuildIndex()` 调用（Web 控制台"重建索引"按钮）

---

## 15.5 生命周期可视化

```text
用户提问
   │
   ▼
SessionManager.getOrCreate(key)  ← 短期会话
   │
   ▼
ContextBuilder.buildMessages(...)
   ├── IdentitySection
   ├── MemorySection      ← 读 MemoryStore.getMemoryContext(msg, budget)
   ├── SkillsSection
   ├── SummarySection     ← Session.summary
   ├── ToolsSection
   └── HistorySection     ← Session.messages
   │
   ▼
ReActExecutor 与 LLM 对话
   │
   ▼
每轮 assistant 消息 append 到 Session.messages
   │
   ▼（消息数超阈值）
SessionSummarizer.summarize()
   │
   ▼（心跳）
MemoryEvolver.evolve()  ← 把重要事实沉淀到 MemoryStore
   │
   ▼
落盘：sessions/*.json 与 memory/*
```

---

## 15.6 Web 控制台集成

| 功能 | Handler |
|------|---------|
| 会话列表 / 详情 / 删除 | `SessionsHandler` |
| 记忆查看 / 编辑 | `WorkspaceHandler` |
| 索引重建 | `WorkspaceHandler` |
| 归档查看 | `WorkspaceHandler` |

UI 支持 Markdown 编辑主题文件，也支持以表格方式编辑结构化条目。

---

## 15.7 常见问题

**Q: `summary` 和 Memory 有什么区别？**
A: `summary` 只在单个会话内有效，会随会话删除而丢失；Memory 是跨会话持久的，即使会话被清理仍可被检索。

**Q: 修改 `MEMORY.md` 后会被重建覆盖吗？**
A: 会。建议改写 `topics/*.md` 或结构化条目，让 `rebuildIndex()` 自动反映；或关闭 `MemoryEvolver` 后手工维护索引层。

**Q: 如何给 Agent 注入启动知识？**
A: 在 `memory/topics/` 预置 Markdown 文件；首次对话相关关键词命中即会注入。

**Q: 会话无限增长怎么办？**
A: 启用 `SessionSummarizer`（默认开启），并在配置里合理设置保留窗口 `memoryWindow`。

---

## 15.8 最佳实践

| 建议 | 原因 |
|------|------|
| `sessionKey` 统一 `channel:chatId` | 跨通道隔离 |
| 长会话必开 SessionSummarizer | 否则上下文暴涨 |
| 重要事实写成独立主题文件 | 精确可控，便于手工维护 |
| 定期归档低重要度条目 | 减小 JSON 体积，提升加载速度 |
| 大型知识库外置 | 结合 MCP 工具或 RAG，不要塞满 Memory |

---

## 15.9 下一步

- 自动沉淀记忆 → [12 · 自我进化](12-self-evolution.md)
- ContextBuilder 如何调用 → [06 · Agent 引擎 §6.6](06-agent-engine.md)
- 安全地读写记忆 → [16 · 安全沙箱](16-security-sandbox.md)
