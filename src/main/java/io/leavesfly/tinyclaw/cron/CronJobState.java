package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 定时任务状态追踪类
 * 记录任务的执行状态、下次运行时间、上次运行时间及错误信息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJobState {
    
    private Long nextRunAtMs;
    private Long lastRunAtMs;
    private String lastStatus;
    private String lastError;
    
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
}
