package io.leavesfly.tinyclaw.collaboration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 多 Agent 协同中的单条消息
 * <p>支持结构化通信：定向消息、消息类型、结构化数据和对话线程。
 *
 * <p>向后兼容：原有的三参数构造函数保持不变，新增字段均有合理默认值。
 * 推荐使用 Builder API 构建复杂消息：
 * <pre>{@code
 * AgentMessage msg = AgentMessage.builder("agent-1", "架构师", "建议使用微服务架构")
 *         .type(MessageType.OPINION)
 *         .targetRole("产品经理")
 *         .putData("confidence", 0.9)
 *         .build();
 * }</pre>
 */
public class AgentMessage {

    /**
     * 消息类型枚举
     * 区分不同语义的 Agent 间通信，便于下游按类型过滤和处理
     */
    public enum MessageType {
        /** 普通观点/发言（默认） */
        OPINION,
        /** 任务执行结果 */
        TASK_RESULT,
        /** 对其他 Agent 的反馈/评审 */
        FEEDBACK,
        /** 投票 */
        VOTE,
        /** 委派任务给其他 Agent */
        DELEGATION,
        /** 系统/编排器产生的控制消息 */
        SYSTEM
    }

    /** 消息唯一标识 */
    private String messageId;

    /** 发言 Agent 的唯一标识 */
    private String agentId;

    /** Agent 的角色名称（如"正方辩手"、"架构师"） */
    private String agentRole;

    /** 发言内容 */
    private String content;

    /** 发言时间戳 */
    private long timestamp;

    /** 消息类型（默认 OPINION） */
    private MessageType messageType;

    /** 目标角色名称（定向通信，null 表示广播给所有参与者） */
    private String targetRole;

    /** 回复的消息 ID（支持对话线程，null 表示顶层消息） */
    private String replyTo;

    /** 结构化附加数据（如投票选项、置信度、任务参数等） */
    private Map<String, Object> structuredData;

    public AgentMessage() {
        this.messageId = generateShortId();
        this.timestamp = System.currentTimeMillis();
        this.messageType = MessageType.OPINION;
    }

    public AgentMessage(String agentId, String agentRole, String content) {
        this();
        this.agentId = agentId;
        this.agentRole = agentRole;
        this.content = content;
    }

    // -------------------------------------------------------------------------
    // Builder API
    // -------------------------------------------------------------------------

    /**
     * 创建 Builder（推荐方式）
     */
    public static Builder builder(String agentId, String agentRole, String content) {
        return new Builder(agentId, agentRole, content);
    }

    public static class Builder {
        private final AgentMessage message;

        private Builder(String agentId, String agentRole, String content) {
            this.message = new AgentMessage(agentId, agentRole, content);
        }

        public Builder type(MessageType messageType) {
            message.messageType = messageType;
            return this;
        }

        public Builder targetRole(String targetRole) {
            message.targetRole = targetRole;
            return this;
        }

        public Builder replyTo(String replyToMessageId) {
            message.replyTo = replyToMessageId;
            return this;
        }

        public Builder putData(String key, Object value) {
            if (message.structuredData == null) {
                message.structuredData = new HashMap<>();
            }
            message.structuredData.put(key, value);
            return this;
        }

        public Builder structuredData(Map<String, Object> data) {
            message.structuredData = data != null ? new HashMap<>(data) : null;
            return this;
        }

        public AgentMessage build() {
            return message;
        }
    }

    // -------------------------------------------------------------------------
    // 便捷工厂方法
    // -------------------------------------------------------------------------

    /**
     * 创建投票消息
     */
    public static AgentMessage vote(String agentId, String agentRole, String voteOption, String content) {
        AgentMessage msg = new AgentMessage(agentId, agentRole, content);
        msg.messageType = MessageType.VOTE;
        msg.structuredData = new HashMap<>();
        msg.structuredData.put("voteOption", voteOption);
        return msg;
    }

    /**
     * 创建任务结果消息
     */
    public static AgentMessage taskResult(String agentId, String agentRole, String taskId,
                                          boolean success, String content) {
        AgentMessage msg = new AgentMessage(agentId, agentRole, content);
        msg.messageType = MessageType.TASK_RESULT;
        msg.structuredData = new HashMap<>();
        msg.structuredData.put("taskId", taskId);
        msg.structuredData.put("success", success);
        return msg;
    }

    /**
     * 创建委派消息
     */
    public static AgentMessage delegation(String agentId, String agentRole,
                                          String targetRole, String taskDescription) {
        AgentMessage msg = new AgentMessage(agentId, agentRole, taskDescription);
        msg.messageType = MessageType.DELEGATION;
        msg.targetRole = targetRole;
        return msg;
    }

    /**
     * 创建系统消息
     */
    public static AgentMessage system(String content) {
        AgentMessage msg = new AgentMessage("system", "System", content);
        msg.messageType = MessageType.SYSTEM;
        return msg;
    }

    // -------------------------------------------------------------------------
    // 查询方法
    // -------------------------------------------------------------------------

    /**
     * 判断消息是否为定向消息（非广播）
     */
    public boolean isDirected() {
        return targetRole != null && !targetRole.isEmpty();
    }

    /**
     * 判断消息是否与指定角色相关（广播消息或定向给该角色的消息）
     */
    public boolean isRelevantTo(String roleName) {
        return !isDirected() || targetRole.equals(roleName);
    }

    /**
     * 获取结构化数据中的值
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        if (structuredData == null) {
            return null;
        }
        return (T) structuredData.get(key);
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public Map<String, Object> getStructuredData() {
        return structuredData;
    }

    public void setStructuredData(Map<String, Object> structuredData) {
        this.structuredData = structuredData;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[").append(agentRole).append("]");
        if (targetRole != null) {
            sb.append(" → [").append(targetRole).append("]");
        }
        if (messageType != null && messageType != MessageType.OPINION) {
            sb.append(" (").append(messageType.name()).append(")");
        }
        sb.append(" ").append(content);
        return sb.toString();
    }

    private static String generateShortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
