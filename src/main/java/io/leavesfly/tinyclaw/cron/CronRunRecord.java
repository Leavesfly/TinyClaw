package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 定时任务单次执行记录。
 *
 * <p>用于执行历史（{@link CronJobState#getHistory()}），把"是否运行 / 运行结果 /
 * 触发方式"分离记录，便于在 Web 控制台排查任务到底跑没跑、跑成什么样。</p>
 *
 * 字段说明：
 * - startedAtMs：开始执行时间戳（毫秒）
 * - durationMs：执行耗时（毫秒）
 * - status：执行结果，ok / error / timeout / skipped（P6：忙碌跳过等非失败性跳过）
 * - trigger：触发方式，schedule（正常调度）/ misfire（停机补跑）/ manual（手动触发）
 * - error：失败原因，成功时为 null
 * - result：处理器返回的结果摘要（截断保存），无结果时为 null
 * - runId（P6）：本次执行实例 id（12 位十六进制短串；手动重试生成新的 runId）
 * - sessionKey（P6）：本次执行使用的会话键（NEW_SESSION 每次不同；CONTINUE_SESSION 固定）
 * - artifactIds（P6）：本次执行新增的成果登记 id（跳过/失败/无产出时为 null）
 * - deliveryStatus（P6）：外部通道投递状态——generated（已生成，投递由通道异步承担）/
 *   skipped（未执行投递）。与"执行成功"分开标注，不把"报告已生成"误报成"消息已送达"。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronRunRecord {

    public static final String STATUS_SKIPPED = "skipped";
    public static final String DELIVERY_GENERATED = "generated";
    public static final String DELIVERY_SKIPPED = "skipped";

    private long startedAtMs;
    private long durationMs;
    private String status;
    private String trigger;
    private String error;
    private String result;

    private String runId;
    private String sessionKey;
    private List<String> artifactIds;
    private String deliveryStatus;

    public CronRunRecord() {}

    public long getStartedAtMs() { return startedAtMs; }
    public void setStartedAtMs(long startedAtMs) { this.startedAtMs = startedAtMs; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }

    public List<String> getArtifactIds() { return artifactIds; }
    public void setArtifactIds(List<String> artifactIds) { this.artifactIds = artifactIds; }

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }
}
