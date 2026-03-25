package io.leavesfly.tinyclaw.agent.collaboration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.HashSet;
import java.util.Set;

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
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
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

            // 参与均衡度：各 Agent 发言次数的变异系数（越高越均衡）
            if (messagesByAgent.size() > 1) {
                double participationBalance = calculateParticipationBalance(messagesByAgent);
                metrics.put("participationBalance", Math.round(participationBalance * 100.0) / 100.0);
            }
        }

        // 协同时长
        long duration = System.currentTimeMillis() - context.getStartTime();
        metrics.put("durationMs", duration);

        // ---- 协同质量评估指标 ----

        // 观点多样性：不同 Agent 观点的差异度（基于发言内容的 Jaccard 距离近似）
        if (messages.size() >= 2) {
            double diversityScore = calculateDiversityScore(messages);
            metrics.put("diversityScore", Math.round(diversityScore * 100.0) / 100.0);
        }

        // 收敛速度：从分歧到共识的轮次比例（越高表示后期越趋于一致）
        int totalRounds = context.getCurrentRound();
        if (totalRounds > 0 && messages.size() >= 3) {
            int lastThirdStart = Math.max(0, messages.size() - messages.size() / 3);
            List<AgentMessage> lastThird = messages.subList(lastThirdStart, messages.size());
            double convergenceRatio = calculateConvergenceRatio(lastThird, messages);
            metrics.put("convergenceRatio", Math.round(convergenceRatio * 100.0) / 100.0);
        }

        this.metrics = metrics;
    }

    /**
     * 计算观点多样性评分（0.0-1.0，越高越多样）
     * 基于不同 Agent 发言内容的关键词重叠度的反向指标
     */
    private static double calculateDiversityScore(List<AgentMessage> messages) {
        Map<String, Set<String>> agentKeywords = new HashMap<>();
        for (AgentMessage msg : messages) {
            String role = msg.getAgentRole() != null ? msg.getAgentRole() : msg.getAgentId();
            agentKeywords.computeIfAbsent(role, k -> new HashSet<>());
            if (msg.getContent() != null) {
                // 提取关键词（按空格和标点分词，取长度 >= 2 的词）
                String[] words = msg.getContent().split("[\\s\\p{Punct}]+");
                for (String word : words) {
                    if (word.length() >= 2) {
                        agentKeywords.get(role).add(word.toLowerCase());
                    }
                }
            }
        }

        if (agentKeywords.size() < 2) return 0.0;

        // 计算所有 Agent 对之间的 Jaccard 距离的平均值
        List<Set<String>> keywordSets = new ArrayList<>(agentKeywords.values());
        double totalDistance = 0.0;
        int pairCount = 0;
        for (int i = 0; i < keywordSets.size(); i++) {
            for (int j = i + 1; j < keywordSets.size(); j++) {
                Set<String> intersection = new HashSet<>(keywordSets.get(i));
                intersection.retainAll(keywordSets.get(j));
                Set<String> union = new HashSet<>(keywordSets.get(i));
                union.addAll(keywordSets.get(j));
                double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
                totalDistance += (1.0 - jaccard); // Jaccard 距离 = 1 - Jaccard 相似度
                pairCount++;
            }
        }
        return pairCount > 0 ? totalDistance / pairCount : 0.0;
    }

    /**
     * 计算收敛比率（0.0-1.0，越高表示后期越趋于一致）
     * 比较后 1/3 消息与全部消息的关键词重叠度
     */
    private static double calculateConvergenceRatio(List<AgentMessage> lastThird,
                                                     List<AgentMessage> allMessages) {
        Set<String> lastThirdKeywords = extractKeywords(lastThird);
        Set<String> allKeywords = extractKeywords(allMessages);
        if (allKeywords.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(lastThirdKeywords);
        intersection.retainAll(allKeywords);
        return (double) intersection.size() / allKeywords.size();
    }

    private static Set<String> extractKeywords(List<AgentMessage> messages) {
        Set<String> keywords = new HashSet<>();
        for (AgentMessage msg : messages) {
            if (msg.getContent() != null) {
                String[] words = msg.getContent().split("[\\s\\p{Punct}]+");
                for (String word : words) {
                    if (word.length() >= 2) {
                        keywords.add(word.toLowerCase());
                    }
                }
            }
        }
        return keywords;
    }

    /**
     * 计算参与均衡度（0.0-1.0，越高越均衡）
     * 基于各 Agent 发言次数的变异系数的反向指标
     */
    private static double calculateParticipationBalance(Map<String, Long> messagesByAgent) {
        double[] counts = messagesByAgent.values().stream().mapToDouble(Long::doubleValue).toArray();
        double mean = 0;
        for (double c : counts) mean += c;
        mean /= counts.length;
        if (mean == 0) return 1.0;

        double variance = 0;
        for (double c : counts) variance += (c - mean) * (c - mean);
        variance /= counts.length;
        double cv = Math.sqrt(variance) / mean; // 变异系数
        return Math.max(0.0, 1.0 - cv); // 反转：cv 越小越均衡
    }

    /**
     * 从 JSON 文件加载协同记录
     *
     * @param file JSON 文件
     * @return 协同记录
     * @throws IOException 读取或解析失败时抛出
     */
    public static CollaborationRecord loadFrom(File file) throws IOException {
        return OBJECT_MAPPER.readValue(file, CollaborationRecord.class);
    }

    /**
     * 从指定目录加载与给定 sessionId 匹配的最新协同记录。
     * 协同记录文件名格式为 collab-{sessionId}-{timestamp}.json，
     * 当存在多个匹配文件时返回时间戳最大（最新）的那个。
     *
     * @param directory 协同记录目录
     * @param sessionId 会话 ID
     * @return 最新的协同记录，未找到时返回 null
     */
    public static CollaborationRecord loadLatest(String directory, String sessionId) {
        File dir = new File(directory);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

        String prefix = "collab-" + sessionId + "-";
        File[] matched = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".json"));
        if (matched == null || matched.length == 0) {
            return null;
        }

        // 按文件名排序取最新（文件名中含时间戳，字典序即时间序）
        Arrays.sort(matched, Comparator.comparing(File::getName));
        File latest = matched[matched.length - 1];

        try {
            return loadFrom(latest);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从指定目录加载所有协同记录
     *
     * @param directory 协同记录目录
     * @return 协同记录列表，目录不存在或无文件时返回空列表
     */
    public static List<CollaborationRecord> loadAll(String directory) {
        File dir = new File(directory);
        if (!dir.exists() || !dir.isDirectory()) {
            return List.of();
        }

        File[] files = dir.listFiles((d, name) -> name.startsWith("collab-") && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<CollaborationRecord> records = new ArrayList<>();
        for (File file : files) {
            try {
                records.add(loadFrom(file));
            } catch (IOException ignored) {
                // 跳过无法解析的文件
            }
        }
        return records;
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