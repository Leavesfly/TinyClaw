package io.leavesfly.tinyclaw.cron;

import java.util.ArrayList;
import java.util.List;

/**
 * Store for cron jobs
 */
public class CronStore {
    
    private int version = 1;
    private List<CronJob> jobs = new ArrayList<>();
    
    public CronStore() {}
    
    // Getters and setters
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    
    public List<CronJob> getJobs() { return jobs; }
    public void setJobs(List<CronJob> jobs) { this.jobs = jobs; }
}
