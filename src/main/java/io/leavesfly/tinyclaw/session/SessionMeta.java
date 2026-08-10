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
        return new SessionMeta(session.getKey(), session.getCreated(), session.getUpdated(),
                session.messageCount(), title, summary != null && !summary.isBlank());
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
}
