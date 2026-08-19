package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
public class SessionManager {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("session");

    /** 缓存中最多保留的会话数 */
    private static final int MAX_CACHED_SESSIONS = 64;
    /** 空闲超过该时长的会话从缓存中移除 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    /** 淘汰前要求的最小空闲时长，避免淘汰正在处理中的会话 */
    private static final long MIN_IDLE_BEFORE_EVICT_MS = 5 * 60 * 1000L;
    /** 两次淘汰扫描的最小间隔 */
    private static final long SWEEP_INTERVAL_MS = 30 * 1000L;

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
        Map<String, SessionMeta> merged = new ConcurrentHashMap<>();
        for (SessionMeta meta : store.listMeta()) {
            if (meta.getKey() != null) {
                merged.put(meta.getKey(), meta);
            }
        }
        // 内存中的会话更新更及时，覆盖索引里的版本
        cache.forEach((key, session) -> merged.put(key, SessionMeta.from(session)));

        List<SessionMeta> metas = new ArrayList<>(merged.values());
        metas.sort(Comparator.comparing(
                SessionMeta::getUpdated, Comparator.nullsLast(Comparator.reverseOrder())));
        return metas;
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
