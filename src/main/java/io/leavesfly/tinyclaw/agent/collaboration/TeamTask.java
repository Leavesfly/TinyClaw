package io.leavesfly.tinyclaw.agent.collaboration;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队任务定义
 * 用于TeamWorkStrategy中的任务分解和依赖管理
 */
public class TeamTask {
    
    /** 任务唯一标识 */
    private String taskId;
    
    /** 任务名称 */
    private String taskName;
    
    /** 任务描述 */
    private String description;
    
    /** 负责该任务的Agent角色 */
    private AgentRole assignee;
    
    /** 依赖的前置任务ID列表 */
    private List<String> dependsOn;
    
    /** 任务状态 */
    private TaskStatus status;
    
    /** 任务结果 */
    private String result;
    
    /** 任务开始时间 */
    private long startTime;
    
    /** 任务完成时间 */
    private long endTime;
    
    public TeamTask() {
        this.dependsOn = new ArrayList<>();
        this.status = TaskStatus.PENDING;
    }
    
    public TeamTask(String taskId, String taskName, AgentRole assignee) {
        this();
        this.taskId = taskId;
        this.taskName = taskName;
        this.assignee = assignee;
    }
    
    /**
     * 添加前置依赖任务
     */
    public void addDependency(String taskId) {
        if (!dependsOn.contains(taskId)) {
            dependsOn.add(taskId);
        }
    }
    
    /**
     * 检查是否有依赖
     */
    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }
    
    /**
     * 标记任务开始
     */
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * 标记任务完成
     */
    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }
    
    /**
     * 标记任务失败
     */
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.result = error;
        this.endTime = System.currentTimeMillis();
    }
    
    /**
     * 检查任务是否已完成（成功或失败）
     */
    public boolean isFinished() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }
    
    /**
     * 获取任务执行时间（毫秒）
     */
    public long getExecutionTime() {
        if (startTime == 0) {
            return 0;
        }
        if (endTime == 0) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }
    
    // Getters and Setters
    
    public String getTaskId() {
        return taskId;
    }
    
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
    
    public String getTaskName() {
        return taskName;
    }
    
    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public AgentRole getAssignee() {
        return assignee;
    }
    
    public void setAssignee(AgentRole assignee) {
        this.assignee = assignee;
    }
    
    public List<String> getDependsOn() {
        return dependsOn;
    }
    
    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn;
    }
    
    public TaskStatus getStatus() {
        return status;
    }
    
    public void setStatus(TaskStatus status) {
        this.status = status;
    }
    
    public String getResult() {
        return result;
    }
    
    public void setResult(String result) {
        this.result = result;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }
    
    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING,    // 等待执行
        RUNNING,    // 执行中
        COMPLETED,  // 已完成
        FAILED      // 失败
    }
    
    @Override
    public String toString() {
        return "TeamTask{" +
                "taskId='" + taskId + '\'' +
                ", taskName='" + taskName + '\'' +
                ", status=" + status +
                '}';
    }
}
