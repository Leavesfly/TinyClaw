package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.*;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.*;

import static io.leavesfly.tinyclaw.agent.AgentConstants.*;

/**
 * LLM 执行器，负责与 LLM 交互和工具调用迭代。
 * 
 * 核心功能：
 * - 管理 LLM 的请求响应循环
 * - 处理工具调用的执行和结果反馈
 * - 支持流式和非流式两种模式
 * - 控制迭代次数避免无限循环
 */
public class LLMExecutor {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent.llm");
    
    private final LLMProvider provider;       // LLM 服务提供者
    private final ToolRegistry tools;         // 工具注册表
    private final SessionManager sessions;    // 会话管理器
    private final String model;               // 使用的模型名称
    private final int maxIterations;          // 最大迭代次数
    
    public LLMExecutor(LLMProvider provider, ToolRegistry tools, SessionManager sessions, 
                      String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.sessions = sessions;
        this.model = model;
        this.maxIterations = maxIterations;
    }
    
    /**
     * 执行 LLM 迭代循环。
     * 
     * 处理流程：
     * 1. 调用 LLM 获取响应
     * 2. 如果没有工具调用请求，返回文本响应
     * 3. 如果有工具调用，执行工具并将结果追加到消息历史
     * 4. 重复上述流程直到达到最大迭代次数或获得最终响应
     * 
     * @param messages 完整的对话历史
     * @param sessionKey 会话标识符
     * @return LLM 的最终回答内容
     * @throws Exception 调用 LLM 或执行工具时的异常
     */
    public String execute(List<Message> messages, String sessionKey) throws Exception {
        int iteration = 0;
        String finalContent = null;
        
        while (iteration < maxIterations) {
            iteration++;
            logger.debug("LLM iteration", Map.of("iteration", iteration, "max", maxIterations));
            
            LLMResponse response = callLLM(messages);
            
            // 没有工具调用，返回最终响应
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                logger.info("LLM response without tool calls", Map.of(
                        "iteration", iteration,
                        "content_chars", finalContent != null ? finalContent.length() : 0
                ));
                break;
            }
            
            // 有工具调用，执行工具并继续迭代
            logToolCalls(response.getToolCalls(), iteration);
            addAssistantMessage(messages, response, sessionKey);
            executeToolCalls(messages, response.getToolCalls(), sessionKey, iteration);
        }
        
        return finalContent;
    }
    
    /**
     * 执行 LLM 流式迭代循环。
     * 
     * 与普通迭代循环相同，但支持流式输出响应内容。
     * 适用于需要实时展示 LLM 响应的场景。
     * 
     * @param messages 完整的对话历史
     * @param sessionKey 会话标识符
     * @param callback 流式内容回调函数
     * @return LLM 的最终回答内容
     * @throws Exception 调用 LLM 或执行工具时的异常
     */
    public String executeStream(List<Message> messages, String sessionKey, 
                               LLMProvider.StreamCallback callback) throws Exception {
        int iteration = 0;
        String finalContent = null;
        
        while (iteration < maxIterations) {
            iteration++;
            logger.debug("LLM stream iteration", Map.of("iteration", iteration, "max", maxIterations));
            
            LLMResponse response = callLLMStream(messages, callback);
            
            // 没有工具调用，返回最终响应
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                logger.info("LLM stream response without tool calls", Map.of(
                        "iteration", iteration,
                        "content_chars", finalContent != null ? finalContent.length() : 0
                ));
                break;
            }
            
            // 有工具调用，执行工具并继续迭代
            logToolCalls(response.getToolCalls(), iteration);
            addAssistantMessage(messages, response, sessionKey);
            executeToolCalls(messages, response.getToolCalls(), sessionKey, iteration);
        }
        
        return finalContent;
    }
    
    /**
     * 调用 LLM 进行对话。
     * 
     * @param messages 对话消息历史
     * @return LLM 响应
     * @throws Exception 调用失败时抛出异常
     */
    private LLMResponse callLLM(List<Message> messages) throws Exception {
        List<ToolDefinition> toolDefs = tools.getDefinitions();
        Map<String, Object> options = buildLLMOptions();
        return provider.chat(messages, toolDefs, model, options);
    }
    
    /**
     * 调用 LLM 进行流式对话。
     * 
     * @param messages 对话消息历史
     * @param callback 流式内容回调函数
     * @return LLM 响应
     * @throws Exception 调用失败时抛出异常
     */
    private LLMResponse callLLMStream(List<Message> messages, 
                                      LLMProvider.StreamCallback callback) throws Exception {
        List<ToolDefinition> toolDefs = tools.getDefinitions();
        Map<String, Object> options = buildLLMOptions();
        return provider.chatStream(messages, toolDefs, model, options, callback);
    }
    
    /**
     * 构建 LLM 调用选项。
     * 
     * @return 包含 max_tokens 和 temperature 的选项映射
     */
    private Map<String, Object> buildLLMOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("max_tokens", DEFAULT_MAX_TOKENS);
        options.put("temperature", DEFAULT_TEMPERATURE);
        return options;
    }
    
    /**
     * 记录工具调用信息。
     * 
     * @param toolCalls 工具调用列表
     * @param iteration 当前迭代次数
     */
    private void logToolCalls(List<ToolCall> toolCalls, int iteration) {
        List<String> toolNames = toolCalls.stream()
                .map(ToolCall::getName)
                .toList();
        
        logger.info("LLM requested tool calls", Map.of(
                "tools", toolNames,
                "count", toolNames.size(),
                "iteration", iteration
        ));
    }
    
    /**
     * 添加助手消息到对话历史。
     * 
     * 将 LLM 的响应（包括工具调用信息）添加到消息列表和会话存储中。
     * 
     * @param messages 对话消息列表
     * @param response LLM 响应
     * @param sessionKey 会话标识符
     */
    private void addAssistantMessage(List<Message> messages, LLMResponse response, String sessionKey) {
        Message assistantMsg = Message.assistant(response.getContent());
        
        // 复制工具调用信息
        List<ToolCall> processedToolCalls = response.getToolCalls().stream()
                .map(tc -> {
                    ToolCall processed = new ToolCall(tc.getId(), tc.getName(), tc.getArguments());
                    processed.setType(tc.getType());
                    return processed;
                })
                .toList();
        
        assistantMsg.setToolCalls(processedToolCalls);
        messages.add(assistantMsg);
        sessions.addFullMessage(sessionKey, assistantMsg);
    }
    
    /**
     * 执行所有工具调用。
     * 
     * 遍历工具调用列表，依次执行每个工具并将结果添加到对话历史中。
     * 
     * @param messages 对话消息列表
     * @param toolCalls 工具调用列表
     * @param sessionKey 会话标识符
     * @param iteration 当前迭代次数
     */
    private void executeToolCalls(List<Message> messages, List<ToolCall> toolCalls, 
                                  String sessionKey, int iteration) {
        for (ToolCall toolCall : toolCalls) {
            // 记录工具调用日志
            String argsPreview = StringUtils.truncate(
                    toolCall.getArguments() != null ? toolCall.getArguments().toString() : "", 
                    200
            );
            logger.info("Tool call", Map.of(
                    "tool", toolCall.getName(),
                    "iteration", iteration,
                    "args_preview", argsPreview
            ));
            
            // 执行工具并保存结果
            String result = executeToolCall(toolCall);
            Message toolResultMsg = Message.tool(toolCall.getId(), result);
            messages.add(toolResultMsg);
            sessions.addFullMessage(sessionKey, toolResultMsg);
        }
    }
    
    /**
     * 执行单个工具调用。
     * 
     * @param toolCall 工具调用信息
     * @return 工具执行结果，如果执行失败返回错误信息
     */
    private String executeToolCall(ToolCall toolCall) {
        try {
            return tools.execute(toolCall.getName(), toolCall.getArguments());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
