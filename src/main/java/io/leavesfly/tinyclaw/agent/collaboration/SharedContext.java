package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    
    public SharedContext() {
        this.history = new CopyOnWriteArrayList<>();
        this.metadata = new ConcurrentHashMap<>();
        this.votesByRound = new ConcurrentHashMap<>();
        this.currentRound = 0;
        this.startTime = System.currentTimeMillis();
    }
    
    public SharedContext(String topic, String userInput) {
        this();
        this.topic = topic;
        this.userInput = userInput;
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
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 协同对话历史 ===\n");
        for (AgentMessage msg : history) {
            sb.append("[").append(msg.getAgentRole()).append("]: ");
            sb.append(msg.getContent()).append("\n\n");
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
