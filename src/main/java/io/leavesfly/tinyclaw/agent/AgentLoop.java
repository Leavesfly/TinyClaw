package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.Tool;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Agent 循环 - 核心 Agent 执行引擎
 * 
 * <p>AgentLoop 是 TinyClaw 的核心执行引擎，负责协调消息处理、会话管理和 LLM 交互。
 * 具体的执行逻辑委托给专门的组件：LLMExecutor（LLM 交互）和 SessionSummarizer（会话摘要）。</p>
 * 
 * <h2>职责：</h2>
 * <ul>
 *   <li>消息路由：处理来自不同通道的入站消息并路由到正确的处理流程</li>
 *   <li>上下文构建：构建包含历史记录、摘要和技能的完整对话上下文</li>
 *   <li>会话管理：维护会话状态并协调消息保存</li>
 *   <li>工具注册：管理可用工具并提供注册接口</li>
 * </ul>
 * 
 * <h2>工作流程：</h2>
 * <ol>
 *   <li>从消息总线接收入站消息</li>
 *   <li>区分系统消息和用户消息</li>
 *   <li>构建完整对话上下文（历史 + 摘要 + 系统提示）</li>
 *   <li>委托给 LLMExecutor 执行 LLM 迭代</li>
 *   <li>保存对话历史</li>
 *   <li>触发 SessionSummarizer 进行摘要（如需要）</li>
 * </ol>
 */
public class AgentLoop {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent");
    
    private final MessageBus bus;
    private final String workspace;
    private final SessionManager sessions;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry tools;
    private final LLMExecutor llmExecutor;
    private final SessionSummarizer summarizer;
    
    private volatile boolean running = false;
    
    /**
     * 构造 AgentLoop 实例
     */
    public AgentLoop(Config config, MessageBus bus, LLMProvider provider) {
        this.bus = bus;
        this.workspace = config.getWorkspacePath();
        
        ensureWorkspaceExists();
        
        this.tools = new ToolRegistry();
        this.sessions = new SessionManager(Paths.get(workspace, "sessions").toString());
        this.contextBuilder = new ContextBuilder(workspace);
        this.contextBuilder.setTools(this.tools);
        
        String model = config.getAgents().getDefaults().getModel();
        int contextWindow = config.getAgents().getDefaults().getMaxTokens();
        int maxIterations = config.getAgents().getDefaults().getMaxToolIterations();
        
        this.llmExecutor = new LLMExecutor(provider, tools, sessions, model, maxIterations);
        this.summarizer = new SessionSummarizer(sessions, provider, model, contextWindow);
        
        logger.info("Agent initialized", Map.of(
                "model", model,
                "workspace", workspace,
                "max_iterations", maxIterations
        ));
    }
    
    private void ensureWorkspaceExists() {
        try {
            Files.createDirectories(Paths.get(workspace));
        } catch (IOException e) {
            logger.warn("Failed to create workspace directory: " + e.getMessage());
        }
    }
    
    /**
     * 运行 Agent 循环（阻塞式）
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
     * 停止 Agent 循环
     */
    public void stop() {
        running = false;
    }
    
    /**
     * 注册一个工具
     */
    public void registerTool(Tool tool) {
        tools.register(tool);
        contextBuilder.setTools(tools);
    }
    
    /**
     * 直接处理消息（用于 CLI 模式）
     */
    public String processDirect(String content, String sessionKey) throws Exception {
        InboundMessage msg = new InboundMessage("cli", "user", "direct", content);
        msg.setSessionKey(sessionKey);
        return processMessage(msg);
    }
    
    /**
     * 处理带通道信息的消息
     */
    public String processDirectWithChannel(String content, String sessionKey, 
                                           String channel, String chatId) throws Exception {
        InboundMessage msg = new InboundMessage(channel, "cron", chatId, content);
        msg.setSessionKey(sessionKey);
        return processMessage(msg);
    }
    
    /**
     * 处理入站消息
     */
    private String processMessage(InboundMessage msg) throws Exception {
        logMessageInfo(msg);
        
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }
        
        return processUserMessage(msg);
    }
    
    private void logMessageInfo(InboundMessage msg) {
        String preview = StringUtils.truncate(msg.getContent(), 80);
        logger.info("Processing message", Map.of(
                "channel", msg.getChannel(),
                "chat_id", msg.getChatId(),
                "sender_id", msg.getSenderId(),
                "session_key", msg.getSessionKey(),
                "preview", preview
        ));
    }
    
    private String processUserMessage(InboundMessage msg) throws Exception {
        String sessionKey = msg.getSessionKey();
        
        List<Message> messages = buildContext(sessionKey, msg);
        sessions.addMessage(sessionKey, "user", msg.getContent());
        
        String response = llmExecutor.execute(messages, sessionKey);
        response = ensureResponse(response);
        
        sessions.addMessage(sessionKey, "assistant", response);
        sessions.save(sessions.getOrCreate(sessionKey));
        
        summarizer.maybeSummarize(sessionKey);
        
        return response;
    }
    
    private List<Message> buildContext(String sessionKey, InboundMessage msg) {
        List<Message> history = sessions.getHistory(sessionKey);
        String summary = sessions.getSummary(sessionKey);
        return contextBuilder.buildMessages(
                history, summary, msg.getContent(), msg.getChannel(), msg.getChatId()
        );
    }
    
    private String ensureResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return "I've completed processing but have no response to give.";
        }
        return response;
    }
    
    /**
     * 处理系统消息
     */
    private String processSystemMessage(InboundMessage msg) throws Exception {
        logger.info("Processing system message", Map.of(
                "sender_id", msg.getSenderId(),
                "chat_id", msg.getChatId()
        ));
        
        String[] origin = parseOrigin(msg.getChatId());
        String originChannel = origin[0];
        String originChatId = origin[1];
        String sessionKey = originChannel + ":" + originChatId;
        String userMessage = "[System: " + msg.getSenderId() + "] " + msg.getContent();
        
        List<Message> messages = buildContext(sessionKey, 
                new InboundMessage(originChannel, msg.getSenderId(), originChatId, userMessage));
        
        sessions.addMessage(sessionKey, "user", userMessage);
        
        String response = llmExecutor.execute(messages, sessionKey);
        response = ensureResponse(response, "Background task completed.");
        
        sessions.addMessage(sessionKey, "assistant", response);
        bus.publishOutbound(new OutboundMessage(originChannel, originChatId, response));
        
        return response;
    }
    
    private String[] parseOrigin(String chatId) {
        String[] parts = chatId.split(":", 2);
        if (parts.length == 2) {
            return new String[]{parts[0], parts[1]};
        }
        return new String[]{"cli", chatId};
    }
    
    private String ensureResponse(String response, String defaultMessage) {
        if (StringUtils.isBlank(response)) {
            return defaultMessage;
        }
        return response;
    }
    
    /**
     * 获取启动信息
     */
    public Map<String, Object> getStartupInfo() {
        Map<String, Object> info = new HashMap<>();
        
        Map<String, Object> toolsInfo = new HashMap<>();
        toolsInfo.put("count", tools.count());
        toolsInfo.put("names", tools.list());
        info.put("tools", toolsInfo);
        
        info.put("skills", contextBuilder.getSkillsInfo());
        
        return info;
    }
}
