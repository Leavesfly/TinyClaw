package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.leavesfly.tinyclaw.providers.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话 - 表示一次对话
 *
 * <h2>不可变转录 + 可变上下文视图</h2>
 * <p>messages 是只增不删的完整转录（source of truth）；{@link #contextStartIndex} 标记
 * LLM 上下文的起点，早于该位置的消息已被 summary 覆盖，不再送入模型，但仍完整保留在
 * 存储中供历史回放。压缩上下文因此不再销毁历史，也不会让 {@link ToolCallRecord} 的
 * 绝对下标失效。</p>
 *
 * <h2>身份与可见性</h2>
 * <p>{@link #owner} / {@link #visibility} / {@link #members} 把“这是谁的会话”显式落到会话上。
 * owner 编码复用记忆层的 {@code u:<channel>:<senderId>} 格式，使两层的身份可直接对应。
 * owner 为空的历史会话视为 legacy，对所有请求可见——过滤能力不得把已有数据静默隐藏。</p>
 *
 * <h2>线程安全</h2>
 * <p>Web HTTP 线程池、Agent 主循环、摘要守护线程会并发访问同一会话，故所有读写都在
 * {@link #lock} 内完成，读操作统一返回快照副本。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Session {

    /** 会话记录的 schema 版本，写入存储用于向后兼容判断 */
    public static final int SCHEMA_VERSION = 1;

    private String key;
    private List<Message> messages;
    private String summary;
    private Instant created;
    private Instant updated;
    /** 工具调用记录列表，用于历史会话回放时重建工具调用卡片 */
    private List<ToolCallRecord> toolCallRecords;

    /**
     * LLM 上下文起点：下标小于该值的消息已被 summary 覆盖，不再进入上下文，
     * 但仍保留在 messages 中用于历史回放。只增不减。
     */
    private int contextStartIndex;

    /** 会话归属人，形如 {@code u:<channel>:<senderId>}；null 表示尚未认领的历史会话 */
    private String owner;

    /** 可见性，默认仅归属人可见 */
    private SessionVisibility visibility = SessionVisibility.PRIVATE;

    /** 除 owner 之外的可见成员，用 LinkedHashSet 保留加入顺序便于展示 */
    private Set<String> members = new LinkedHashSet<>();

    /**
     * 当前进度卡，null 表示无进行中的长任务。
     *
     * <p>不写转录（参见 {@link SessionProgress}），因此对 Jackson 屏蔽；
     * 它经 {@link SessionMeta} 进入会话索引。</p>
     */
    @JsonIgnore
    private SessionProgress progress;

    /** 会话内所有读写的互斥锁 */
    @JsonIgnore
    private final ReentrantLock lock = new ReentrantLock();

    /** 已落盘的消息条数，增量 append 时据此计算 delta */
    @JsonIgnore
    private int persistedMessageCount;

    /** 已落盘的工具调用记录条数 */
    @JsonIgnore
    private int persistedRecordCount;

    /** summary / contextStartIndex 是否存在未落盘的变更 */
    @JsonIgnore
    private boolean compactionDirty;

    /** owner / visibility / members 是否存在未落盘的变更 */
    @JsonIgnore
    private boolean identityDirty;

    /** 会话已被删除：阻止仍持有引用的异步任务把它写回磁盘（僵尸复活） */
    @JsonIgnore
    private volatile boolean deleted;

    public Session() {
        this.messages = new ArrayList<>();
        this.toolCallRecords = new ArrayList<>();
        this.created = Instant.now();
        this.updated = Instant.now();
    }

    public Session(String key) {
        this();
        this.key = key;
    }

    // ==================== Getters and Setters ====================

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    /**
     * 返回内部消息列表的快照副本。供 Jackson 序列化与测试使用；
     * 业务代码请用 {@link #getHistory()} 或 {@link #getContextMessages()}。
     */
    public List<Message> getMessages() {
        return getHistory();
    }

    public void setMessages(List<Message> messages) {
        lock.lock();
        try {
            this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        } finally {
            lock.unlock();
        }
    }

    public String getSummary() {
        lock.lock();
        try {
            return summary;
        } finally {
            lock.unlock();
        }
    }

    public void setSummary(String summary) {
        lock.lock();
        try {
            this.summary = summary;
            this.updated = Instant.now();
            this.compactionDirty = true;
        } finally {
            lock.unlock();
        }
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getUpdated() {
        lock.lock();
        try {
            return updated;
        } finally {
            lock.unlock();
        }
    }

    public void setUpdated(Instant updated) {
        lock.lock();
        try {
            this.updated = updated;
        } finally {
            lock.unlock();
        }
    }

    public List<ToolCallRecord> getToolCallRecords() {
        lock.lock();
        try {
            return new ArrayList<>(toolCallRecords);
        } finally {
            lock.unlock();
        }
    }

    public void setToolCallRecords(List<ToolCallRecord> toolCallRecords) {
        lock.lock();
        try {
            this.toolCallRecords = toolCallRecords != null
                    ? new ArrayList<>(toolCallRecords) : new ArrayList<>();
        } finally {
            lock.unlock();
        }
    }

    public int getContextStartIndex() {
        lock.lock();
        try {
            return contextStartIndex;
        } finally {
            lock.unlock();
        }
    }

    public void setContextStartIndex(int contextStartIndex) {
        lock.lock();
        try {
            this.contextStartIndex = Math.max(0, contextStartIndex);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 身份与可见性 ====================

    public String getOwner() {
        lock.lock();
        try {
            return owner;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 直接设置 owner。仅供反序列化与管理操作使用；
     * 消息链路请用 {@link #claimOwner(String)}，它不会抢占已有归属。
     */
    public void setOwner(String owner) {
        lock.lock();
        try {
            this.owner = owner;
            this.identityDirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 认领归属：仅当前无 owner 时写入。
     *
     * <p>每条消息都会调本方法，因此必须幂等且不覆盖：群聊里后发言的人不应当把会话
     * 从创建者手里抢过去，否则会话归属会随最后一个发言者漂移。</p>
     *
     * @param candidate 候选归属人；空值被忽略
     * @return 实际写入返回 true
     */
    public boolean claimOwner(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        lock.lock();
        try {
            if (owner != null && !owner.isBlank()) {
                return false;
            }
            this.owner = candidate;
            this.identityDirty = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public SessionVisibility getVisibility() {
        lock.lock();
        try {
            return visibility;
        } finally {
            lock.unlock();
        }
    }

    public void setVisibility(SessionVisibility visibility) {
        lock.lock();
        try {
            SessionVisibility target = visibility != null ? visibility : SessionVisibility.PRIVATE;
            if (target != this.visibility) {
                this.visibility = target;
                this.identityDirty = true;
            }
        } finally {
            lock.unlock();
        }
    }

    public Set<String> getMembers() {
        lock.lock();
        try {
            return new LinkedHashSet<>(members);
        } finally {
            lock.unlock();
        }
    }

    public void setMembers(Set<String> members) {
        lock.lock();
        try {
            this.members = members != null ? new LinkedHashSet<>(members) : new LinkedHashSet<>();
            this.identityDirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加可见成员，实际新增时返回 true
     */
    public boolean addMember(String member) {
        if (member == null || member.isBlank()) {
            return false;
        }
        lock.lock();
        try {
            if (!members.add(member)) {
                return false;
            }
            this.identityDirty = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判定会话对指定访问者是否可见。
     *
     * <p>两个故意开放的口子：</p>
     * <ul>
     *   <li>{@code viewer} 为空 → 可见。调用方未声明身份就不过滤，Web 控制台当前只有单密码
     *       鉴权、没有用户身份，若默认全部不可见会直接把控制台变成空列表。</li>
     *   <li>{@code owner} 为空 → 可见。本特性上线前的历史会话都没有归属，
     *       把它们当作不可见等于升级后历史会话集体消失。</li>
     * </ul>
     */
    public boolean isVisibleTo(String viewer) {
        if (viewer == null || viewer.isBlank()) {
            return true;
        }
        lock.lock();
        try {
            if (owner == null || owner.isBlank()) {
                return true;
            }
            return visibility == SessionVisibility.SHARED
                    || owner.equals(viewer)
                    || members.contains(viewer);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 进度卡 ====================

    @JsonIgnore
    public SessionProgress getProgress() {
        lock.lock();
        try {
            return progress;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 更新或清除进度卡。传 null 表示任务结束。
     *
     * <p>不标脏位：进度不进转录，也不应该因为进度变化而触发一次转录写盘。</p>
     */
    public void setProgress(SessionProgress progress) {
        lock.lock();
        try {
            this.progress = progress;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 写入 ====================

    /**
     * 向会话添加一条简单消息
     */
    public void addMessage(String role, String content) {
        addFullMessage(new Message(role, content));
    }

    /**
     * 向会话添加完整消息（包含工具调用），入库时补齐 id 与 timestamp
     */
    public void addFullMessage(Message message) {
        if (message == null) {
            return;
        }
        lock.lock();
        try {
            stampIdentity(message);
            this.messages.add(message);
            this.updated = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加一条工具调用记录。
     * messageIndex 由调用方传入，表示触发该工具调用的 assistant 消息在完整转录中的绝对下标。
     */
    public void addToolCallRecord(ToolCallRecord record) {
        if (record == null) {
            return;
        }
        lock.lock();
        try {
            this.toolCallRecords.add(record);
            this.updated = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 为消息补齐持久化身份字段（幂等：已有值不覆盖）
     */
    private void stampIdentity(Message message) {
        if (message.getId() == null) {
            message.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(Instant.now());
        }
    }

    // ==================== 读取 ====================

    /**
     * 获取完整转录的快照副本（含已压缩部分）。
     * 用于历史回放，以及计算 {@link ToolCallRecord} 的绝对下标。
     */
    public List<Message> getHistory() {
        lock.lock();
        try {
            return new ArrayList<>(messages);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取送入 LLM 的上下文消息快照：从 {@link #contextStartIndex} 起的部分。
     * 已被 summary 覆盖的早期消息不包含在内。
     */
    public List<Message> getContextMessages() {
        lock.lock();
        try {
            if (contextStartIndex <= 0) {
                return new ArrayList<>(messages);
            }
            if (contextStartIndex >= messages.size()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(messages.subList(contextStartIndex, messages.size()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 完整转录的消息条数
     */
    public int messageCount() {
        lock.lock();
        try {
            return messages.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在一次加锁中取得上下文快照：起点、完整转录长度、上下文消息一致对应同一时刻。
     *
     * <p>摘要任务据此计算压缩边界。若分多次读取，摘要期间新追加的消息会让边界算错，
     * 进而把「未被摘要」的消息也压缩掉。</p>
     */
    public ContextSnapshot snapshotContext() {
        lock.lock();
        try {
            int start = Math.min(contextStartIndex, messages.size());
            return new ContextSnapshot(start, messages.size(),
                    new ArrayList<>(messages.subList(start, messages.size())));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 上下文快照：{@code startIndex} 为上下文起点，{@code totalMessages} 为完整转录长度，
     * {@code contextMessages} 为二者之间的消息副本。
     */
    public record ContextSnapshot(int startIndex, int totalMessages, List<Message> contextMessages) {

        public static ContextSnapshot empty() {
            return new ContextSnapshot(0, 0, List.of());
        }
    }

    // ==================== 上下文压缩 ====================

    /**
     * 以非破坏方式压缩上下文：写入 summary 并把上下文起点前移到 newStartIndex。
     *
     * <p>调用方传入的 newStartIndex 应基于生成该 summary 时的历史快照计算，
     * 这样即使摘要期间有新消息追加，也只会压缩「已被摘要覆盖」的区间，
     * 不会吞掉未摘要的消息。起点只增不减，重复或过期的压缩请求会被忽略。</p>
     *
     * @param summary       摘要内容，为 null 时保留原有摘要
     * @param newStartIndex 新的上下文起点（完整转录中的绝对下标）
     * @return 实际生效返回 true；起点未前移返回 false
     */
    public boolean compactContext(String summary, int newStartIndex) {
        lock.lock();
        try {
            int target = Math.min(Math.max(newStartIndex, 0), messages.size());
            target = adjustStartIndexForToolMessageIntegrity(target);
            if (target <= contextStartIndex) {
                return false;
            }
            this.contextStartIndex = target;
            if (summary != null) {
                this.summary = summary;
            }
            this.updated = Instant.now();
            this.compactionDirty = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 调整上下文起点，确保不破坏 tool_calls / tool 消息的配对关系。
     *
     * 策略：
     * 1. 如果起始位置是 tool 消息，向前查找其对应的 assistant(tool_calls) 消息并包含它
     * 2. 如果向前找不到对应的 assistant 消息，则向后跳过所有孤立的 tool 消息
     */
    private int adjustStartIndexForToolMessageIntegrity(int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) {
            return startIndex;
        }

        Message startMessage = messages.get(startIndex);
        if (!"tool".equals(startMessage.getRole())) {
            return startIndex;
        }

        // 向前查找最近的 assistant(tool_calls) 消息
        for (int i = startIndex - 1; i >= 0; i--) {
            Message candidate = messages.get(i);
            if ("assistant".equals(candidate.getRole())
                    && candidate.getToolCalls() != null
                    && !candidate.getToolCalls().isEmpty()) {
                return i;
            }
            // 如果遇到非 tool 且非目标 assistant 的消息，停止向前查找
            if (!"tool".equals(candidate.getRole())) {
                break;
            }
        }

        // 向前找不到配对的 assistant，向后跳过所有孤立的 tool 消息
        int adjusted = startIndex;
        while (adjusted < messages.size() && "tool".equals(messages.get(adjusted).getRole())) {
            adjusted++;
        }
        return adjusted;
    }

    // ==================== 持久化协作（仅 session 包内使用） ====================

    /**
     * 在锁保护下计算尚未落盘的增量，交给存储层写入，成功后推进落盘水位。
     *
     * <p>整个过程持锁，保证「取增量 → 写盘 → 推进水位」是原子的：并发的
     * addMessage 会等待，不会出现序列化中途集合被修改，也不会漏写或重复写。</p>
     *
     * @param writer 增量写入器，抛异常表示写入失败，水位不推进
     */
    void persistDelta(DeltaWriter writer) throws Exception {
        lock.lock();
        try {
            List<Message> newMessages = messages.size() > persistedMessageCount
                    ? new ArrayList<>(messages.subList(persistedMessageCount, messages.size()))
                    : List.of();
            List<ToolCallRecord> newRecords = toolCallRecords.size() > persistedRecordCount
                    ? new ArrayList<>(toolCallRecords.subList(persistedRecordCount, toolCallRecords.size()))
                    : List.of();

            if (newMessages.isEmpty() && newRecords.isEmpty() && !compactionDirty && !identityDirty) {
                return;
            }

            writer.write(new Delta(persistedMessageCount, newMessages, newRecords,
                    compactionDirty, summary, contextStartIndex,
                    identityDirty, new Identity(owner, visibility, new LinkedHashSet<>(members))));

            persistedMessageCount = messages.size();
            persistedRecordCount = toolCallRecords.size();
            compactionDirty = false;
            identityDirty = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 标记全部内容已落盘（用于加载完成或全量重写之后重置水位）
     */
    void markFullyPersisted() {
        lock.lock();
        try {
            persistedMessageCount = messages.size();
            persistedRecordCount = toolCallRecords.size();
            compactionDirty = false;
            identityDirty = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 是否存在未落盘的变更
     */
    boolean hasPendingChanges() {
        lock.lock();
        try {
            return compactionDirty
                    || identityDirty
                    || messages.size() > persistedMessageCount
                    || toolCallRecords.size() > persistedRecordCount;
        } finally {
            lock.unlock();
        }
    }

    @JsonIgnore
    public boolean isDeleted() {
        return deleted;
    }

    void markDeleted() {
        this.deleted = true;
    }

    /**
     * 一次落盘的增量快照
     */
    record Delta(int fromIndex,
                 List<Message> newMessages,
                 List<ToolCallRecord> newRecords,
                 boolean compactionChanged,
                 String summary,
                 int contextStartIndex,
                 boolean identityChanged,
                 Identity identity) {
    }

    /**
     * 身份快照，随增量一并交给存储层。
     *
     * <p>身份三个字段必须作为一个整体写入：存储层按“最后一行胜”读取，
     * 分字段写会让只改了 visibility 的一行把 members 清空。</p>
     */
    record Identity(String owner, SessionVisibility visibility, Set<String> members) {

        /** 是否已有归属或成员，均为空时无需写入 meta 行 */
        boolean isEmpty() {
            return (owner == null || owner.isBlank()) && members.isEmpty();
        }
    }

    /**
     * 增量写入回调，由存储层实现
     */
    interface DeltaWriter {
        void write(Delta delta) throws Exception;
    }
}
