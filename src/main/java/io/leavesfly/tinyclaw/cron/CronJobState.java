package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 定时任务状态追踪类
 * 记录任务的执行状态、下次运行时间、上次运行时间、错误信息及执行历史
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJobState {
    
    private Long nextRunAtMs;
    private Long lastRunAtMs;
    private String lastStatus;
    private String lastError;
    private List<CronRunRecord> history;
    
    public CronJobState() {}
    
    // Getter 和 Setter 方法
    public Long getNextRunAtMs() { return nextRunAtMs; }
    public void setNextRunAtMs(Long nextRunAtMs) { this.nextRunAtMs = nextRunAtMs; }
    
    public Long getLastRunAtMs() { return lastRunAtMs; }
    public void setLastRunAtMs(Long lastRunAtMs) { this.lastRunAtMs = lastRunAtMs; }
    
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    
    public List<CronRunRecord> getHistory() { return history; }
    public void setHistory(List<CronRunRecord> history) { this.history = history; }
}
