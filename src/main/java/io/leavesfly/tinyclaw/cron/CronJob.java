package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 定时任务实体类，表示一个定时任务的完整信息。
 * 
 * 核心属性：
 * - id：任务唯一标识符
 * - name：任务名称（用户友好的描述）
 * - enabled：任务启用状态（可暂停任务而不删除）
 * - schedule：调度配置（支持间隔和 cron 表达式）
 * - payload：任务负载（要执行的消息内容）
 * - state：任务运行状态（最后执行时间、下次执行时间等）
 * - createdAtMs：创建时间戳（毫秒）
 * - updatedAtMs：更新时间戳（毫秒）
 * - deleteAfterRun：执行后是否自动删除（一次性任务）
 * 
 * 使用场景：
 * - 定期消息发送
 * - 一次性定时任务
 * - 循环执行任务
 * 
 * 序列化说明：
 * - 使用 Jackson 进行 JSON 序列化
 * - 空字段不会被序列化（JsonInclude.NON_NULL）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJob {
    
    private String id;              // 任务唯一标识符
    private String name;            // 任务名称
    private boolean enabled = true; // 任务启用状态（默认启用）
    private CronSchedule schedule;  // 调度配置
    private CronPayload payload;    // 任务负载
    private CronJobState state;     // 任务运行状态
    private long createdAtMs;       // 创建时间戳（毫秒）
    private long updatedAtMs;       // 更新时间戳（毫秒）
    private boolean deleteAfterRun; // 执行后是否自动删除
    
    /**
     * 构造函数，初始化任务状态。
     */
    public CronJob() {
        this.state = new CronJobState();
    }
    
    /**
     * 获取任务 ID。
     * 
     * @return 任务唯一标识符
     */
    public String getId() { 
        return id; 
    }
    
    /**
     * 设置任务 ID。
     * 
     * @param id 任务唯一标识符
     */
    public void setId(String id) { 
        this.id = id; 
    }
    
    /**
     * 获取任务名称。
     * 
     * @return 任务名称
     */
    public String getName() { 
        return name; 
    }
    
    /**
     * 设置任务名称。
     * 
     * @param name 任务名称
     */
    public void setName(String name) { 
        this.name = name; 
    }
    
    /**
     * 检查任务是否启用。
     * 
     * @return 启用返回 true，禁用返回 false
     */
    public boolean isEnabled() { 
        return enabled; 
    }
    
    /**
     * 设置任务启用状态。
     * 
     * @param enabled 启用状态
     */
    public void setEnabled(boolean enabled) { 
        this.enabled = enabled; 
    }
    
    /**
     * 获取调度配置。
     * 
     * @return 调度配置对象
     */
    public CronSchedule getSchedule() { 
        return schedule; 
    }
    
    /**
     * 设置调度配置。
     * 
     * @param schedule 调度配置对象
     */
    public void setSchedule(CronSchedule schedule) { 
        this.schedule = schedule; 
    }
    
    /**
     * 获取任务负载。
     * 
     * @return 任务负载对象
     */
    public CronPayload getPayload() { 
        return payload; 
    }
    
    /**
     * 设置任务负载。
     * 
     * @param payload 任务负载对象
     */
    public void setPayload(CronPayload payload) { 
        this.payload = payload; 
    }
    
    /**
     * 获取任务运行状态。
     * 
     * @return 任务运行状态对象
     */
    public CronJobState getState() { 
        return state; 
    }
    
    /**
     * 设置任务运行状态。
     * 
     * @param state 任务运行状态对象
     */
    public void setState(CronJobState state) { 
        this.state = state; 
    }
    
    /**
     * 获取创建时间戳。
     * 
     * @return 创建时间戳（毫秒）
     */
    public long getCreatedAtMs() { 
        return createdAtMs; 
    }
    
    /**
     * 设置创建时间戳。
     * 
     * @param createdAtMs 创建时间戳（毫秒）
     */
    public void setCreatedAtMs(long createdAtMs) { 
        this.createdAtMs = createdAtMs; 
    }
    
    /**
     * 获取更新时间戳。
     * 
     * @return 更新时间戳（毫秒）
     */
    public long getUpdatedAtMs() { 
        return updatedAtMs; 
    }
    
    /**
     * 设置更新时间戳。
     * 
     * @param updatedAtMs 更新时间戳（毫秒）
     */
    public void setUpdatedAtMs(long updatedAtMs) { 
        this.updatedAtMs = updatedAtMs; 
    }
    
    /**
     * 检查任务是否在执行后删除。
     * 
     * @return 执行后删除返回 true，否则返回 false
     */
    public boolean isDeleteAfterRun() { 
        return deleteAfterRun; 
    }
    
    /**
     * 设置任务是否在执行后删除。
     * 
     * @param deleteAfterRun 执行后是否删除
     */
    public void setDeleteAfterRun(boolean deleteAfterRun) { 
        this.deleteAfterRun = deleteAfterRun; 
    }
}