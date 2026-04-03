package io.leavesfly.tinyclaw.collaboration;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 多 Agent 协同的共享上下文
 * <p>管理协同过程中的共享对话历史、扩展元数据和最终结论。
 * 策略特定的状态（投票、共识等）由各策略自行管理。
 */
public class SharedContext {
    
    /** 协同主题/目标 */
    private String topic;
    
    /** 用户原始输入 */
    private String userInput;
    
    /** 共享对话历史 */
    private List<AgentMessage> history;
    
    /** 扩展元数据（供策略存放自定义数据，不建议存放高频状态） */
    private Map<String, Object> metadata;
    
    /** 最终结论 */
    private String finalConclusion;
    
    /** 当前轮次 */
    private int currentRound;
    
    /** 协同开始时间 */
    private long startTime;
    
    /** 流式回调（用于输出 Agent 发言） */
    private volatile LLMProvider.EnhancedStreamCallback streamCallback;

    /** Token 预算上限（0 表示不限制） */
    private long tokenBudget;

    /** 累计消耗的 token 数量（输入 + 输出） */
    private volatile long totalTokensUsed;
    
    public SharedContext() {
        this.history = new CopyOnWriteArrayList<>();
        this.metadata = new ConcurrentHashMap<>();
        this.totalTokensUsed = 0;
        this.tokenBudget = 0;
        this.currentRound = 0;
        this.startTime = System.currentTimeMillis();
    }
    
    /** 主 Agent 传入的对话上下文摘要（帮助协同 Agent 理解完整背景） */
    private String contextSummary;

    public SharedContext(String topic, String userInput) {
        this();
        this.topic = topic;
        this.userInput = userInput;
    }

    /**
     * 设置主 Agent 的对话上下文摘要
     * <p>协同 Agent 可通过此摘要了解用户与主 Agent 之前的对话背景，
     * 避免因上下文缺失而产生偏离用户意图的回答。
     *
     * @param summary 主 Agent 对话摘要
     */
    public void setContextSummary(String summary) {
        this.contextSummary = summary;
    }
    
    /**
     * 添加一条 Agent 发言到历史，并触发流式回调。
     *
     * <p>使用 {@link CopyOnWriteArrayList} 保证并发写入安全。
     * 流式回调在 add 完成后触发，避免回调内读取 history 时出现可见性问题。
     *
     * <p>当消息已通过 {@code speakStream} 逐 chunk 流式输出过时，
     * 调用方应使用 {@link #addMessageSilent(AgentMessage)} 避免重复推送完整消息。
     */
    public void addMessage(AgentMessage message) {
        if (message == null) {
            return;
        }
        history.add(message);

        LLMProvider.EnhancedStreamCallback cb = streamCallback;
        if (cb != null) {
            String agentName = message.getAgentRole() != null
                    ? message.getAgentRole() : message.getAgentId();
            cb.onEvent(StreamEvent.collaborateAgent(agentName, message.getContent()));
        }
    }

    /**
     * 静默添加消息到历史（不触发流式回调）。
     * <p>用于已通过 {@code speakStream} 逐 chunk 流式输出过的消息，
     * 避免完整消息再次通过 {@code COLLABORATE_AGENT} 事件重复推送给用户。
     *
     * @param message Agent 消息
     */
    public void addMessageSilent(AgentMessage message) {
        if (message == null) {
            return;
        }
        history.add(message);
    }

    /**
     * 添加一条 Agent 发言（便捷方法）
     */
    public void addMessage(String agentId, String agentRole, String content) {
        addMessage(new AgentMessage(agentId, agentRole, content));
    }
    
    /**
     * 获取指定 Agent 的所有发言
     */
    public List<AgentMessage> getMessagesByAgent(String agentId) {
        return history.stream()
                .filter(m -> agentId.equals(m.getAgentId()))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取指定角色的所有发言
     */
    public List<AgentMessage> getMessagesByRole(String roleName) {
        return history.stream()
                .filter(m -> roleName.equals(m.getAgentRole()))
                .collect(Collectors.toList());
    }

    /**
     * 获取与指定角色相关的消息（广播消息 + 定向给该角色的消息）
     * <p>用于定向通信场景，Agent 只看到与自己相关的消息，减少无关上下文的 token 消耗。
     *
     * @param roleName 角色名称
     * @return 与该角色相关的消息列表
     */
    public List<AgentMessage> getMessagesRelevantTo(String roleName) {
        return history.stream()
                .filter(m -> m.isRelevantTo(roleName))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定类型的所有消息
     */
    public List<AgentMessage> getMessagesByType(AgentMessage.MessageType messageType) {
        return history.stream()
                .filter(m -> messageType == m.getMessageType())
                .collect(Collectors.toList());
    }
    
    /**
     * 获取最近 N 条发言
     */
    public List<AgentMessage> getRecentMessages(int n) {
        int size = history.size();
        if (n >= size) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(size - n, size));
    }
    
    /**
     * 构建对话历史的文本表示（供 Agent 参考）
     */
    public String buildHistoryText() {
        StringBuilder sb = new StringBuilder();

        // 如果有主 Agent 上下文摘要，先输出背景信息
        if (contextSummary != null && !contextSummary.isEmpty()) {
            sb.append("=== 对话背景 ===\n");
            sb.append(contextSummary).append("\n\n");
        }

        if (history.isEmpty()) {
            return sb.toString();
        }
        sb.append("=== 协同对话历史 ===\n");
        for (AgentMessage msg : history) {
            sb.append("[").append(msg.getAgentRole()).append("]: ");
            sb.append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建与指定角色相关的对话历史文本（定向通信优化版）
     * <p>只包含广播消息和定向给该角色的消息，减少无关上下文。
     *
     * @param roleName 角色名称
     * @return 过滤后的对话历史文本
     */
    public String buildHistoryTextFor(String roleName) {
        List<AgentMessage> relevant = getMessagesRelevantTo(roleName);
        if (relevant.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 协同对话历史 ===\n");
        for (AgentMessage msg : relevant) {
            sb.append("[").append(msg.getAgentRole()).append("]");
            if (msg.isDirected()) {
                sb.append(" → [").append(msg.getTargetRole()).append("]");
            }
            sb.append(": ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Token 预算追踪
    // -------------------------------------------------------------------------

    /**
     * 获取累计消耗的 token 数量
     */
    public long getTotalTokensUsed() {
        return totalTokensUsed;
    }

    /**
     * 设置 token 预算上限
     *
     * @param budget token 预算，0 表示不限制
     */
    public void setTokenBudget(long budget) {
        this.tokenBudget = budget;
    }

    /**
     * 检查是否已超出 token 预算
     *
     * @return 如果设置了预算且已超出则返回 true
     */
    public boolean isTokenBudgetExceeded() {
        return tokenBudget > 0 && totalTokensUsed >= tokenBudget;
    }

    // -------------------------------------------------------------------------
    // 扩展元数据（供策略存放自定义数据）
    // -------------------------------------------------------------------------

    /**
     * 设置扩展元数据
     */
    public void setMeta(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * 获取扩展元数据
     */
    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key) {
        return (T) metadata.get(key);
    }
    
    /**
     * 进入下一轮
     */
    public void nextRound() {
        currentRound++;
    }
    
    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------
    
    public String getTopic() {
        return topic;
    }
    
    public void setTopic(String topic) {
        this.topic = topic;
    }
    
    public String getUserInput() {
        return userInput;
    }
    
    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }
    
    public List<AgentMessage> getHistory() {
        return history;
    }
    
    public void setHistory(List<AgentMessage> history) {
        this.history = history;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    public String getFinalConclusion() {
        return finalConclusion;
    }
    
    public void setFinalConclusion(String finalConclusion) {
        this.finalConclusion = finalConclusion;
    }
    
    public int getCurrentRound() {
        return currentRound;
    }
    
    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    /**
     * 获取协同已进行的时间（毫秒）
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * 设置流式回调，用于输出 Agent 发言等协同过程信息。
     * 
     * @param callback 流式回调，可为 null
     */
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }
    
    /**
     * 获取流式回调
     */
    public LLMProvider.EnhancedStreamCallback getStreamCallback() {
        return streamCallback;
    }
}
