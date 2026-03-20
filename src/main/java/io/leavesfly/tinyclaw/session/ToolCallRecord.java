package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.Instant;

/**
 * 工具调用记录，用于持久化工具调用过程，支持历史会话回放。
 *
 * 只存储摘要信息（参数和结果均截断），不存储完整内容，控制存储开销。
 * afterAssistantIndex 表示该工具调用发生在第几条 assistant 消息之后（从 0 开始），
 * 前端据此将工具调用卡片插入到正确的位置。
 */
public class ToolCallRecord {

    /** 工具名称，如 write_file、list_directory */
    private String toolName;

    /** 参数摘要（截断到 200 字符），用于卡片展示 */
    private String argsSummary;

    /** 结果摘要（截断到 200 字符），用于卡片展示 */
    private String resultSummary;

    /** 工具执行是否成功 */
    private boolean success;

    /** 工具调用发生的时间 */
    private Instant timestamp;

    /**
     * 触发该工具调用的 assistant 消息在 session messages 列表中的绝对位置索引（从 0 开始）。
     * SessionsHandler 按此索引将工具调用记录附加到对应的 assistant 消息上。
     * @JsonAlias 兼容旧版本 session 文件中的 afterAssistantIndex 字段名。
     */
    @JsonAlias("afterAssistantIndex")
    private int messageIndex;

    public ToolCallRecord() {
        this.timestamp = Instant.now();
    }

    public ToolCallRecord(String toolName, String argsSummary, String resultSummary,
                          boolean success, int messageIndex) {
        this.toolName = toolName;
        this.argsSummary = argsSummary;
        this.resultSummary = resultSummary;
        this.success = success;
        this.messageIndex = messageIndex;
        this.timestamp = Instant.now();
    }

    // ==================== Getters and Setters ====================

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgsSummary() {
        return argsSummary;
    }

    public void setArgsSummary(String argsSummary) {
        this.argsSummary = argsSummary;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public int getMessageIndex() {
        return messageIndex;
    }

    public void setMessageIndex(int messageIndex) {
        this.messageIndex = messageIndex;
    }

    /**
     * 截断字符串到指定长度，超出部分用省略号替代。
     */
    public static String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "…";
    }
}
