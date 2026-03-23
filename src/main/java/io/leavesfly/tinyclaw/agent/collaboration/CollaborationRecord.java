package io.leavesfly.tinyclaw.agent.collaboration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 协同记录
 * 记录完整的协同过程信息，用于可观测性和调试
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollaborationRecord {

    /** 协同会话 ID */
    private String sessionId;

    /** 协同模式 */
    private String mode;

    /** 协同目标 */
    private String goal;

    /** 参与角色列表 */
    private List<String> participants;

    /** 完整对话历史 */
    private List<AgentMessage> messages;

    /** 最终结论 */
    private String conclusion;

    /** 开始时间 */
    private long startTime;

    /** 结束时间 */
    private long endTime;

    /** 总轮次 */
    private int totalRounds;

    /** 状态（成功/失败/超时） */
    private String status;

    /** 统计指标 */
    private Map<String, Object> metrics;

    /** Jackson ObjectMapper（静态，线程安全） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * 协同状态枚举
     */
    public enum Status {
        SUCCESS,
        FAILURE,
        TIMEOUT
    }

    public CollaborationRecord() {
        this.participants = new ArrayList<>();
        this.messages = new ArrayList<>();
        this.metrics = new HashMap<>();
    }

    /**
     * 从 SharedContext 构建协同记录
     *
     * @param context   共享上下文
     * @param config    协同配置
     * @param sessionId 会话 ID
     * @return 协同记录
     */
    public static CollaborationRecord fromContext(SharedContext context, 
                                                  CollaborationConfig config, 
                                                  String sessionId) {
        CollaborationRecord record = new CollaborationRecord();
        record.setSessionId(sessionId);
        record.setMode(config.getMode().name());
        record.setGoal(config.getGoal());
        record.setStartTime(context.getStartTime());
        record.setEndTime(System.currentTimeMillis());
        record.setTotalRounds(context.getCurrentRound());
        record.setConclusion(context.getFinalConclusion());
        
        // 提取参与角色
        if (config.getRoles() != null) {
            List<String> participants = config.getRoles().stream()
                    .map(AgentRole::getRoleName)
                    .collect(Collectors.toList());
            record.setParticipants(participants);
        }
        
        // 复制对话历史
        record.setMessages(new ArrayList<>(context.getHistory()));
        
        // 计算统计指标
        record.calculateMetrics(context);
        
        return record;
    }

    /**
     * 计算统计指标
     */
    private void calculateMetrics(SharedContext context) {
        Map<String, Object> metrics = new HashMap<>();
        List<AgentMessage> messages = context.getHistory();
        
        if (!messages.isEmpty()) {
            // 消息总数
            metrics.put("totalMessages", messages.size());
            
            // 每个 Agent 的发言次数
            Map<String, Long> messagesByAgent = messages.stream()
                    .collect(Collectors.groupingBy(
                            msg -> msg.getAgentRole() != null ? msg.getAgentRole() : msg.getAgentId(),
                            Collectors.counting()
                    ));
            metrics.put("messagesByAgent", messagesByAgent);
            
            // 平均响应长度
            double avgLength = messages.stream()
                    .mapToInt(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
                    .average()
                    .orElse(0.0);
            metrics.put("averageResponseLength", Math.round(avgLength * 100.0) / 100.0);
            
            // 总字符数
            int totalChars = messages.stream()
                    .mapToInt(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
                    .sum();
            metrics.put("totalCharacters", totalChars);
        }
        
        // 协同时长
        long duration = System.currentTimeMillis() - context.getStartTime();
        metrics.put("durationMs", duration);
        
        this.metrics = metrics;
    }

    /**
     * 序列化为 JSON 字符串
     *
     * @return JSON 字符串
     */
    public String toJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize CollaborationRecord to JSON", e);
        }
    }

    /**
     * 保存到指定目录
     *
     * @param directory 目录路径
     * @return 保存的文件路径
     */
    public String saveTo(String directory) {
        try {
            // 确保目录存在
            Path dirPath = Paths.get(directory);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            
            // 生成文件名：collab-<sessionId>-<timestamp>.json
            String fileName = String.format("collab-%s-%d.json", 
                    sessionId, 
                    System.currentTimeMillis());
            Path filePath = dirPath.resolve(fileName);
            
            // 写入文件
            OBJECT_MAPPER.writeValue(filePath.toFile(), this);
            
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save CollaborationRecord to " + directory, e);
        }
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public List<String> getParticipants() {
        return participants;
    }

    public void setParticipants(List<String> participants) {
        this.participants = participants;
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentMessage> messages) {
        this.messages = messages;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public int getTotalRounds() {
        return totalRounds;
    }

    public void setTotalRounds(int totalRounds) {
        this.totalRounds = totalRounds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }
}
