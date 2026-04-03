package io.leavesfly.tinyclaw.collaboration.workflow;

import io.leavesfly.tinyclaw.collaboration.SharedContext;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow 执行上下文
 * 管理工作流执行过程中的变量、节点结果和表达式解析
 */
public class WorkflowContext {
    
    /** 表达式匹配模式: ${node.result} 或 ${variables.key} */
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    
    /** 共享上下文（与其他协同策略共用） */
    private final SharedContext sharedContext;
    
    /** 全局变量 */
    private final Map<String, Object> variables;
    
    /** 节点执行结果 */
    private final Map<String, NodeResult> nodeResults;
    
    /** 工作流开始时间 */
    private final long startTime;
    
    /** 已执行节点数 */
    private int executedNodeCount;
    
    public WorkflowContext(SharedContext sharedContext, Map<String, Object> initialVariables) {
        this.sharedContext = sharedContext;
        this.variables = new HashMap<>();
        if (initialVariables != null) {
            this.variables.putAll(initialVariables);
        }
        this.nodeResults = new HashMap<>();
        this.startTime = System.currentTimeMillis();
        this.executedNodeCount = 0;
    }
    
    /**
     * 设置变量
     */
    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }
    
    /**
     * 获取变量
     */
    public Object getVariable(String key) {
        return variables.get(key);
    }
    
    /**
     * 记录节点结果
     */
    public void setNodeResult(String nodeId, NodeResult result) {
        nodeResults.put(nodeId, result);
        if (result.isFinished()) {
            executedNodeCount++;
        }
    }
    
    /**
     * 获取节点结果
     */
    public NodeResult getNodeResult(String nodeId) {
        return nodeResults.get(nodeId);
    }
    
    /**
     * 检查节点是否已完成
     */
    public boolean isNodeCompleted(String nodeId) {
        NodeResult result = nodeResults.get(nodeId);
        return result != null && result.isFinished();
    }
    
    /**
     * 检查所有依赖节点是否已完成
     */
    public boolean areDependenciesCompleted(WorkflowNode node) {
        if (!node.hasDependencies()) {
            return true;
        }
        for (String depId : node.getDependsOn()) {
            if (!isNodeCompleted(depId)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 检查依赖节点是否有失败的
     */
    public boolean hasFailedDependency(WorkflowNode node) {
        if (!node.hasDependencies()) {
            return false;
        }
        for (String depId : node.getDependsOn()) {
            NodeResult result = nodeResults.get(depId);
            if (result != null && result.getStatus() == NodeResult.Status.FAILED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查当前节点是否应被条件分支跳过。
     *
     * <p>遍历当前节点的所有前置依赖，若某个前置节点是 CONDITIONAL 节点且配置了多分支路由，
     * 则检查该条件节点激活的目标节点是否包含当前节点。
     * 若当前节点不在任何激活分支的目标中，则应被跳过。
     *
     * @param node     待检查的节点
     * @param allNodes Workflow 中所有节点的映射（nodeId → WorkflowNode）
     * @return true 表示当前节点应被跳过（未被激活的条件分支）
     */
    public boolean isNodeBranchSkipped(WorkflowNode node, Map<String, WorkflowNode> allNodes) {
        for (String depId : node.getDependsOn()) {
            WorkflowNode depNode = allNodes.get(depId);
            if (depNode == null || depNode.getType() != WorkflowNode.NodeType.CONDITIONAL) {
                continue;
            }
            // 该前置节点是 CONDITIONAL 且有多分支路由
            if (depNode.getBranches().isEmpty()) {
                continue;
            }
            // 读取该条件节点激活的目标节点 ID
            Object activatedTarget = variables.get("_branch_" + depId);
            if (activatedTarget == null) {
                // 条件节点尚未执行或未激活任何分支，跳过当前节点
                return true;
            }
            // 当前节点不是被激活的目标，应跳过
            if (!node.getId().equals(activatedTarget.toString())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 解析表达式
     * 支持: ${nodeId.result}, ${nodeId.agentResults.agentName}, ${variables.key}
     */
    public String resolveExpression(String expression) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        
        Matcher matcher = EXPRESSION_PATTERN.matcher(expression);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String path = matcher.group(1);
            String value = resolvePath(path);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : ""));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    /**
     * 解析路径表达式
     */
    private String resolvePath(String path) {
        String[] parts = path.split("\\.", 2);
        if (parts.length < 1) {
            return null;
        }
        
        String root = parts[0];
        
        // 处理 variables.xxx
        if ("variables".equals(root) && parts.length > 1) {
            Object value = variables.get(parts[1]);
            return value != null ? value.toString() : null;
        }
        
        // 处理 nodeId.xxx
        NodeResult nodeResult = nodeResults.get(root);
        if (nodeResult == null) {
            return null;
        }
        
        if (parts.length == 1) {
            return nodeResult.getResult();
        }
        
        String subPath = parts[1];
        
        // nodeId.result
        if ("result".equals(subPath)) {
            return nodeResult.getResult();
        }
        
        // nodeId.results (所有Agent结果)
        if ("results".equals(subPath)) {
            return formatAgentResults(nodeResult.getAgentResults());
        }
        
        // nodeId.status
        if ("status".equals(subPath)) {
            return nodeResult.getStatus().name();
        }
        
        // nodeId.error
        if ("error".equals(subPath)) {
            return nodeResult.getError();
        }
        
        // nodeId.agentResults.agentName
        if (subPath.startsWith("agentResults.")) {
            String agentName = subPath.substring("agentResults.".length());
            return nodeResult.getAgentResults().get(agentName);
        }
        
        return null;
    }
    
    /**
     * 格式化Agent结果为文本
     */
    private String formatAgentResults(Map<String, String> agentResults) {
        if (agentResults == null || agentResults.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : agentResults.entrySet()) {
            sb.append("【").append(entry.getKey()).append("】\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.toString().trim();
    }
    
    /**
     * 构建依赖节点的输入内容
     */
    public String buildDependencyInput(WorkflowNode node) {
        if (!node.hasDependencies()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== 前置节点结果 ===\n\n");
        
        for (String depId : node.getDependsOn()) {
            NodeResult depResult = nodeResults.get(depId);
            if (depResult != null && depResult.isSuccess()) {
                sb.append("【").append(depId).append("】\n");
                
                // 优先使用Agent结果（更详细）
                if (!depResult.getAgentResults().isEmpty()) {
                    sb.append(formatAgentResults(depResult.getAgentResults()));
                } else if (depResult.getResult() != null) {
                    sb.append(depResult.getResult());
                }
                sb.append("\n\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 获取工作流已执行时间（毫秒）
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }
    
    // Getters
    
    public SharedContext getSharedContext() {
        return sharedContext;
    }
    
    public Map<String, Object> getVariables() {
        return variables;
    }
    
    public Map<String, NodeResult> getNodeResults() {
        return nodeResults;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public int getExecutedNodeCount() {
        return executedNodeCount;
    }
}
