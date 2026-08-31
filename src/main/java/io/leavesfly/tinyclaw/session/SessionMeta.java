package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 会话元信息 - 列表展示所需的轻量视图
 *
 * <p>存在的意义是让「列出会话」不必加载任何会话正文：元信息集中维护在存储层索引里，
 * 列表接口只读索引，避免一次列表请求把整个 sessions 目录读进内存。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionMeta {

    private String key;
    private Instant created;
    private Instant updated;
    private int messageCount;
    /** 首条用户消息的截断文本，作为会话标题预览 */
    private String title;
    private boolean hasSummary;
    /** 会话归属人，null 表示尚未认领的历史会话 */
    private String owner;
    private SessionVisibility visibility = SessionVisibility.PRIVATE;
    /**
     * 当前进度卡，null 表示无进行中的长任务。
     * <p>跟着索引一起落盘，使浏览器刷新后仍能续看。</p>
     */
    private SessionProgress progress;

    public SessionMeta() {
    }

    public SessionMeta(String key, Instant created, Instant updated,
                       int messageCount, String title, boolean hasSummary) {
        this.key = key;
        this.created = created;
        this.updated = updated;
        this.messageCount = messageCount;
        this.title = title;
        this.hasSummary = hasSummary;
    }

    /**
     * 从完整会话对象提取元信息
     */
    public static SessionMeta from(Session session) {
        String title = session.getHistory().stream()
                .filter(m -> "user".equals(m.getRole())
                        && m.getContent() != null && !m.getContent().isBlank())
                .findFirst()
                .map(m -> truncateTitle(m.getContent()))
                .orElse("");
        String summary = session.getSummary();
        SessionMeta meta = new SessionMeta(session.getKey(), session.getCreated(), session.getUpdated(),
                session.messageCount(), title, summary != null && !summary.isBlank());
        meta.owner = session.getOwner();
        meta.visibility = session.getVisibility();
        meta.progress = session.getProgress();
        return meta;
    }

    /**
     * 对指定访问者是否可见。
     *
     * <p>与 {@link Session#isVisibleTo(String)} 保持同一套语义，但列表接口只能拿到元信息，
     * 不能为了判可见性而把每个会话正文都加载一遍——那正是元信息索引要避开的开销。
     * 代价是索引里不存 members，因此成员在列表阶段按 PRIVATE 会话处理；
     * 成员仍可直接打开会话详情，那条路径读的是 {@link Session}。</p>
     */
    public boolean isVisibleTo(String viewer) {
        if (viewer == null || viewer.isBlank()) {
            return true;
        }
        if (owner == null || owner.isBlank()) {
            return true;
        }
        return visibility == SessionVisibility.SHARED || owner.equals(viewer);
    }

    private static String truncateTitle(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 15 ? oneLine.substring(0, 15) + "…" : oneLine;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getUpdated() {
        return updated;
    }

    public void setUpdated(Instant updated) {
        this.updated = updated;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isHasSummary() {
        return hasSummary;
    }

    public void setHasSummary(boolean hasSummary) {
        this.hasSummary = hasSummary;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public SessionVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(SessionVisibility visibility) {
        this.visibility = visibility != null ? visibility : SessionVisibility.PRIVATE;
    }

    public SessionProgress getProgress() {
        return progress;
    }

    public void setProgress(SessionProgress progress) {
        this.progress = progress;
    }
}
