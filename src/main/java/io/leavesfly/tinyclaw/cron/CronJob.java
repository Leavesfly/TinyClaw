package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 表示一个定时任务
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJob {
    
    private String id;
    private String name;
    private boolean enabled = true;
    private CronSchedule schedule;
    private CronPayload payload;
    private CronJobState state;
    private long createdAtMs;
    private long updatedAtMs;
    private boolean deleteAfterRun;
    
    public CronJob() {
        this.state = new CronJobState();
    }
    
    // Getter 和 Setter 方法
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public CronSchedule getSchedule() { return schedule; }
    public void setSchedule(CronSchedule schedule) { this.schedule = schedule; }
    
    public CronPayload getPayload() { return payload; }
    public void setPayload(CronPayload payload) { this.payload = payload; }
    
    public CronJobState getState() { return state; }
    public void setState(CronJobState state) { this.state = state; }
    
    public long getCreatedAtMs() { return createdAtMs; }
    public void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
    
    public long getUpdatedAtMs() { return updatedAtMs; }
    public void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }
    
    public boolean isDeleteAfterRun() { return deleteAfterRun; }
    public void setDeleteAfterRun(boolean deleteAfterRun) { this.deleteAfterRun = deleteAfterRun; }
}