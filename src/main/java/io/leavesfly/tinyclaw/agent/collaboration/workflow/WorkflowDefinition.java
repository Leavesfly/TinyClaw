package io.leavesfly.tinyclaw.agent.collaboration.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow 定义
 * 描述完整的工作流结构，包括节点、变量和输出表达式
 */
public class WorkflowDefinition {
    
    /** 工作流名称 */
    private String name;
    
    /** 工作流描述 */
    private String description;
    
    /** 工作流版本 */
    private String version;
    
    /** 节点列表 */
    private List<WorkflowNode> nodes;
    
    /** 全局变量 */
    private Map<String, Object> variables;
    
    /** 输出表达式（如 "${final.result}"） */
    private String outputExpression;
    
    /** 超时时间（毫秒），0表示不限制 */
    private long timeoutMs;
    
    /** 最大节点执行数（防止无限循环） */
    private int maxNodeExecutions;
    
    public WorkflowDefinition() {
        this.nodes = new ArrayList<>();
        this.variables = new HashMap<>();
        this.maxNodeExecutions = 100;
    }
    
    public WorkflowDefinition(String name) {
        this();
        this.name = name;
    }
    
    /**
     * 添加节点
     */
    public WorkflowDefinition addNode(WorkflowNode node) {
        nodes.add(node);
        return this;
    }
    
    /**
     * 设置全局变量
     */
    public WorkflowDefinition setVariable(String key, Object value) {
        variables.put(key, value);
        return this;
    }
    
    /**
     * 设置输出表达式
     */
    public WorkflowDefinition withOutput(String expression) {
        this.outputExpression = expression;
        return this;
    }
    
    /**
     * 根据ID获取节点
     */
    public WorkflowNode getNode(String id) {
        return nodes.stream()
                .filter(n -> id.equals(n.getId()))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 获取入口节点（无依赖的节点）
     */
    public List<WorkflowNode> getEntryNodes() {
        List<WorkflowNode> entries = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!node.hasDependencies()) {
                entries.add(node);
            }
        }
        return entries;
    }
    
    /**
     * 获取终止节点（没有被其他节点依赖的节点）
     */
    public List<WorkflowNode> getTerminalNodes() {
        List<WorkflowNode> terminals = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            boolean isTerminal = true;
            for (WorkflowNode other : nodes) {
                if (other.getDependsOn().contains(node.getId())) {
                    isTerminal = false;
                    break;
                }
            }
            if (isTerminal) {
                terminals.add(node);
            }
        }
        return terminals;
    }
    
    /**
     * 验证工作流定义的有效性
     */
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        
        // 检查是否有节点
        if (nodes.isEmpty()) {
            result.addError("工作流没有定义任何节点");
            return result;
        }
        
        // 检查节点ID唯一性
        Map<String, Integer> idCount = new HashMap<>();
        for (WorkflowNode node : nodes) {
            if (node.getId() == null || node.getId().isEmpty()) {
                result.addError("存在未设置ID的节点");
            } else {
                idCount.merge(node.getId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : idCount.entrySet()) {
            if (entry.getValue() > 1) {
                result.addError("节点ID重复: " + entry.getKey());
            }
        }
        
        // 检查依赖是否存在
        for (WorkflowNode node : nodes) {
            for (String depId : node.getDependsOn()) {
                if (getNode(depId) == null) {
                    result.addError("节点 " + node.getId() + " 依赖的节点不存在: " + depId);
                }
            }
        }
        
        // 检查是否有入口节点
        if (getEntryNodes().isEmpty()) {
            result.addError("工作流没有入口节点（所有节点都有依赖，可能存在循环依赖）");
        }
        
        return result;
    }
    
    // Getters and Setters
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public List<WorkflowNode> getNodes() {
        return nodes;
    }
    
    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes;
    }
    
    public Map<String, Object> getVariables() {
        return variables;
    }
    
    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }
    
    public String getOutputExpression() {
        return outputExpression;
    }
    
    public void setOutputExpression(String outputExpression) {
        this.outputExpression = outputExpression;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
    
    public int getMaxNodeExecutions() {
        return maxNodeExecutions;
    }
    
    public void setMaxNodeExecutions(int maxNodeExecutions) {
        this.maxNodeExecutions = maxNodeExecutions;
    }
    
    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        @Override
        public String toString() {
            if (isValid()) {
                return "Valid";
            }
            return "Invalid: " + String.join("; ", errors);
        }
    }
}
