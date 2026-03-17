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
     * 根据任务描述生成 Workflow
     *
     * @param taskDescription 任务描述
     * @param rolesData       用户预定义的角色列表，每个元素包含 name 和 prompt 字段；为 null 时由 LLM 自行设计角色
     */
    public WorkflowDefinition generate(String taskDescription, List<Map<String, Object>> rolesData) {
        logger.info("开始生成 Workflow", Map.of(
                "taskLength", taskDescription.length(),
                "predefinedRoles", rolesData != null ? rolesData.size() : 0
        ));
        
        try {
            // 构建生成提示
            String prompt = buildGenerationPrompt(taskDescription, rolesData);
            
            // 调用 LLM
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", getSystemPrompt()));
            messages.add(new Message("user", prompt));
            
            LLMResponse response = provider.chat(messages, null, model, null);
            String content = response.getContent();
            
            // 解析 JSON
            WorkflowDefinition workflow = parseWorkflowFromResponse(content);
            
            // 验证
            WorkflowDefinition.ValidationResult validation = workflow.validate();
            if (!validation.isValid()) {
                logger.warn("生成的 Workflow 验证失败", Map.of(
                        "errors", validation.getErrors().toString()
                ));
            }
            
            logger.info("Workflow 生成成功", Map.of(
                    "name", workflow.getName() != null ? workflow.getName() : "unnamed",
                    "nodeCount", workflow.getNodes().size()
            ));
            
            return workflow;
            
        } catch (Exception e) {
            logger.error("Workflow 生成失败", Map.of("error", e.getMessage()));
            // 返回一个简单的默认工作流
            return createFallbackWorkflow(taskDescription);
        }
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
     * 创建降级工作流（当生成失败时使用）
     */
    private WorkflowDefinition createFallbackWorkflow(String taskDescription) {
        WorkflowDefinition workflow = new WorkflowDefinition("默认工作流");
        workflow.setDescription("自动生成失败，使用默认单 Agent 工作流");
        
        // 创建单个分析节点
        WorkflowNode analyzeNode = new WorkflowNode("analyze", WorkflowNode.NodeType.SINGLE);
        analyzeNode.setName("任务分析");
        analyzeNode.addAgent(AgentRole.of("分析师", 
                "你是一个任务分析专家。请分析用户的任务需求，给出详细的分析和建议。"));
        workflow.addNode(analyzeNode);
        
        workflow.setOutputExpression("${analyze.result}");
        
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
