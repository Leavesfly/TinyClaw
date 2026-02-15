package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.*;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.*;

import static io.leavesfly.tinyclaw.agent.AgentConstants.*;

/**
 * LLM 执行器 - 负责与 LLM 交互和工具调用迭代
 */
public class LLMExecutor {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent.llm");
    
    private final LLMProvider provider;
    private final ToolRegistry tools;
    private final SessionManager sessions;
    private final String model;
    private final int maxIterations;
    
    public LLMExecutor(LLMProvider provider, ToolRegistry tools, SessionManager sessions, 
                      String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.sessions = sessions;
        this.model = model;
        this.maxIterations = maxIterations;
    }
    
    /**
     * 运行 LLM 迭代循环
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
            
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                logger.info("LLM response without tool calls", Map.of(
                        "iteration", iteration,
                        "content_chars", finalContent != null ? finalContent.length() : 0
                ));
                break;
            }
            
            logToolCalls(response.getToolCalls(), iteration);
            addAssistantMessage(messages, response, sessionKey);
            executeToolCalls(messages, response.getToolCalls(), sessionKey, iteration);
        }
        
        return finalContent;
    }
    
    /**
     * 运行 LLM 流式迭代循环
     * 
     * @param messages 完整的对话历史
     * @param sessionKey 会话标识符
     * @param callback 流式内容回调
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
            
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                logger.info("LLM stream response without tool calls", Map.of(
                        "iteration", iteration,
                        "content_chars", finalContent != null ? finalContent.length() : 0
                ));
                break;
            }
            
            logToolCalls(response.getToolCalls(), iteration);
            addAssistantMessage(messages, response, sessionKey);
            executeToolCalls(messages, response.getToolCalls(), sessionKey, iteration);
        }
        
        return finalContent;
    }
    
    private LLMResponse callLLM(List<Message> messages) throws Exception {
        List<ToolDefinition> toolDefs = tools.getDefinitions();
        Map<String, Object> options = new HashMap<>();
        options.put("max_tokens", DEFAULT_MAX_TOKENS);
        options.put("temperature", DEFAULT_TEMPERATURE);
        return provider.chat(messages, toolDefs, model, options);
    }
    
    private LLMResponse callLLMStream(List<Message> messages, 
                                      LLMProvider.StreamCallback callback) throws Exception {
        List<ToolDefinition> toolDefs = tools.getDefinitions();
        Map<String, Object> options = new HashMap<>();
        options.put("max_tokens", DEFAULT_MAX_TOKENS);
        options.put("temperature", DEFAULT_TEMPERATURE);
        return provider.chatStream(messages, toolDefs, model, options, callback);
    }
    
    private void logToolCalls(List<ToolCall> toolCalls, int iteration) {
        List<String> toolNames = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            toolNames.add(toolCall.getName());
        }
        logger.info("LLM requested tool calls", Map.of(
                "tools", toolNames,
                "count", toolNames.size(),
                "iteration", iteration
        ));
    }
    
    private void addAssistantMessage(List<Message> messages, LLMResponse response, String sessionKey) {
        Message assistantMsg = new Message("assistant", response.getContent());
        List<ToolCall> processedToolCalls = new ArrayList<>();
        
        for (ToolCall toolCall : response.getToolCalls()) {
            ToolCall processedTc = new ToolCall(
                    toolCall.getId(), 
                    toolCall.getName(), 
                    toolCall.getArguments()
            );
            processedTc.setType(toolCall.getType());
            processedToolCalls.add(processedTc);
        }
        
        assistantMsg.setToolCalls(processedToolCalls);
        messages.add(assistantMsg);
        sessions.addFullMessage(sessionKey, assistantMsg);
    }
    
    private void executeToolCalls(List<Message> messages, List<ToolCall> toolCalls, 
                                  String sessionKey, int iteration) {
        for (ToolCall toolCall : toolCalls) {
            String argsPreview = StringUtils.truncate(
                    toolCall.getArguments() != null ? toolCall.getArguments().toString() : "", 200
            );
            logger.info("Tool call", Map.of(
                    "tool", toolCall.getName(),
                    "iteration", iteration,
                    "args_preview", argsPreview
            ));
            
            String result = executeToolCall(toolCall);
            Message toolResultMsg = Message.tool(toolCall.getId(), result);
            messages.add(toolResultMsg);
            sessions.addFullMessage(sessionKey, toolResultMsg);
        }
    }
    
    private String executeToolCall(ToolCall toolCall) {
        try {
            return tools.execute(toolCall.getName(), toolCall.getArguments());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
