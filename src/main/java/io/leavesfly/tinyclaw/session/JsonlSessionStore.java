package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;
import io.leavesfly.tinyclaw.providers.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于 append-only JSONL 的会话存储
 *
 * <h2>为什么是 append-only</h2>
 * <p>旧实现每次落盘都全量重写整个会话 JSON，一轮多次工具调用就是多次全量重写，写放大是
 * O(n²)。这里改为一行一条记录、只追加增量：单次写入代价与新增内容成正比，与历史长度无关；
 * 进程中途崩溃最多损坏末行，前面的内容依然可读。</p>
 *
 * <h2>文件布局</h2>
 * <pre>
 * sessions/
 *   _index.json                        会话元信息索引，列表查询只读它
 *   telegram_123-9f8e7d6c.jsonl        会话转录（header / msg / tool / compact 四类行）
 *   telegram_123.json.migrated         迁移后保留的旧格式备份
 *   corrupt/xxx.jsonl.corrupt.169...   无法解析时隔离保留的原文件
 * </pre>
 *
 * <h2>损坏处理</h2>
 * <p>解析失败绝不静默丢弃：能读出多少就恢复多少并告警；整份无法识别时把原文件移入
 * corrupt/ 保留证据，再交由上层新建会话——原始数据不会被覆盖式销毁。</p>
 *
 * <h2>已知限制</h2>
 * <p>未做跨进程文件锁，同一 workspace 同时运行多个实例时以最后写入者为准；索引可由
 * {@link #rebuildIndex()} 从转录文件重建，因此索引丢失不会造成数据损失。</p>
 */
public class JsonlSessionStore implements SessionStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("session.store");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 未知字段一律忽略：新增字段后回退版本不会导致整份会话读取失败
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final String JSONL_SUFFIX = ".jsonl";
    private static final String LEGACY_SUFFIX = ".json";
    private static final String MIGRATED_SUFFIX = ".json.migrated";
    private static final String INDEX_FILE = "_index.json";

    /** 行类型：文件头 / 消息 / 工具调用记录 / 上下文压缩 / 身份与可见性 */
    private static final String T_HEADER = "header";
    private static final String T_MSG = "msg";
    private static final String T_TOOL = "tool";
    private static final String T_COMPACT = "compact";
    private static final String T_META = "meta";

    /** 索引刷盘节流间隔：索引可重建，无需每次写入都落盘 */
    private static final long INDEX_FLUSH_INTERVAL_MS = 2000;
    /** 文件名中可读前缀的最大长度，避免超出文件系统单段 255 字节限制 */
    private static final int MAX_READABLE_NAME_LENGTH = 80;

    /** 搜索结果片段在命中位置两侧各保留的字符数 */
    private static final int SNIPPET_CONTEXT_CHARS = 40;


    private final Path root;
    private final Map<String, SessionMeta> index = new ConcurrentHashMap<>();
    private final Object indexWriteLock = new Object();

    private volatile boolean indexDirty;
    private volatile long lastIndexFlushAt;

    public JsonlSessionStore(String storagePath) throws IOException {
        this.root = Paths.get(storagePath);
        Files.createDirectories(root);
        if (!loadIndex()) {
            rebuildIndex();
        }
        discardStaleProgress();
    }

    /**
     * 丢弃索引里遗留的进度卡。
     *
     * <p>进度卡描述的是“正在跑”的任务。进程重启后这些任务已经不存在，把上次的进度
     * 读回来只会在界面上留下一个永远不会前进的进度条，比没有进度更误导人。</p>
     */
    private void discardStaleProgress() {
        boolean cleared = false;
        for (SessionMeta meta : index.values()) {
            if (meta.getProgress() != null) {
                meta.setProgress(null);
                cleared = true;
            }
        }
        if (cleared) {
            indexDirty = true;
            flush();
        }
    }

    // ==================== 读取 ====================

    @Override
    public Session load(String key) {
        Path path = sessionPath(key);
        if (!Files.exists(path)) {
            return migrateLegacy(key);
        }
        return readJsonl(key, path);
    }

    /**
     * 逐行解析 JSONL 转录。单行解析失败不会导致整份会话丢失：
     * 末行损坏视为崩溃留下的残行直接忽略，中间行损坏则跳过并计数告警。
     */
    private Session readJsonl(String key, Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            logger.error("Failed to read session file", Map.of(
                    "key", key, "error", String.valueOf(e.getMessage())));
            return null;
        }
        if (raw.length == 0) {
            return null;
        }

        List<Message> messages = new ArrayList<>();
        List<ToolCallRecord> records = new ArrayList<>();
        String summary = null;
        int contextStartIndex = 0;
        Instant created = null;
        Instant lastActivity = null;
        String owner = null;
        SessionVisibility visibility = null;
        Set<String> members = null;
        boolean recognized = false;
        int malformed = 0;

        String[] lines = new String(raw, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception e) {
                if (i == lines.length - 1) {
                    logger.warn("Ignored truncated tail line", Map.of("key", key));
                } else {
                    malformed++;
                }
                continue;
            }

            try {
                switch (node.path("t").asText("")) {
                    case T_HEADER -> {
                        recognized = true;
                        created = readInstant(node.get("created"));
                    }
                    case T_MSG -> {
                        recognized = true;
                        Message msg = MAPPER.treeToValue(node.get("data"), Message.class);
                        if (msg != null) {
                            messages.add(msg);
                            if (msg.getTimestamp() != null) {
                                lastActivity = msg.getTimestamp();
                            }
                        }
                    }
                    case T_TOOL -> {
                        recognized = true;
                        ToolCallRecord record = MAPPER.treeToValue(node.get("data"), ToolCallRecord.class);
                        if (record != null) {
                            records.add(record);
                        }
                    }
                    case T_COMPACT -> {
                        recognized = true;
                        summary = node.path("summary").asText(null);
                        contextStartIndex = node.path("contextStartIndex").asInt(0);
                        Instant ts = readInstant(node.get("ts"));
                        if (ts != null) {
                            lastActivity = ts;
                        }
                    }
                    case T_META -> {
                        recognized = true;
                        // 整行覆盖而非逐字段合并：写入时就是一个完整身份快照，
                        // 按字段合并会让“移除成员”这类变更永远生效不了
                        owner = node.path("owner").asText(null);
                        visibility = readVisibility(node.path("visibility").asText(null));
                        members = readMembers(node.get("members"));
                    }
                    default -> {
                        // 未知行类型：来自更新版本的记录，忽略但不视为损坏
                    }
                }
            } catch (Exception e) {
                malformed++;
            }
        }

        if (malformed > 0) {
            logger.warn("Recovered session with malformed lines skipped", Map.of(
                    "key", key, "malformed_lines", malformed, "recovered_messages", messages.size()));
        }

        if (!recognized) {
            quarantine(path, key, "unrecognized-format");
            return null;
        }

        Session session = new Session(key);
        session.setCreated(created != null ? created : fileTime(path));
        session.setMessages(messages);
        session.setToolCallRecords(records);
        if (summary != null && !summary.isEmpty()) {
            session.setSummary(summary);
        }
        session.setContextStartIndex(contextStartIndex);
        if (owner != null && !owner.isBlank()) {
            session.setOwner(owner);
        }
        if (visibility != null) {
            session.setVisibility(visibility);
        }
        if (members != null && !members.isEmpty()) {
            session.setMembers(members);
        }
        session.setUpdated(lastActivity != null ? lastActivity : fileTime(path));
        session.markFullyPersisted();

        touchIndex(session);
        logger.debug("Loaded session: " + key + " (" + messages.size() + " messages)");
        return session;
    }

    /**
     * 把旧的单文件 JSON 会话迁移为 JSONL，原文件重命名为 .json.migrated 保留备份。
     * 解析失败时隔离原文件，不做任何破坏性处理。
     */
    private Session migrateLegacy(String key) {
        Path legacy = root.resolve(readableName(key) + LEGACY_SUFFIX);
        if (!Files.exists(legacy)) {
            return null;
        }

        Session session;
        try {
            session = MAPPER.readValue(Files.readAllBytes(legacy), Session.class);
        } catch (Exception e) {
            logger.error("Failed to parse legacy session, quarantined", Map.of(
                    "key", key, "error", String.valueOf(e.getMessage())));
            quarantine(legacy, key, "legacy-parse-failed");
            return null;
        }

        if (session.getKey() == null || session.getKey().isEmpty()) {
            session.setKey(key);
        }

        try {
            writeFull(session);
            Files.move(legacy, root.resolve(readableName(key) + MIGRATED_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
            logger.info("Migrated legacy session to jsonl", Map.of(
                    "key", key, "messages", session.messageCount()));
        } catch (Exception e) {
            logger.error("Failed to migrate legacy session", Map.of(
                    "key", key, "error", String.valueOf(e.getMessage())));
            return null;
        }
        return session;
    }

    // ==================== 写入 ====================

    @Override
    public void persist(Session session) {
        if (session == null || session.getKey() == null) {
            return;
        }
        // 已删除的会话不得写回，否则异步摘要任务会把它复活成空壳
        if (session.isDeleted()) {
            logger.debug("Skipped persist for deleted session: " + session.getKey());
            return;
        }

        try {
            session.persistDelta(delta -> {
                Path path = sessionPath(session.getKey());
                StringBuilder buffer = new StringBuilder();
                if (!Files.exists(path)) {
                    buffer.append(headerLine(session));
                }
                int messageIndex = delta.fromIndex();
                for (Message msg : delta.newMessages()) {
                    buffer.append(messageLine(messageIndex++, msg));
                }
                for (ToolCallRecord record : delta.newRecords()) {
                    buffer.append(recordLine(record));
                }
                if (delta.compactionChanged()) {
                    buffer.append(compactLine(delta.summary(), delta.contextStartIndex()));
                }
                if (delta.identityChanged()) {
                    buffer.append(metaLine(delta.identity()));
                }
                JsonFileStore.appendAndSync(path, buffer.toString());
            });
            touchIndex(session);
        } catch (Exception e) {
            logger.error("Failed to persist session", Map.of(
                    "key", session.getKey(), "error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 全量重写会话文件（迁移与索引重建场景使用）
     */
    private void writeFull(Session session) throws IOException {
        StringBuilder buffer = new StringBuilder(headerLine(session));
        List<Message> messages = session.getHistory();
        for (int i = 0; i < messages.size(); i++) {
            buffer.append(messageLine(i, messages.get(i)));
        }
        for (ToolCallRecord record : session.getToolCallRecords()) {
            buffer.append(recordLine(record));
        }
        String summary = session.getSummary();
        if ((summary != null && !summary.isEmpty()) || session.getContextStartIndex() > 0) {
            buffer.append(compactLine(summary, session.getContextStartIndex()));
        }
        Session.Identity identity = new Session.Identity(
                session.getOwner(), session.getVisibility(), session.getMembers());
        if (!identity.isEmpty()) {
            buffer.append(metaLine(identity));
        }

        JsonFileStore.writeAtomic(sessionPath(session.getKey()), buffer.toString());
        session.markFullyPersisted();
        touchIndex(session);
    }

    private String headerLine(Session session) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", T_HEADER);
        node.put("v", Session.SCHEMA_VERSION);
        node.put("key", session.getKey());
        node.set("created", MAPPER.valueToTree(session.getCreated()));
        return MAPPER.writeValueAsString(node) + "\n";
    }

    private String messageLine(int index, Message message) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", T_MSG);
        node.put("i", index);
        node.set("data", MAPPER.valueToTree(message));
        return MAPPER.writeValueAsString(node) + "\n";
    }

    private String recordLine(ToolCallRecord record) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", T_TOOL);
        node.set("data", MAPPER.valueToTree(record));
        return MAPPER.writeValueAsString(node) + "\n";
    }

    private String compactLine(String summary, int contextStartIndex) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", T_COMPACT);
        node.put("summary", summary);
        node.put("contextStartIndex", contextStartIndex);
        node.set("ts", MAPPER.valueToTree(Instant.now()));
        return MAPPER.writeValueAsString(node) + "\n";
    }

    /**
     * 身份行：写完整快照，读取时最后一行胜。
     *
     * <p>身份变更频率极低（基本只在会话首次认领时发生一次），因此追加全量快照
     * 不会造成可观察的写放大，而它换来的好处是读取逻辑只需“取最后一行”。</p>
     */
    private String metaLine(Session.Identity identity) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", T_META);
        node.put("owner", identity.owner());
        node.put("visibility", identity.visibility() != null
                ? identity.visibility().name() : SessionVisibility.PRIVATE.name());
        node.set("members", MAPPER.valueToTree(identity.members()));
        node.set("ts", MAPPER.valueToTree(Instant.now()));
        return MAPPER.writeValueAsString(node) + "\n";
    }

    // ==================== 删除与隔离 ====================

    @Override
    public void delete(String key) {
        index.remove(key);
        indexDirty = true;
        try {
            Files.deleteIfExists(sessionPath(key));
            Files.deleteIfExists(root.resolve(readableName(key) + LEGACY_SUFFIX));
            logger.debug("Deleted session: " + key);
        } catch (IOException e) {
            logger.warn("Failed to delete session file: " + key);
        }
        flush();
    }

    /**
     * 把无法解析的文件移入 corrupt/ 保留，而不是让上层覆盖销毁
     */
    private void quarantine(Path path, String key, String reason) {
        Path target = JsonFileStore.quarantine(path, reason);
        if (target != null) {
            logger.error("Session file quarantined, original content preserved", Map.of(
                    "key", key, "reason", reason, "quarantined_to", target.toString()));
        }
    }

    // ==================== 元信息索引 ====================

    @Override
    public List<SessionMeta> listMeta() {
        List<SessionMeta> metas = new ArrayList<>(index.values());
        metas.sort(Comparator.comparing(
                SessionMeta::getUpdated, Comparator.nullsLast(Comparator.reverseOrder())));
        return metas;
    }

    @Override
    public boolean exists(String key) {
        return index.containsKey(key)
                || Files.exists(sessionPath(key))
                || Files.exists(root.resolve(readableName(key) + LEGACY_SUFFIX));
    }

    // ==================== 全文检索 ====================

    /**
     * 按子串扫描转录文件。
     *
     * <h2>为何不建倒排索引</h2>
     * <p>倒排索引需要分词，而中文分词在无依赖的前提下只能做到很粗；更重要的是索引需要
     * 与转录保持同步，而一个会不同步的索引比没有索引更难排查。个人部署的会话量级（
     * 千数量级消息）下直接扫文件完全够用，且永远不会与真实数据不一致。</p>
     *
     * <h2>扫描顺序</h2>
     * <p>按索引的最后更新时间倒序逐个会话扫，凑够 {@code limit} 就停：
     * 用户搜的绝大多数是最近的对话，先扫活跃会话能让常见查询在读几个文件后就返回。</p>
     */
    @Override
    public List<SessionSearchHit> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<SessionSearchHit> hits = new ArrayList<>();

        for (SessionMeta meta : listMeta()) {
            if (hits.size() >= limit) {
                break;
            }
            searchOne(meta, needle, limit, hits);
        }
        return hits;
    }

    /**
     * 扫单个会话的转录文件。
     *
     * <p>直接逐行读 JSONL 而不走 {@link #load}：搜索只需要 msg 行的文本，
     * 把会话完整反序列化成对象会把整个 sessions 目录都拉进内存，
     * 也会意外把会话写进 {@code index}。</p>
     *
     * <p>行上的 {@code i} 字段就是写入时记下的绝对下标，因此无需自己计数
     * ——自己计数会在中间行损坏时与真实下标错位。</p>
     */
    private void searchOne(SessionMeta meta, String needle, int limit, List<SessionSearchHit> hits) {
        Path path = sessionPath(meta.getKey());
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (hits.size() >= limit) {
                    return;
                }
                if (line.isBlank() || !line.toLowerCase(Locale.ROOT).contains(needle)) {
                    // 先在原始行上做一次廉价筛选，避开大量不可能命中的行的 JSON 解析
                    continue;
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                if (!T_MSG.equals(node.path("t").asText(""))) {
                    continue;
                }
                JsonNode data = node.path("data");
                String content = data.path("content").asText("");
                int matchAt = content.toLowerCase(Locale.ROOT).indexOf(needle);
                if (matchAt < 0) {
                    // 行里命中但不在 content（如命中了工具参数或字段名），不算可展示的命中
                    continue;
                }
                hits.add(new SessionSearchHit(meta.getKey(),
                        node.path("i").asInt(-1),
                        data.path("role").asText(""),
                        snippet(content, matchAt, needle.length()),
                        meta.getTitle()));
            }
        } catch (IOException e) {
            logger.warn("Failed to scan session for search: " + meta.getKey());
        }
    }

    /**
     * 截取命中位置前后的上下文片段，两端有裁剪时加省略号
     */
    private String snippet(String content, int matchAt, int matchLength) {
        int from = Math.max(0, matchAt - SNIPPET_CONTEXT_CHARS);
        int to = Math.min(content.length(), matchAt + matchLength + SNIPPET_CONTEXT_CHARS);
        String core = content.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + core + (to < content.length() ? "…" : "");
    }

    private void touchIndex(Session session) {
        index.put(session.getKey(), SessionMeta.from(session));
        indexDirty = true;
        long now = System.currentTimeMillis();
        if (now - lastIndexFlushAt >= INDEX_FLUSH_INTERVAL_MS) {
            flush();
        }
    }

    @Override
    public void flush() {
        if (!indexDirty) {
            return;
        }
        synchronized (indexWriteLock) {
            if (!indexDirty) {
                return;
            }
            try {
                JsonFileStore.writeAtomic(root.resolve(INDEX_FILE),
                        MAPPER.writeValueAsString(index));
                indexDirty = false;
                lastIndexFlushAt = System.currentTimeMillis();
            } catch (IOException e) {
                logger.warn("Failed to flush session index: " + e.getMessage());
            }
        }
    }

    /**
     * 载入元信息索引，成功返回 true
     */
    private boolean loadIndex() {
        Path indexPath = root.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            return false;
        }
        try {
            Map<String, SessionMeta> loaded = MAPPER.readValue(Files.readAllBytes(indexPath),
                    MAPPER.getTypeFactory().constructMapType(
                            java.util.HashMap.class, String.class, SessionMeta.class));
            index.putAll(loaded);
            lastIndexFlushAt = System.currentTimeMillis();
            logger.info("Loaded session index", Map.of("sessions", index.size()));
            return true;
        } catch (Exception e) {
            logger.warn("Session index unreadable, rebuilding from transcripts: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从转录文件重建索引。仅在索引缺失或损坏时执行一次，之后由增量维护。
     */
    private void rebuildIndex() {
        try (Stream<Path> files = Files.list(root)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        // 下划线开头的是管理文件（索引、活跃会话指针），不是会话转录
                        if (name.startsWith("_")) {
                            return false;
                        }
                        return name.endsWith(JSONL_SUFFIX) || name.endsWith(LEGACY_SUFFIX);
                    })
                    .toList();

            for (Path path : candidates) {
                String key = peekKey(path);
                if (key == null) {
                    continue;
                }
                Session session = load(key);
                if (session != null) {
                    index.put(key, SessionMeta.from(session));
                }
            }
            indexDirty = true;
            flush();
            logger.info("Rebuilt session index", Map.of("sessions", index.size()));
        } catch (IOException e) {
            logger.warn("Failed to rebuild session index: " + e.getMessage());
        }
    }

    /**
     * 从文件中嗅探会话 key：JSONL 读首行 header，旧格式读 key 字段
     */
    private String peekKey(Path path) {
        try {
            if (path.getFileName().toString().endsWith(JSONL_SUFFIX)) {
                try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                    return lines.findFirst()
                            .map(line -> {
                                try {
                                    return MAPPER.readTree(line).path("key").asText(null);
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .orElse(null);
                }
            }
            return MAPPER.readTree(Files.readAllBytes(path)).path("key").asText(null);
        } catch (Exception e) {
            logger.warn("Failed to peek session key: " + path.getFileName());
            return null;
        }
    }

    // ==================== 文件名映射 ====================

    /**
     * 会话文件路径：可读前缀 + key 哈希短码。
     * 加哈希是因为单纯替换非法字符会让不同 key（如 a:b 与 a_b）落到同一文件互相覆盖。
     */
    private Path sessionPath(String key) {
        return root.resolve(readableName(key) + "-" + shortHash(key) + JSONL_SUFFIX);
    }

    /**
     * 替换文件名中的不安全字符并限长，与旧实现保持一致以便识别旧文件
     */
    private String readableName(String key) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        String safe = key.replaceAll("[:/\\\\*?\"<>|]", "_");
        return safe.length() > MAX_READABLE_NAME_LENGTH
                ? safe.substring(0, MAX_READABLE_NAME_LENGTH) : safe;
    }

    private String shortHash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key == null
                    ? new byte[0] : key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(key).hashCode());
        }
    }

    // ==================== 小工具 ====================

    private Instant readInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(node, Instant.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析可见性枚举。未知取值退回 PRIVATE：读不懂时选择更保守的一侧，
     * 而不是把一个本应私有的会话当成共享会话。
     */
    private SessionVisibility readVisibility(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SessionVisibility.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SessionVisibility.PRIVATE;
        }
    }

    private Set<String> readMembers(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        Set<String> members = new LinkedHashSet<>();
        node.forEach(item -> {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                members.add(value);
            }
        });
        return members;
    }

    private Instant fileTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }
}
