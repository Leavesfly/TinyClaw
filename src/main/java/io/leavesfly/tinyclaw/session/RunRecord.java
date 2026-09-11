package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一次 Web 根执行的登记记录（P1：长任务与确认恢复）。
 *
 * <p>定位是「执行摘要」而非事件日志：会话转录（Session.messages）仍是正式消息事实源，
 * 本记录只承载跨刷新可见的执行身份、状态与时间，供前端恢复运行状态、幂等去重与
 * 任务概览使用。不建设逐 token 事件流，完整回放由会话转录承担。</p>
 *
 * <p>状态机：CREATED → RUNNING → WAITING_USER → RUNNING → … → 终态。
 * 终态固定为 COMPLETED / FAILED / CANCELLED / INTERRUPTED；CANCELLING 为
 * 停止请求已发出、执行器尚未退出的中间态。最终状态由执行结果确定，
 * 不把 SSE 的 [DONE] 当作业务成功。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunRecord {

    /** 执行状态（字符串形式持久化，读取旧文件时兼容未知值按 FAILED 处理防误判）。 */
    public enum Status {
        CREATED, RUNNING, WAITING_USER, CANCELLING,
        COMPLETED, FAILED, CANCELLED, INTERRUPTED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }
    }

    /** runId，全局唯一（UUID 短串）。 */
    public String id;
    /** 客户端幂等键：同一请求重复提交返回既有执行，不再运行工具。 */
    public String clientRequestId;
    /** 归属会话。 */
    public String sessionKey;
    /** 当前状态。 */
    public String status;
    /** 创建时间（epoch millis）。 */
    public long createdAt;
    /** 最后状态变更时间。 */
    public long updatedAt;
    /** 结束时间，仅终态有值。 */
    public Long endedAt;
    /** 提交消息预览（截断，仅用于概览展示，不含完整正文）。 */
    public String messagePreview;
    /** 全局单调序列（同一毫秒内创建的多条记录仍能稳定排序）。 */
    public long seq;
    /** 失败原因摘要（仅 FAILED/CANCELLED/INTERRUPTED 有值）。 */
    public String errorSummary;

    public RunRecord() {
        // Jackson 反序列化需要
    }

    public static RunRecord create(String id, String clientRequestId, String sessionKey, String messagePreview) {
        RunRecord r = new RunRecord();
        r.id = id;
        r.clientRequestId = clientRequestId;
        r.sessionKey = sessionKey;
        r.status = Status.CREATED.name();
        r.createdAt = System.currentTimeMillis();
        r.updatedAt = r.createdAt;
        r.messagePreview = preview(messagePreview);
        return r;
    }

    /** 消息预览截断：登记记录不承载完整正文。 */
    private static String preview(String message) {
        if (message == null) {
            return "";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 80 ? normalized.substring(0, 80) + "…" : normalized;
    }

    public Status statusEnum() {
        try {
            return Status.valueOf(status);
        } catch (Exception e) {
            return Status.FAILED;
        }
    }

    /** 是否终态。 */
    public boolean isTerminal() {
        return statusEnum().isTerminal();
    }

    /** 状态流转（含时间戳刷新）。非法流转静默忽略，由 RunRegistry 负责串行化调用。 */
    public void transitionTo(Status next) {
        Status current = statusEnum();
        if (current.isTerminal()) {
            return; // 终态不可再流转
        }
        if (next == Status.CREATED) {
            return; // 不允许回退
        }
        this.status = next.name();
        this.updatedAt = System.currentTimeMillis();
        if (next.isTerminal()) {
            this.endedAt = this.updatedAt;
        }
    }
}
