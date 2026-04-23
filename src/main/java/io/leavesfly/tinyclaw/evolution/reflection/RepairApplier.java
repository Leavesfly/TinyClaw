package io.leavesfly.tinyclaw.evolution.reflection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 修复提案应用器。
 *
 * <p>将已审批通过的 {@link RepairProposal} 落盘为持久化的工具增强配置，
 * 供 {@link io.leavesfly.tinyclaw.tools.ToolRegistry} 和
 * {@link io.leavesfly.tinyclaw.agent.ContextBuilder} 在运行时消费。
 *
 * <p>三种落盘方式：
 * <ol>
 *   <li><b>工具描述变体</b>（{@code tool-descriptions/}）：
 *       替换 / 增补工具的 description 和 parameters schema，
 *       ToolRegistry 在生成 ToolDefinition 时优先使用变体文件中的描述；</li>
 *   <li><b>参数校验规则</b>（{@code validation-rules/}）：
 *       JSON 文件，key=参数名，value=校验表达式，
 *       ToolRegistry.execute 前可读取并预校验参数；</li>
 *   <li><b>Few-shot 示范</b>（{@code few-shot/}）：
 *       Markdown 文件，在 ContextBuilder 构建 system prompt 时注入到工具说明段落中。</li>
 * </ol>
 *
 * <p>持久化目录：{@code {workspace}/evolution/reflection/repairs/}
 */
public class RepairApplier {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("reflection.applier");

    private static final String REPAIRS_DIR = "evolution/reflection/repairs";
    private static final String DESCRIPTIONS_DIR = "tool-descriptions";
    private static final String VALIDATIONS_DIR = "validation-rules";
    private static final String FEW_SHOT_DIR = "few-shot";
    private static final String PROPOSALS_FILE = "proposals.json";

    private final Path repairsRoot;
    private final ObjectMapper mapper;

    /** 内存缓存：工具描述变体 (toolName -> description) */
    private final Map<String, String> descriptionOverrides = new ConcurrentHashMap<>();

    /** 内存缓存：校验规则 (toolName -> { paramName -> rule }) */
    private final Map<String, Map<String, String>> validationRules = new ConcurrentHashMap<>();

    /** 内存缓存：few-shot 示范 (toolName -> example text) */
    private final Map<String, String> fewShotExamples = new ConcurrentHashMap<>();

    /** 标记初始化是否成功完成（目录创建 + 历史数据加载） */
    private volatile boolean initialized = false;

    public RepairApplier(String workspace) {
        this.repairsRoot = Paths.get(workspace, REPAIRS_DIR);
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            ensureDirectories();
            loadAll();
            this.initialized = true;
        } catch (Exception e) {
            logger.error("RepairApplier initialization failed, repairs disabled", Map.of("error", e.getMessage()));
        }
    }

    /** 初始化是否成功。若返回 false，apply/rollback 等操作将不可用。 */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 应用一个已批准的提案。
     *
     * @param proposal 已批准的提案
     * @return true 表示应用成功
     */
    public boolean apply(RepairProposal proposal) {
        if (proposal == null || proposal.getStatus() != RepairProposal.Status.APPROVED) {
            logger.warn("Cannot apply proposal: not approved or null");
            return false;
        }

        try {
            switch (proposal.getType()) {
                case DESCRIPTION_REWRITE:
                    applyDescriptionRewrite(proposal);
                    break;
                case VALIDATION_RULE:
                    applyValidationRule(proposal);
                    break;
                case FEW_SHOT_EXAMPLE:
                    applyFewShotExample(proposal);
                    break;
                default:
                    logger.warn("Unknown proposal type: " + proposal.getType());
                    return false;
            }
            proposal.markApplied();
            persistProposal(proposal);
            logger.info("Repair proposal applied", Map.of(
                    "proposalId", proposal.getProposalId(),
                    "tool", proposal.getToolName(),
                    "type", proposal.getType().name()));
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply proposal", Map.of(
                    "proposalId", proposal.getProposalId(), "error", e.getMessage()));
            return false;
        }
    }

    /**
     * 回滚一个已应用的提案。
     *
     * @param proposal 已应用的提案
     * @return true 表示回滚成功
     */
    public boolean rollback(RepairProposal proposal) {
        if (proposal == null) return false;

        try {
            String toolName = proposal.getToolName();
            switch (proposal.getType()) {
                case DESCRIPTION_REWRITE:
                    descriptionOverrides.remove(toolName);
                    Files.deleteIfExists(descriptionFilePath(toolName));
                    break;
                case VALIDATION_RULE:
                    validationRules.remove(toolName);
                    Files.deleteIfExists(validationFilePath(toolName));
                    break;
                case FEW_SHOT_EXAMPLE:
                    fewShotExamples.remove(toolName);
                    Files.deleteIfExists(fewShotFilePath(toolName));
                    break;
            }
            proposal.setStatus(RepairProposal.Status.APPROVED); // 重置状态，允许重新 apply
            logger.info("Repair proposal rolled back", Map.of(
                    "proposalId", proposal.getProposalId(), "tool", toolName));
            return true;
        } catch (Exception e) {
            logger.error("Failed to rollback proposal", Map.of(
                    "proposalId", proposal.getProposalId(), "error", e.getMessage()));
            return false;
        }
    }

    // ==================== 查询接口 ====================

    /**
     * 获取工具的描述覆写（如有）。
     *
     * @param toolName 工具名
     * @return 覆写的描述文本，无覆写时返回 null
     */
    public String getDescriptionOverride(String toolName) {
        return descriptionOverrides.get(toolName);
    }

    /**
     * 获取工具的参数校验规则（如有）。
     *
     * @param toolName 工具名
     * @return 校验规则 map（paramName -> rule），无规则时返回 null
     */
    public Map<String, String> getValidationRules(String toolName) {
        return validationRules.get(toolName);
    }

    /**
     * 获取工具的 few-shot 示范文本（如有）。
     *
     * @param toolName 工具名
     * @return few-shot 文本，无示范时返回 null
     */
    public String getFewShotExample(String toolName) {
        return fewShotExamples.get(toolName);
    }

    /**
     * 获取所有 few-shot 示范（供 ContextBuilder 使用）。
     */
    public Map<String, String> getAllFewShotExamples() {
        return Collections.unmodifiableMap(fewShotExamples);
    }

    /**
     * 检查工具是否有任何修复配置。
     */
    public boolean hasRepairsFor(String toolName) {
        return descriptionOverrides.containsKey(toolName)
                || validationRules.containsKey(toolName)
                || fewShotExamples.containsKey(toolName);
    }

    /**
     * 获取所有已应用修复的统计。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("descriptionOverrides", descriptionOverrides.size());
        stats.put("validationRules", validationRules.size());
        stats.put("fewShotExamples", fewShotExamples.size());

        Set<String> allTools = new LinkedHashSet<>();
        allTools.addAll(descriptionOverrides.keySet());
        allTools.addAll(validationRules.keySet());
        allTools.addAll(fewShotExamples.keySet());
        stats.put("toolsWithRepairs", allTools.size());
        stats.put("tools", allTools);
        return stats;
    }

    // ==================== 应用逻辑 ====================

    private void applyDescriptionRewrite(RepairProposal proposal) throws IOException {
        String toolName = proposal.getToolName();
        String content = proposal.getProposedContent();
        descriptionOverrides.put(toolName, content);
        Files.writeString(descriptionFilePath(toolName), content);
    }

    private void applyValidationRule(RepairProposal proposal) throws IOException {
        String toolName = proposal.getToolName();
        String ruleJson = proposal.getProposedContent();

        Map<String, String> rules;
        try {
            rules = mapper.readValue(ruleJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            // 如果不是标准 map 格式，存为单条规则
            rules = Map.of("_raw", ruleJson);
        }
        validationRules.put(toolName, rules);
        Files.writeString(validationFilePath(toolName), mapper.writeValueAsString(rules));
    }

    private void applyFewShotExample(RepairProposal proposal) throws IOException {
        String toolName = proposal.getToolName();
        String content = proposal.getProposedContent();
        fewShotExamples.put(toolName, content);
        Files.writeString(fewShotFilePath(toolName), content);
    }

    // ==================== 持久化 ====================

    /**
     * 持久化提案到 proposals.json（原子写入：先写临时文件再 rename）。
     */
    private void persistProposal(RepairProposal proposal) {
        try {
            Path file = repairsRoot.resolve(PROPOSALS_FILE);
            List<Map<String, Object>> existing = new ArrayList<>();
            if (Files.exists(file)) {
                String json = Files.readString(file);
                if (json != null && !json.isBlank()) {
                    existing = mapper.readValue(json, new TypeReference<>() {});
                }
            }

            // 去重（同 proposalId 替换）
            existing.removeIf(m -> proposal.getProposalId().equals(m.get("proposalId")));
            existing.add(proposal.toMap());

            // 原子写入：先写临时文件，再 rename 覆盖目标文件
            Path tmpFile = repairsRoot.resolve(PROPOSALS_FILE + ".tmp");
            Files.writeString(tmpFile, mapper.writeValueAsString(existing));
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to persist proposal", Map.of("error", e.getMessage()));
        }
    }

    private void loadAll() {
        loadDescriptions();
        loadValidations();
        loadFewShots();
    }

    private void loadDescriptions() {
        Path dir = repairsRoot.resolve(DESCRIPTIONS_DIR);
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".txt"))
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString().replace(".txt", "");
                            descriptionOverrides.put(name, Files.readString(p));
                        } catch (IOException e) {
                            logger.warn("Failed to load description override: " + p);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to list description overrides: " + e.getMessage());
        }
    }

    private void loadValidations() {
        Path dir = repairsRoot.resolve(VALIDATIONS_DIR);
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString().replace(".json", "");
                            Map<String, String> rules = mapper.readValue(
                                    Files.readString(p), new TypeReference<>() {});
                            validationRules.put(name, rules);
                        } catch (IOException e) {
                            logger.warn("Failed to load validation rules: " + p);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to list validation rules: " + e.getMessage());
        }
    }

    private void loadFewShots() {
        Path dir = repairsRoot.resolve(FEW_SHOT_DIR);
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString().replace(".md", "");
                            fewShotExamples.put(name, Files.readString(p));
                        } catch (IOException e) {
                            logger.warn("Failed to load few-shot example: " + p);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to list few-shot examples: " + e.getMessage());
        }
    }

    // ==================== 路径辅助 ====================

    private Path descriptionFilePath(String toolName) {
        return repairsRoot.resolve(DESCRIPTIONS_DIR).resolve(toolName + ".txt");
    }

    private Path validationFilePath(String toolName) {
        return repairsRoot.resolve(VALIDATIONS_DIR).resolve(toolName + ".json");
    }

    private Path fewShotFilePath(String toolName) {
        return repairsRoot.resolve(FEW_SHOT_DIR).resolve(toolName + ".md");
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(repairsRoot.resolve(DESCRIPTIONS_DIR));
            Files.createDirectories(repairsRoot.resolve(VALIDATIONS_DIR));
            Files.createDirectories(repairsRoot.resolve(FEW_SHOT_DIR));
        } catch (IOException e) {
            logger.warn("Failed to create repair directories: " + e.getMessage());
        }
    }
}
