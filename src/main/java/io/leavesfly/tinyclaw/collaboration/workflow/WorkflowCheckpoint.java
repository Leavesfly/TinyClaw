package io.leavesfly.tinyclaw.collaboration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流检查点 - 一次工作流执行的可恢复快照。
 *
 * <h2>要解决的问题</h2>
 * <p>DAG 协同里靠后的节点失败或超时，整轮结果就作废；重跑时前面已经成功的节点会被
 * 原封不动地再跑一遍，既浪费 token 也浪费时间。检查点让重跑只补未完成的部分。</p>
 *
 * <h2>只存两样东西</h2>
 * <p>变量表与已完成节点的结果——这正好是 {@link WorkflowContext} 里全部的可变状态。
 * 工作流定义本身不存：它由调用方在重跑时重新提供，存一份反而会在定义变更后
 * 用旧定义悄悄覆盖新意图。</p>
 *
 * <h2>为什么只存"已完成"的节点</h2>
 * <p>RUNNING 状态的节点在进程退出时并未真的在跑。把它按已完成恢复会跳过一个从未产出结果
 * 的节点，后续节点将读到空输入；把它按未完成恢复则只是重跑一次，代价可接受。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowCheckpoint {

    /** 运行标识，由工作流结构指纹推导，相同结构的重跑能找回同一份检查点 */
    private String runId;

    /** 工作流名称，仅用于人工排查时辨认 */
    private String workflowName;

    /** 变量表快照 */
    private Map<String, Object> variables = new LinkedHashMap<>();

    /** 已完成节点的结果快照（nodeId → 结果） */
    private Map<String, NodeResult> nodeResults = new LinkedHashMap<>();

    /** 快照写入时刻（epoch 毫秒） */
    private long savedAt;

    public WorkflowCheckpoint() {
    }

    public WorkflowCheckpoint(String runId, String workflowName,
                              Map<String, Object> variables,
                              Map<String, NodeResult> nodeResults) {
        this.runId = runId;
        this.workflowName = workflowName;
        this.variables = variables != null ? new LinkedHashMap<>(variables) : new LinkedHashMap<>();
        this.nodeResults = nodeResults != null
                ? new LinkedHashMap<>(nodeResults) : new LinkedHashMap<>();
        this.savedAt = System.currentTimeMillis();
    }

    /** 已完成节点数，用于日志与进度展示 */
    public int completedNodeCount() {
        return nodeResults.size();
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables != null ? variables : new LinkedHashMap<>();
    }

    public Map<String, NodeResult> getNodeResults() {
        return nodeResults;
    }

    public void setNodeResults(Map<String, NodeResult> nodeResults) {
        this.nodeResults = nodeResults != null ? nodeResults : new LinkedHashMap<>();
    }

    public long getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(long savedAt) {
        this.savedAt = savedAt;
    }
}
