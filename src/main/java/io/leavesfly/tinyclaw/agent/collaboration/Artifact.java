package io.leavesfly.tinyclaw.agent.collaboration;

import java.util.HashMap;
import java.util.Map;

/**
 * 协同工件（Artifact）
 * <p>表示多 Agent 协同过程中产生的结构化中间产物，如代码、设计文档、分析报告等。
 * 相比通过对话历史传递自由文本，Artifact 提供类型化、可检索的共享机制，
 * 减少 token 浪费，提升信息传递精度。
 *
 * <p>使用示例：
 * <pre>{@code
 * Artifact artifact = Artifact.of("design-doc", ArtifactType.DESIGN, "微服务架构设计方案...", "架构师");
 * context.publishArtifact(artifact);
 *
 * // 后续 Agent 按类型检索
 * List<Artifact> designs = context.getArtifactsByType(ArtifactType.DESIGN);
 * }</pre>
 */
public class Artifact {

    /**
     * 工件类型枚举
     */
    public enum ArtifactType {
        /** 代码片段或完整代码文件 */
        CODE,
        /** 设计文档或架构方案 */
        DESIGN,
        /** 分析报告 */
        ANALYSIS,
        /** 测试用例或测试结果 */
        TEST,
        /** 数据或数据集 */
        DATA,
        /** 通用文档 */
        DOCUMENT,
        /** 决策记录 */
        DECISION,
        /** 其他 */
        OTHER
    }

    /** 工件唯一标识 */
    private String artifactId;

    /** 工件类型 */
    private ArtifactType type;

    /** 工件内容 */
    private String content;

    /** 产出者角色名称 */
    private String producer;

    /** 工件标题/简短描述 */
    private String title;

    /** 创建时间戳 */
    private long createdAt;

    /** 版本号（支持同一 artifactId 的多次更新） */
    private int version;

    /** 附加元数据（如文件路径、语言类型等） */
    private Map<String, Object> metadata;

    public Artifact() {
        this.createdAt = System.currentTimeMillis();
        this.version = 1;
        this.metadata = new HashMap<>();
    }

    public Artifact(String artifactId, ArtifactType type, String content, String producer) {
        this();
        this.artifactId = artifactId;
        this.type = type;
        this.content = content;
        this.producer = producer;
    }

    /**
     * 工厂方法：快速创建工件
     */
    public static Artifact of(String artifactId, ArtifactType type, String content, String producer) {
        return new Artifact(artifactId, type, content, producer);
    }

    /**
     * 创建代码工件
     */
    public static Artifact code(String artifactId, String code, String producer, String language) {
        Artifact artifact = new Artifact(artifactId, ArtifactType.CODE, code, producer);
        artifact.metadata.put("language", language);
        return artifact;
    }

    /**
     * 创建分析报告工件
     */
    public static Artifact analysis(String artifactId, String content, String producer) {
        return new Artifact(artifactId, ArtifactType.ANALYSIS, content, producer);
    }

    /**
     * 创建决策记录工件
     */
    public static Artifact decision(String artifactId, String content, String producer) {
        return new Artifact(artifactId, ArtifactType.DECISION, content, producer);
    }

    // -------------------------------------------------------------------------
    // 链式配置
    // -------------------------------------------------------------------------

    public Artifact withTitle(String title) {
        this.title = title;
        return this;
    }

    public Artifact withMeta(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    /**
     * 创建该工件的新版本（内容更新，版本号递增）
     */
    public Artifact newVersion(String updatedContent, String updater) {
        Artifact updated = new Artifact(this.artifactId, this.type, updatedContent, updater);
        updated.title = this.title;
        updated.version = this.version + 1;
        updated.metadata = new HashMap<>(this.metadata);
        return updated;
    }

    /**
     * 获取工件的摘要表示（用于注入 Agent 上下文，避免传递完整内容）
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[工件:").append(artifactId).append("]");
        if (title != null) {
            sb.append(" ").append(title);
        }
        sb.append(" (类型:").append(type.name());
        sb.append(", 产出者:").append(producer);
        sb.append(", v").append(version).append(")");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public ArtifactType getType() {
        return type;
    }

    public void setType(ArtifactType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getProducer() {
        return producer;
    }

    public void setProducer(String producer) {
        this.producer = producer;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    @Override
    public String toString() {
        return toSummary();
    }
}
