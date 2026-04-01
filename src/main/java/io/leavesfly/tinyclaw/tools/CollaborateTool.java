package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.agent.collaboration.workflow.WorkflowGenerator;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;


import java.util.*;

/**
 * 多Agent协同工具
 * 允许主Agent启动多Agent协同完成复杂任务
 */
public class CollaborateTool implements Tool, StreamAwareTool {
    
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
    @Override
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }
    
    @Override
    public String name() {
        return "collaborate";
    }
    
    @Override
    public String description() {
        return "启动多Agent协同完成复杂任务。三种模式：\n" +
                "- discuss: 多角色讨论/辩论/角色扮演，适合需要多视角分析、观点碰撞的场景\n" +
                "- tasks: 任务分解后并行执行，适合可拆分为独立子任务的场景\n" +
                "- workflow: 复杂多步骤流程编排（自动生成执行计划），适合有依赖关系的复杂任务";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // mode：3 种核心模式
        Map<String, Object> modeParam = new LinkedHashMap<>();
        modeParam.put("type", "string");
        modeParam.put("description", "协同模式: discuss(多角色讨论)/tasks(任务分解并行执行)/workflow(复杂流程编排)");
        modeParam.put("enum", Arrays.asList("discuss", "tasks", "workflow"));
        properties.put("mode", modeParam);

        // topic：协同目标
        Map<String, Object> topicParam = new LinkedHashMap<>();
        topicParam.put("type", "string");
        topicParam.put("description", "协同主题/目标，清晰描述要完成的任务");
        properties.put("topic", topicParam);

        // roles：参与角色
        Map<String, Object> rolesParam = new LinkedHashMap<>();
        rolesParam.put("type", "array");
        rolesParam.put("description", "参与角色列表，每个角色包含name(角色名)和prompt(角色系统提示词)");
        Map<String, Object> roleItem = new LinkedHashMap<>();
        roleItem.put("type", "object");
        Map<String, Object> roleProps = new LinkedHashMap<>();
        roleProps.put("name", Map.of("type", "string", "description", "角色名称"));
        roleProps.put("prompt", Map.of("type", "string", "description", "角色的系统提示词，定义角色的专业背景和行为"));
        roleItem.put("properties", roleProps);
        roleItem.put("required", Arrays.asList("name", "prompt"));
        rolesParam.put("items", roleItem);
        properties.put("roles", rolesParam);

        // max_rounds：最大轮次
        Map<String, Object> maxRoundsParam = new LinkedHashMap<>();
        maxRoundsParam.put("type", "integer");
        maxRoundsParam.put("description", "最大讨论轮次（仅discuss模式，默认3）");
        maxRoundsParam.put("default", 3);
        properties.put("max_rounds", maxRoundsParam);

        // style：可选的风格提示
        Map<String, Object> styleParam = new LinkedHashMap<>();
        styleParam.put("type", "string");
        styleParam.put("description", "可选风格提示。discuss模式: debate(辩论)/roleplay(角色扮演)/consensus(共识投票)/dynamic(动态路由)；tasks模式: parallel(扁平并行,默认)/hierarchy(层级汇报)");
        properties.put("style", styleParam);

        // context_summary：上下文摘要
        Map<String, Object> contextSummaryParam = new LinkedHashMap<>();
        contextSummaryParam.put("type", "string");
        contextSummaryParam.put("description", "当前对话的上下文摘要，帮助协同Agent理解背景（可选）");
        properties.put("context_summary", contextSummaryParam);

        params.put("properties", properties);
        params.put("required", Arrays.asList("mode", "topic", "roles"));

        return params;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> args) throws ToolException {
        if (orchestrator == null) {
            throw new ToolException("协同编排器未初始化");
        }

        String modeStr = (String) args.get("mode");
        String topic = (String) args.get("topic");
        List<Map<String, Object>> rolesData = (List<Map<String, Object>>) args.get("roles");
        Integer maxRounds = args.get("max_rounds") != null
                ? ((Number) args.get("max_rounds")).intValue() : 3;
        String styleStr = (String) args.get("style");

        if (modeStr == null || topic == null) {
            throw new ToolException("缺少必要参数: mode 和 topic");
        }

        logger.info("启动协同", Map.of(
                "mode", modeStr,
                "topic", topic.length() > 50 ? topic.substring(0, 50) + "..." : topic
        ));

        try {
            // 构建配置
            CollaborationConfig config = buildConfig(modeStr, topic, maxRounds, styleStr, rolesData);

            // 解析角色
            parseRoles(config, rolesData);

            // 解析上下文摘要
            String contextSummary = (String) args.get("context_summary");
            if (contextSummary != null && !contextSummary.isEmpty()) {
                config.withMeta("contextSummary", contextSummary);
            }

            // workflow 模式：自动生成执行计划
            if (config.getMode() == CollaborationConfig.Mode.WORKFLOW && config.getWorkflow() == null) {
                if (provider == null) {
                    throw new ToolException("workflow模式需要配置LLM Provider");
                }
                WorkflowGenerator generator = new WorkflowGenerator(provider, model);
                config.setWorkflow(generator.generate(topic, rolesData));
            }

            // 执行协同
            String result = streamCallback != null
                    ? orchestrator.orchestrateWithStream(config, topic, streamCallback)
                    : orchestrator.orchestrate(config, topic);

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
     * 根据 mode 和 style 构建 CollaborationConfig
     */
    private CollaborationConfig buildConfig(String modeStr, String topic, int maxRounds,
                                            String styleStr, List<Map<String, Object>> rolesData) {
        return switch (modeStr.toLowerCase()) {
            case "discuss" -> {
                CollaborationConfig config = CollaborationConfig.discuss(topic, maxRounds);
                if (styleStr != null) {
                    config.setDiscussStyle(parseDiscussStyle(styleStr));
                }
                yield config;
            }
            case "tasks" -> {
                CollaborationConfig config = CollaborationConfig.tasks(topic);
                if (styleStr != null) {
                    config.setTasksStyle(parseTasksStyle(styleStr));
                }
                // tasks 模式：自动将角色转换为任务
                if (rolesData != null) {
                    for (Map<String, Object> roleData : rolesData) {
                        String roleName = (String) roleData.get("name");
                        String rolePrompt = (String) roleData.get("prompt");
                        if (roleName != null && rolePrompt != null) {
                            AgentRole assignee = AgentRole.of(roleName, rolePrompt);
                            TeamTask task = new TeamTask(roleName, roleName, assignee);
                            task.setDescription(rolePrompt);
                            config.addTask(task);
                        }
                    }
                }
                yield config;
            }
            case "workflow" -> CollaborationConfig.workflow(topic, null);
            default -> throw new IllegalArgumentException("Unknown mode: " + modeStr);
        };
    }

    /**
     * 解析角色列表到配置中
     */
    @SuppressWarnings("unchecked")
    private void parseRoles(CollaborationConfig config, List<Map<String, Object>> rolesData) {
        if (rolesData == null) {
            return;
        }
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

    private CollaborationConfig.DiscussStyle parseDiscussStyle(String style) {
        return switch (style.toLowerCase()) {
            case "debate" -> CollaborationConfig.DiscussStyle.DEBATE;
            case "roleplay" -> CollaborationConfig.DiscussStyle.ROLEPLAY;
            case "consensus" -> CollaborationConfig.DiscussStyle.CONSENSUS;
            case "dynamic" -> CollaborationConfig.DiscussStyle.DYNAMIC;
            default -> null;
        };
    }

    private CollaborationConfig.TasksStyle parseTasksStyle(String style) {
        return switch (style.toLowerCase()) {
            case "parallel" -> CollaborationConfig.TasksStyle.PARALLEL;
            case "hierarchy" -> CollaborationConfig.TasksStyle.HIERARCHY;
            default -> CollaborationConfig.TasksStyle.PARALLEL;
        };
    }
}
