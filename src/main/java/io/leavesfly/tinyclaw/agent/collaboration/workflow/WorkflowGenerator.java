package io.leavesfly.tinyclaw.agent.collaboration.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.agent.collaboration.AgentRole;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow 生成器
 * 使用 LLM 根据任务描述动态生成 Workflow 定义
 */
public class WorkflowGenerator {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");
    
    /** JSON 代码块提取正则 */
    private static final Pattern JSON_PATTERN = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    
    /** LLM Provider */
    private final LLMProvider provider;
    
    /** 使用的模型 */
    private final String model;
    
    /** Jackson ObjectMapper */
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public WorkflowGenerator(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }
    
    /**
     * 根据任务描述生成 Workflow。
     *
     * <p>生成策略：
     * <ol>
     *   <li>首先使用完整 prompt 调用 LLM 生成工作流；</li>
     *   <li>若失败，使用简化 prompt 重试一次（降低 LLM 理解难度）；</li>
     *   <li>两次均失败时，才降级为单节点兜底工作流，并明确记录失败原因。</li>
     * </ol>
     *
     * @param taskDescription 任务描述
     * @param rolesData       用户预定义的角色列表，每个元素包含 name 和 prompt 字段；为 null 时由 LLM 自行设计角色
     */
    public WorkflowDefinition generate(String taskDescription, List<Map<String, Object>> rolesData) {
        logger.info("开始生成 Workflow", Map.of(
                "taskLength", taskDescription.length(),
                "predefinedRoles", rolesData != null ? rolesData.size() : 0
        ));

        // 第一次尝试：完整 prompt
        try {
            WorkflowDefinition workflow = attemptGenerate(
                    buildGenerationPrompt(taskDescription, rolesData), taskDescription);
            logger.info("Workflow 生成成功（首次）", Map.of(
                    "name", workflow.getName() != null ? workflow.getName() : "unnamed",
                    "nodeCount", workflow.getNodes().size()
            ));
            return workflow;
        } catch (Exception firstError) {
            logger.warn("Workflow 首次生成失败，尝试简化 prompt 重试", Map.of(
                    "error", firstError.getMessage()
            ));
        }

        // 第二次尝试：简化 prompt（去掉角色约束，让 LLM 自由发挥）
        try {
            WorkflowDefinition workflow = attemptGenerate(
                    buildSimplifiedPrompt(taskDescription), taskDescription);
            logger.info("Workflow 生成成功（重试）", Map.of(
                    "name", workflow.getName() != null ? workflow.getName() : "unnamed",
                    "nodeCount", workflow.getNodes().size()
            ));
            return workflow;
        } catch (Exception retryError) {
            logger.error("Workflow 重试生成仍失败，降级为单节点兜底工作流", Map.of(
                    "error", retryError.getMessage()
            ));
        }

        // 两次均失败：降级兜底，明确告知调用方
        return createFallbackWorkflow(taskDescription);
    }

    /**
     * 执行一次 LLM 生成并解析，验证通过后返回工作流。
     * 验证失败时抛出异常，由调用方决定是否重试。
     */
    private WorkflowDefinition attemptGenerate(String prompt, String taskDescription) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", getSystemPrompt()));
        messages.add(new Message("user", prompt));

        LLMResponse response = provider.chat(messages, null, model, null);
        WorkflowDefinition workflow = parseWorkflowFromResponse(response.getContent());

        WorkflowDefinition.ValidationResult validation = workflow.validate();
        if (!validation.isValid()) {
            throw new IllegalStateException("生成的 Workflow 验证失败: " + validation.getErrors());
        }

        return workflow;
    }

    /**
     * 构建简化版生成提示（重试时使用）。
     * 去掉角色约束，缩短描述，降低 LLM 理解难度。
     */
    private String buildSimplifiedPrompt(String taskDescription) {
        // 截断过长的任务描述，避免 LLM 因上下文过长而输出混乱
        String truncated = taskDescription.length() > 500
                ? taskDescription.substring(0, 500) + "..."
                : taskDescription;
        return "请为以下任务设计一个简单的多 Agent 工作流（2-3 个节点即可）：\n\n" + truncated
                + "\n\n只需返回合法的 JSON，不要有任何解释文字。";
    }
    
    /**
     * 获取系统提示
     */
    private String getSystemPrompt() {
        return """
            你是一个工作流设计专家。你的任务是根据用户需求设计多 Agent 协作的工作流。
            
            你必须返回一个 JSON 格式的工作流定义，包含以下结构：
            
            {
              "name": "工作流名称",
              "description": "工作流描述",
              "nodes": [
                {
                  "id": "唯一节点ID",
                  "name": "节点显示名称",
                  "type": "节点类型",
                  "agents": [
                    {"roleId": "角色ID", "roleName": "角色名称", "systemPrompt": "角色提示词"}
                  ],
                  "dependsOn": ["依赖的节点ID"],
                  "inputExpression": "可选的输入表达式"
                }
              ],
              "outputExpression": "${最终节点ID.result}"
            }
            
            可用的节点类型（type）：
            - SINGLE: 单个 Agent 执行任务
            - PARALLEL: 多个 Agent 并行执行（结果会合并）
            - SEQUENTIAL: 多个 Agent 顺序执行（前一个的输出作为下一个的输入）
            - AGGREGATE: 聚合多个依赖节点的结果
            
            设计原则：
            1. 分析任务，识别需要哪些专业角色
            2. 设计合理的执行顺序和并行关系
            3. 使用 dependsOn 指定节点依赖
            4. 最后通常需要一个汇总或决策节点
            
            只返回 JSON，不要有其他内容。
            """;
    }
    
    /**
     * 构建生成提示，若用户预定义了角色则将角色信息注入提示词
     */
    private String buildGenerationPrompt(String taskDescription, List<Map<String, Object>> rolesData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下任务设计一个多 Agent 协作的工作流：\n\n").append(taskDescription);

        if (rolesData != null && !rolesData.isEmpty()) {
            prompt.append("\n\n用户已预定义了以下角色，请优先使用这些角色（roleName 和 systemPrompt 直接复用）：\n");
            for (Map<String, Object> role : rolesData) {
                String name = (String) role.get("name");
                String rolePrompt = (String) role.get("prompt");
                if (name != null && rolePrompt != null) {
                    prompt.append("- 角色名称: ").append(name)
                          .append("，系统提示词: ").append(rolePrompt).append("\n");
                }
            }
            prompt.append("如果任务需要额外角色，可以自行补充。");
        }

        return prompt.toString();
    }
    
    /**
     * 从 LLM 响应中解析 Workflow
     */
    private WorkflowDefinition parseWorkflowFromResponse(String response) {
        try {
            // 尝试提取 JSON 代码块
            String json = extractJson(response);
            
            // 解析 JSON
            JsonNode root = objectMapper.readTree(json);
            
            WorkflowDefinition workflow = new WorkflowDefinition();
            
            // 解析基本属性
            if (root.has("name")) {
                workflow.setName(root.get("name").asText());
            }
            if (root.has("description")) {
                workflow.setDescription(root.get("description").asText());
            }
            if (root.has("outputExpression")) {
                workflow.setOutputExpression(root.get("outputExpression").asText());
            }
            
            // 解析节点
            if (root.has("nodes") && root.get("nodes").isArray()) {
                for (JsonNode nodeElement : root.get("nodes")) {
                    WorkflowNode node = parseNode(nodeElement);
                    workflow.addNode(node);
                }
            }
            
            return workflow;
        } catch (Exception e) {
            throw new RuntimeException("解析 Workflow JSON 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析单个节点
     */
    private WorkflowNode parseNode(JsonNode nodeJson) {
        WorkflowNode node = new WorkflowNode();
        
        // 基本属性
        if (nodeJson.has("id")) {
            node.setId(nodeJson.get("id").asText());
        }
        if (nodeJson.has("name")) {
            node.setName(nodeJson.get("name").asText());
        }
        if (nodeJson.has("type")) {
            String typeStr = nodeJson.get("type").asText();
            node.setType(WorkflowNode.NodeType.valueOf(typeStr.toUpperCase()));
        }
        if (nodeJson.has("inputExpression")) {
            node.setInputExpression(nodeJson.get("inputExpression").asText());
        }
        if (nodeJson.has("condition")) {
            node.setCondition(nodeJson.get("condition").asText());
        }
        
        // 解析依赖
        if (nodeJson.has("dependsOn") && nodeJson.get("dependsOn").isArray()) {
            List<String> deps = new ArrayList<>();
            for (JsonNode dep : nodeJson.get("dependsOn")) {
                deps.add(dep.asText());
            }
            node.setDependsOn(deps);
        }
        
        // 解析 Agents
        if (nodeJson.has("agents") && nodeJson.get("agents").isArray()) {
            List<AgentRole> agents = new ArrayList<>();
            for (JsonNode agentElement : nodeJson.get("agents")) {
                AgentRole role = new AgentRole();
                
                if (agentElement.has("roleId")) {
                    role.setRoleId(agentElement.get("roleId").asText());
                }
                if (agentElement.has("roleName") || agentElement.has("name")) {
                    String name = agentElement.has("roleName") 
                            ? agentElement.get("roleName").asText()
                            : agentElement.get("name").asText();
                    role.setRoleName(name);
                    if (role.getRoleId() == null) {
                        role.setRoleId(name);
                    }
                }
                if (agentElement.has("systemPrompt") || agentElement.has("prompt")) {
                    String prompt = agentElement.has("systemPrompt")
                            ? agentElement.get("systemPrompt").asText()
                            : agentElement.get("prompt").asText();
                    role.setSystemPrompt(prompt);
                }
                if (agentElement.has("model")) {
                    role.setModel(agentElement.get("model").asText());
                }
                
                agents.add(role);
            }
            node.setAgents(agents);
        }
        
        return node;
    }
    
    /**
     * 从响应中提取 JSON
     */
    private String extractJson(String response) {
        // 尝试匹配代码块
        Matcher matcher = JSON_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // 尝试直接解析（可能整个响应就是 JSON）
        String trimmed = response.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        
        // 尝试找到第一个 { 和最后一个 }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        
        throw new IllegalArgumentException("无法从响应中提取 JSON");
    }
    
    /**
     * 创建兜底工作流（两次 LLM 生成均失败时使用）。
     *
     * <p>与原来的静默降级不同，此处创建一个包含两个节点的工作流：
     * 第一个节点负责分析任务，第二个节点基于分析结果给出最终建议，
     * 保留了基本的多步骤语义，同时在工作流描述中明确标注为兜底模式，
     * 方便调用方感知并在日志中追踪。
     */
    private WorkflowDefinition createFallbackWorkflow(String taskDescription) {
        logger.warn("使用兜底工作流", Map.of(
                "taskDescriptionLength", taskDescription.length()
        ));

        WorkflowDefinition workflow = new WorkflowDefinition("兜底工作流");
        workflow.setDescription("[FALLBACK] LLM 工作流生成失败（已重试一次），使用兜底双节点工作流");

        // 节点1：任务分析
        WorkflowNode analyzeNode = new WorkflowNode("analyze", WorkflowNode.NodeType.SINGLE);
        analyzeNode.setName("任务分析");
        analyzeNode.addAgent(AgentRole.of("分析师",
                "你是一个任务分析专家。请仔细分析用户的任务需求，拆解关键问题，给出结构化的分析报告。"));
        workflow.addNode(analyzeNode);

        // 节点2：综合建议（依赖分析节点）
        WorkflowNode adviceNode = new WorkflowNode("advice", WorkflowNode.NodeType.SINGLE);
        adviceNode.setName("综合建议");
        adviceNode.addAgent(AgentRole.of("顾问",
                "你是一个资深顾问。请基于前置分析结果，给出具体可执行的建议和行动方案。"));
        adviceNode.dependsOn("analyze");
        workflow.addNode(adviceNode);

        workflow.setOutputExpression("${advice.result}");

        return workflow;
    }
    
    /**
     * 从 JSON 字符串解析 Workflow（用于用户直接提供的配置）
     */
    public WorkflowDefinition parseFromJson(String json) {
        return parseWorkflowFromResponse(json);
    }
    
    /**
     * 从 Map 解析 Workflow（用于工具参数）
     */
    @SuppressWarnings("unchecked")
    public WorkflowDefinition parseFromMap(Map<String, Object> data) {
        try {
            // 转换为 JSON 再解析
            String json = objectMapper.writeValueAsString(data);
            return parseWorkflowFromResponse(json);
        } catch (Exception e) {
            throw new RuntimeException("解析 Workflow Map 失败: " + e.getMessage(), e);
        }
    }
}
