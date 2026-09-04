package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 - 会话的缓存与协调层
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>{@link SessionStore}：会话存在哪、怎么落盘（append-only JSONL）；</li>
 *   <li>本类：内存缓存、按需加载、有界淘汰、生命周期协调；</li>
 *   <li>{@link Session}：单个会话的数据与并发一致性。</li>
 * </ul>
 *
 * <h2>缓存策略</h2>
 * <p>缓存有界：超出容量或空闲超时的会话会被淘汰，淘汰前先把未落盘增量刷盘。为避免把
 * 正在处理中的会话淘汰掉造成同一 key 出现两个实例，只淘汰空闲超过
 * {@link #MIN_IDLE_BEFORE_EVICT_MS} 的会话——宁可短时超出容量，也不牺牲一致性。</p>
 *
 * <h2>读取语义</h2>
 * <ul>
 *   <li>{@link #getHistory}：完整转录，用于历史回放与计算工具调用记录的绝对下标；</li>
 *   <li>{@link #getContextMessages}：送入 LLM 的上下文，已压缩的早期消息不包含在内。</li>
 * </ul>
 */
public class SessionManager implements SessionProgressSink {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("session");

    /** 缓存中最多保留的会话数 */
    private static final int MAX_CACHED_SESSIONS = 64;
    /** 空闲超过该时长的会话从缓存中移除 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    /** 淘汰前要求的最小空闲时长，避免淘汰正在处理中的会话 */
    private static final long MIN_IDLE_BEFORE_EVICT_MS = 5 * 60 * 1000L;
    /** 两次淘汰扫描的最小间隔 */
    private static final long SWEEP_INTERVAL_MS = 30 * 1000L;

    /** 搜索未指定上限时的默认结果数 */
    private static final int DEFAULT_SEARCH_LIMIT = 30;
    /** 搜索结果硬上限：避免调用方传个巨大值把整个目录扫完并全部载入内存 */
    private static final int MAX_SEARCH_LIMIT = 200;
    /** 搜索片段在命中位置两侧各保留的字符数 */
    private static final int SNIPPET_CONTEXT_CHARS = 40;

    private final Map<String, Session> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    private final SessionStore store;
    /** 存储是否可持久：纯内存模式下缓存就是唯一副本，不能淘汰 */
    private final boolean durable;

    private volatile long lastSweepAt;

    /**
     * @param storagePath 会话存储目录；为空则使用纯内存模式（不落盘）
     */
    public SessionManager(String storagePath) {
        this.store = createStore(storagePath);
        this.durable = this.store != SessionStore.NOOP;
    }

    private SessionManager(SessionStore store) {
        this.store = store != null ? store : SessionStore.NOOP;
        this.durable = this.store != SessionStore.NOOP;
    }

    /**
     * 注入自定义存储实现，便于测试与替换后端。
     * 用工厂方法而非公开重载构造器，避免 {@code new SessionManager(null)} 产生歧义。
     */
    public static SessionManager withStore(SessionStore store) {
        return new SessionManager(store);
    }

    private static SessionStore createStore(String storagePath) {
        if (storagePath == null || storagePath.isEmpty()) {
            return SessionStore.NOOP;
        }
        try {
            return new JsonlSessionStore(storagePath);
        } catch (IOException e) {
            logger.error("Failed to initialize session store, falling back to memory-only",
                    Map.of("path", storagePath, "error", String.valueOf(e.getMessage())));
            return SessionStore.NOOP;
        }
    }

    // ==================== 获取 ====================

    /**
     * 获取或创建会话：命中缓存直接返回，磁盘存在则懒加载，否则新建
     */
    public Session getOrCreate(String key) {
        Session session = cache.computeIfAbsent(key, k -> {
            Session loaded = store.load(k);
            if (loaded != null) {
                return loaded;
            }
            logger.debug("Created new session: " + k);
            return new Session(k);
        });
        touch(key);
        return session;
    }

    /**
     * 获取已存在的会话（缓存优先，其次懒加载），不存在返回 null。
     * 与 {@link #getOrCreate} 的差别是绝不创建新会话——异步任务应使用本方法，
     * 否则会把已被删除的会话复活成空壳。
     */
    private Session getExisting(String key) {
        Session cached = cache.get(key);
        if (cached != null) {
            touch(key);
            return cached;
        }
        if (!store.exists(key)) {
            return null;
        }
        Session loaded = cache.computeIfAbsent(key, store::load);
        if (loaded == null) {
            cache.remove(key);
            return null;
        }
        touch(key);
        return loaded;
    }

    /**
     * 会话是否存在（含磁盘上尚未加载的）
     */
    public boolean exists(String key) {
        return cache.containsKey(key) || store.exists(key);
    }

    // ==================== 写入 ====================

    /**
     * 添加简单消息到会话
     */
    public void addMessage(String sessionKey, String role, String content) {
        getOrCreate(sessionKey).addMessage(role, content);
    }

    /**
     * 添加完整消息（包括工具调用）到会话
     */
    public void addFullMessage(String sessionKey, Message message) {
        getOrCreate(sessionKey).addFullMessage(message);
    }

    /**
     * 添加工具调用记录到会话。
     * record 中的 messageIndex 是完整转录中的绝对下标，由调用方基于 {@link #getHistory} 计算。
     */
    public void addToolCallRecord(String sessionKey, ToolCallRecord record) {
        getOrCreate(sessionKey).addToolCallRecord(record);
    }

    /**
     * 设置会话摘要
     */
    public void setSummary(String sessionKey, String summary) {
        Session session = getExisting(sessionKey);
        if (session != null) {
            session.setSummary(summary);
        }
    }

    // ==================== 身份与可见性 ====================

    /**
     * 为会话认领归属并设定默认可见性，已有归属时不做任何改动。
     *
     * <p>可见性只在认领那一刻设定：后续用户可能手动把会话改成共享，
     * 每条消息都重新写一遍默认值会把这个修改静默改回去。</p>
     *
     * @param owner      归属人标识，建议用 {@code MemoryScope.ofUser} 的编码
     * @param visibility 首次认领时采用的可见性
     * @return 实际完成认领返回 true
     */
    public boolean claimOwner(String sessionKey, String owner, SessionVisibility visibility) {
        Session session = getExisting(sessionKey);
        if (session == null || !session.claimOwner(owner)) {
            return false;
        }
        session.setVisibility(visibility);
        return true;
    }

    /**
     * 读取会话归属人，不存在或未认领返回 null
     */
    public String getOwner(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.getOwner() : null;
    }

    /**
     * 修改会话可见性，会话不存在时返回 false
     */
    public boolean setVisibility(String sessionKey, SessionVisibility visibility) {
        Session session = getExisting(sessionKey);
        if (session == null) {
            return false;
        }
        session.setVisibility(visibility);
        save(sessionKey);
        return true;
    }

    /**
     * 添加可见成员，实际新增时落盘并返回 true
     */
    public boolean addMember(String sessionKey, String member) {
        Session session = getExisting(sessionKey);
        if (session == null || !session.addMember(member)) {
            return false;
        }
        save(sessionKey);
        return true;
    }

    // ==================== 进度卡 ====================

    /**
     * 更新会话进度卡。传 null 表示任务结束，清除进度。
     *
     * <p>进度本身不进转录，这里仍调 {@code store.persist} 是为了把进度推进会话索引，
     * 让列表接口也能看到“哪些会话在跑”。无待落盘增量时 persist 不会产生文件写入，
     * 只会刷新索引（索引刷盘自带节流）。</p>
     */
    @Override
    public void setProgress(String sessionKey, SessionProgress progress) {
        Session session = getExisting(sessionKey);
        if (session == null) {
            return;
        }
        session.setProgress(progress);
        store.persist(session);
    }

    /**
     * 读取当前进度卡，无进行中任务时返回 null
     */
    public SessionProgress getProgress(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.getProgress() : null;
    }

    /**
     * 记录一次全新提示词的输入消息：把 prompt 中的非 system 消息追加到会话。
     *
     * <p>{@code ReActExecutor} 只持久化它自己产生的 assistant / tool 消息，输入消息由调用方
     * 负责入库。本方法面向输入为「system + user 一次性提示词」的调用方（子代理、协同角色），
     * 不适用于主链路：主链路的输入是完整历史，且已在调用前单独写入了用户消息。</p>
     *
     * <p>system 消息按设计不入库：它是由技能、记忆、工具清单每轮实时重建的派生数据，
     * 存下来既会过期，也会与 ContextBuilder 每轮注入的那条重复。</p>
     */
    public void recordPromptMessages(String sessionKey, List<Message> prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return;
        }
        Session session = getOrCreate(sessionKey);
        for (Message message : prompt) {
            if (message != null && !"system".equals(message.getRole())) {
                session.addFullMessage(message);
            }
        }
    }

    /**
     * 记录一轮对话的最终回复并落盘，与 {@link #recordPromptMessages} 成对使用。
     *
     * <p>最终回复由调用方而非 {@code ReActExecutor} 写入，因为只有调用方知道经 Stop hook
     * 改写后的最终文本；若由 executor 先写一份，会造成存储的内容与用户实际看到的不一致。</p>
     */
    public void recordReply(String sessionKey, String reply) {
        recordReply(sessionKey, reply, null);
    }

    /**
     * 记录最终回复并附带思考过程（可选）。
     *
     * <p>思考内容仅用于前端历史回放展示，不会进入后续 LLM 上下文
     * （请求体由 LLMRequestBuilder 逐字段构造，不读取 thinking 字段）。</p>
     *
     * @param thinking 思考过程全文，null 或空串表示无思考内容
     */
    public void recordReply(String sessionKey, String reply, String thinking) {
        if (reply == null || reply.isEmpty()) {
            return;
        }
        Message message = Message.assistant(reply);
        if (thinking != null && !thinking.isEmpty()) {
            message.setThinking(thinking);
        }
        addFullMessage(sessionKey, message);
        save(sessionKey);
    }

    /**
     * 以非破坏方式压缩上下文：写入摘要并前移上下文起点，完整转录保持不变。
     *
     * @param newStartIndex 新的上下文起点，应基于生成摘要时的快照计算
     * @return 压缩是否实际生效
     */
    public boolean compactContext(String sessionKey, String summary, int newStartIndex) {
        Session session = getExisting(sessionKey);
        return session != null && session.compactContext(summary, newStartIndex);
    }

    /**
     * 持久化会话（只写入未落盘的增量）
     */
    public void save(Session session) {
        if (session == null) {
            return;
        }
        store.persist(session);
    }

    /**
     * 持久化指定会话；会话不存在时静默跳过，不会创建空会话
     */
    public void save(String sessionKey) {
        Session session = getExisting(sessionKey);
        if (session != null) {
            store.persist(session);
        }
    }

    // ==================== 派生（fork） ====================

    /**
     * 从源会话的某个截断点派生一个独立的新会话，用于「重新生成 / 回溯重发」。
     *
     * <p>严格遵循不可变转录原则：源会话只读不改，新会话复制 {@code [0, cutIndex)} 区间的
     * 消息（每条克隆为全新 id，避免跨会话 id 撞车破坏搜索去重），并复制落在该区间内的
     * 工具调用记录、身份归属与压缩标记。派生会话作为一个独立分支存在，源会话历史完整保留。
     * 由于新会话从空转录起步，其落盘水位为 0，复制进去的消息会被 {@code persistDelta}
     * 当作全新增量 append 到新的 JSONL 文件，与源会话文件互不影响。</p>
     *
     * @param sourceKey 源会话 key
     * @param cutIndex  截断点（完整转录中的绝对下标，复制严格小于该值的消息）；
     *                  会被夹取到 {@code [0, history.size()]}
     * @param newKey    新会话 key；为空则由本方法基于源 key 生成唯一分支 key
     * @return 新会话的 key；源会话不存在时返回 null
     */
    public String forkSession(String sourceKey, int cutIndex, String newKey) {
        Session source = getExisting(sourceKey);
        if (source == null) {
            return null;
        }
        List<Message> history = source.getHistory();
        int cut = Math.max(0, Math.min(cutIndex, history.size()));
        String targetKey = (newKey != null && !newKey.isBlank())
                ? newKey : generateForkKey(sourceKey);

        Session fork = getOrCreate(targetKey);
        // 复制截断点之前的消息：克隆为全新身份，保留原时间戳以维持 Trace 时序连续
        for (int i = 0; i < cut; i++) {
            fork.addFullMessage(cloneForFork(history.get(i)));
        }
        // 复制落在保留区间内的工具调用记录：messageIndex 在等长前缀复制后仍然对齐
        for (ToolCallRecord record : source.getToolCallRecords()) {
            if (record != null && record.getMessageIndex() < cut) {
                fork.addToolCallRecord(record);
            }
        }
        // 身份与可见性：分支继承源会话归属，避免派生会话在列表里变成无主 legacy
        fork.setOwner(source.getOwner());
        fork.setVisibility(source.getVisibility());
        fork.setMembers(source.getMembers());
        // 压缩标记：仅当上下文起点落在保留区间内才继承，否则分支从头开始（起点归零）
        if (source.getContextStartIndex() <= cut) {
            String summary = source.getSummary();
            if (summary != null && !summary.isBlank()) {
                fork.setSummary(summary);
            }
            fork.setContextStartIndex(source.getContextStartIndex());
        }
        save(fork);
        logger.info("Forked session", Map.of(
                "source", sourceKey,
                "fork", targetKey,
                "copied_messages", cut));
        return targetKey;
    }

    /**
     * 为 fork 克隆一条消息：复制全部语义字段并保留原时间戳，但故意把 id 留空，
     * 让 {@link Session#addFullMessage} 补齐为全新 id，避免源会话与分支共享消息 id。
     */
    private Message cloneForFork(Message src) {
        Message copy = new Message();
        copy.setRole(src.getRole());
        copy.setContent(src.getContent());
        copy.setThinking(src.getThinking());
        copy.setToolCallId(src.getToolCallId());
        if (src.getImages() != null) {
            copy.setImages(new ArrayList<>(src.getImages()));
        }
        if (src.getToolCalls() != null) {
            copy.setToolCalls(new ArrayList<>(src.getToolCalls()));
        }
        copy.setTimestamp(src.getTimestamp());
        return copy;
    }

    /**
     * 基于源 key 生成唯一的分支 key：{@code <base>-r<36 进制时间戳>}。
     *
     * <p>先剥离源 key 上已有的 {@code -r…} 分支后缀，避免对分支再派生时 key 无限增长；
     * 由于源 key 以 {@code web:} 之类前缀开头，追加后缀不会破坏前缀语义
     * （如 ExecTool 的 HITL 审批门控依赖 {@code web} 前缀）。</p>
     */
    private String generateForkKey(String sourceKey) {
        String base = sourceKey.replaceAll("-r[0-9a-z]+$", "");
        return base + "-r" + Long.toString(System.currentTimeMillis(), 36);
    }

    // ==================== 读取 ====================

    /**
     * 获取完整消息转录（含已被摘要压缩的早期消息）。
     * 用于历史回放，以及计算工具调用记录的绝对下标。
     */
    public List<Message> getHistory(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.getHistory() : List.of();
    }

    /**
     * 获取送入 LLM 的上下文消息（已压缩的早期消息不包含在内）
     */
    public List<Message> getContextMessages(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.getContextMessages() : List.of();
    }

    /**
     * 在一次加锁中取得上下文快照，供摘要任务计算压缩边界，避免读到不一致的中间态
     */
    public Session.ContextSnapshot snapshotContext(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.snapshotContext() : Session.ContextSnapshot.empty();
    }

    /**
     * 获取会话的摘要
     */
    public String getSummary(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.getSummary() : "";
    }

    /**
     * 获取会话的工具调用记录列表
     */
    public List<ToolCallRecord> getToolCallRecords(String sessionKey) {
        Session session = getExisting(sessionKey);
        return session != null ? session.getToolCallRecords() : List.of();
    }

    /**
     * 列出所有会话的元信息，按最后更新时间倒序。
     * 只读元信息索引，不会加载任何会话正文。
     */
    public List<SessionMeta> listMeta() {
        return listMeta(null);
    }

    /**
     * 列出指定访问者可见的会话元信息。
     *
     * @param viewer 访问者标识；为空表示不过滤（保持无身份调用方的原有行为）
     */
    public List<SessionMeta> listMeta(String viewer) {
        Map<String, SessionMeta> merged = new ConcurrentHashMap<>();
        for (SessionMeta meta : store.listMeta()) {
            if (meta.getKey() != null) {
                merged.put(meta.getKey(), meta);
            }
        }
        // 内存中的会话更新更及时，覆盖索引里的版本
        cache.forEach((key, session) -> merged.put(key, SessionMeta.from(session)));

        List<SessionMeta> metas = new ArrayList<>(merged.values());
        metas.removeIf(meta -> !meta.isVisibleTo(viewer));
        metas.sort(Comparator.comparing(
                SessionMeta::getUpdated, Comparator.nullsLast(Comparator.reverseOrder())));
        return metas;
    }

    // ==================== 全文检索 ====================

    /**
     * 搜索历史消息，返回命中的会话与消息下标。
     *
     * <p>先扫存储，再把内存中尚未落盘的消息补上：刚说完的话也能被搜到。
     * 已落盘的那部分会在两边都命中，按 (sessionKey, messageIndex) 去重。</p>
     *
     * @param query 查询词，大小写不敏感的精确子串（不做分词）
     * @param limit 结果上限；非正数时采用 {@link #DEFAULT_SEARCH_LIMIT}
     * @param viewer 访问者标识；为空表示不过滤
     */
    public List<SessionSearchHit> search(String query, int limit, String viewer) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int bounded = limit > 0 ? Math.min(limit, MAX_SEARCH_LIMIT) : DEFAULT_SEARCH_LIMIT;
        String needle = query.toLowerCase(Locale.ROOT);

        List<SessionSearchHit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (SessionSearchHit hit : store.search(query, bounded)) {
            if (!isVisible(hit.sessionKey(), viewer)) {
                continue;
            }
            if (seen.add(hit.sessionKey() + "#" + hit.messageIndex())) {
                hits.add(hit);
            }
        }

        // 内存中的会话可能含未落盘增量，补扫一遍
        for (Session session : cache.values()) {
            if (hits.size() >= bounded) {
                break;
            }
            if (!session.isVisibleTo(viewer)) {
                continue;
            }
            searchInMemory(session, needle, bounded, seen, hits);
        }

        return hits;
    }

    private void searchInMemory(Session session, String needle, int limit,
                                Set<String> seen, List<SessionSearchHit> hits) {
        List<Message> history = session.getHistory();
        String title = SessionMeta.from(session).getTitle();
        for (int i = 0; i < history.size() && hits.size() < limit; i++) {
            String content = history.get(i).getContent();
            if (content == null) {
                continue;
            }
            int matchAt = content.toLowerCase(Locale.ROOT).indexOf(needle);
            if (matchAt < 0) {
                continue;
            }
            if (!seen.add(session.getKey() + "#" + i)) {
                continue;
            }
            hits.add(new SessionSearchHit(session.getKey(), i, history.get(i).getRole(),
                    snippet(content, matchAt, needle.length()), title));
        }
    }

    private String snippet(String content, int matchAt, int matchLength) {
        int from = Math.max(0, matchAt - SNIPPET_CONTEXT_CHARS);
        int to = Math.min(content.length(), matchAt + matchLength + SNIPPET_CONTEXT_CHARS);
        String core = content.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + core + (to < content.length() ? "…" : "");
    }

    /**
     * 判定存储层命中的会话对访问者是否可见。
     *
     * <p>只看索引元信息，不为判可见性而加载会话正文——否则一次搜索会把
     * 所有命中会话都拉进缓存，把有界缓存冲掉。</p>
     */
    private boolean isVisible(String sessionKey, String viewer) {
        if (viewer == null || viewer.isBlank()) {
            return true;
        }
        Session cached = cache.get(sessionKey);
        if (cached != null) {
            return cached.isVisibleTo(viewer);
        }
        return store.listMeta().stream()
                .filter(meta -> sessionKey.equals(meta.getKey()))
                .findFirst()
                .map(meta -> meta.isVisibleTo(viewer))
                .orElse(true);
    }

    // ==================== 删除与生命周期 ====================

    /**
     * 删除会话：从缓存移除、标记删除位（阻止异步任务写回复活）、删除存储文件
     */
    public void deleteSession(String key) {
        Session removed = cache.remove(key);
        lastAccess.remove(key);
        if (removed != null) {
            removed.markDeleted();
        }
        store.delete(key);
    }

    /**
     * 刷盘并释放资源，进程退出前调用
     */
    public void close() {
        cache.values().forEach(session -> {
            if (session.hasPendingChanges()) {
                store.persist(session);
            }
        });
        store.flush();
    }

    // ==================== 缓存淘汰 ====================

    private void touch(String key) {
        lastAccess.put(key, System.currentTimeMillis());
        sweepIfNeeded();
    }

    /**
     * 按需淘汰缓存：先清理超时会话，容量仍超限时再淘汰空闲最久的。
     * 淘汰前刷盘，且只淘汰空闲足够久的会话，避免与正在进行的对话产生双实例。
     * 纯内存模式下缓存就是唯一副本，不执行任何淘汰。
     */
    private void sweepIfNeeded() {
        if (!durable) {
            return;
        }
        long now = System.currentTimeMillis();
        if (cache.size() <= MAX_CACHED_SESSIONS && now - lastSweepAt < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepAt = now;

        lastAccess.forEach((key, accessedAt) -> {
            if (now - accessedAt > CACHE_TTL_MS) {
                evict(key);
            }
        });

        if (cache.size() <= MAX_CACHED_SESSIONS) {
            return;
        }

        List<String> byIdle = new ArrayList<>(cache.keySet());
        byIdle.sort(Comparator.comparingLong(k -> lastAccess.getOrDefault(k, 0L)));
        for (String key : byIdle) {
            if (cache.size() <= MAX_CACHED_SESSIONS) {
                break;
            }
            if (now - lastAccess.getOrDefault(key, 0L) < MIN_IDLE_BEFORE_EVICT_MS) {
                break; // 剩下的都是活跃会话，宁可超出容量也不冒双实例的风险
            }
            evict(key);
        }
    }

    private void evict(String key) {
        Session session = cache.get(key);
        if (session == null) {
            lastAccess.remove(key);
            return;
        }
        if (session.hasPendingChanges()) {
            store.persist(session);
        }
        cache.remove(key);
        lastAccess.remove(key);
        logger.debug("Evicted session from cache: " + key);
    }
}
