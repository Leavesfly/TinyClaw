package io.leavesfly.tinyclaw.collaboration.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 工作流检查点存储 - 落在 {@code workspace/collaboration/checkpoints/} 下。
 *
 * <h2>runId 为什么由结构指纹推导</h2>
 * <p>检查点只有在重跑时能被找回才有意义，所以运行标识必须可复现。用随机 UUID 则每次重跑
 * 都是新 id，检查点永远读不到；用工作流名称则不同任务会互相串。指纹取"节点结构 + 提示词"，
 * 既能唯一区分不同任务（提示词里含具体目标），又不受运行期注入的变量影响。</p>
 *
 * <h2>刻意不做的事</h2>
 * <p>不清理过期检查点：正常完成会自行删除，残留的都是异常中断留下的，而它们恰恰是
 * 用户下次重跑要用的东西。按时间自动删会把唯一的恢复依据删掉。</p>
 */
public class WorkflowCheckpointStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("workflow");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 新增字段后回退版本不应导致整份检查点读取失败
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String CHECKPOINT_DIR = "checkpoints";

    private final Path root;

    /**
     * @param workspace 工作空间路径；为空时本存储降级为不可用（{@link #isEnabled()} 返回 false）
     */
    public WorkflowCheckpointStore(String workspace) {
        this.root = (workspace == null || workspace.isBlank())
                ? null
                : Paths.get(workspace, "collaboration", CHECKPOINT_DIR);
    }

    /** 是否具备落盘能力。workspace 缺失时工作流仍应正常执行，只是没有续跑能力 */
    public boolean isEnabled() {
        return root != null;
    }

    /**
     * 由工作流结构推导稳定的运行标识。
     *
     * <p>纳入指纹的是节点 id、类型、依赖与输入表达式，以及工作流名称和输出表达式。
     * <b>不纳入变量表</b>：contextSummary 这类运行期注入的值每轮都不同，
     * 一旦纳入就等于每次都是新 runId，续跑永远不会命中。</p>
     */
    public static String deriveRunId(WorkflowDefinition workflow) {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(workflow.getName() != null ? workflow.getName() : "").append('\u0000');
        fingerprint.append(workflow.getOutputExpression() != null
                ? workflow.getOutputExpression() : "").append('\u0000');

        List<WorkflowNode> nodes = workflow.getNodes();
        if (nodes != null) {
            // 按 id 排序：同一份工作流的节点列表顺序可能因生成过程而不同，
            // 不排序会让语义相同的定义算出不同指纹
            nodes.stream()
                    .filter(node -> node != null && node.getId() != null)
                    .sorted(java.util.Comparator.comparing(WorkflowNode::getId))
                    .forEach(node -> fingerprint
                            .append(node.getId()).append('\u0001')
                            .append(node.getType() != null ? node.getType().name() : "").append('\u0001')
                            .append(String.join(",", node.getDependsOn())).append('\u0001')
                            .append(node.getInputExpression() != null
                                    ? node.getInputExpression() : "").append('\u0000'));
        }
        return shortHash(fingerprint.toString());
    }

    /**
     * 读取检查点，不存在或无法解析时返回 null。
     *
     * <p>解析失败按"没有检查点"处理并把原文件移入 corrupt/ 保留：续跑是优化项，
     * 让一份坏掉的检查点阻断整个工作流执行是本末倒置。</p>
     */
    public WorkflowCheckpoint load(String runId) {
        if (!isEnabled()) {
            return null;
        }
        Path path = pathOf(runId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            WorkflowCheckpoint checkpoint = MAPPER.readValue(
                    Files.readString(path, StandardCharsets.UTF_8), WorkflowCheckpoint.class);
            if (checkpoint == null) {
                return null;
            }
            logger.info("载入工作流检查点", Map.of(
                    "runId", runId,
                    "completedNodes", checkpoint.completedNodeCount()));
            return checkpoint;
        } catch (Exception e) {
            Path quarantined = JsonFileStore.quarantine(path, "workflow-checkpoint-unreadable");
            logger.warn("工作流检查点无法解析，已隔离并按无检查点继续", Map.of(
                    "runId", runId,
                    "quarantined_to", quarantined != null ? quarantined.toString() : "none"));
            return null;
        }
    }

    /**
     * 写入检查点。
     *
     * <p>失败只告警不抛：检查点写不进去只影响下次能否续跑，不应当让正在成功执行的
     * 工作流因此中断。</p>
     */
    public void save(WorkflowCheckpoint checkpoint) {
        if (!isEnabled() || checkpoint == null || checkpoint.getRunId() == null) {
            return;
        }
        try {
            JsonFileStore.writeJson(MAPPER, pathOf(checkpoint.getRunId()), checkpoint);
        } catch (IOException e) {
            logger.warn("工作流检查点写入失败: " + e.getMessage());
        }
    }

    /**
     * 删除检查点，正常完成后调用。
     */
    public void delete(String runId) {
        if (!isEnabled() || runId == null) {
            return;
        }
        try {
            Files.deleteIfExists(pathOf(runId));
        } catch (IOException e) {
            logger.warn("工作流检查点删除失败: " + e.getMessage());
        }
    }

    private Path pathOf(String runId) {
        return root.resolve(runId + ".json");
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
