package io.leavesfly.tinyclaw.evolution.reflection;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.*;

/**
 * 一次反思引擎生成的修复提案。
 *
 * <p>由 ReflectionEngine 在 LLM 分析失败模式后构造，代表对某个工具的
 * 一项具体改进建议。提案经过 HITL（Human-In-The-Loop）审批后，由
 * RepairApplier 落盘生效。
 *
 * <p>三种提案类型：
 * <ul>
 *   <li>{@link Type#DESCRIPTION_REWRITE}：重写工具的 description / parameters schema，
 *       让 LLM 更准确地理解工具用途和参数约束；</li>
 *   <li>{@link Type#VALIDATION_RULE}：在工具执行前添加参数校验规则，提前拒绝不合法参数，
 *       避免无意义的远程调用；</li>
 *   <li>{@link Type#FEW_SHOT_EXAMPLE}：在系统提示中注入工具使用示范（few-shot），
 *       引导 LLM 按正确模式构造参数。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepairProposal {

    /** 提案类型 */
    public enum Type {
        DESCRIPTION_REWRITE,
        VALIDATION_RULE,
        FEW_SHOT_EXAMPLE
    }

    /** 提案审批状态 */
    public enum Status {
        /** 待审批 */
        PENDING,
        /** 已批准，待应用 */
        APPROVED,
        /** 已应用生效 */
        APPLIED,
        /** 已被人工拒绝 */
        REJECTED,
        /** 已过期（超过观察窗口未审批） */
        EXPIRED
    }

    private String proposalId;
    private String toolName;
    private Type type;
    private Status status;

    /** 提案摘要（一句话描述改了什么） */
    private String summary;

    /** 根因分析（LLM 生成的诊断文本） */
    private String rootCauseAnalysis;

    /** 提案内容，根据 type 不同含义不同：
     *  - DESCRIPTION_REWRITE: 新的 description 文本
     *  - VALIDATION_RULE: 校验规则 JSON（key=参数名, value=校验表达式）
     *  - FEW_SHOT_EXAMPLE: few-shot 示范文本
     */
    private String proposedContent;

    /** 原始内容（用于 diff 展示和回滚） */
    private String originalContent;

    /** 影响度估算（0.0~1.0），用于自动审批判定 */
    private double impactScore;

    /** 触发此提案的检测结果类型 */
    private String triggerType;

    /** 关联的失败聚类 key */
    private String clusterKey;

    /** 证据：相关的失败事件 ID 列表 */
    private List<String> evidenceEventIds;

    /** 生成时间 */
    private Instant createdAt;

    /** 审批时间 */
    private Instant reviewedAt;

    /** 审批人备注 */
    private String reviewNote;

    public RepairProposal() {
        this.proposalId = UUID.randomUUID().toString().substring(0, 8);
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.evidenceEventIds = new ArrayList<>();
    }

    // ==================== 构建辅助 ====================

    public static RepairProposal descriptionRewrite(String toolName, String summary,
                                                     String rootCause, String newDescription,
                                                     String originalDescription) {
        RepairProposal proposal = new RepairProposal();
        proposal.toolName = toolName;
        proposal.type = Type.DESCRIPTION_REWRITE;
        proposal.summary = summary;
        proposal.rootCauseAnalysis = rootCause;
        proposal.proposedContent = newDescription;
        proposal.originalContent = originalDescription;
        return proposal;
    }

    public static RepairProposal validationRule(String toolName, String summary,
                                                 String rootCause, String ruleJson) {
        RepairProposal proposal = new RepairProposal();
        proposal.toolName = toolName;
        proposal.type = Type.VALIDATION_RULE;
        proposal.summary = summary;
        proposal.rootCauseAnalysis = rootCause;
        proposal.proposedContent = ruleJson;
        return proposal;
    }

    public static RepairProposal fewShotExample(String toolName, String summary,
                                                  String rootCause, String exampleText) {
        RepairProposal proposal = new RepairProposal();
        proposal.toolName = toolName;
        proposal.type = Type.FEW_SHOT_EXAMPLE;
        proposal.summary = summary;
        proposal.rootCauseAnalysis = rootCause;
        proposal.proposedContent = exampleText;
        return proposal;
    }

    // ==================== 状态转换 ====================

    public void approve(String note) {
        this.status = Status.APPROVED;
        this.reviewedAt = Instant.now();
        this.reviewNote = note;
    }

    public void reject(String note) {
        this.status = Status.REJECTED;
        this.reviewedAt = Instant.now();
        this.reviewNote = note;
    }

    public void markApplied() {
        this.status = Status.APPLIED;
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
    }

    // ==================== 序列化 ====================

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("proposalId", proposalId);
        map.put("toolName", toolName);
        map.put("type", type != null ? type.name() : null);
        map.put("status", status != null ? status.name() : null);
        map.put("summary", summary);
        map.put("rootCauseAnalysis", rootCauseAnalysis);
        map.put("proposedContent", proposedContent);
        map.put("originalContent", originalContent);
        map.put("impactScore", impactScore);
        map.put("triggerType", triggerType);
        map.put("clusterKey", clusterKey);
        map.put("evidenceEventIds", evidenceEventIds);
        map.put("createdAt", createdAt != null ? createdAt.toString() : null);
        map.put("reviewedAt", reviewedAt != null ? reviewedAt.toString() : null);
        map.put("reviewNote", reviewNote);
        return map;
    }

    // ==================== Getter / Setter ====================

    public String getProposalId() { return proposalId; }
    public void setProposalId(String proposalId) { this.proposalId = proposalId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getRootCauseAnalysis() { return rootCauseAnalysis; }
    public void setRootCauseAnalysis(String rootCauseAnalysis) { this.rootCauseAnalysis = rootCauseAnalysis; }

    public String getProposedContent() { return proposedContent; }
    public void setProposedContent(String proposedContent) { this.proposedContent = proposedContent; }

    public String getOriginalContent() { return originalContent; }
    public void setOriginalContent(String originalContent) { this.originalContent = originalContent; }

    public double getImpactScore() { return impactScore; }
    public void setImpactScore(double impactScore) { this.impactScore = Math.max(0.0, Math.min(1.0, impactScore)); }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getClusterKey() { return clusterKey; }
    public void setClusterKey(String clusterKey) { this.clusterKey = clusterKey; }

    public List<String> getEvidenceEventIds() { return evidenceEventIds; }
    public void setEvidenceEventIds(List<String> evidenceEventIds) { this.evidenceEventIds = evidenceEventIds; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
