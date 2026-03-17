package io.leavesfly.tinyclaw.agent.collaboration.workflow;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点执行结果
 */
public class NodeResult {
    
    /**
     * 执行状态
     */
    public enum Status {
        PENDING,    // 等待执行
        RUNNING,    // 执行中
        COMPLETED,  // 执行成功
        FAILED,     // 执行失败
        SKIPPED     // 跳过（条件不满足）
    }
    
    /** 节点ID */
    private String nodeId;
    
    /** 执行状态 */
    private Status status;
    
    /** 执行结果（文本） */
    private String result;
    
    /** 结构化结果（用于多Agent并行时） */
    private Map<String, String> agentResults;
    
    /** 错误信息（失败时） */
    private String error;
    
    /** 开始时间 */
    private long startTime;
    
    /** 结束时间 */
    private long endTime;
    
    /** 重试次数 */
    private int retryCount;
    
    public NodeResult(String nodeId) {
        this.nodeId = nodeId;
        this.status = Status.PENDING;
        this.agentResults = new HashMap<>();
    }
    
    /**
     * 标记开始执行
     */
    public void markStarted() {
        this.status = Status.RUNNING;
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * 标记执行成功
     */
    public void markCompleted(String result) {
        this.status = Status.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }
    
    /**
     * 标记执行失败
     */
    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }
    
    /**
     * 标记跳过
     */
    public void markSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.result = reason;
        this.endTime = System.currentTimeMillis();
    }
    
    /**
     * 添加单个Agent的结果
     */
    public void addAgentResult(String agentName, String result) {
        agentResults.put(agentName, result);
    }
    
    /**
     * 检查是否完成（成功、失败或跳过）
     */
    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.SKIPPED;
    }
    
    /**
     * 检查是否成功
     */
    public boolean isSuccess() {
        return status == Status.COMPLETED;
    }
    
    /**
     * 获取执行时间（毫秒）
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
    
    /**
     * 增加重试计数
     */
    public void incrementRetry() {
        retryCount++;
    }
    
    // Getters and Setters
    
    public String getNodeId() {
        return nodeId;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
    
    public String getResult() {
        return result;
    }
    
    public void setResult(String result) {
        this.result = result;
    }
    
    public Map<String, String> getAgentResults() {
        return agentResults;
    }
    
    public String getError() {
        return error;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    @Override
    public String toString() {
        return "NodeResult{" +
                "nodeId='" + nodeId + '\'' +
                ", status=" + status +
                ", executionTime=" + getExecutionTime() + "ms" +
                '}';
    }
}
