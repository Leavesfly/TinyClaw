# 15 · 会话与记忆

> `session/` + `memory/`：短期对话上下文与长期知识沉淀。

---

## 15.1 两种"记忆"的分工

| 维度 | Session（短期） | Memory（长期） |
|------|------------------|----------------|
| 承载 | 当前对话的完整消息列表 | 跨会话的知识、偏好、事实 |
| 键 | `sessionKey = "{channel}:{chatId}"` | 全局（面向当前 Agent） |
| 生命周期 | 活跃期 + 滚动摘要 | 持久（除非显式归档） |
| 存储 | `workspace/sessions/*.jsonl`（append-only） | `workspace/memory/` |
| 查询 | 上下文窗口整段追加进上下文 | 按相关性 + Token 预算选择性注入 |

两者配合完成 Agent 的"**此时此刻在说什么**"和"**过去记得什么**"。

---

## 15.2 Session — 短期会话

### 15.2.1 数据结构

```java
class Session {
    String key;                           // "telegram:123456"
    List<Message> messages;               // 完整转录，只增不删
    String summary;                       // 滚动摘要（由 SessionSummarizer 写入）
    int contextStartIndex;                // LLM 上下文起点，之前的消息已被 summary 覆盖
    Instant created;
    Instant updated;
    List<ToolCallRecord> toolCallRecords; // 工具调用历史（UI 回放用）
}
```

`Message` 遵循 OpenAI 规范：`role` ∈ `system|user|assistant|tool`，`content`、`tool_calls`、`tool_call_id`，
另有两个**仅用于持久化**的身份字段 `id` / `timestamp`（由 Session 入库时补齐，不会进入 LLM 请求体）。

**不可变转录 + 可变上下文视图**：`messages` 是唯一事实源，只增不删；`contextStartIndex`
标记哪一段才送入模型。因此压缩上下文不会销毁历史，也不会让 `ToolCallRecord` 的绝对下标失效。

### 15.2.2 分层职责

| 层 | 类 | 职责 |
|----|-----|------|
| 存储 | `SessionStore` / `JsonlSessionStore` | 会话存在哪、怎么落盘 |
| 缓存与协调 | `SessionManager` | 按需加载、有界淘汰、生命周期 |
| 数据与并发 | `Session` | 单个会话的数据与一致性（内部持锁） |

`SessionManager` 要点：

- **按需加载**：`getOrCreate(key)` 命中缓存 → 未命中从磁盘加载 → 都没有则新建
- **两种读取语义**：`getHistory()` 返回完整转录（历史回放、计算绝对下标）；`getContextMessages()` 返回送入 LLM 的上下文
- **有界缓存**：超容量或空闲超时则淘汰，淘汰前先刷盘；只淘汰空闲足够久的会话，避免同一 key 出现两个实例
- **元信息列表**：`listMeta()` 只读索引，不加载任何会话正文，按 `updated` 倒序
- **删除不复活**：删除时打标记位，阻止仍持有旧引用的异步任务把会话写回

构造时指定 `storagePath`，若为空则只内存不持久化（测试场景，此时不做任何淘汰）。

### 15.2.3 存储格式：append-only JSONL

一行一条记录，只追加增量——单次写入代价与新增内容成正比，与历史长度无关（旧实现每次都全量重写整个 JSON，一轮多次工具调用就是多次全量重写）：

```text
sessions/
  _index.json                        会话元信息索引，列表查询只读它（可从转录重建）
  _active-sessions.json              各聊天当前活跃的会话指针（/new 跨进程生效）
  telegram_123-9f8e7d6c.jsonl        会话转录：header / msg / tool / compact 四类行
  telegram_123.json.migrated         迁移后保留的旧格式备份
  corrupt/xxx.jsonl.corrupt.169...   无法解析时隔离保留的原文件
```

健壮性要点：

- 文件名 = 可读前缀 + key 的哈希短码，避免不同 key（如 `a:b` 与 `a_b`）撞到同一文件
- 追加后 `fsync`；全量写走「唯一临时名 → fsync → rename」
- 末行残缺视为崩溃残留直接忽略，中间行损坏则跳过并告警，**能读出多少恢复多少**
- 整份无法识别时移入 `corrupt/` 保留证据，而不是让上层新建空会话覆盖销毁
- 未知字段与未知行类型一律忽略，新增字段后回退版本不会读不出整份会话

旧的单文件 `{key}.json` 首次访问时自动迁移为 JSONL，原文件重命名为 `.json.migrated` 保留。

> 已知限制：未做跨进程文件锁，同一 workspace 同时跑多个实例时以最后写入者为准。

### 15.2.4 上下文压缩与 tool 消息配对

上下文起点若落在 `tool` 消息上，会破坏 `assistant(tool_calls)` ↔ `tool(result)` 的**配对关系**，导致 OpenAI 兼容协议报错。

TinyClaw 的策略：

```text
起点落在 tool 消息上 → 向前查找最近的 assistant(tool_calls)：
  找到 → 起点调到那条 assistant
  找不到 → 向后跳过所有孤立 tool 消息
```

保证无论如何压缩，送入模型的序列都合法。`ContextBuilder` 另有一道同质的兜底校验。

### 15.2.5 ToolCallRecord

专供 UI 回放的"事件流"记录：

- 工具名、参数摘要（截断后）
- 结果摘要、成功与否、时间戳
- `messageIndex`：触发它的 assistant 消息在**完整转录**中的绝对下标（兼容旧字段名 `afterAssistantIndex`）

因为压缩不删消息，这个绝对下标永不失效。Web 控制台的会话时间线由此渲染。

### 15.2.6 SessionSummarizer

当**上下文**消息数或估算 Token 超过阈值时，由 `MessageRouter.persistAndSummarize()` 触发：

```text
1. 取上下文快照 ContextSnapshot（起点、完整转录长度、上下文消息）
2. 基于快照计算压缩边界 = totalMessages - RECENT_MESSAGES_TO_KEEP
3. 调 LLM：对边界之前的消息生成摘要（量大时分批后合并）
4. compactContext(summary, 边界)：写入 summary 并前移上下文起点，**不删任何消息**
5. ContextBuilder 后续把 summary 注入系统提示词
```

为什么边界必须取自快照：摘要在守护线程异步执行，LLM 调用的几秒里对话仍在追加消息。
若用完成时的长度重新计算边界，那段新增、尚未被摘要的消息会被错误划入已压缩区间。
压缩起点只增不减，迟到的摘要任务不会把上下文回退。

摘要一次仅占几百 token，显著降低长会话成本。

### 15.2.7 会话键格式

- Telegram: `telegram:123456789`
- 飞书:   `feishu:oc_xxxxx`
- CLI:    `cli:user`
- Cron:   `cron:{jobId}`
- 心跳:   `system:heartbeat`
- `/new` 后: `{channel}:{chatId}:{timestamp}`

会话键默认由 `InboundMessage.getSessionKey()` 基于 `channel` + `chatId` 动态组装，确保跨通道不冲突。

**通道地址 ≠ 会话身份**：一个聊天可以先后开启多个会话。`/new` 会生成带时间戳的新
会话键，并把该聊天的活跃指针指向它。指针由 `ActiveSessionRegistry` 持久化到
`sessions/_active-sessions.json`，因此重启后不会退回到最早那个会话（早期实现只存内存，
重启即丢，`/new` 建出来的会话会变成孤儿）。

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
