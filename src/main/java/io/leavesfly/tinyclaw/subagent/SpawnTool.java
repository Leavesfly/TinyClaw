package io.leavesfly.tinyclaw.subagent;

import io.leavesfly.tinyclaw.tools.StreamAwareTool;
import io.leavesfly.tinyclaw.tools.Tool;
import io.leavesfly.tinyclaw.tools.ToolContextAware;
import io.leavesfly.tinyclaw.tools.ToolException;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理生成工具 - 支持同步（subagent as tool）和异步（fire-and-forget）两种模式。
 * 
 * 默认同步模式：子代理执行完毕后将实际结果作为 tool_result 返回给主 Agent，
 * 主 Agent 可基于结果继续推理。
 * 
 * 异步模式（async=true）：子代理在后台运行，立即返回确认信息，
 * 完成后通过 MessageBus 通知主 Agent。
 * 
 * 支持通过 agent 参数指定专职子代理（workspace/agents/&lt;name&gt;/AGENT.md 动态定义），
 * 可用子代理清单会动态注入工具描述和参数 enum，供主 Agent 的 LLM 感知与选择。
 */
public class SpawnTool implements Tool, ToolContextAware, StreamAwareTool {
    
    private final SubagentManager manager;
    private String originChannel = "cli";
    private String originChatId = "direct";
    /** 流式回调（用于输出子代理执行过程） */
    private volatile LLMProvider.EnhancedStreamCallback streamCallback;
    
    public SpawnTool(SubagentManager manager) {
        this.manager = manager;
    }
    
    @Override
    public String name() {
        return "spawn";
    }
    
    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append("派生一个独立的子代理来执行指定任务。支持两种执行模式：\n")
          .append("- sync（默认）: 阻塞等待子代理完成并返回完整结果。当你需要基于子代理的输出继续推理、做决策或组合多个结果时使用。\n")
          .append("- async: 子代理在后台运行，立即返回确认信息，完成后通过消息通知。适合耗时较长且不需要立即使用结果的后台任务。\n")
          .append("适用场景：将复杂任务拆分为独立子任务、需要隔离上下文的专项处理、并行处理多个独立子问题。");

        // 动态注入可用的专职子代理清单，供 LLM 判断该派给谁
        SubagentsLoader loader = manager != null ? manager.getAgentsLoader() : null;
        if (loader != null) {
            String summary = loader.buildAgentsSummary();
            if (!summary.isEmpty()) {
                sb.append("\n可用的专职子代理（通过 agent 参数指定，任务与其擅长领域匹配时优先使用）：\n")
                  .append(summary)
                  .append("\n不指定 agent 时使用通用子代理。");
            }
        }
        return sb.toString();
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new LinkedHashMap<>();
        
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "子代理要执行的任务描述。应包含清晰的目标、必要的上下文信息和期望的输出格式，使子代理能独立完成任务而无需额外澄清。");
        properties.put("task", task);
        
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("type", "string");
        label.put("description", "任务的简短标签（3-10字），用于在执行过程中标识和显示该子任务。例如：'代码审查'、'数据分析'。");
        properties.put("label", label);
        
        // 动态生成可用专职子代理的 enum（每次构建请求时重新读取，天然支持热更新）
        SubagentsLoader loader = manager != null ? manager.getAgentsLoader() : null;
        if (loader != null) {
            List<SubagentDefinition> defs = loader.listAgents();
            if (!defs.isEmpty()) {
                Map<String, Object> agent = new LinkedHashMap<>();
                agent.put("type", "string");
                agent.put("description", "专职子代理名称（可选）。指定后使用该子代理的专属系统提示词、模型和工具集执行任务；不指定则使用通用子代理。");
                List<String> names = new ArrayList<>();
                for (SubagentDefinition def : defs) {
                    names.add(def.getName());
                }
                agent.put("enum", names);
                properties.put("agent", agent);
            }
        }
        
        Map<String, Object> async = new LinkedHashMap<>();
        async.put("type", "boolean");
        async.put("description", "执行模式。false（默认）: 同步阻塞，等待子代理完成并返回结果，适合需要基于结果继续推理的场景。true: 异步后台运行，立即返回确认信息，适合耗时的后台任务。");
        async.put("default", false);
        properties.put("async", async);
        
        params.put("properties", properties);
        params.put("required", List.of("task"));
        
        return params;
    }
    
    /**
     * 设置生成上下文
     */
    public void setContext(String channel, String chatId) {
        this.originChannel = channel != null ? channel : "cli";
        this.originChatId = chatId != null ? chatId : "direct";
    }
    
    @Override
    public void setChannelContext(String channel, String chatId) {
        setContext(channel, chatId);
    }
    
    /**
     * 设置流式回调，用于输出子代理的执行过程。
     * 
     * @param callback 流式回调，可为 null
     */
    @Override
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return "错误: 任务参数是必需的";
        }
        
        String label = (String) args.get("label");
        String agent = (String) args.get("agent");
        
        if (manager == null) {
            return "错误: 子代理管理器未配置";
        }
        
        boolean async = Boolean.TRUE.equals(args.get("async"));
        
        if (async) {
            // 异步模式：后台运行，立即返回确认信息
            return manager.spawn(task, label, agent, originChannel, originChatId);
        }
        
        // 同步模式（默认）：阻塞等待子代理完成，返回实际结果
        // 如果有流式回调，使用流式版本输出子代理的执行过程
        return manager.spawnAndWaitStream(task, label, agent, streamCallback);
    }
}