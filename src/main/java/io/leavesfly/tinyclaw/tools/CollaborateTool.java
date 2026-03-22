package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.agent.collaboration.*;

import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowDefinition;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowGenerator;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowNode;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;


import java.util.*;

/**
 * 多Agent协同工具
 * 允许主Agent启动多Agent协同完成复杂任务
 */
public class CollaborateTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("tools");
    
    /** 协同编排器 */
    private AgentOrchestrator orchestrator;
    
    /** LLM Provider（用于生成Workflow） */
    private LLMProvider provider;
    
    /** 模型名称 */
    private String model;
    
    /** 流式回调（用于输出协同过程） */
    private volatile LLMProvider.EnhancedStreamCallback streamCallback;
    
    public CollaborateTool() {
        // orchestrator 需要在注册时设置
    }
    
    public CollaborateTool(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }
    
    /**
     * 设置编排器
     */
    public void setOrchestrator(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }
    
    /**
     * 设置LLM上下文（用于生成Workflow）
     */
    public void setLLMContext(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }
    
    /**
     * 设置流式回调，用于输出协同的执行过程。
     * 
     * @param callback 流式回调，可为 null
     */
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }
    
    @Override
    public String name() {
        return "collaborate";
    }
    
    @Override
    public String description() {
        return "启动多Agent协同完成复杂任务。支持协同模式：" +
                "debate(辩论-正反方观点对决)、" +
                "team(团队协作-任务分解并行执行)、" +
                "roleplay(角色扮演-多角色对话模拟)、" +
                "consensus(共识决策-讨论后投票)、" +
                "hierarchy(分层决策-层级汇报式决策)、" +
                "workflow(通用工作流-LLM动态生成执行计划)。" +
                "workflow模式支持节点类型：" +
                "SINGLE(单Agent执行)、PARALLEL(多Agent并行)、SEQUENTIAL(多Agent顺序)、" +
                "CONDITIONAL(条件分支-根据条件选择路径)、LOOP(循环执行-直到条件满足)、AGGREGATE(聚合多节点结果)";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new LinkedHashMap<>();
        
        // mode参数
        Map<String, Object> modeParam = new LinkedHashMap<>();
        modeParam.put("type", "string");
        modeParam.put("description", "协同模式: debate/team/roleplay/consensus/hierarchy/workflow");
        modeParam.put("enum", Arrays.asList("debate", "team", "roleplay", "consensus", "hierarchy", "workflow"));
        properties.put("mode", modeParam);
        
        // topic参数
        Map<String, Object> topicParam = new LinkedHashMap<>();
        topicParam.put("type", "string");
        topicParam.put("description", "协同主题/目标");
        properties.put("topic", topicParam);
        
        // roles参数
        Map<String, Object> rolesParam = new LinkedHashMap<>();
        rolesParam.put("type", "array");
        rolesParam.put("description", "参与角色定义，每个角色包含name和prompt字段");
        Map<String, Object> roleItem = new LinkedHashMap<>();
        roleItem.put("type", "object");
        Map<String, Object> roleProps = new LinkedHashMap<>();
        roleProps.put("name", Map.of("type", "string", "description", "角色名称"));
        roleProps.put("prompt", Map.of("type", "string", "description", "角色的系统提示词"));
        roleProps.put("allowed_tools", Map.of("type", "array", "description", "该角色允许使用的工具名称白名单，为空则不限制",
                "items", Map.of("type", "string")));
        roleItem.put("properties", roleProps);
        rolesParam.put("items", roleItem);
        properties.put("roles", rolesParam);
        
        // max_rounds参数
        Map<String, Object> maxRoundsParam = new LinkedHashMap<>();
        maxRoundsParam.put("type", "integer");
        maxRoundsParam.put("description", "最大轮次（默认3）");
        maxRoundsParam.put("default", 3);
        properties.put("max_rounds", maxRoundsParam);
        
        // hierarchy参数（分层决策专用）
        Map<String, Object> hierarchyParam = new LinkedHashMap<>();
        hierarchyParam.put("type", "object");
        hierarchyParam.put("description", "分层决策配置（仅hierarchy模式需要）");
        properties.put("hierarchy", hierarchyParam);
        
        // consensus_threshold参数（共识决策专用）
        Map<String, Object> thresholdParam = new LinkedHashMap<>();
        thresholdParam.put("type", "number");
        thresholdParam.put("description", "共识阈值0.0-1.0（仅consensus模式，默认0.6）");
        thresholdParam.put("default", 0.6);
        properties.put("consensus_threshold", thresholdParam);
        
        // workflow参数（通用工作流专用）
        Map<String, Object> workflowParam = new LinkedHashMap<>();
        workflowParam.put("type", "object");
        workflowParam.put("description", "工作流定义（仅workflow模式，可选，不提供则由LLM自动生成）");

        // agent 对象 schema（节点内复用）
        Map<String, Object> agentItemSchema = new LinkedHashMap<>();
        agentItemSchema.put("type", "object");
        Map<String, Object> agentItemProps = new LinkedHashMap<>();
        agentItemProps.put("name", Map.of("type", "string", "description", "角色名称"));
        agentItemProps.put("prompt", Map.of("type", "string", "description", "角色系统提示词"));
        agentItemProps.put("allowed_tools", Map.of("type", "array",
                "description", "该角色允许使用的工具名称白名单，为空则不限制",
                "items", Map.of("type", "string")));
        agentItemSchema.put("properties", agentItemProps);

        // 节点 schema
        Map<String, Object> nodeItemSchema = new LinkedHashMap<>();
        nodeItemSchema.put("type", "object");
        Map<String, Object> nodeProps = new LinkedHashMap<>();
        nodeProps.put("id", Map.of("type", "string", "description", "节点唯一ID"));
        nodeProps.put("type", Map.of("type", "string",
                "description", "节点类型: SINGLE(单Agent)/PARALLEL(并行)/SEQUENTIAL(顺序)/CONDITIONAL(条件分支)/LOOP(循环)/AGGREGATE(聚合)",
                "enum", Arrays.asList("SINGLE", "PARALLEL", "SEQUENTIAL", "CONDITIONAL", "LOOP", "AGGREGATE")));
        nodeProps.put("agents", Map.of("type", "array", "description", "该节点使用的角色列表", "items", agentItemSchema));
        nodeProps.put("dependsOn", Map.of("type", "array", "description", "依赖的节点ID列表",
                "items", Map.of("type", "string")));
        nodeProps.put("inputExpression", Map.of("type", "string", "description", "输入表达式，如 ${nodeId.result}"));
        nodeProps.put("condition", Map.of("type", "string", "description", "条件表达式（可选）"));
        nodeProps.put("branches", Map.of("type", "object", "description",
                "分支路由表（仅CONDITIONAL节点），key为条件值如approve/reject/default，value为目标节点ID"));
        nodeProps.put("max_retries", Map.of("type", "integer", "description", "节点失败时的最大重试次数（默认0不重试）"));
        nodeProps.put("timeout_ms", Map.of("type", "integer", "description", "节点执行超时时间（毫秒，默认0不限制）"));
        nodeProps.put("config", Map.of("type", "object", "description", "节点额外配置（可选）"));
        nodeItemSchema.put("properties", nodeProps);

        // workflow 完整 properties
        Map<String, Object> workflowProps = new LinkedHashMap<>();
        workflowProps.put("name", Map.of("type", "string", "description", "工作流名称"));
        workflowProps.put("description", Map.of("type", "string", "description", "工作流描述"));
        workflowProps.put("nodes", Map.of("type", "array", "description", "工作流节点列表", "items", nodeItemSchema));
        workflowProps.put("outputExpression", Map.of("type", "string", "description", "最终输出表达式，如 ${nodeId.result}"));
        workflowProps.put("variables", Map.of("type", "object", "description", "工作流初始变量（可选）"));
        workflowParam.put("properties", workflowProps);

        properties.put("workflow", workflowParam);
        
        // timeout_ms参数（全局超时，所有模式通用）
        Map<String, Object> timeoutParam = new LinkedHashMap<>();
        timeoutParam.put("type", "integer");
        timeoutParam.put("description", "全局超时时间（毫秒），超时后协同不会启动，0表示不限制（默认0）");
        timeoutParam.put("default", 0);
        properties.put("timeout_ms", timeoutParam);

        params.put("properties", properties);
        params.put("required", Arrays.asList("mode", "topic"));
        
        return params;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> args) throws ToolException {
        if (orchestrator == null) {
            throw new ToolException("协同编排器未初始化");
        }
        
        // 解析参数
        String modeStr = (String) args.get("mode");
        String topic = (String) args.get("topic");
        List<Map<String, Object>> rolesData = (List<Map<String, Object>>) args.get("roles");
        Integer maxRounds = args.get("max_rounds") != null ?
                ((Number) args.get("max_rounds")).intValue() : 3;
        Long timeoutMs = args.get("timeout_ms") != null ?
                ((Number) args.get("timeout_ms")).longValue() : 0L;
        
        if (modeStr == null || topic == null) {
            throw new ToolException("缺少必要参数: mode 和 topic");
        }
        
        logger.info("启动协同", Map.of(
                "mode", modeStr,
                "topic", topic.length() > 50 ? topic.substring(0, 50) + "..." : topic
        ));
        
        try {
            // 解析模式
            CollaborationConfig.Mode mode = parseMode(modeStr);
            
            // 构建配置
            CollaborationConfig config = new CollaborationConfig();
            config.setMode(mode);
            config.setGoal(topic);
            config.setMaxRounds(maxRounds);
            if (timeoutMs > 0) {
                config.setTimeoutMs(timeoutMs);
            }

            // 解析角色（支持 allowed_tools 工具白名单）
            if (rolesData != null) {
                for (Map<String, Object> roleData : rolesData) {
                    String roleName = (String) roleData.get("name");
                    String rolePrompt = (String) roleData.get("prompt");
                    if (roleName != null && rolePrompt != null) {
                        AgentRole role = AgentRole.of(roleName, rolePrompt);
                        List<String> allowedTools = (List<String>) roleData.get("allowed_tools");
                        if (allowedTools != null) {
                            allowedTools.forEach(role::addAllowedTool);
                        }
                        config.addRole(role);
                    }
                }
            }
            
            // 处理特定模式的额外配置
            if (mode == CollaborationConfig.Mode.CONSENSUS) {
                Double threshold = args.get("consensus_threshold") != null ?
                        ((Number) args.get("consensus_threshold")).doubleValue() : 0.6;
                config.setConsensusThreshold(threshold);
            } else if (mode == CollaborationConfig.Mode.HIERARCHY) {
                Map<String, Object> hierarchyData = (Map<String, Object>) args.get("hierarchy");
                if (hierarchyData != null) {
                    HierarchyConfig hierarchy = parseHierarchy(hierarchyData);
                    config.setHierarchy(hierarchy);
                }
            } else if (mode == CollaborationConfig.Mode.WORKFLOW) {
                // 处理workflow模式
                WorkflowDefinition workflow;
                Map<String, Object> workflowData = (Map<String, Object>) args.get("workflow");
                if (workflowData != null) {
                    // 用户提供了workflow定义
                    workflow = parseWorkflow(workflowData);
                } else if (provider != null) {
                    // 由LLM动态生成workflow，传入用户预定义的角色信息
                    WorkflowGenerator generator = new WorkflowGenerator(provider, model);
                    workflow = generator.generate(topic, rolesData);
                } else {
                    throw new ToolException("workflow模式需要提供workflow定义或配置LLM Provider");
                }
                config.setWorkflow(workflow);
            }
            
            // 执行协同（如果有流式回调，使用流式版本）
            String result;
            if (streamCallback != null) {
                result = orchestrator.orchestrateWithStream(config, topic, streamCallback);
            } else {
                result = orchestrator.orchestrate(config, topic);
            }
            
            logger.info("协同完成", Map.of("mode", modeStr));
            
            return result;
            
        } catch (IllegalArgumentException e) {
            throw new ToolException("无效的协同模式: " + modeStr);
        } catch (Exception e) {
            logger.error("协同执行失败", Map.of("error", e.getMessage()));
            throw new ToolException("协同执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析协同模式
     */
    private CollaborationConfig.Mode parseMode(String modeStr) {
        return switch (modeStr.toLowerCase()) {
            case "debate" -> CollaborationConfig.Mode.DEBATE;
            case "team" -> CollaborationConfig.Mode.TEAM;
            case "roleplay" -> CollaborationConfig.Mode.ROLEPLAY;
            case "consensus" -> CollaborationConfig.Mode.CONSENSUS;
            case "hierarchy" -> CollaborationConfig.Mode.HIERARCHY;
            case "workflow" -> CollaborationConfig.Mode.WORKFLOW;
            default -> throw new IllegalArgumentException("Unknown mode: " + modeStr);
        };
    }
    
    /**
     * 解析分层决策配置
     */
    /**
     * 解析分层决策配置
     * levels 数组按索引顺序对应层级（index=0 为底层），每项包含 agents 和可选的 aggregationPrompt
     */
    @SuppressWarnings("unchecked")
    private HierarchyConfig parseHierarchy(Map<String, Object> data) {
        HierarchyConfig config = new HierarchyConfig();

        List<Map<String, Object>> levelsData = (List<Map<String, Object>>) data.get("levels");
        if (levelsData == null) {
            return config;
        }

        // 按数组顺序追加层级（index=0 为底层，与 HierarchyConfig 约定一致）
        for (Map<String, Object> levelData : levelsData) {
            String aggregationPrompt = (String) levelData.get("aggregationPrompt");

            // 解析该层的 Agent 列表（支持 allowed_tools 工具白名单）
            List<AgentRole> levelRoles = new ArrayList<>();
            List<Map<String, Object>> agentsData = (List<Map<String, Object>>) levelData.get("agents");
            if (agentsData != null) {
                for (Map<String, Object> agentData : agentsData) {
                    String name = (String) agentData.get("name");
                    String prompt = (String) agentData.get("prompt");
                    if (name != null && prompt != null) {
                        AgentRole role = AgentRole.of(name, prompt);
                        List<String> allowedTools = (List<String>) agentData.get("allowed_tools");
                        if (allowedTools != null) {
                            allowedTools.forEach(role::addAllowedTool);
                        }
                        levelRoles.add(role);
                    }
                }
            }

            config.addLevel(levelRoles, aggregationPrompt);
        }

        return config;
    }
    
    /**
     * 解析Workflow定义
     */
    @SuppressWarnings("unchecked")
    private WorkflowDefinition parseWorkflow(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "Workflow");
        String description = (String) data.get("description");
        String outputExpression = (String) data.get("outputExpression");
        
        WorkflowDefinition workflow = new WorkflowDefinition(name);
        workflow.setDescription(description);
        workflow.setOutputExpression(outputExpression != null ? outputExpression : "${final.result}");
        
        // 解析节点
        List<Map<String, Object>> nodesData = (List<Map<String, Object>>) data.get("nodes");
        if (nodesData != null) {
            for (Map<String, Object> nodeData : nodesData) {
                WorkflowNode node = parseWorkflowNode(nodeData);
                workflow.addNode(node);
            }
        }
        
        // 解析变量
        Map<String, Object> variables = (Map<String, Object>) data.get("variables");
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                workflow.setVariable(entry.getKey(), entry.getValue());
            }
        }
        
        return workflow;
    }
    
    /**
     * 解析Workflow节点
     */
    @SuppressWarnings("unchecked")
    private WorkflowNode parseWorkflowNode(Map<String, Object> data) {
        String id = (String) data.get("id");
        String typeStr = (String) data.get("type");
        WorkflowNode.NodeType type = WorkflowNode.NodeType.valueOf(typeStr.toUpperCase());
        
        WorkflowNode node = new WorkflowNode(id, type);
        
        // 解析依赖
        List<String> dependsOn = (List<String>) data.get("dependsOn");
        if (dependsOn != null) {
            for (String dep : dependsOn) {
                node.dependsOn(dep);
            }
        }
        
        // 解析表达式
        String inputExpression = (String) data.get("inputExpression");
        if (inputExpression != null) {
            node.setInputExpression(inputExpression);
        }
        
        String condition = (String) data.get("condition");
        if (condition != null) {
            node.setCondition(condition);
        }
        
        // 解析 CONDITIONAL 节点的分支路由表
        Map<String, Object> branchesRaw = (Map<String, Object>) data.get("branches");
        if (branchesRaw != null) {
            for (Map.Entry<String, Object> entry : branchesRaw.entrySet()) {
                node.addBranch(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        // 解析节点级重试次数
        if (data.get("max_retries") != null) {
            node.setMaxRetries(((Number) data.get("max_retries")).intValue());
        }

        // 解析节点级超时
        if (data.get("timeout_ms") != null) {
            node.setTimeoutMs(((Number) data.get("timeout_ms")).longValue());
        }

        // 解析Agent角色（支持 allowed_tools 工具白名单）
        List<Map<String, Object>> agentsData = (List<Map<String, Object>>) data.get("agents");
        if (agentsData != null) {
            for (Map<String, Object> agentData : agentsData) {
                String agentName = (String) agentData.get("name");
                String prompt = (String) agentData.get("prompt");
                if (agentName != null && prompt != null) {
                    AgentRole role = AgentRole.of(agentName, prompt);
                    List<String> allowedTools = (List<String>) agentData.get("allowed_tools");
                    if (allowedTools != null) {
                        allowedTools.forEach(role::addAllowedTool);
                    }
                    node.addAgent(role);
                }
            }
        }

        // 解析节点额外配置
        Map<String, Object> config = (Map<String, Object>) data.get("config");
        if (config != null) {
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                node.getConfig().put(entry.getKey(), entry.getValue());
            }
        }

        return node;
    }
}
