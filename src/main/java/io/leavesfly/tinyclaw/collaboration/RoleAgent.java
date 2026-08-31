package io.leavesfly.tinyclaw.collaboration;

import io.leavesfly.tinyclaw.react.ReActExecutor;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个Agent执行器
 * 封装Agent的执行能力，用于多Agent协同场景
 */
public class RoleAgent {
    
    /**
     * 协同角色一律拿不到的工具：协同只能由主 Agent 发起。
     *
     * <p>{@code collaborate} 在注册表里是单实例，其 orchestrator、各策略实例与公共线程池
     * 也都是单例：角色在协同中再调它，等于让同一套可变状态自我嵌套——流式回调与
     * sessionKey 被内层覆写且不会还原，内层结束时的 clearProgress 会抹掉外层的进度卡，
     * 讨论策略进入时的 resetState 会清空外层的投票与共识，并行波次里还会出现外层线程
     * 等待排在同一队列尾部的内层任务。协同要嵌套得先有独立的执行上下文，这里先断掉。</p>
     */
    private static final List<String> DENIED_TOOLS = List.of(CollaborateTool.NAME);
    
    /** Agent唯一标识（格式：collab-<sessionId>-<sequence>） */
    private final String agentId;
    
    /** Agent角色 */
    private final AgentRole role;
    
    /** LLM执行器 */
    private final ReActExecutor reActExecutor;
    
    /** 会话管理器 */
    private final SessionManager sessionManager;
    
    /** 会话键 */
    private final String sessionKey;
    
    /** 基础系统提示词（可选，继承自主 Agent 的核心身份信息） */
    private final String baseSystemPrompt;
    
    /**
     * 发言轮次计数器，为每次流式发言生成唯一的 turn 标识。
     *
     * <p>前端按 turn 而非「当前发言块」给协同发言分块：并行协同（Tasks 模式 / 并行工作流节点）
     * 下多个 Agent 的事件交错到达，只认「当前块」会把同一次发言拆成多段；而顺序型多轮辩论
     * 里同一 Agent 的两轮发言 turn 不同，天然分成两块。用 Atomic 计数是因为同一个 RoleAgent
     * 可能被并行波次里的多个任务同时调用。</p>
     */
    private final AtomicInteger turnSeq = new AtomicInteger();
    
    /**
     * 构造 RoleAgent，使用外部传入的共享 SessionManager。
     *
     * <p>协同场景下所有 RoleAgent 共享同一个 SessionManager 实例（由 ExecutionContext 持有），
     * 避免每个 RoleAgent 独立初始化 SessionManager 带来的重复磁盘 IO 开销。
     *
     * @param role           Agent 角色定义
     * @param provider       LLM 服务提供者
     * @param tools          工具注册表
     * @param sharedSessions 共享会话管理器（由调用方统一创建）
     * @param model          默认模型名称
     * @param maxIterations  最大迭代次数
     * @param sessionId      协同会话 ID（用于日志关联和调试）
     * @param sequence       Agent 序号（在协同会话内唯一）
     * @param baseSystemPrompt 基础系统提示词（可选，继承自主 Agent 的核心身份信息）
     */
    public RoleAgent(AgentRole role, LLMProvider provider, ToolRegistry tools,
                     SessionManager sharedSessions, String model, int maxIterations,
                     String sessionId, int sequence, String baseSystemPrompt) {
        this.agentId = "collab-" + sessionId + "-" + sequence;
        this.role = role;
        this.sessionManager = sharedSessions;
        this.sessionKey = "collab:" + agentId;
        this.baseSystemPrompt = baseSystemPrompt;

        // 使用角色指定的模型，如果没有则使用默认模型
        String effectiveModel = (role.getModel() != null && !role.getModel().isEmpty())
                ? role.getModel() : model;

        // 按角色的工具白名单过滤工具集，实现差异化工具权限；
        // 再统一剥掉协同工具自身，白名单里显式写了也不放行
        ToolRegistry effectiveTools = (role.hasToolRestrictions()
                ? tools.filter(role.getAllowedTools())
                : tools).exclude(DENIED_TOOLS);

        this.reActExecutor = new ReActExecutor(provider, effectiveTools, sessionManager,
                effectiveModel, null, maxIterations);
    }
    
    /**
     * Agent发言（基于共享上下文）
     * 
     * @param context 共享上下文
     * @return Agent的回复内容
     */
    public String speak(SharedContext context) {
        return run(buildMessages(context), null);
    }
    
    /**
     * Agent发言（带自定义提示）
     * 
     * @param context 共享上下文
     * @param customPrompt 自定义提示（追加到系统提示后）
     * @return Agent的回复内容
     */
    public String speak(SharedContext context, String customPrompt) {
        return run(buildMessages(context, customPrompt), null);
    }

    /**
     * Agent 流式发言（基于共享上下文）
     * <p>LLM 生成回复时逐 chunk 通过 {@link StreamEvent#collaborateAgentChunk} 事件输出，
     * 用户无需等待完整回复即可看到 Agent 的发言过程。
     *
     * @param context  共享上下文
     * @param callback 流式回调，接收 COLLABORATE_AGENT_CHUNK / COLLABORATE_AGENT_THINKING 事件
     * @return Agent 的完整回复内容
     */
    public String speakStream(SharedContext context, LLMProvider.EnhancedStreamCallback callback) {
        return speakStream(context, null, callback);
    }

    /**
     * Agent 流式发言（带自定义提示）
     * <p>LLM 生成回复时逐 chunk 通过 {@link StreamEvent#collaborateAgentChunk} 事件输出，
     * 推理过程另走 {@link StreamEvent#collaborateAgentThinking} 事件。本次发言的所有事件
     * 共用同一个 turn 标识，前端据此把它们聚到同一个发言块内。
     *
     * @param context      共享上下文
     * @param customPrompt 自定义提示（追加到系统提示后）
     * @param callback     流式回调，接收 COLLABORATE_AGENT_CHUNK / COLLABORATE_AGENT_THINKING 事件
     * @return Agent 的完整回复内容
     */
    public String speakStream(SharedContext context, String customPrompt,
                              LLMProvider.EnhancedStreamCallback callback) {
        String turn = agentId + "#" + turnSeq.incrementAndGet();
        // 以增强回调中继：思考内容单独走 COLLABORATE_AGENT_THINKING，不能混进发言正文。
        // 若传普通 StreamCallback，ReActExecutor 内部的 wrap() 会把 THINKING 事件 format()
        // 成带 💭 前缀的文本并当作 chunk 发出，导致思维链逐行碎片化夹在正文里。
        // 按「语义族」而非单一事件类型归并：本次发言里若还嵌了子代理，它的思考也会以
        // SUBAGENT_THINKING 到达，只认 THINKING 就会让这些内容落进 default 被 format()
        // 成 💭 文本行——正文里于是夹着一串带气泡前缀的推理碎片
        LLMProvider.EnhancedStreamCallback eventRelay = event -> {
            switch (event.getType()) {
                case CONTENT, SUBAGENT_CONTENT, COLLABORATE_AGENT, COLLABORATE_AGENT_CHUNK ->
                        callback.onEvent(StreamEvent.collaborateAgentChunk(
                                role.getRoleName(), event.getContent(), turn));
                case THINKING, SUBAGENT_THINKING, COLLABORATE_AGENT_THINKING ->
                        callback.onEvent(StreamEvent.collaborateAgentThinking(
                                role.getRoleName(), event.getContent(), turn));
                // 工具调用保持结构化，只标注归属角色与轮次，前端在本次发言块内渲染工具卡片
                case TOOL_START, TOOL_END -> callback.onEvent(
                        event.withScope("agent", role.getRoleName()).withScope("turn", turn));
                // 剩下的都是起止标记，以可读文本行混入发言内容
                default -> callback.onEvent(
                        StreamEvent.collaborateAgentChunk(role.getRoleName(), event.format(), turn));
            }
        };
        return run(buildMessages(context, customPrompt), eventRelay);
    }

    /**
     * Agent直接回答（不使用共享上下文历史）
     * 
     * @param userMessage 用户消息
     * @return Agent的回复内容
     */
    public String answer(String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", buildSystemPrompt(null)));
        messages.add(new Message("user", userMessage));
        return run(messages, null);
    }

    /**
     * 统一的执行入口：提示词入库 → 执行 → 最终回复入库。
     *
     * <p>两头入库都不能省：{@code ReActExecutor} 只写工具循环的中间态（带 tool_calls 的
     * assistant 与 tool 结果），一轮对话的输入与最终回复由调用方负责。四个发言入口
     * 在此汇总，避免日后新增入口时又漏掉入库。</p>
     *
     * @param messages   本次发言的提示词（system + user）
     * @param chunkRelay 流式回调，为 null 时走非流式执行
     * @return Agent 的回复内容，执行异常时返回错误描述
     */
    private String run(List<Message> messages, LLMProvider.StreamCallback chunkRelay) {
        sessionManager.recordPromptMessages(sessionKey, messages);
        try {
            String reply = chunkRelay != null
                    ? reActExecutor.executeStream(messages, sessionKey, chunkRelay)
                    : reActExecutor.execute(messages, sessionKey);
            sessionManager.recordReply(sessionKey, reply);
            return reply;
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 构建消息列表（包含系统提示和共享上下文历史）
     */
    private List<Message> buildMessages(SharedContext context) {
        return buildMessages(context, null);
    }

    /**
     * 构建消息列表（包含系统提示、共享上下文历史和可选的自定义提示）
     */
    private List<Message> buildMessages(SharedContext context, String customPrompt) {
        List<Message> messages = new ArrayList<>();

        String systemPromptText = buildSystemPrompt(customPrompt);
        messages.add(new Message("system", systemPromptText));

        String userContent = buildUserContent(context);
        messages.add(new Message("user", userContent));

        return messages;
    }

    /**
     * 构建系统提示词
     * 优先使用 baseSystemPrompt 作为前缀，然后追加角色提示和自定义提示
     */
    private String buildSystemPrompt(String customPrompt) {
        StringBuilder promptBuilder = new StringBuilder();
        
        // 添加基础系统提示词（如果存在）
        if (baseSystemPrompt != null && !baseSystemPrompt.isEmpty()) {
            promptBuilder.append(baseSystemPrompt);
            promptBuilder.append("\n\n");
        }
        
        // 添加角色系统提示词
        promptBuilder.append(role.getSystemPrompt());
        
        // 追加自定义提示（如果存在）
        if (customPrompt != null && !customPrompt.isEmpty()) {
            promptBuilder.append("\n\n").append(customPrompt);
        }
        
        return promptBuilder.toString();
    }

    /**
     * 构建用户侧消息内容：协同主题 + 用户需求 + 对话历史 + 发言引导
     */
    private String buildUserContent(SharedContext context) {
        StringBuilder content = new StringBuilder();
        content.append("【协同主题】").append(context.getTopic()).append("\n\n");

        String userInput = context.getUserInput();
        if (userInput != null && !userInput.isEmpty()) {
            content.append("【用户需求】").append(userInput).append("\n\n");
        }

        String historyText = context.buildHistoryText();
        if (!historyText.isEmpty()) {
            content.append(historyText).append("\n");
        }

        content.append("请基于以上信息，以【").append(role.getRoleName()).append("】的角色给出你的观点或回复。");
        return content.toString();
    }
    
    // Getters
    
    public String getAgentId() {
        return agentId;
    }
    
    public AgentRole getRole() {
        return role;
    }
    
    public String getRoleName() {
        return role.getRoleName();
    }
    
    public SessionManager getSessionManager() {
        return sessionManager;
    }
    
    @Override
    public String toString() {
        return "RoleAgent{" +
                "agentId='" + agentId + '\'' +
                ", role=" + role.getRoleName() +
                '}';
    }
}