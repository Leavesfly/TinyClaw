
package io.leavesfly.tinyclaw.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.evolution.EvaluationFeedback;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMException;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.util.JsonFileStore;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 记忆进化引擎（autoDream），驱动记忆系统的自动维护和自我进化。
 *
 * 三阶段进化流程：
 * 1. Gather：收集 MEMORIES.json 中未整合的结构化记忆条目
 * 2. Consolidate：调用 LLM 整合记忆到主题文件 + 去重压缩结构化记忆
 * 3. Prune & Index：衰减归档低分记忆 + 重建 MEMORY.md 索引
 *
 * 触发条件：
 * - 距上次 autoDream ≥ 24h 且累计 ≥ 5 个新会话（新增记忆条目）
 * - 衰减归档（纯计算）每次心跳都执行
 */
public class MemoryEvolver {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("memory.evolver");

    /** 触发整合的记忆条目数量阈值（按归属域分别计算） */
    private static final int CONSOLIDATION_THRESHOLD = 50;

    /** 归属域参与整合所需的最少条目数，避免为单条记忆烧掉一次 LLM 调用 */
    private static final int MIN_ENTRIES_PER_SCOPE = 3;

    /** 单轮进化最多处理的归属域个数，限制用户数增长时的 LLM 调用量 */
    private static final int MAX_SCOPES_PER_CYCLE = 5;

    /** 归档的综合得分阈值 */
    private static final double ARCHIVE_SCORE_THRESHOLD = 0.10;

    /** 活跃记忆的最大条目数 */
    private static final int MAX_ACTIVE_ENTRIES = 200;

    /** LLM 整合的最大 token 数（需容纳覆盖式输出的完整主题内容） */
    private static final int EVOLUTION_MAX_TOKENS = 4096;

    /** LLM 整合的温度参数 */
    private static final double EVOLUTION_TEMPERATURE = 0.3;

    /** autoDream 的最小冷却间隔（毫秒），默认 24 小时 */
    private static final long EVOLUTION_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    /** 触发 autoDream 所需的最小新增记忆条目数 */
    private static final int MIN_NEW_ENTRIES_FOR_EVOLUTION = 5;

    /** 整合 LLM 调用应对瞬时网络故障的最大尝试次数 */
    private static final int CONSOLIDATION_MAX_ATTEMPTS = 3;

    /** 整合 LLM 调用重试的基础退避间隔（毫秒），实际间隔按尝试次数线性放大 */
    private static final long CONSOLIDATION_RETRY_BACKOFF_MS = 2000L;

    /** 单个已有主题注入整合提示词的最大字符数，防止历史膨胀的主题撑爆 prompt */
    private static final int MAX_TOPIC_PROMPT_CHARS = 4000;

    /** 进化状态持久化文件名（位于 memory 目录下） */
    private static final String STATE_FILE = "evolution-state.json";

    private final MemoryStore memoryStore;
    private final LLMProvider provider;
    private final String model;
    private final String stateFilePath;
    private final ObjectMapper stateMapper = new ObjectMapper();

    /** 上次执行 autoDream 的时间戳 */
    private final AtomicLong lastEvolutionTimeMs = new AtomicLong(0);

    /** 上次进化时的记忆条目数量，用于增量检测 */
    private final AtomicInteger entryCountAtLastEvolution = new AtomicInteger(0);

    public MemoryEvolver(MemoryStore memoryStore, LLMProvider provider, String model) {
        this.memoryStore = memoryStore;
        this.provider = provider;
        this.model = model;
        this.stateFilePath = Paths.get(memoryStore.getMemoryDir(), STATE_FILE).toString();
        loadState();
    }

    /**
     * 执行记忆进化周期。
     *
     * - Prune & Index（纯计算）：每次心跳都执行
     * - Gather + Consolidate（调用 LLM）：≥24h 且 ≥5 条新增记忆时才执行
     */
    public void evolve() {
        long now = System.currentTimeMillis();
        long lastTime = lastEvolutionTimeMs.get();
        boolean cooldownExpired = (now - lastTime) >= EVOLUTION_COOLDOWN_MS;
        int currentEntryCount = memoryStore.getEntries().size();
        int newEntryCount = currentEntryCount - entryCountAtLastEvolution.get();
        boolean hasEnoughNewEntries = newEntryCount >= MIN_NEW_ENTRIES_FOR_EVOLUTION;

        boolean shouldRunFullEvolution = cooldownExpired && hasEnoughNewEntries;

        if (shouldRunFullEvolution) {
            logger.info("Starting autoDream cycle (gather + consolidate + prune)",
                    Map.of("new_entries", newEntryCount));
        } else {
            logger.debug("Skipping autoDream consolidation phase",
                    Map.of("cooldown_expired", cooldownExpired,
                            "new_entries", newEntryCount,
                            "hours_since_last", (now - lastTime) / 3600000.0));
        }

        // Phase 1+2: Gather + Consolidate（调用 LLM，受冷却保护）
        if (shouldRunFullEvolution) {
            boolean consolidated = false;
            try {
                consolidated = gatherAndConsolidate();
            } catch (Exception e) {
                logEvolutionFailure("Gather and consolidate phase failed", e);
            }

            // 仅在整合成功时推进冷却计时。瞬时网络故障（如 SocketTimeoutException）不应
            // 烧掉 24h 冷却窗口，否则一次网络抖动会导致记忆整合被跳过一整天。失败时保持
            // 计时不变，下一次心跳（默认 30 分钟后）会再次尝试。
            if (consolidated) {
                lastEvolutionTimeMs.set(System.currentTimeMillis());
                entryCountAtLastEvolution.set(memoryStore.getEntries().size());
                saveState();
            } else {
                logger.warn("Consolidation did not complete; keeping cooldown open for retry on next heartbeat");
            }
        }

        // Phase 3: Prune & Index（纯计算，每次心跳都执行）
        try {
            pruneAndIndex();
        } catch (Exception e) {
            logEvolutionFailure("Prune and index phase failed", e);
        }

        logger.info("Memory evolution cycle completed",
                Map.of("full_evolution", shouldRunFullEvolution,
                        "stats", memoryStore.getStats()));
    }

    // ==================== Phase 1+2: Gather + Consolidate ====================

    /**
     * 收集结构化记忆并调用 LLM 整合。
     *
     * <p>整合按归属域分组独立进行。若把所有域的记忆一起交给 LLM，不同用户、不同聊天
     * 的内容会被合入同一批主题文件，条目级的归属隔离会在主题层被绕过。</p>
     *
     * <p>整合任务（在单个域内）：</p>
     * <ul>
     *   <li>将相关记忆归类到该域的主题文件</li>
     *   <li>合并重复记忆、解决矛盾、压缩冗余</li>
     *   <li>生成或更新主题文件内容</li>
     * </ul>
     *
     * @return 本轮处理的所有域是否均成功（任一域失败则不推进冷却计时）
     */
    private boolean gatherAndConsolidate() {
        List<MemoryEntry> allEntries = memoryStore.getEntries();
        if (allEntries.isEmpty()) {
            logger.debug("No entries to consolidate");
            return true;
        }

        Map<String, List<MemoryEntry>> byScope = allEntries.stream()
                .collect(Collectors.groupingBy(
                        entry -> MemoryScope.normalize(entry.getScope()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // 按条目数降序优先处理堆积最多的域，单轮设上限；未轮到的域在后续周期处理
        List<Map.Entry<String, List<MemoryEntry>>> targets = byScope.entrySet().stream()
                .filter(group -> group.getValue().size() >= MIN_ENTRIES_PER_SCOPE)
                .sorted(Comparator.<Map.Entry<String, List<MemoryEntry>>>comparingInt(
                        group -> group.getValue().size()).reversed())
                .limit(MAX_SCOPES_PER_CYCLE)
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            logger.debug("No scope reached the minimum entry count for consolidation",
                    Map.of("scopes", byScope.size()));
            return true;
        }

        boolean allSucceeded = true;
        for (Map.Entry<String, List<MemoryEntry>> group : targets) {
            if (!consolidateScope(group.getKey(), group.getValue())) {
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }

    /**
     * 整合单个归属域内的记忆，主题文件与整合结果均保留在同一域内。
     *
     * @param scope        归属域
     * @param scopeEntries 该域下的记忆快照
     * @return 整合是否完成（LLM 调用失败返回 false）
     */
    private boolean consolidateScope(String scope, List<MemoryEntry> scopeEntries) {
        boolean needsConsolidation = scopeEntries.size() >= CONSOLIDATION_THRESHOLD;
        String prompt = buildConsolidatePrompt(scope, scopeEntries, needsConsolidation);

        String result;
        try {
            result = callConsolidationLLM(prompt);
        } catch (Exception e) {
            logEvolutionFailure("Failed to consolidate memories for scope " + scope, e);
            return false;
        }

        if (StringUtils.isBlank(result)) {
            return true;
        }

        // 解析主题文件输出，写入当前域的主题目录
        parseAndWriteTopics(scope, result);

        // 如果需要整合，解析整合后的记忆列表
        if (needsConsolidation) {
            List<MemoryEntry> consolidated =
                    parseMemoryLines(result, "MEMORY", "evolution_consolidate", scope);
            if (!consolidated.isEmpty() && consolidated.size() < scopeEntries.size()) {
                int maxAccessCount = scopeEntries.stream()
                        .mapToInt(MemoryEntry::getAccessCount)
                        .max().orElse(0);
                for (MemoryEntry entry : consolidated) {
                    entry.setAccessCount(Math.max(1, maxAccessCount / 2));
                }
                // diff 式替换：仅移除本域快照里的条目，LLM 调用期间新增的记忆与其他域不受影响
                Set<String> snapshotIds = scopeEntries.stream()
                        .map(MemoryEntry::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                memoryStore.replaceEntries(snapshotIds, consolidated);
                logger.info("Memories consolidated", Map.of(
                        "scope", scope,
                        "before", scopeEntries.size(),
                        "after", consolidated.size()));
            }
        }
        return true;
    }

    /**
     * 调用 LLM 执行记忆整合，对瞬时网络故障（读超时、连接超时等）进行有限次退避重试。
     *
     * <p>记忆整合是后台任务，单次瞬时 {@link SocketTimeoutException} 不应直接判定失败。
     * 仅对可恢复的网络异常重试；其他错误（如鉴权失败、参数错误）立即抛出，避免无谓等待。</p>
     *
     * @param prompt 整合提示词
     * @return LLM 返回的文本内容
     * @throws InterruptedException 线程在退避等待期间被中断
     */
    private String callConsolidationLLM(String prompt) throws InterruptedException {
        List<Message> messages = List.of(Message.user(prompt));
        Map<String, Object> options = Map.of(
                "max_tokens", EVOLUTION_MAX_TOKENS,
                "temperature", EVOLUTION_TEMPERATURE
        );

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= CONSOLIDATION_MAX_ATTEMPTS; attempt++) {
            try {
                LLMResponse response = provider.chat(messages, null, model, options);
                return response.getContent();
            } catch (RuntimeException e) {
                lastError = e;
                boolean canRetry = isTransientNetworkError(e) && attempt < CONSOLIDATION_MAX_ATTEMPTS;
                if (!canRetry) {
                    throw e;
                }
                long backoffMs = CONSOLIDATION_RETRY_BACKOFF_MS * attempt;
                logger.warn("Consolidation LLM call timed out, retrying", Map.of(
                        "attempt", attempt,
                        "max_attempts", CONSOLIDATION_MAX_ATTEMPTS,
                        "backoff_ms", backoffMs,
                        "root_cause", LLMException.rootCauseMessage(e)));
                Thread.sleep(backoffMs);
            }
        }
        // 循环内必然 return 或 throw，此处仅为满足编译
        throw lastError;
    }

    /**
     * 判断异常链中是否包含可恢复的瞬时网络故障（读/连接超时、被中断的 IO 等）。
     */
    private static boolean isTransientNetworkError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof InterruptedIOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 记录记忆进化阶段失败的详细日志，包含异常类型、根因及完整堆栈。
     * 原日志仅含外层包装异常消息（如 LLMException 的 "执行请求失败"），会丢失底层网络根因。
     */
    private void logEvolutionFailure(String message, Exception e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("error", e.getMessage());
        fields.put("error_type", e.getClass().getName());
        fields.put("root_cause", LLMException.rootCauseMessage(e));
        // 传入异常对象以输出完整调用堆栈
        logger.error(message, fields, e);
    }
    
    /**
     * 构建整合提示词。仅注入当前归属域的记忆与主题，不跟其他域的内容混在一起。
     */
    private String buildConsolidatePrompt(String scope, List<MemoryEntry> currentEntries,
                                          boolean needsConsolidation) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个记忆管理系统。请分析以下结构化记忆，完成指定任务。\n\n");

        // 任务一：整理主题文件（覆盖式）
        prompt.append("## 任务一：整理主题文件\n\n");
        prompt.append("将下方记忆与已有主题内容合并整理，为每个需要更新的主题输出完整的最新版本。\n");
        prompt.append("注意：输出内容将直接覆盖原主题文件，因此必须包含该主题需要保留的全部要点。\n");
        prompt.append("每个主题按以下格式输出：\n");
        prompt.append("TOPIC|主题名称（英文短横线命名，如 user-preferences）\n");
        prompt.append("主题内容（Markdown 格式，简洁的要点列表）\n");
        prompt.append("END_TOPIC\n\n");
        prompt.append("常见主题示例：user-preferences, project-patterns, lessons-learned, key-facts\n");
        prompt.append("- 合并重复信息、解决矛盾、压缩冗余，保留最新/最准确的版本\n");
        prompt.append("- 没有新内容需要合入的主题请不要输出，未输出的主题保持原样\n");
        prompt.append("- 如果某条记忆不属于任何主题，可以跳过\n\n");

        // 任务二：整合记忆（条件触发）
        if (needsConsolidation) {
            prompt.append("## 任务二：整合结构化记忆\n\n");
            prompt.append("当前记忆数量较多（").append(currentEntries.size()).append(" 条），请同时进行整合：\n");
            prompt.append("- 合并重复项、解决矛盾信息、移除已归类到主题文件的内容\n");
            prompt.append("- 保留尚未归类的独特记忆\n");
            prompt.append("- 每条整合后的记忆按以下格式输出：MEMORY|重要性评分|标签1,标签2|内容\n");
            prompt.append("- 重要性评分：0.0 到 1.0\n\n");
        }

        // 当前记忆列表
        prompt.append("## 当前结构化记忆\n\n");
        for (int i = 0; i < currentEntries.size(); i++) {
            MemoryEntry entry = currentEntries.get(i);
            prompt.append(String.format("%d. [重要性=%.1f, 标签=%s, 来源=%s] %s\n",
                    i + 1, entry.getImportance(), entry.getTags(),
                    entry.getSource(), entry.getContent()));
        }

        // 现有主题文件全文（覆盖式更新的合并基准）
        List<String> existingTopics = memoryStore.listTopics(scope);
        if (!existingTopics.isEmpty()) {
            prompt.append("\n## 已有主题文件（完整内容，输出时请在此基础上合并更新）\n\n");
            for (String topic : existingTopics) {
                String content = memoryStore.readTopic(scope, topic);
                if (content.length() > MAX_TOPIC_PROMPT_CHARS) {
                    content = content.substring(0, MAX_TOPIC_PROMPT_CHARS) + "\n\n_(内容过长已截断，请优先压缩此主题)_";
                }
                prompt.append("### ").append(topic).append("\n\n").append(content).append("\n\n");
            }
        }

        return prompt.toString();
    }

    /**
     * 解析 LLM 输出中的主题文件块并覆盖写入指定归属域。
     *
     * <p>prompt 已携带该域已有主题全文，LLM 输出的是合并后的完整版本，因此直接覆盖
     * 而非追加，避免主题文件随每轮整合无限膨胀。未以 END_TOPIC 正常终止的块
     * （如输出被截断）会被丢弃，不会用半截内容覆盖原文件。</p>
     */
    private void parseAndWriteTopics(String scope, String llmOutput) {
        String[] lines = llmOutput.split("\n");
        String currentTopicName = null;
        StringBuilder currentTopicContent = null;
        int topicsWritten = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("TOPIC|")) {
                currentTopicName = trimmed.substring("TOPIC|".length()).trim()
                        .toLowerCase().replaceAll("[^a-z0-9\\-]", "-");
                currentTopicContent = new StringBuilder();
            } else if ("END_TOPIC".equals(trimmed) && currentTopicName != null && currentTopicContent != null) {
                String content = currentTopicContent.toString().trim();
                if (StringUtils.isNotBlank(content)) {
                    memoryStore.writeTopic(scope, currentTopicName, content);
                    topicsWritten++;
                }
                currentTopicName = null;
                currentTopicContent = null;
            } else if (currentTopicContent != null) {
                currentTopicContent.append(line).append("\n");
            }
        }

        if (topicsWritten > 0) {
            logger.info("Wrote topic files from consolidation", Map.of(
                    "scope", scope, "count", topicsWritten));
        }
    }

    /**
     * 解析 LLM 输出中指定前缀的记忆行，并将结果归属到指定域。
     * 格式：{prefix}|importance|tag1,tag2|content
     */
    private List<MemoryEntry> parseMemoryLines(String llmOutput, String prefix, String source, String scope) {
        List<MemoryEntry> entries = new ArrayList<>();
        String[] lines = llmOutput.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith(prefix + "|")) {
                continue;
            }

            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) {
                continue;
            }

            try {
                double importance = Double.parseDouble(parts[1].trim());
                List<String> tags = Arrays.stream(parts[2].trim().split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .collect(Collectors.toList());
                String content = parts[3].trim();

                if (StringUtils.isNotBlank(content)) {
                    entries.add(new MemoryEntry(scope, content, importance, tags, source));
                }
            } catch (NumberFormatException e) {
                logger.debug("Skipped malformed memory line: " + line);
            }
        }

        return entries;
    }

    // ==================== Phase 3: Prune & Index ====================

    /**
     * 衰减归档低分记忆 + 落盘访问计数 + 重建索引。
     * 纯计算操作，每次心跳都执行。
     */
    private void pruneAndIndex() {
        decayAndArchive();
        // 读路径累积的访问计数变更在此统一落盘
        memoryStore.flush();
        memoryStore.rebuildIndex();
    }

    // ==================== 进化状态持久化 ====================

    /**
     * 从 memory/evolution-state.json 加载上次进化状态。
     * 避免进程重启后冷却计时归零，导致每次重启都立即触发一轮 LLM 整合。
     */
    private void loadState() {
        Map<String, Object> state = JsonFileStore.readJson(stateMapper, Paths.get(stateFilePath),
                new TypeReference<Map<String, Object>>() {}, Map::of);
        Object lastTime = state.get("last_evolution_time_ms");
        Object lastCount = state.get("entry_count_at_last_evolution");
        if (lastTime instanceof Number time) {
            lastEvolutionTimeMs.set(time.longValue());
        }
        if (lastCount instanceof Number count) {
            entryCountAtLastEvolution.set(count.intValue());
        }
    }

    /**
     * 保存当前进化状态，在每次整合成功后调用。
     */
    private void saveState() {
        try {
            Map<String, Object> state = Map.of(
                    "last_evolution_time_ms", lastEvolutionTimeMs.get(),
                    "entry_count_at_last_evolution", entryCountAtLastEvolution.get());
            JsonFileStore.writeJson(stateMapper, Paths.get(stateFilePath), state);
        } catch (IOException e) {
            logger.warn("Failed to save evolution state: " + e.getMessage());
        }
    }

    /**
     * 对低分记忆进行衰减和归档。
     *
     * 两个归档条件（满足任一即归档）：
     * 1. 综合得分低于 ARCHIVE_SCORE_THRESHOLD
     * 2. 活跃记忆总数超过 MAX_ACTIVE_ENTRIES
     */
    private void decayAndArchive() {
        List<MemoryEntry> currentEntries = new ArrayList<>(memoryStore.getEntries());
        if (currentEntries.isEmpty()) {
            return;
        }

        currentEntries.sort(Comparator.comparingDouble(MemoryEntry::computeScore));

        Set<MemoryEntry> toArchiveSet = new LinkedHashSet<>();

        // 条件 1：得分低于阈值
        for (MemoryEntry entry : currentEntries) {
            if (entry.computeScore() < ARCHIVE_SCORE_THRESHOLD) {
                toArchiveSet.add(entry);
            }
        }

        // 条件 2：超过最大活跃数量
        int remainingAfterScoreArchive = currentEntries.size() - toArchiveSet.size();
        if (remainingAfterScoreArchive > MAX_ACTIVE_ENTRIES) {
            int excessCount = remainingAfterScoreArchive - MAX_ACTIVE_ENTRIES;
            for (MemoryEntry entry : currentEntries) {
                if (excessCount <= 0) {
                    break;
                }
                if (!toArchiveSet.contains(entry)) {
                    toArchiveSet.add(entry);
                    excessCount--;
                }
            }
        }

        if (!toArchiveSet.isEmpty()) {
            memoryStore.archiveEntries(new ArrayList<>(toArchiveSet));
            logger.info("Archived low-score memories", Map.of(
                    "archived_count", toArchiveSet.size(),
                    "remaining_count", memoryStore.getEntries().size()));
        }
    }

    // ==================== 基于评估反馈的智能进化 ====================

    /**
     * 基于评估反馈的智能记忆进化。
     *
     * 根据反馈分数采取不同策略：
     * - 高分会话（> 0.8）：提炼为高重要性记忆，学习成功模式
     * - 低分会话（< 0.3）：分析失败原因，生成避坑记忆
     *
     * <p>提炼出的内容可能引用会话原文，因此归入该会话对应的聊天域而不是全局域。</p>
     */
    public void evolveWithFeedback(EvaluationFeedback feedback) {
        if (feedback == null) {
            return;
        }

        double score = feedback.getPrimaryScore();
        String sessionKey = feedback.getSessionKey();

        logger.debug("Evolving with feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", score,
                "mode", feedback.getEvalMode()));

        if (score > 0.8) {
            extractHighValueMemories(feedback);
        } else if (score < 0.3) {
            extractLessonsLearned(feedback);
        }
    }

    private void extractHighValueMemories(EvaluationFeedback feedback) {
        String sessionKey = feedback.getSessionKey();
        String textualGradient = feedback.getTextualGradient();

        String content;
        if (StringUtils.isNotBlank(textualGradient)) {
            content = "[成功模式] " + textualGradient;
        } else {
            content = String.format("[成功会话] 会话 %s 获得高分 (%.2f)，表明当前处理方式有效",
                    sessionKey != null ? sessionKey : "unknown", feedback.getPrimaryScore());
        }

        List<String> tags = new ArrayList<>();
        tags.add("success_pattern");
        tags.add(feedback.getEvalMode() != null ? feedback.getEvalMode().name().toLowerCase() : "implicit");
        if (sessionKey != null && sessionKey.contains(":")) {
            tags.add(sessionKey.substring(0, sessionKey.indexOf(":")));
        }

        memoryStore.addEntry(MemoryScope.ofSessionKey(sessionKey), content, 0.7, tags, "evolution_feedback");
        logger.info("Extracted high-value memory from positive feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", feedback.getPrimaryScore()));
    }

    private void extractLessonsLearned(EvaluationFeedback feedback) {
        String sessionKey = feedback.getSessionKey();
        String textualGradient = feedback.getTextualGradient();

        String content;
        if (StringUtils.isNotBlank(textualGradient)) {
            content = "[避坑经验] " + textualGradient;
        } else {
            StringBuilder sb = new StringBuilder("[待改进] ");
            if (feedback.hasMetric("tool_success_rate")) {
                double toolRate = feedback.getMetric("tool_success_rate");
                if (toolRate < 0.5) {
                    sb.append("工具调用成功率低 (").append(String.format("%.0f%%", toolRate * 100)).append("); ");
                }
            }
            if (feedback.hasMetric("retry_count")) {
                double retryRatio = feedback.getMetric("retry_count");
                if (retryRatio > 0.4) {
                    sb.append("用户多次重试; ");
                }
            }
            sb.append("会话评分: ").append(String.format("%.2f", feedback.getPrimaryScore()));
            content = sb.toString();
        }

        List<String> tags = new ArrayList<>();
        tags.add("lesson_learned");
        tags.add("improvement_needed");
        if (sessionKey != null && sessionKey.contains(":")) {
            tags.add(sessionKey.substring(0, sessionKey.indexOf(":")));
        }

        memoryStore.addEntry(MemoryScope.ofSessionKey(sessionKey), content, 0.8, tags, "evolution_feedback");
        logger.info("Extracted lesson from negative feedback", Map.of(
                "session", sessionKey != null ? sessionKey : "unknown",
                "score", feedback.getPrimaryScore()));
    }
}
