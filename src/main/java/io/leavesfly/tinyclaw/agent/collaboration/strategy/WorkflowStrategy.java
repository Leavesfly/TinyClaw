package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationConfig;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowDefinition;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowEngine;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * 通用 Workflow 策略
 * 基于 DSL 定义的工作流执行，支持 LLM 动态生成
 */
public class WorkflowStrategy implements CollaborationStrategy {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");
    
    /** Workflow 执行引擎 */
    private final WorkflowEngine engine;
    
    /** LLM Provider（用于执行） */
    private LLMProvider provider;
    
    /** 工具注册表 */
    private ToolRegistry tools;
    
    /** 工作空间路径 */
    private String workspace;
    
    /** 默认模型 */
    private String model;
    
    /** 最大迭代次数 */
    private int maxIterations;
    
    public WorkflowStrategy() {
        this.engine = new WorkflowEngine();
    }
    
    /**
     * 设置执行上下文（在 execute 前调用）
     */
    public void setExecutionContext(LLMProvider provider, ToolRegistry tools,
                                     String workspace, String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
    }
    
    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        WorkflowDefinition workflow = config.getWorkflow();
        
        if (workflow == null) {
            return "未提供 Workflow 定义";
        }
        
        // 验证工作流
        WorkflowDefinition.ValidationResult validation = workflow.validate();
        if (!validation.isValid()) {
            logger.error("Workflow 定义无效", Map.of("errors", validation.getErrors().toString()));
            return "Workflow 定义无效: " + String.join("; ", validation.getErrors());
        }
        
        logger.info("开始执行 Workflow 策略", Map.of(
                "workflowName", workflow.getName() != null ? workflow.getName() : "unnamed",
                "nodeCount", workflow.getNodes().size()
        ));
        
        try {
            // 执行工作流
            String result = engine.execute(
                    workflow, context, provider, tools, workspace, model, maxIterations);
            
            context.setFinalConclusion(result);
            
            logger.info("Workflow 执行完成", Map.of(
                    "resultLength", result.length()
            ));
            
            return result;
            
        } catch (Exception e) {
            logger.error("Workflow 执行失败", Map.of("error", e.getMessage()));
            return "Workflow 执行失败: " + e.getMessage();
        }
    }
    
    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // Workflow 由引擎控制终止
        return false;
    }
    
    @Override
    public AgentExecutor getNextSpeaker(SharedContext context, List<AgentExecutor> agents) {
        // Workflow 不使用轮流发言机制
        return null;
    }
    
    @Override
    public String getName() {
        return "Workflow";
    }
    
    @Override
    public String getDescription() {
        return "通用 Workflow 策略：基于 DSL 定义的工作流执行，支持并行、顺序、条件、循环等节点类型";
    }
    
    /**
     * 关闭策略（释放资源）
     */
    public void shutdown() {
        engine.shutdown();
    }
}
