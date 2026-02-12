package io.leavesfly.tinyclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import io.leavesfly.tinyclaw.providers.*;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.Tool;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 循环 - 核心 Agent 执行引擎
 * 
 * <p>AgentLoop 是 TinyClaw 的核心执行引擎，负责处理消息、与大语言模型交互、执行工具调用以及管理会话状态。
 * 它提供了一套完整的对话处理流程，包括消息路由、LLM 迭代、工具执行和会话摘要等功能。</p>
 * 
 * <h2>核心功能：</h2>
 * <ul>
 *   <li>消息处理：处理来自不同通道（CLI、Telegram、Discord 等）的入站消息</li>
 *   <li>LLM 交互：支持多轮对话和工具调用的迭代处理</li>
 *   <li>工具执行：注册和执行各种工具，扩展 Agent 能力</li>
 *   <li>会话管理：维护对话历史和会话状态</li>
 *   <li>自动摘要：当对话过长时自动生成摘要以节省 token</li>
 *   <li>上下文构建：动态构建包含历史记录和技能的对话上下文</li>
 * </ul>
 * 
 * <h2>工作流程：</h2>
 * <ol>
 *   <li>从消息总线接收入站消息</li>
 *   <li>构建包含历史记录的完整对话上下文</li>
 *   <li>调用 LLM 进行推理</li>
 *   <li>如果 LLM 请求工具调用，则执行相应工具</li>
 *   <li>将工具执行结果返回给 LLM 进行下一轮迭代</li>
 *   <li>保存对话历史并触发摘要（如需要）</li>
 * </ol>
 * 
 * @author TinyClaw Team
 * @version 0.1.0
 */
public class AgentLoop {
    
    /** Agent 专用日志记录器 */
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent");
    
    /** JSON 对象映射器，用于序列化和反序列化 */
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /** LLM 默认最大 token 数 */
    private static final int DEFAULT_MAX_TOKENS = 8192;
    
    /** LLM 默认温度参数 */
    private static final double DEFAULT_TEMPERATURE = 0.7;
    
    /** 触发摘要的消息数量阈值 */
    private static final int SUMMARIZE_MESSAGE_THRESHOLD = 20;
    
    /** 触发摘要的 token 百分比阈值（上下文窗口的 75%） */
    private static final int SUMMARIZE_TOKEN_PERCENTAGE = 75;
    
    /** 摘要时保留的最近消息数量 */
    private static final int RECENT_MESSAGES_TO_KEEP = 4;
    
    /** 批量摘要的消息数量阈值 */
    private static final int BATCH_SUMMARIZE_THRESHOLD = 10;
    
    /** 摘要生成的最大 token 数 */
    private static final int SUMMARY_MAX_TOKENS = 1024;
    
    /** 摘要生成的温度参数（较低以确保准确性） */
    private static final double SUMMARY_TEMPERATURE = 0.3;
    
    /** 消息总线，用于接收和发送消息 */
    private final MessageBus bus;
    
    /** LLM 提供者，负责与大语言模型交互 */
    private final LLMProvider provider;
    
    /** 工作空间路径 */
    private final String workspace;
    
    /** 使用的 LLM 模型名称 */
    private final String model;
    
    /** 上下文窗口大小（token 数） */
    private final int contextWindow;
    
    /** 最大工具调用迭代次数 */
    private final int maxIterations;
    
    /** 会话管理器，负责维护对话历史和会话状态 */
    private final SessionManager sessions;
    
    /** 上下文构建器，负责构建对话上下文 */
    private final ContextBuilder contextBuilder;
    
    /** 工具注册表，管理所有可用工具 */
    private final ToolRegistry tools;
    
    /** 运行状态标志 */
    private volatile boolean running = false;
    
    /** 正在进行摘要的会话集合，用于避免重复摘要 */
    private final Set<String> summarizing = ConcurrentHashMap.newKeySet();
    
    /**
     * 构造 AgentLoop 实例
     * 
     * <p>初始化 Agent 的所有核心组件，包括消息总线、LLM 提供者、工具注册表、会话管理器和上下文构建器。
     * 该构造函数会创建必要的工作空间目录，并记录初始化信息。</p>
     * 
     * @param config 配置对象，包含工作空间路径、模型配置等
     * @param bus 消息总线，用于接收和发送消息
     * @param provider LLM 提供者，负责与大语言模型交互
     */
    public AgentLoop(Config config, MessageBus bus, LLMProvider provider) {
        this.bus = bus;
        this.provider = provider;
        this.workspace = config.getWorkspacePath();
        this.model = config.getAgents().getDefaults().getModel();
        this.contextWindow = config.getAgents().getDefaults().getMaxTokens();
        this.maxIterations = config.getAgents().getDefaults().getMaxToolIterations();
        
        // 确保工作空间目录存在
        try {
            Files.createDirectories(Paths.get(workspace));
        } catch (IOException e) {
            logger.warn("Failed to create workspace directory: " + e.getMessage());
        }
        
        // 初始化核心组件：工具注册表、会话管理器、上下文构建器
        this.tools = new ToolRegistry();
        this.sessions = new SessionManager(Paths.get(workspace, "sessions").toString());
        this.contextBuilder = new ContextBuilder(workspace);
        this.contextBuilder.setTools(this.tools);
        
        logger.info("Agent initialized", Map.of(
                "model", model,
                "workspace", workspace,
                "max_iterations", maxIterations
        ));
    }
    
    /**
     * 运行Agent循环（阻塞式）
     * 
     * 这是Agent的主要执行循环，会持续监听并处理消息总线上的入站消息。
     * 循环会在running标志被设置为false时优雅退出。
     * 
     * 处理流程：
     * 1. 从消息总线消费入站消息
     * 2. 调用processMessage处理消息
     * 3. 处理过程中发生的任何异常都会被捕获并记录
     * 4. 循环持续运行直到收到停止信号
     */
    public void run() {
        running = true;
        logger.info("Agent loop started");
        
        while (running) {
            try {
                InboundMessage msg = bus.consumeInbound();
                processMessage(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing message", Map.of("error", e.getMessage()));
            }
        }
        
        logger.info("Agent loop stopped");
    }
    
    /**
     * 停止Agent循环
     * 
     * 设置运行标志为false，使主循环能够优雅退出。
     * 不会强制中断正在进行的操作，而是等待当前循环迭代完成。
     */
    public void stop() {
        running = false;
    }
    
    /**
     * 注册一个工具
     * 
     * 将新的工具添加到工具注册表中，并更新上下文构建器中的工具引用。
     * 这允许在运行时动态扩展Agent的功能。
     * 
     * @param tool 要注册的工具实例
     */
    public void registerTool(Tool tool) {
        tools.register(tool);
        contextBuilder.setTools(tools);
    }
    
    /**
     * 直接处理消息（用于CLI模式）
     * 
     * 为命令行界面提供直接的消息处理能力，绕过正常的通道路由机制。
     * 主要用于测试和直接交互场景。
     * 
     * @param content 消息内容
     * @param sessionKey 会话标识符
     * @return 处理结果
     * @throws Exception 处理过程中可能出现的异常
     */
    public String processDirect(String content, String sessionKey) throws Exception {
        InboundMessage msg = new InboundMessage("cli", "user", "direct", content);
        msg.setSessionKey(sessionKey);
        return processMessage(msg);
    }
    
    /**
     * 处理带通道信息的消息
     * 
     * 处理来自特定通道的消息，保留通道和聊天ID信息以便正确路由响应。
     * 这是处理来自Telegram、Discord等外部通道消息的标准方法。
     * 
     * @param content 消息内容
     * @param sessionKey 会话标识符
     * @param channel 通道名称（如"telegram"、"discord"）
     * @param chatId 聊天ID
     * @return 处理结果
     * @throws Exception 处理过程中可能出现的异常
     */
    public String processDirectWithChannel(String content, String sessionKey, String channel, String chatId) throws Exception {
        InboundMessage msg = new InboundMessage(channel, "cron", chatId, content);
        msg.setSessionKey(sessionKey);
        return processMessage(msg);
    }
    
    /**
     * 处理入站消息
     * 
     * 这是消息处理的核心方法，负责完整的消息处理生命周期：
     * 1. 记录消息日志和预览信息
     * 2. 区分系统消息和用户消息并分别处理
     * 3. 构建包含历史记录的完整对话上下文
     * 4. 执行LLM迭代处理
     * 5. 保存对话历史
     * 6. 必要时触发会话摘要
     * 
     * @param msg 入站消息对象
     * @return 处理结果字符串
     * @throws Exception 处理过程中可能出现的异常
     */
    private String processMessage(InboundMessage msg) throws Exception {
        String preview = StringUtils.truncate(msg.getContent(), 80);
        logger.info("Processing message", Map.of(
                "channel", msg.getChannel(),
                "chat_id", msg.getChatId(),
                "sender_id", msg.getSenderId(),
                "session_key", msg.getSessionKey(),
                "preview", preview
        ));
        
        // 处理 system messages
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }
        
        // 构建 messages
        String sessionKey = msg.getSessionKey();
        List<Message> history = sessions.getHistory(sessionKey);
        String summary = sessions.getSummary(sessionKey);
        
        List<Message> messages = contextBuilder.buildMessages(
                history, summary, msg.getContent(), msg.getChannel(), msg.getChatId()
        );
        
        // 保存 user message
        sessions.addMessage(sessionKey, "user", msg.getContent());
        
        // 运行 LLM iteration
        String response = runLLMIteration(messages, sessionKey, msg.getChannel(), msg.getChatId());
        
        // 处理 empty response
        if (StringUtils.isBlank(response)) {
            response = "I've completed processing but have no response to give.";
        }
        
        // 保存 assistant response
        sessions.addMessage(sessionKey, "assistant", response);
        sessions.save(sessions.getOrCreate(sessionKey));
        
        // Trigger summarization if needed
        maybeSummarize(sessionKey);
        
        return response;
    }
    
    /**
     * 处理系统消息
     * 
     * 专门处理由系统内部生成的消息，如定时任务触发的消息。
     * 系统消息通常包含特殊的聊天ID格式"channel:chat_id"来指定原始来源。
     * 处理后的响应会通过消息总线发送回原始通道。
     * 
     * @param msg 系统消息对象
     * @return 处理结果
     * @throws Exception 处理过程中可能出现的异常
     */
    private String processSystemMessage(InboundMessage msg) throws Exception {
        logger.info("Processing system message", Map.of(
                "sender_id", msg.getSenderId(),
                "chat_id", msg.getChatId()
        ));
        
        // Parse origin from chat_id (format: "channel:chat_id")
        String originChannel = "cli";
        String originChatId = msg.getChatId();
        String[] parts = msg.getChatId().split(":", 2);
        if (parts.length == 2) {
            originChannel = parts[0];
            originChatId = parts[1];
        }
        
        String sessionKey = originChannel + ":" + originChatId;
        String userMessage = "[System: " + msg.getSenderId() + "] " + msg.getContent();
        
        // 构建 messages
        List<Message> history = sessions.getHistory(sessionKey);
        String summary = sessions.getSummary(sessionKey);
        List<Message> messages = contextBuilder.buildMessages(
                history, summary, userMessage, originChannel, originChatId
        );
        
        sessions.addMessage(sessionKey, "user", userMessage);
        
        String response = runLLMIteration(messages, sessionKey, originChannel, originChatId);
        
        if (StringUtils.isBlank(response)) {
            response = "Background task completed.";
        }
        
        sessions.addMessage(sessionKey, "assistant", response);
        
        // 发送 response via bus
        bus.publishOutbound(new OutboundMessage(originChannel, originChatId, response));
        
        return response;
    }
    
    /**
     * 运行LLM迭代循环
     * 
     * 这是与大语言模型交互的核心逻辑，支持工具调用的多轮迭代：
     * 1. 构建工具定义列表供LLM使用
     * 2. 调用LLM进行推理
     * 3. 如果LLM请求工具调用，则执行相应工具
     * 4. 将工具执行结果返回给LLM
     * 5. 重复上述过程直到达到最大迭代次数或LLM产生最终回答
     * 
     * @param messages 完整的对话历史（包括系统提示）
     * @param sessionKey 会话标识符
     * @param channel 通道名称
     * @param chatId 聊天ID
     * @return LLM的最终回答内容
     * @throws Exception 调用LLM或执行工具时可能出现的异常
     */
    private String runLLMIteration(List<Message> messages, String sessionKey, String channel, String chatId) throws Exception {
        int iteration = 0;
        String finalContent = null;
        
        while (iteration < maxIterations) {
            iteration++;
            
            logger.debug("LLM iteration", Map.of("iteration", iteration, "max", maxIterations));
            
            // 构建 tool definitions
            List<ToolDefinition> toolDefs = tools.getDefinitions();
            
            // Prepare options
            Map<String, Object> options = new HashMap<>();
            options.put("max_tokens", DEFAULT_MAX_TOKENS);
            options.put("temperature", DEFAULT_TEMPERATURE);
            
            // Call LLM
            LLMResponse response = provider.chat(messages, toolDefs, model, options);
            
            // 检查 if no tool calls
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                logger.info("LLM response without tool calls", Map.of(
                        "iteration", iteration,
                        "content_chars", finalContent != null ? finalContent.length() : 0
                ));
                break;
            }
            
            // Log tool calls
            List<String> toolNames = new ArrayList<>();
            for (ToolCall toolCall : response.getToolCalls()) {
                toolNames.add(toolCall.getName());
            }
            logger.info("LLM requested tool calls", Map.of(
                    "tools", toolNames,
                    "count", toolNames.size(),
                    "iteration", iteration
            ));
            
            // 构建 assistant message with tool calls
            Message assistantMsg = new Message("assistant", response.getContent());
            List<ToolCall> processedToolCalls = new ArrayList<>();
            for (ToolCall toolCall : response.getToolCalls()) {
                ToolCall processedTc = new ToolCall(toolCall.getId(), toolCall.getName(), toolCall.getArguments());
                processedTc.setType(toolCall.getType());
                processedToolCalls.add(processedTc);
            }
            assistantMsg.setToolCalls(processedToolCalls);
            messages.add(assistantMsg);
            
            // 保存 assistant message
            sessions.addFullMessage(sessionKey, assistantMsg);
            
            // 执行 tool calls
            for (ToolCall toolCall : response.getToolCalls()) {
                String argsPreview = StringUtils.truncate(
                        toolCall.getArguments() != null ? toolCall.getArguments().toString() : "", 200);
                logger.info("Tool call", Map.of(
                        "tool", toolCall.getName(),
                        "iteration", iteration,
                        "args_preview", argsPreview
                ));
                
                String result;
                try {
                    result = tools.execute(toolCall.getName(), toolCall.getArguments());
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                
                // Add tool result message
                Message toolResultMsg = Message.tool(toolCall.getId(), result);
                messages.add(toolResultMsg);
                sessions.addFullMessage(sessionKey, toolResultMsg);
            }
        }
        
        return finalContent;
    }
    
    /**
     * 根据需要触发会话摘要
     * 
     * 监控会话长度，当对话历史过长时自动触发摘要过程以节省token：
     * - 当消息数量超过20条时触发
     * - 当估算的token数超过上下文窗口的75%时触发
     * - 摘要过程在后台线程中异步执行，不影响主线程性能
     * - 使用ConcurrentHashMap避免重复摘要同一会话
     * 
     * @param sessionKey 需要检查的会话标识符
     */
    private void maybeSummarize(String sessionKey) {
        List<Message> history = sessions.getHistory(sessionKey);
        int tokenEstimate = estimateTokens(history);
        int threshold = contextWindow * SUMMARIZE_TOKEN_PERCENTAGE / 100;
        
        if (history.size() > SUMMARIZE_MESSAGE_THRESHOLD || tokenEstimate > threshold) {
            if (summarizing.add(sessionKey)) {
                // Summarize in background
                Thread thread = new Thread(() -> {
                    try {
                        summarizeSession(sessionKey);
                    } finally {
                        summarizing.remove(sessionKey);
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
        }
    }
    
    /**
     * 摘要一个会话
     * 
     * 执行会话摘要的具体实现：
     * 1. 保留最近4条消息不变
     * 2. 对历史消息进行过滤（只保留用户和助手消息，排除过长消息）
     * 3. 当消息超过10条时，分批摘要后合并
     * 4. 构造摘要提示词，要求保留核心上下文和关键点
     * 5. 调用LLM生成摘要
     * 6. 更新会话的摘要信息并截断历史记录
     * 
     * @param sessionKey 需要摘要的会话标识符
     */
    private void summarizeSession(String sessionKey) {
        List<Message> history = sessions.getHistory(sessionKey);
        String existingSummary = sessions.getSummary(sessionKey);
        
        if (history.size() <= RECENT_MESSAGES_TO_KEEP) {
            return;
        }
        
        // Keep last 4 messages for continuity
        List<Message> toSummarize = new ArrayList<>(history.subList(0, history.size() - RECENT_MESSAGES_TO_KEEP));
        
        // Filter out non-user/assistant messages and oversized messages
        List<Message> validMessages = new ArrayList<>();
        int maxMessageTokens = contextWindow / 2;
        boolean omitted = false;
        
        for (Message m : toSummarize) {
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) {
                continue;
            }
            int msgTokens = StringUtils.estimateTokens(m.getContent());
            if (msgTokens > maxMessageTokens) {
                omitted = true;
                continue;
            }
            validMessages.add(m);
        }
        
        if (validMessages.isEmpty()) {
            return;
        }
        
        String finalSummary;
        
        // Multi-Part Summarization: Split into two parts if history is significant
        if (validMessages.size() > BATCH_SUMMARIZE_THRESHOLD) {
            int mid = validMessages.size() / 2;
            List<Message> part1 = validMessages.subList(0, mid);
            List<Message> part2 = validMessages.subList(mid, validMessages.size());
            
            String s1 = summarizeBatch(part1, existingSummary);
            String s2 = summarizeBatch(part2, null);
            
            // Merge them
            if (s1 != null && s2 != null) {
                String mergePrompt = String.format(
                        "Merge these two conversation summaries into one cohesive summary:\n\n1: %s\n\n2: %s",
                        s1, s2
                );
                try {
                    List<Message> mergeMessages = List.of(Message.user(mergePrompt));
                    Map<String, Object> options = new HashMap<>();
                    options.put("max_tokens", SUMMARY_MAX_TOKENS);
                    options.put("temperature", SUMMARY_TEMPERATURE);
                    
                    LLMResponse response = provider.chat(mergeMessages, null, model, options);
                    finalSummary = response.getContent();
                } catch (Exception e) {
                    // Fallback: just concatenate
                    finalSummary = s1 + " " + s2;
                }
            } else {
                finalSummary = s1 != null ? s1 : s2;
            }
        } else {
            finalSummary = summarizeBatch(validMessages, existingSummary);
        }
        
        // Add note if some messages were omitted
        if (omitted && finalSummary != null) {
            finalSummary += "\n[Note: Some oversized messages were omitted from this summary for efficiency.]";
        }
        
        if (StringUtils.isNotBlank(finalSummary)) {
            sessions.setSummary(sessionKey, finalSummary);
            sessions.truncateHistory(sessionKey, RECENT_MESSAGES_TO_KEEP);
            sessions.save(sessions.getOrCreate(sessionKey));
            logger.info("Session summarized", Map.of(
                    "session_key", sessionKey,
                    "original_messages", toSummarize.size(),
                    "valid_messages", validMessages.size()
            ));
        }
    }
    
    /**
     * 批量摘要一组消息
     * 
     * 对指定批次的消息生成摘要，可选地结合已有摘要上下文。
     * 
     * @param batch 要摘要的消息批次
     * @param existingSummary 已有的摘要（可选，用于增量摘要）
     * @return 生成的摘要内容，失败时返回null
     */
    private String summarizeBatch(List<Message> batch, String existingSummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Provide a concise summary of this conversation segment, preserving core context and key points.\n");
        
        if (StringUtils.isNotBlank(existingSummary)) {
            prompt.append("Existing context: ").append(existingSummary).append("\n");
        }
        
        prompt.append("\nCONVERSATION:\n");
        for (Message m : batch) {
            prompt.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        
        try {
            List<Message> summaryMessages = List.of(Message.user(prompt.toString()));
            Map<String, Object> options = new HashMap<>();
            options.put("max_tokens", SUMMARY_MAX_TOKENS);
            options.put("temperature", SUMMARY_TEMPERATURE);
            
            LLMResponse response = provider.chat(summaryMessages, null, model, options);
            return response.getContent();
        } catch (Exception e) {
            logger.error("Failed to summarize batch", Map.of("error", e.getMessage()));
            return null;
        }
    }
    
    /**
     * 估算消息列表的token数量
     * 
     * 使用简单的字符计数方法粗略估算token数量，用于判断是否需要触发摘要。
     * 这是一个近似计算，实际token数可能有所不同。
     * 
     * @param messages 消息列表
     * @return 估算的token总数
     */
    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += StringUtils.estimateTokens(m.getContent());
        }
        return total;
    }
    
    /**
     * 获取启动信息
     * 
     * 返回Agent启动时的关键信息，包括已注册工具的数量和名称，
     * 以及技能系统的相关信息。主要用于状态报告和监控。
     * 
     * @return 包含启动信息的映射
     */
    public Map<String, Object> getStartupInfo() {
        Map<String, Object> info = new HashMap<>();
        
        // Tools info
        Map<String, Object> toolsInfo = new HashMap<>();
        toolsInfo.put("count", tools.count());
        toolsInfo.put("names", tools.list());
        info.put("tools", toolsInfo);
        
        // Skills info
        info.put("skills", contextBuilder.getSkillsInfo());
        
        return info;
    }
}
