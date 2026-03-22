package io.leavesfly.tinyclaw.agent.collaboration;

import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 多Agent协同的共享上下文
 * 管理协同过程中的共享对话历史、元数据和最终结论
 */
public class SharedContext {
    
    /** 协同主题/目标 */
    private String topic;
    
    /** 用户原始输入 */
    private String userInput;
    
    /** 共享对话历史 */
    private List<AgentMessage> history;
    
    /** 元数据（投票结果、角色定义、中间状态等） */
    private Map<String, Object> metadata;
    
    /** 最终结论 */
    private String finalConclusion;
    
    /** 当前轮次 */
    private int currentRound;
    
    /** 协同开始时间 */
    private long startTime;
    
    /** 流式回调（用于输出 Agent 发言） */
    private volatile LLMProvider.EnhancedStreamCallback streamCallback;
    
    public SharedContext() {
        this.history = new CopyOnWriteArrayList<>();
        this.metadata = new HashMap<>();
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

        // 在 add 完成后再触发回调，避免回调内读 history 时看到不完整状态
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
     * 获取指定Agent的所有发言
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
     * 获取最近N条发言
     */
    public List<AgentMessage> getRecentMessages(int n) {
        int size = history.size();
        if (n >= size) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(size - n, size));
    }
    
    /**
     * 构建对话历史的文本表示（供Agent参考）
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
    
    /**
     * 设置元数据
     */
    public void setMeta(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * 获取元数据
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
    
    // Getters and Setters
    
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
