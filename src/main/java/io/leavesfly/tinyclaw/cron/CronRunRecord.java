package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 定时任务单次执行记录。
 *
 * <p>用于执行历史（{@link CronJobState#getHistory()}），把"是否运行 / 运行结果 /
 * 触发方式"分离记录，便于在 Web 控制台排查任务到底跑没跑、跑成什么样。</p>
 *
 * 字段说明：
 * - startedAtMs：开始执行时间戳（毫秒）
 * - durationMs：执行耗时（毫秒）
 * - status：执行结果，ok / error / timeout
 * - trigger：触发方式，schedule（正常调度）/ misfire（停机补跑）/ manual（手动触发）
 * - error：失败原因，成功时为 null
 * - result：处理器返回的结果摘要（截断保存），无结果时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronRunRecord {

    private long startedAtMs;
    private long durationMs;
    private String status;
    private String trigger;
    private String error;
    private String result;

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
}
