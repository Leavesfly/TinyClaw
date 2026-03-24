package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 多 Agent 协同的共享上下文
 * <p>管理协同过程中的共享对话历史、终止状态、投票数据和最终结论。
 *
 * <p>高频使用的协同状态（终止标记、共识选项、投票结果等）已提升为一等字段，
 * 提供类型安全的访问方式，避免通过弱类型 metadata Map 传递。
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

    // -------------------------------------------------------------------------
    // 类型安全的协同状态字段（替代原 metadata 中的字符串 key）
    // -------------------------------------------------------------------------

    /** 角色扮演模式：主动结束对话的角色名称（非 null 表示已被主动结束） */
    private volatile String endedByRole;

    /** 共识模式：是否已达成共识 */
    private volatile boolean consensusReached;

    /** 共识模式：达成共识的选项 */
    private volatile String consensusOption;

    /** 共识模式：各轮投票结果（轮次 → 选项 → 投票者列表） */
    private final Map<Integer, Map<String, List<String>>> votesByRound;

    // -------------------------------------------------------------------------
    // Artifact 工件存储（结构化中间产物共享）
    // -------------------------------------------------------------------------

    /** 工件存储（artifactId → 最新版本的 Artifact） */
    private final Map<String, Artifact> artifacts;

    // -------------------------------------------------------------------------
    // Token 预算追踪
    // -------------------------------------------------------------------------

    /** 累计消耗的 token 数量（输入 + 输出） */
    private final AtomicLong totalTokensUsed;

    /** Token 预算上限（0 表示不限制） */
    private long tokenBudget;
    
    public SharedContext() {
        this.history = new CopyOnWriteArrayList<>();
        this.metadata = new ConcurrentHashMap<>();
        this.votesByRound = new ConcurrentHashMap<>();
        this.artifacts = new ConcurrentHashMap<>();
        this.totalTokensUsed = new AtomicLong(0);
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
     * 获取主 Agent 的对话上下文摘要
     */
    public String getContextSummary() {
        return contextSummary;
    }
    
    /**
     * 添加一条 Agent 发言到历史，并触发流式回调。
     *
     * <p>使用 {@link CopyOnWriteArrayList} 保证并发写入安全。
     * 流式回调在 add 完成后触发，避免回调内读取 history 时出现可见性问题。
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
    // 类型安全的协同状态操作
    // -------------------------------------------------------------------------

    /**
     * 标记对话被某角色主动结束（角色扮演模式）
     */
    public void markEndedBy(String roleName) {
        this.endedByRole = roleName;
    }

    /**
     * 获取主动结束对话的角色名称，未结束时返回 null
     */
    public String getEndedByRole() {
        return endedByRole;
    }

    /**
     * 判断对话是否已被主动结束
     */
    public boolean isEndedByRole() {
        return endedByRole != null;
    }

    /**
     * 标记共识已达成
     *
     * @param option 达成共识的选项
     */
    public void markConsensusReached(String option) {
        this.consensusReached = true;
        this.consensusOption = option;
    }

    /**
     * 判断是否已达成共识
     */
    public boolean isConsensusReached() {
        return consensusReached;
    }

    /**
     * 获取达成共识的选项
     */
    public String getConsensusOption() {
        return consensusOption;
    }

    /**
     * 记录某轮的投票结果
     *
     * @param round 轮次
     * @param votes 选项 → 投票者列表
     */
    public void setVotes(int round, Map<String, List<String>> votes) {
        votesByRound.put(round, votes);
    }

    /**
     * 获取某轮的投票结果
     */
    public Map<String, List<String>> getVotes(int round) {
        return votesByRound.get(round);
    }

    /**
     * 获取最新一轮的投票结果
     */
    public Map<String, List<String>> getLatestVotes() {
        return votesByRound.get(currentRound);
    }

    // -------------------------------------------------------------------------
    // Artifact 工件操作
    // -------------------------------------------------------------------------

    /**
     * 发布工件到共享上下文
     * <p>如果已存在同 ID 的工件，将被新版本覆盖。
     *
     * @param artifact 要发布的工件
     */
    public void publishArtifact(Artifact artifact) {
        if (artifact == null || artifact.getArtifactId() == null) {
            return;
        }
        artifacts.put(artifact.getArtifactId(), artifact);
    }

    /**
     * 根据 ID 获取工件
     */
    public Artifact getArtifact(String artifactId) {
        return artifacts.get(artifactId);
    }

    /**
     * 根据类型获取所有工件
     */
    public List<Artifact> getArtifactsByType(Artifact.ArtifactType type) {
        return artifacts.values().stream()
                .filter(a -> type == a.getType())
                .collect(Collectors.toList());
    }

    /**
     * 根据产出者获取所有工件
     */
    public List<Artifact> getArtifactsByProducer(String producer) {
        return artifacts.values().stream()
                .filter(a -> producer.equals(a.getProducer()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有工件
     */
    public Map<String, Artifact> getArtifacts() {
        return artifacts;
    }

    /**
     * 构建所有工件的摘要文本（用于注入 Agent 上下文）
     */
    public String buildArtifactsSummary() {
        if (artifacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 已产出工件 ===\n");
        for (Artifact artifact : artifacts.values()) {
            sb.append(artifact.toSummary()).append("\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Token 预算追踪
    // -------------------------------------------------------------------------

    /**
     * 记录 token 消耗
     *
     * @param tokens 本次消耗的 token 数量
     */
    public void addTokensUsed(long tokens) {
        totalTokensUsed.addAndGet(tokens);
    }

    /**
     * 获取累计消耗的 token 数量
     */
    public long getTotalTokensUsed() {
        return totalTokensUsed.get();
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
     * 获取 token 预算上限
     */
    public long getTokenBudget() {
        return tokenBudget;
    }

    /**
     * 检查是否已超出 token 预算
     *
     * @return 如果设置了预算且已超出则返回 true
     */
    public boolean isTokenBudgetExceeded() {
        return tokenBudget > 0 && totalTokensUsed.get() >= tokenBudget;
    }

    /**
     * 获取剩余 token 预算
     *
     * @return 剩余预算，未设置预算时返回 Long.MAX_VALUE
     */
    public long getRemainingTokenBudget() {
        if (tokenBudget <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, tokenBudget - totalTokensUsed.get());
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
