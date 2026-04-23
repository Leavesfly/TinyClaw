package io.leavesfly.tinyclaw.evolution.reflection;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.TimeoutException;

/**
 * 异常语义分类器。
 *
 * <p>把真实的 Java Throwable 映射到 {@link ToolCallEvent.ErrorType}，作为
 * 后续模式挖掘与反思的一阶统计维度。分类遵循"从具体到抽象"的顺序：
 * <ol>
 *   <li>已知的第三方异常类型（网络、文件、超时）</li>
 *   <li>异常消息中的关键词（沙箱、黑名单、not found、unauthorized）</li>
 *   <li>兜底为 {@link ToolCallEvent.ErrorType#UNKNOWN_ERROR}</li>
 * </ol>
 *
 * <p>该类保持无状态、无依赖，便于单测与在任何线程中调用。
 */
public final class ErrorClassifier {

    private ErrorClassifier() {}

    /**
     * 将一次失败的异常分类为 {@link ToolCallEvent.ErrorType}。
     *
     * @param error 异常（允许为 null，null 视为 UNKNOWN_ERROR）
     * @return 分类结果
     */
    public static ToolCallEvent.ErrorType classify(Throwable error) {
        if (error == null) {
            return ToolCallEvent.ErrorType.UNKNOWN_ERROR;
        }

        // 1) 按异常类型分类（优先级最高，最精准）
        if (error instanceof SocketTimeoutException || error instanceof TimeoutException) {
            return ToolCallEvent.ErrorType.TIMEOUT;
        }
        if (error instanceof ConnectException || error instanceof UnknownHostException) {
            return ToolCallEvent.ErrorType.NETWORK_ERROR;
        }
        if (error instanceof NoSuchFileException) {
            return ToolCallEvent.ErrorType.NOT_FOUND;
        }
        if (error instanceof AccessDeniedException) {
            return ToolCallEvent.ErrorType.PERMISSION_DENIED;
        }
        if (error instanceof IllegalArgumentException) {
            return ToolCallEvent.ErrorType.VALIDATION_ERROR;
        }

        // 2) 按异常消息关键词分类
        String message = error.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (containsAny(lower, "sandbox", "workspace violation", "denied by security",
                    "path outside", "out of workspace", "blocked command", "blacklisted")) {
                return ToolCallEvent.ErrorType.SECURITY_VIOLATION;
            }
            if (containsAny(lower, "timed out", "timeout")) {
                return ToolCallEvent.ErrorType.TIMEOUT;
            }
            if (containsAny(lower, "not found", "no such file", "does not exist",
                    "cannot find", "不存在")) {
                return ToolCallEvent.ErrorType.NOT_FOUND;
            }
            if (containsAny(lower, "unauthorized", "forbidden", "permission denied",
                    "access denied", "401", "403")) {
                return ToolCallEvent.ErrorType.PERMISSION_DENIED;
            }
            if (containsAny(lower, "connection refused", "unreachable", "dns", "network")) {
                return ToolCallEvent.ErrorType.NETWORK_ERROR;
            }
            if (containsAny(lower, "tool not found", "unknown tool", "no such tool")) {
                return ToolCallEvent.ErrorType.TOOL_NOT_FOUND;
            }
            if (containsAny(lower, "invalid argument", "missing required", "validation",
                    "must be", "required field", "bad request", "400")) {
                return ToolCallEvent.ErrorType.VALIDATION_ERROR;
            }
            if (containsAny(lower, "500", "502", "503", "504", "internal server error",
                    "upstream", "rate limit", "too many requests", "429")) {
                return ToolCallEvent.ErrorType.UPSTREAM_ERROR;
            }
        }

        // 3) 递归检查 cause（避免 wrap 异常丢信息）
        if (error.getCause() != null && error.getCause() != error) {
            ToolCallEvent.ErrorType inner = classify(error.getCause());
            if (inner != ToolCallEvent.ErrorType.UNKNOWN_ERROR) {
                return inner;
            }
        }

        return ToolCallEvent.ErrorType.UNKNOWN_ERROR;
    }

    /**
     * 基于异常栈的前 N 帧计算稳定 hash，用于同根因样本的去重聚类。
     *
     * @param error 异常
     * @param depth 采用的栈帧深度（建议 3-5）
     * @return 十六进制 hash，异常为空时返回空串
     */
    public static String stackHash(Throwable error, int depth) {
        if (error == null) {
            return "";
        }
        StackTraceElement[] trace = error.getStackTrace();
        if (trace == null || trace.length == 0) {
            return Integer.toHexString(error.getClass().getName().hashCode());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(error.getClass().getName()).append('|');
        int take = Math.min(depth, trace.length);
        for (int i = 0; i < take; i++) {
            StackTraceElement f = trace[i];
            sb.append(f.getClassName()).append('#').append(f.getMethodName()).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static boolean containsAny(String s, String... keywords) {
        for (String k : keywords) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
