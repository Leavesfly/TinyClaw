package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.agent.evolution.FeedbackManager;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.*;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.session.ToolCallRecord;
import io.leavesfly.tinyclaw.tools.*;
import io.leavesfly.tinyclaw.tools.TokenUsageStore;
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
    private static final int MAX_EMPTY_RESPONSE_RETRIES = 2;
    private static final String EMPTY_RESPONSE_FALLBACK = "抱歉，我暂时无法处理这个请求。请稍后重试，或尝试换一种方式描述你的需求。";
    
    private final LLMProvider provider;       // LLM 服务提供者
    private final ToolRegistry tools;         // 工具注册表
    private final SessionManager sessions;    // 会话管理器
    private final String model;               // 使用的模型名称
    private final String providerName;        // 提供商名称（如 dashscope、openai）
    private final int maxIterations;          // 最大迭代次数

    /** Token 消耗存储（可选，注入后自动记录每次 LLM 调用的 token 数据） */
    private volatile TokenUsageStore tokenUsageStore;

    /** 反馈管理器（可选，用于进化模块） */
    private volatile FeedbackManager feedbackManager;
    
    /** 当前会话标识（用于反馈记录） */
    private String currentSessionKey;
    
    /** 当前流式回调（用于传递给子代理和协同工具） */
    private volatile LLMProvider.EnhancedStreamCallback currentEnhancedCallback;
    
    public LLMExecutor(LLMProvider provider, ToolRegistry tools, SessionManager sessions,
                      String model, String providerName, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.sessions = sessions;
        this.model = model;
        this.providerName = providerName != null ? providerName : "unknown";
        this.maxIterations = maxIterations;
    }
    
    /**
     * 设置 Token 消耗存储（可选，注入后自动记录每次 LLM 调用的 token 数据）。
     *
     * @param tokenUsageStore Token 消耗存储实例
     */
    public void setTokenUsageStore(TokenUsageStore tokenUsageStore) {
        this.tokenUsageStore = tokenUsageStore;
    }

    /**
     * 设置反馈管理器（可选，用于进化模块）。
     *
     * @param feedbackManager 反馈管理器实例
     */
    public void setFeedbackManager(FeedbackManager feedbackManager) {
        this.feedbackManager = feedbackManager;
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
        this.currentSessionKey = sessionKey; // 设置当前会话用于反馈记录
        int iteration = 0;
        String finalContent = null;
        int emptyRetries = 0;
        int totalAttempts = 0;
        int maxTotalAttempts = maxIterations + MAX_EMPTY_RESPONSE_RETRIES;
        
        while (iteration < maxIterations) {
            totalAttempts++;
            if (totalAttempts > maxTotalAttempts) {
                logger.error("LLM exceeded max total attempts (iterations + empty retries)", Map.of(
                        "totalAttempts", totalAttempts,
                        "maxTotalAttempts", maxTotalAttempts,
                        "iterations", iteration,
                        "emptyRetries", emptyRetries
                ));
                finalContent = EMPTY_RESPONSE_FALLBACK;
                break;
            }
            
            iteration++;
            logger.debug("LLM iteration", Map.of("iteration", iteration, "max", maxIterations));
            
            LLMResponse response = callLLM(messages);
            
            // 没有工具调用，返回最终响应
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                
                // 空响应保护：如果内容为空且未超过重试次数，则重试
                if (isEmptyContent(finalContent) && emptyRetries < MAX_EMPTY_RESPONSE_RETRIES) {
                    emptyRetries++;
                    logger.warn("LLM returned empty response, retrying", Map.of(
                            "iteration", iteration,
                            "retry", emptyRetries,
                            "max_retries", MAX_EMPTY_RESPONSE_RETRIES
                    ));
                    // 空响应重试不计入工具迭代次数，但受 totalAttempts 保护
                    iteration--;
                    continue;
                }
                
                // 重试耗尽仍为空，使用兜底提示
                if (isEmptyContent(finalContent)) {
                    logger.warn("LLM returned empty response after retries, using fallback", Map.of(
                            "iteration", iteration,
                            "retries_exhausted", emptyRetries
                    ));
                    finalContent = EMPTY_RESPONSE_FALLBACK;
                }
                
                logger.info("LLM response without tool calls", Map.of(
                        "iteration", iteration,
                        "content_chars", finalContent.length()
                ));
                break;
            }
            
            // 有工具调用，重置空响应重试计数
            emptyRetries = 0;
            
            // 有工具调用，执行工具并继续迭代
            logToolCalls(response.getToolCalls(), iteration);
            addAssistantMessage(messages, response, sessionKey);
            executeToolCalls(messages, response.getToolCalls(), sessionKey, iteration);
            // 每轮工具调用后保存一次，防止多轮迭代中途崩溃丢失进度
            sessions.save(sessions.getOrCreate(sessionKey));
        }
        
        if (finalContent == null) {
            logger.warn("LLM iteration limit reached without final response", Map.of(
                    "maxIterations", maxIterations,
                    "totalAttempts", totalAttempts
            ));
            finalContent = EMPTY_RESPONSE_FALLBACK;
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
        this.currentSessionKey = sessionKey;
        // 将回调包装为增强版本，用于输出工具调用等过程信息
        this.currentEnhancedCallback = LLMProvider.EnhancedStreamCallback.wrap(callback);
            
        int iteration = 0;
        String finalContent = null;
        int emptyRetries = 0;
        int totalAttempts = 0;
        int maxTotalAttempts = maxIterations + MAX_EMPTY_RESPONSE_RETRIES;
            
        try {
            while (iteration < maxIterations) {
                totalAttempts++;
                if (totalAttempts > maxTotalAttempts) {
                    logger.error("LLM stream exceeded max total attempts (iterations + empty retries)", Map.of(
                            "totalAttempts", totalAttempts,
                            "maxTotalAttempts", maxTotalAttempts,
                            "iterations", iteration,
                            "emptyRetries", emptyRetries
                    ));
                    finalContent = EMPTY_RESPONSE_FALLBACK;
                    if (callback != null) {
                        callback.onChunk(finalContent);
                    }
                    break;
                }
                    
                iteration++;
                logger.debug("LLM stream iteration", Map.of("iteration", iteration, "max", maxIterations));
                    
                LLMResponse response = callLLMStream(messages, callback);
                    
                // 没有工具调用，返回最终响应
                if (!response.hasToolCalls()) {
                    finalContent = response.getContent();
                        
                    // 空响应保护：如果内容为空且未超过重试次数，则重试
                    if (isEmptyContent(finalContent) && emptyRetries < MAX_EMPTY_RESPONSE_RETRIES) {
                        emptyRetries++;
                        logger.warn("LLM stream returned empty response, retrying", Map.of(
                                "iteration", iteration,
                                "retry", emptyRetries,
                                "max_retries", MAX_EMPTY_RESPONSE_RETRIES
                        ));
                        iteration--;
                        continue;
                    }
                        
                    // 重试耗尽仍为空，使用兆底提示并通过回调通知用户
                    if (isEmptyContent(finalContent)) {
                        logger.warn("LLM stream returned empty response after retries, using fallback", Map.of(
                                "iteration", iteration,
                                "retries_exhausted", emptyRetries
                        ));
                        finalContent = EMPTY_RESPONSE_FALLBACK;
                        if (callback != null) {
                            callback.onChunk(finalContent);
                        }
                    }
                        
                    logger.info("LLM stream response without tool calls", Map.of(
                            "iteration", iteration,
                            "content_chars", finalContent.length()
                    ));
                    break;
                }
                    
                // 有工具调用，重置空响应重试计数
                emptyRetries = 0;
                    
                // 有工具调用，执行工具并继续迭代（使用流式版本输出过程信息）
                logToolCalls(response.getToolCalls(), iteration);
                addAssistantMessage(messages, response, sessionKey);
                executeToolCallsWithStream(messages, response.getToolCalls(), sessionKey, iteration);
                // 每轮工具调用后保存一次，防止多轮迭代中途崩溃丢失进度
                sessions.save(sessions.getOrCreate(sessionKey));
            }
                
            if (finalContent == null) {
                logger.warn("LLM stream iteration limit reached without final response", Map.of(
                        "maxIterations", maxIterations,
                        "totalAttempts", totalAttempts
                ));
                finalContent = EMPTY_RESPONSE_FALLBACK;
                if (callback != null) {
                    callback.onChunk(finalContent);
                }
            }
        } finally {
            // 清理当前流式回调
            this.currentEnhancedCallback = null;
        }
            
        return finalContent;
    }
        
    /**
     * 获取当前的增强流式回调（用于子代理和协同工具）。
     * 
     * @return 当前的增强流式回调，如果不在流式模式则返回 null
     */
    public LLMProvider.EnhancedStreamCallback getEnhancedCallback() {
        return currentEnhancedCallback;
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
        LLMResponse response = provider.chat(messages, toolDefs, model, options);
        recordTokenUsage(response);
        return response;
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
        LLMResponse response = provider.chatStream(messages, toolDefs, model, options, callback);
        recordTokenUsage(response);
        return response;
    }

    /**
     * 将本次 LLM 调用的 token 消耗记录到 TokenUsageStore。
     * 仅在 tokenUsageStore 已注入且响应包含 usage 信息时执行。
     *
     * @param response LLM 响应
     */
    private void recordTokenUsage(LLMResponse response) {
        if (tokenUsageStore == null || response == null || response.getUsage() == null) {
            return;
        }
        LLMResponse.UsageInfo usage = response.getUsage();
        tokenUsageStore.record(providerName, model,
                usage.getPromptTokens(), usage.getCompletionTokens());
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
            String result = executeToolCall(toolCall, sessionKey);
            Message toolResultMsg = Message.tool(toolCall.getId(), result);
            messages.add(toolResultMsg);
            sessions.addFullMessage(sessionKey, toolResultMsg);
        }
    }
    
    /**
     * 执行所有工具调用（流式版本）。
     * 
     * 与普通版本类似，但通过增强回调输出工具调用的过程信息。
     * 
     * @param messages 对话消息列表
     * @param toolCalls 工具调用列表
     * @param sessionKey 会话标识符
     * @param iteration 当前迭代次数
     */
    private void executeToolCallsWithStream(List<Message> messages, List<ToolCall> toolCalls, 
                                            String sessionKey, int iteration) {
        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.getName();
            Map<String, Object> args = toolCall.getArguments();
            
            // 记录工具调用日志
            String argsPreview = StringUtils.truncate(
                    args != null ? args.toString() : "", 
                    200
            );
            logger.info("Tool call", Map.of(
                    "tool", toolName,
                    "iteration", iteration,
                    "args_preview", argsPreview
            ));
            
            // 通过增强回调输出工具调用开始事件
            if (currentEnhancedCallback != null) {
                currentEnhancedCallback.onEvent(StreamEvent.toolStart(toolName, args));
            }
            
            // 执行工具
            String result = executeToolCallWithStream(toolCall, sessionKey);
            boolean success = result != null && !result.startsWith("Error:");
            
            // 通过增强回调输出工具调用结束事件
            if (currentEnhancedCallback != null) {
                currentEnhancedCallback.onEvent(StreamEvent.toolEnd(toolName, result, success));
            }

            // 持久化工具调用记录（摘要信息），用于历史会话回放时重建工具调用卡片
            // 用 session 里实际存储的消息数量 - 1 作为触发本次工具调用的 assistant 消息的绝对位置索引。
            // 注意：不能用 messages.size()-1，因为 messages 是发给 LLM 的上下文（可能被截断），
            // 与 session 里存储的完整历史长度不同。
            String argsSummary = ToolCallRecord.truncate(args != null ? args.toString() : "", 200);
            String resultSummary = ToolCallRecord.truncate(result, 200);
            int messageIndex = sessions.getHistory(sessionKey).size() - 1;
            ToolCallRecord record = new ToolCallRecord(toolName, argsSummary, resultSummary, success, messageIndex);
            sessions.addToolCallRecord(sessionKey, record);

            // 保存结果
            Message toolResultMsg = Message.tool(toolCall.getId(), result);
            messages.add(toolResultMsg);
            sessions.addFullMessage(sessionKey, toolResultMsg);
        }
    }
    
    /**
     * 执行单个工具调用。
     * 
     * @param toolCall 工具调用信息
     * @param sessionKey 会话标识符
     * @return 工具执行结果，如果执行失败返回错误信息
     */
    private String executeToolCall(ToolCall toolCall, String sessionKey) {
        String toolName = toolCall.getName();
        boolean success = false;
        String result;
        
        try {
            // 设置工具上下文（从 sessionKey 解析 channel 和 chatId）
            setToolContext(toolName, sessionKey, null);
            result = tools.execute(toolName, toolCall.getArguments());
            // 工具执行成功（没有以 "Error:" 开头）
            success = result != null && !result.startsWith("Error:");
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
        }
        
        // 记录工具执行结果到反馈管理器
        if (feedbackManager != null && currentSessionKey != null) {
            feedbackManager.recordToolResult(currentSessionKey, toolName, success);
        }
        
        return result;
    }
    
    /**
     * 执行单个工具调用（流式版本）。
     * 
     * 与普通版本类似，但会将流式回调传递给支持流式输出的工具（如 SpawnTool、CollaborateTool）。
     * 
     * @param toolCall 工具调用信息
     * @param sessionKey 会话标识符
     * @return 工具执行结果，如果执行失败返回错误信息
     */
    private String executeToolCallWithStream(ToolCall toolCall, String sessionKey) {
        String toolName = toolCall.getName();
        boolean success = false;
        String result;
        
        try {
            // 设置工具上下文，并传递流式回调
            setToolContext(toolName, sessionKey, currentEnhancedCallback);
            result = tools.execute(toolName, toolCall.getArguments());
            // 工具执行成功（没有以 "Error:" 开头）
            success = result != null && !result.startsWith("Error:");
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
        }
        
        // 记录工具执行结果到反馈管理器
        if (feedbackManager != null && currentSessionKey != null) {
            feedbackManager.recordToolResult(currentSessionKey, toolName, success);
        }
        
        return result;
    }
    
    /**
     * 设置工具的上下文信息（channel、chatId 和流式回调）。
     * 
     * 对于需要上下文感知的工具（如 SpawnTool、CollaborateTool、MessageTool、CronTool），
     * 在执行前设置当前的通道、聊天 ID 和流式回调。
     * 
     * @param toolName 工具名称
     * @param sessionKey 会话标识符（格式：channel:chatId）
     * @param streamCallback 流式回调（用于子代理和协同工具，可为 null）
     */
    private void setToolContext(String toolName, String sessionKey, 
                               LLMProvider.EnhancedStreamCallback streamCallback) {
        if (sessionKey == null || !sessionKey.contains(":")) {
            return;
        }
        
        String[] parts = sessionKey.split(":", 2);
        String channel = parts[0];
        String chatId = parts[1];
        
        // 获取工具实例并设置上下文
        tools.get(toolName).ifPresent(tool -> {
            if (tool instanceof SpawnTool spawnTool) {
                spawnTool.setContext(channel, chatId);
                // 传递流式回调给 SpawnTool
                spawnTool.setStreamCallback(streamCallback);
            } else if (tool instanceof CollaborateTool collaborateTool) {
                // 传递流式回调给 CollaborateTool
                collaborateTool.setStreamCallback(streamCallback);
            } else if (tool instanceof MessageTool messageTool) {
                messageTool.setContext(channel, chatId);
            } else if (tool instanceof CronTool cronTool) {
                cronTool.setContext(channel, chatId);
            }
        });
    }
    
    /**
     * 判断 LLM 响应内容是否为空。
     * 
     * @param content 响应内容
     * @return 内容为 null 或空白字符串时返回 true
     */
    private boolean isEmptyContent(String content) {
        return content == null || content.trim().isEmpty();
    }
}
