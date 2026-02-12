package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * State tracking for a cron job
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJobState {
    
    private Long nextRunAtMs;
    private Long lastRunAtMs;
    private String lastStatus;
    private String lastError;
    
    public CronJobState() {}
    
    // Getters and setters
    public Long getNextRunAtMs() { return nextRunAtMs; }
    public void setNextRunAtMs(Long nextRunAtMs) { this.nextRunAtMs = nextRunAtMs; }
    
    public Long getLastRunAtMs() { return lastRunAtMs; }
    public void setLastRunAtMs(Long lastRunAtMs) { this.lastRunAtMs = lastRunAtMs; }
    
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
