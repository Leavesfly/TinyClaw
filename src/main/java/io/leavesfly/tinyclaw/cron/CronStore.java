package io.leavesfly.tinyclaw.cron;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务存储类
 * 负责持久化存储所有定时任务的配置和状态
 */
public class CronStore {
    
    private int version = 1;
    private List<CronJob> jobs = new ArrayList<>();
    
    public CronStore() {}
    
    // Getter 和 Setter 方法
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    
    public List<CronJob> getJobs() { return jobs; }
    public void setJobs(List<CronJob> jobs) { this.jobs = jobs; }
}
