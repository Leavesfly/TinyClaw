package io.leavesfly.tinyclaw.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结构化记忆条目，是自进化记忆系统的基本单元。
 *
 * 每条记忆包含内容本身及其元数据，用于支持：
 * - 重要性评分：决定记忆在上下文中的优先级
 * - 时间衰减：越久远的记忆重要性越低（除非被频繁访问）
 * - 相关性检索：通过标签进行粗粒度匹配
 * - 访问追踪：记录记忆被使用的频率，高频访问的记忆抵抗衰减
 *
 * 评分公式（综合得分）：
 *   score = importance * decayFactor(age) * (1 + log(accessCount + 1))
 */
public class MemoryEntry {

    /** 记忆的唯一标识（基于创建时间戳 + 哈希） */
    private String id;

    /** 记忆内容 */
    private String content;

    /** 重要性评分，范围 0.0 ~ 1.0 */
    private double importance;

    /** 创建时间 */
    private Instant createdAt;

    /** 最后访问时间 */
    private Instant lastAccessedAt;

    /** 访问次数 */
    private int accessCount;

    /** 标签列表，用于粗粒度的相关性匹配 */
    private List<String> tags;

    /** 记忆来源（如 session_summary, heartbeat, user_explicit, evolution） */
    private String source;

    public MemoryEntry() {
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
        this.accessCount = 0;
        this.importance = 0.5;
        this.tags = new ArrayList<>();
        this.source = "unknown";
    }

    public MemoryEntry(String content, double importance, List<String> tags, String source) {
        this();
        this.content = content;
        this.importance = Math.max(0.0, Math.min(1.0, importance));
        if (tags != null) {
            this.tags = new ArrayList<>(tags);
        }
        this.source = source;
        this.id = generateId();
    }

    /**
     * 计算记忆的综合得分，综合考虑重要性、时间衰减和访问频率。
     *
     * 公式：score = importance * decayFactor * accessBoost
     * - decayFactor = 1 / (1 + ageDays / halfLifeDays)，半衰期默认 30 天
     * - accessBoost = 1 + ln(accessCount + 1)
     *
     * @return 综合得分，值越高越应该被优先选入上下文
     */
    public double computeScore() {
        double halfLifeDays = 30.0;
        double ageDays = (Instant.now().toEpochMilli() - createdAt.toEpochMilli()) / (1000.0 * 60 * 60 * 24);
        double decayFactor = 1.0 / (1.0 + ageDays / halfLifeDays);
        double accessBoost = 1.0 + Math.log(accessCount + 1);
        return importance * decayFactor * accessBoost;
    }

    /**
     * 记录一次访问，更新访问计数和最后访问时间。
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessedAt = Instant.now();
    }

    /**
     * 检查记忆是否与给定关键词相关。
     * 通过标签匹配和内容包含两种方式判断。
     *
     * @param keywords 关键词列表
     * @return 匹配的关键词数量
     */
    public int computeRelevance(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int matchCount = 0;
        String lowerContent = content != null ? content.toLowerCase() : "";
        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();
            // 标签精确匹配
            if (tags.stream().anyMatch(tag -> tag.toLowerCase().contains(lowerKeyword))) {
                matchCount += 2; // 标签匹配权重更高
            }
            // 内容包含匹配
            if (lowerContent.contains(lowerKeyword)) {
                matchCount += 1;
            }
        }
        return matchCount;
    }

    private String generateId() {
        return String.valueOf(Instant.now().toEpochMilli()) + "_" +
                Integer.toHexString(Objects.hash(content, createdAt));
    }

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) {
        this.importance = Math.max(0.0, Math.min(1.0, importance));
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>(); }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    @Override
    public String toString() {
        return String.format("MemoryEntry{id='%s', importance=%.2f, score=%.3f, tags=%s, source='%s'}",
                id, importance, computeScore(), tags, source);
    }
}
