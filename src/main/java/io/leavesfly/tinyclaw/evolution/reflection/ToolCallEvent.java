package io.leavesfly.tinyclaw.evolution.reflection;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次工具调用的原子事件记录。
 *
 * <p>该对象是 Reflection 2.0 的最小数据单元，由 {@link ToolCallRecorder} 在
 * 每次 {@code ToolRegistry.execute()} 完成后同步构造、异步落盘。每一个 event
 * 之后会被 {@code FailureDetector}/{@code PatternMiner} 聚合分析，并最终驱动
 * {@code ReflectionEngine} 产生修复提案。
 *
 * <p>字段设计聚焦以下 4 个维度：
 * <ul>
 *   <li>身份：eventId / traceId / sessionKey</li>
 *   <li>调用上下文：toolName / model / rawArgs / priorToolsInTurn</li>
 *   <li>结果：success / errorType / errorMessage / stackHash / durationMs</li>
 *   <li>辅助诊断：userMessageSnippet / argsFingerprint</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallEvent {

    /** 错误分类枚举，供聚类与根因分析使用。 */
    public enum ErrorType {
        /** 参数缺失 / 类型错误 / schema 违规 */
        VALIDATION_ERROR,
        /** 沙箱拒绝（工作空间外路径、黑名单命令等） */
        SECURITY_VIOLATION,
        /** 文件/目录/资源不存在 */
        NOT_FOUND,
        /** 网络错误、DNS 失败、连接拒绝 */
        NETWORK_ERROR,
        /** 超时 */
        TIMEOUT,
        /** 权限不足（但未触发沙箱） */
        PERMISSION_DENIED,
        /** LLM 调用了不存在的工具或字段完全错位 */
        TOOL_NOT_FOUND,
        /** 上游服务 5xx / API 限流 */
        UPSTREAM_ERROR,
        /** 其他未分类的异常 */
        UNKNOWN_ERROR,
        /** 调用成功（success=true 时使用） */
        NONE
    }

    private String eventId;
    private String traceId;
    private String sessionKey;
    private String toolName;
    private String modelName;
    private String providerName;

    /** 参数原文（脱敏后的），用于回溯具体失败样本。 */
    private Map<String, Object> rawArgs;

    /** 参数指纹：对 rawArgs 做归一化后用于聚类的 key，例如 "path:/abs/*" */
    private String argsFingerprint;

    private boolean success;
    private ErrorType errorType;
    private String errorClass;
    private String errorMessage;
    /** 栈前 N 帧的 hash，用于去重相同根因 */
    private String stackHash;

    private long durationMs;
    private int retryCount;
    private Instant timestamp;

    /** 用户原始消息的前 120 字符（脱敏截断） */
    private String userMessageSnippet;

    /** 本轮之前已经调用过的工具名列表（用于识别"重复失败重试"模式） */
    private List<String> priorToolsInTurn;

    public ToolCallEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.rawArgs = new LinkedHashMap<>();
        this.errorType = ErrorType.NONE;
    }

    // ==================== 构造辅助 ====================

    /**
     * 创建一个构造中的 event builder，配合 try/finally 使用。
     *
     * @param toolName 工具名
     * @param args     原始参数
     * @param traceId  链路 ID（可为 null）
     */
    public static ToolCallEvent begin(String toolName, Map<String, Object> args, String traceId) {
        ToolCallEvent event = new ToolCallEvent();
        event.toolName = toolName;
        event.traceId = traceId;
        if (args != null) {
            event.rawArgs = new LinkedHashMap<>(args);
        }
        return event;
    }

    /** 标记成功并记录耗时。 */
    public void markSuccess(long durationMs) {
        this.success = true;
        this.errorType = ErrorType.NONE;
        this.durationMs = durationMs;
    }

    /** 标记失败并记录异常信息。 */
    public void markFailure(ErrorType type, Throwable error, long durationMs) {
        this.success = false;
        this.errorType = type != null ? type : ErrorType.UNKNOWN_ERROR;
        this.durationMs = durationMs;
        if (error != null) {
            this.errorClass = error.getClass().getName();
            this.errorMessage = truncate(error.getMessage(), 500);
        }
    }

    // ==================== Getter / Setter ====================

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public Map<String, Object> getRawArgs() { return rawArgs; }
    public void setRawArgs(Map<String, Object> rawArgs) { this.rawArgs = rawArgs; }

    public String getArgsFingerprint() { return argsFingerprint; }
    public void setArgsFingerprint(String argsFingerprint) { this.argsFingerprint = argsFingerprint; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public ErrorType getErrorType() { return errorType; }
    public void setErrorType(ErrorType errorType) { this.errorType = errorType; }

    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String errorClass) { this.errorClass = errorClass; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getStackHash() { return stackHash; }
    public void setStackHash(String stackHash) { this.stackHash = stackHash; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getUserMessageSnippet() { return userMessageSnippet; }
    public void setUserMessageSnippet(String userMessageSnippet) {
        this.userMessageSnippet = truncate(userMessageSnippet, 120);
    }

    public List<String> getPriorToolsInTurn() { return priorToolsInTurn; }
    public void setPriorToolsInTurn(List<String> priorToolsInTurn) {
        this.priorToolsInTurn = priorToolsInTurn;
    }

    // ==================== 工具方法 ====================

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
