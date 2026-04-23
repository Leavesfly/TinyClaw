package io.leavesfly.tinyclaw.evolution.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反思引擎（LLM-as-Reviewer）。
 *
 * <p>当 {@link FailureDetector} 检测到失败模式时，本引擎负责：
 * <ol>
 *   <li>收集失败证据：从 {@link PatternMiner} 获取聚类结果及代表性样本；</li>
 *   <li>构造反思 Prompt：将工具定义、失败样本、健康度统计拼装成结构化的分析请求；</li>
 *   <li>调用 LLM 进行根因分析；</li>
 *   <li>解析 LLM 输出为 {@link RepairProposal}；</li>
 *   <li>按配置决定是否自动批准低影响提案。</li>
 * </ol>
 *
 * <p>冷却机制：两次反思之间间隔不小于 {@link ReflectionConfig#getReflectionIntervalHours()} 小时，
 * 避免高频打扰 LLM。
 */
public class ReflectionEngine {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("reflection.engine");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMProvider provider;
    private final String model;
    private final ReflectionConfig config;
    private final ToolCallLogStore logStore;
    private final PatternMiner patternMiner;
    private final ToolHealthAggregator aggregator;
    private final ToolRegistry toolRegistry;

    /** 所有历史提案（线程安全） */
    private final CopyOnWriteArrayList<RepairProposal> proposals = new CopyOnWriteArrayList<>();

    /** 上次反思时间戳 */
    private final AtomicLong lastReflectionTimeMs = new AtomicLong(0);

    public ReflectionEngine(LLMProvider provider, String model, ReflectionConfig config,
                            ToolCallLogStore logStore, PatternMiner patternMiner,
                            ToolHealthAggregator aggregator, ToolRegistry toolRegistry) {
        this.provider = provider;
        this.model = resolveModel(model, config);
        this.config = config;
        this.logStore = logStore;
        this.patternMiner = patternMiner;
        this.aggregator = aggregator;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 根据检测结果执行反思，生成修复提案。
     *
     * <p>会检查冷却时间，若未冷却则跳过。
     *
     * @param detections 失败检测结果列表
     * @return 本次生成的提案列表（可能为空）
     */
    public List<RepairProposal> reflect(List<FailureDetector.DetectionResult> detections) {
        if (detections == null || detections.isEmpty()) {
            return Collections.emptyList();
        }

        if (!canReflect()) {
            logger.debug("Reflection on cooldown, skipping");
            return Collections.emptyList();
        }

        // 按工具分组
        Map<String, List<FailureDetector.DetectionResult>> byTool = new LinkedHashMap<>();
        for (FailureDetector.DetectionResult detection : detections) {
            byTool.computeIfAbsent(detection.getToolName(), k -> new ArrayList<>()).add(detection);
        }

        List<RepairProposal> newProposals = new ArrayList<>();
        for (Map.Entry<String, List<FailureDetector.DetectionResult>> entry : byTool.entrySet()) {
            try {
                List<RepairProposal> toolProposals = reflectForTool(entry.getKey(), entry.getValue());
                newProposals.addAll(toolProposals);
            } catch (Exception e) {
                logger.error("Reflection failed for tool", Map.of(
                        "tool", entry.getKey(),
                        "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "detectionCount", entry.getValue().size()));
            }
        }

        if (!newProposals.isEmpty()) {
            proposals.addAll(newProposals);
            lastReflectionTimeMs.set(System.currentTimeMillis());
            logger.info("Reflection completed", Map.of("proposals_generated", newProposals.size()));
        }

        return newProposals;
    }

    /**
     * 对单个工具执行反思。
     */
    private List<RepairProposal> reflectForTool(String toolName,
                                                 List<FailureDetector.DetectionResult> detections) throws Exception {
        // 1. 收集失败聚类
        Instant windowEnd = Instant.now();
        Instant windowStart = windowEnd.minusSeconds(config.getDetectionWindowMinutes() * 60L);
        List<PatternMiner.FailureCluster> clusters = patternMiner.mine(
                toolName, windowStart, windowEnd, config.getMaxSamplesPerReflection());

        if (clusters.isEmpty()) {
            logger.debug("No failure clusters found for tool", Map.of("tool", toolName));
            return Collections.emptyList();
        }

        // 2. 获取工具定义和健康度
        String toolDefinition = getToolDefinition(toolName);
        ToolHealthStat healthStat = aggregator.query(toolName, config.getDetectionWindowMinutes());

        // 3. 构造反思 Prompt
        String reflectionPrompt = buildReflectionPrompt(toolName, toolDefinition, healthStat,
                clusters, detections);

        // 4. 调用 LLM
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(SYSTEM_PROMPT));
        messages.add(Message.user(reflectionPrompt));

        Map<String, Object> options = Map.of(
                "temperature", config.getReflectionTemperature(),
                "max_tokens", config.getReflectionMaxTokens());

        LLMResponse response = provider.chat(messages, Collections.emptyList(), model, options);
        String llmOutput = response.getContent();

        if (llmOutput == null || llmOutput.isBlank()) {
            logger.warn("LLM returned empty reflection for tool", Map.of("tool", toolName));
            return Collections.emptyList();
        }

        // 5. 解析 LLM 输出为提案
        List<RepairProposal> parsedProposals = parseProposals(toolName, llmOutput, clusters, detections);

        // 6. 自动批准低影响提案
        if (config.isAutoApproveLowImpact()) {
            for (RepairProposal proposal : parsedProposals) {
                if (proposal.getImpactScore() < config.getAutoApproveImpactBelow()
                        && proposal.getType() == RepairProposal.Type.DESCRIPTION_REWRITE) {
                    proposal.approve("Auto-approved: low impact score " + proposal.getImpactScore());
                    logger.info("Auto-approved low-impact proposal", Map.of(
                            "proposalId", proposal.getProposalId(), "tool", toolName));
                }
            }
        }

        return parsedProposals;
    }

    // ==================== Prompt 构建 ====================

    private static final String SYSTEM_PROMPT = """
            你是一名工具可靠性工程师，正在分析一个 AI Agent 的工具调用模式。
            你的任务是诊断工具失败的原因，并提出具体的修复方案。
            
            你将收到以下信息：
            1. 工具的当前定义（名称、描述、参数 schema）
            2. 健康度统计（成功率、延迟、错误分布）
            3. 失败聚类及其代表性样本
            4. 触发本次分析的检测告警
            
            针对每个问题，请生成一个 JSON 数组形式的修复提案。每个提案必须包含：
            - "type"：取值为 "DESCRIPTION_REWRITE"、"VALIDATION_RULE"、"FEW_SHOT_EXAMPLE" 之一
            - "summary"：一句话描述修复内容
            - "rootCause"：2-3 句话的根因分析
            - "content"：实际的修复内容（新描述 / 校验规则 JSON / few-shot 示范文本）
            - "impactScore"：0.0-1.0 的影响评分（0=外观优化，1=严重问题）
            
            各提案类型指引：
            - DESCRIPTION_REWRITE：重写工具描述以防止 LLM 误解。明确参数约束，澄清边界情况。
            - VALIDATION_RULE：JSON 对象，key 为参数名，value 为校验表达式
              （例如 {"path": "must_start_with: /workspace", "limit": "range: 1-100"}）。
            - FEW_SHOT_EXAMPLE：提供 1-2 个使用真实参数的正确调用示范。
            
            仅输出合法的 JSON 数组，不要包含 markdown 标记或 JSON 以外的任何说明文字。
            """;

    private String buildReflectionPrompt(String toolName, String toolDefinition,
                                          ToolHealthStat healthStat,
                                          List<PatternMiner.FailureCluster> clusters,
                                          List<FailureDetector.DetectionResult> detections) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Tool Under Review: ").append(toolName).append("\n\n");

        sb.append("## Current Tool Definition\n");
        sb.append(toolDefinition).append("\n\n");

        sb.append("## Health Statistics\n");
        sb.append(String.format("- Total Calls: %d\n", healthStat.getTotalCalls()));
        sb.append(String.format("- Success Rate: %.1f%%\n", healthStat.getSuccessRate() * 100));
        sb.append(String.format("- P50 Latency: %.0fms, P95: %.0fms, P99: %.0fms\n",
                healthStat.getP50Ms(), healthStat.getP95Ms(), healthStat.getP99Ms()));
        if (!healthStat.getErrorTypeHistogram().isEmpty()) {
            sb.append("- Error Distribution: ").append(healthStat.getErrorTypeHistogram()).append("\n");
        }
        sb.append("\n");

        sb.append("## Detection Alerts\n");
        for (FailureDetector.DetectionResult detection : detections) {
            sb.append(String.format("- [%s] %s\n", detection.getType().name(), detection.getDescription()));
        }
        sb.append("\n");

        sb.append("## Failure Clusters\n");
        int clusterLimit = Math.min(clusters.size(), config.getMaxSamplesPerReflection());
        for (int i = 0; i < clusterLimit; i++) {
            sb.append(clusters.get(i).toReflectionSummary());
        }

        return sb.toString();
    }

    private String getToolDefinition(String toolName) {
        return toolRegistry.get(toolName)
                .map(tool -> String.format("Name: %s\nDescription: %s\nParameters: %s",
                        tool.name(), tool.description(), tool.parameters()))
                .orElseGet(() -> {
                    logger.warn("Tool definition not found during reflection", Map.of("tool", toolName));
                    return String.format("Name: %s\nDescription: [definition unavailable — tool may have been unregistered]\nParameters: {}", toolName);
                });
    }

    // ==================== LLM 输出解析 ====================

    private List<RepairProposal> parseProposals(String toolName, String llmOutput,
                                                 List<PatternMiner.FailureCluster> clusters,
                                                 List<FailureDetector.DetectionResult> detections) {
        List<RepairProposal> result = new ArrayList<>();
        try {
            // 提取 JSON 数组（LLM 可能输出 markdown 包裹的 JSON）
            String jsonText = extractJsonArray(llmOutput);
            JsonNode array = MAPPER.readTree(jsonText);

            if (!array.isArray()) {
                logger.warn("LLM output is not a JSON array, wrapping as single element");
                array = MAPPER.createArrayNode().add(array);
            }

            String triggerType = detections.isEmpty() ? "UNKNOWN" : detections.get(0).getType().name();
            String clusterKey = clusters.isEmpty() ? "" : clusters.get(0).getClusterKey();
            String originalDescription = getToolDefinition(toolName);

            for (JsonNode node : array) {
                try {
                    RepairProposal proposal = nodeToProposal(node, toolName, originalDescription);
                    proposal.setTriggerType(triggerType);
                    proposal.setClusterKey(clusterKey);

                    // 收集证据事件 ID（去重）
                    Set<String> eventIdSet = new LinkedHashSet<>();
                    for (PatternMiner.FailureCluster cluster : clusters) {
                        for (ToolCallEvent sample : cluster.getRepresentativeSamples()) {
                            eventIdSet.add(sample.getEventId());
                        }
                    }
                    proposal.setEvidenceEventIds(new ArrayList<>(eventIdSet));

                    result.add(proposal);
                } catch (Exception e) {
                    logger.warn("Failed to parse proposal node: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse LLM reflection output",
                    Map.of("error", e.getMessage(), "output_preview",
                            llmOutput.length() > 200 ? llmOutput.substring(0, 200) + "..." : llmOutput));
        }
        return result;
    }

    private RepairProposal nodeToProposal(JsonNode node, String toolName, String originalDescription) {
        String typeStr = node.has("type") ? node.get("type").asText() : "DESCRIPTION_REWRITE";
        RepairProposal.Type type;
        try {
            type = RepairProposal.Type.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            type = RepairProposal.Type.DESCRIPTION_REWRITE;
        }

        String summary = node.has("summary") ? node.get("summary").asText() : "No summary";
        String rootCause = node.has("rootCause") ? node.get("rootCause").asText() : "";
        String content = node.has("content") ? node.get("content").asText() : "";
        double impactScore = node.has("impactScore") ? node.get("impactScore").asDouble(0.5) : 0.5;

        RepairProposal proposal;
        switch (type) {
            case VALIDATION_RULE:
                proposal = RepairProposal.validationRule(toolName, summary, rootCause, content);
                break;
            case FEW_SHOT_EXAMPLE:
                proposal = RepairProposal.fewShotExample(toolName, summary, rootCause, content);
                break;
            default:
                proposal = RepairProposal.descriptionRewrite(
                        toolName, summary, rootCause, content, originalDescription);
                break;
        }
        proposal.setImpactScore(impactScore);
        return proposal;
    }

    /**
     * 从 LLM 输出中提取 JSON 数组，处理可能的 markdown 代码块包裹。
     */
    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        String trimmed = text.trim();

        // 去除 markdown 代码块
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBackticks = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBackticks > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBackticks).trim();
            }
        }

        // 找到第一个 [ 和最后一个 ]
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        // fallback：尝试找 { } 作为单个对象
        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return "[" + trimmed.substring(objStart, objEnd + 1) + "]";
        }

        return "[]";
    }

    // ==================== 提案管理 ====================

    /** 获取所有提案。 */
    public List<RepairProposal> getProposals() {
        return Collections.unmodifiableList(proposals);
    }

    /** 按状态筛选提案。 */
    public List<RepairProposal> getProposalsByStatus(RepairProposal.Status status) {
        List<RepairProposal> result = new ArrayList<>();
        for (RepairProposal proposal : proposals) {
            if (proposal.getStatus() == status) {
                result.add(proposal);
            }
        }
        return result;
    }

    /** 按 ID 查找提案。 */
    public Optional<RepairProposal> findProposal(String proposalId) {
        return proposals.stream()
                .filter(p -> p.getProposalId().equals(proposalId))
                .findFirst();
    }

    /** 审批提案。 */
    public boolean approveProposal(String proposalId, String note) {
        Optional<RepairProposal> found = findProposal(proposalId);
        if (found.isPresent() && found.get().getStatus() == RepairProposal.Status.PENDING) {
            found.get().approve(note);
            return true;
        }
        return false;
    }

    /** 拒绝提案。 */
    public boolean rejectProposal(String proposalId, String note) {
        Optional<RepairProposal> found = findProposal(proposalId);
        if (found.isPresent() && found.get().getStatus() == RepairProposal.Status.PENDING) {
            found.get().reject(note);
            return true;
        }
        return false;
    }

    /** 获取统计信息。 */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProposals", proposals.size());
        stats.put("pendingCount", getProposalsByStatus(RepairProposal.Status.PENDING).size());
        stats.put("approvedCount", getProposalsByStatus(RepairProposal.Status.APPROVED).size());
        stats.put("appliedCount", getProposalsByStatus(RepairProposal.Status.APPLIED).size());
        stats.put("rejectedCount", getProposalsByStatus(RepairProposal.Status.REJECTED).size());
        stats.put("lastReflectionTime", lastReflectionTimeMs.get());
        return stats;
    }

    // ==================== 工具方法 ====================

    private boolean canReflect() {
        long cooldownMs = config.getReflectionIntervalHours() * 60 * 60 * 1000L;
        return System.currentTimeMillis() - lastReflectionTimeMs.get() >= cooldownMs;
    }

    private static String resolveModel(String defaultModel, ReflectionConfig config) {
        String reflectionModel = config.getReflectionModel();
        return (reflectionModel != null && !reflectionModel.isBlank()) ? reflectionModel : defaultModel;
    }
}
