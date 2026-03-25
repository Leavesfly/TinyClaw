package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.*;
import java.util.concurrent.*;

/**
 * 分层决策策略（层级汇报型）
 * 多层金字塔式决策结构，底层 Agent 并行分析，逐层汇报，顶层决策。
 */
public class HierarchyStrategy implements CollaborationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    private static final int LEVEL_TIMEOUT_MINUTES = 5;

    /** 公共线程池（由 AgentOrchestrator 统一管理生命周期） */
    private final ExecutorService executor;

    /** 执行上下文（用于创建层级 Agent） */
    private final ExecutionContext executionContext;

    public HierarchyStrategy(ExecutionContext executionContext, CollaborationExecutorPool executorPool) {
        this.executionContext = executionContext;
        this.executor = executorPool.getExecutor();
    }

    @Override
    public String execute(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        HierarchyConfig hierarchy = config.getHierarchy();

        if (hierarchy == null || !hierarchy.isValid()) {
            return "未配置分层决策层级";
        }

        int levelCount = hierarchy.getLevelCount();

        logger.info("开始分层决策", Map.of(
                "topic", context.getTopic(),
                "levels", levelCount
        ));

        // 存储每层的结果（roleName -> 该角色的输出）
        Map<Integer, Map<String, String>> levelResults = new HashMap<>();

        // 从底层（index=0）开始，逐层执行
        for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
            List<AgentRole> levelRoles = hierarchy.getLevelAgents(levelIndex);

            logger.info("执行层级", Map.of("level", levelIndex, "agents", levelRoles.size()));

            List<RoleAgent> levelAgents = createLevelAgents(levelRoles);
            Map<String, String> lowerResults = levelIndex > 0 ? levelResults.get(levelIndex - 1) : null;
            String aggregationPrompt = hierarchy.getAggregationPrompt(levelIndex);

            Map<String, String> results = executeLevelInParallel(
                    levelAgents, context, levelIndex, aggregationPrompt, lowerResults);

            levelResults.put(levelIndex, results);

            // 将该层结果写入共享上下文，供流式输出和后续层参考
            for (Map.Entry<String, String> entry : results.entrySet()) {
                context.addMessage("level-" + levelIndex, entry.getKey(), entry.getValue());
            }
        }

        Map<String, String> topResults = levelResults.get(levelCount - 1);
        String conclusion = (topResults != null && !topResults.isEmpty())
                ? buildConclusion(context, levelResults, levelCount)
                : "分层决策未能产生有效结论";

        context.setFinalConclusion(conclusion);

        logger.info("分层决策完成", Map.of(
                "levels", levelCount,
                "totalMessages", context.getHistory().size()
        ));

        return conclusion;
    }

    /**
     * 为指定层的角色列表创建 RoleAgent
     */
    private List<RoleAgent> createLevelAgents(List<AgentRole> roles) {
        List<RoleAgent> agents = new ArrayList<>();
        for (AgentRole role : roles) {
            // 使用 ExecutionContext 的工厂方法统一创建 RoleAgent
            agents.add(executionContext.createAgentExecutor(role));
        }
        return agents;
    }

    /**
     * 同层 Agent 并行执行，返回 roleName -> 输出 的映射
     */
    private Map<String, String> executeLevelInParallel(List<RoleAgent> agents,
                                                        SharedContext context,
                                                        int levelIndex,
                                                        String aggregationPrompt,
                                                        Map<String, String> lowerResults) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (RoleAgent agent : agents) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                String result = executeAgent(agent, context, levelIndex, aggregationPrompt, lowerResults);
                results.put(agent.getRoleName(), result);
            }, executor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(LEVEL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("层级执行超时或异常", Map.of("level", levelIndex, "error", e.getMessage()));
        }

        return results;
    }

    /**
     * 执行单个 Agent，根据所在层级构建不同的提示词
     */
    private String executeAgent(RoleAgent agent, SharedContext context,
                                int levelIndex, String aggregationPrompt,
                                Map<String, String> lowerResults) {
        try {
            String prompt = buildPrompt(levelIndex, aggregationPrompt, lowerResults);
            String result = speakWithStream(agent, context, prompt);

            logger.info("Agent执行完成", Map.of(
                    "agent", agent.getRoleName(),
                    "level", levelIndex,
                    "resultLength", result.length()
            ));

            return result;
        } catch (Exception e) {
            logger.error("Agent执行失败", Map.of(
                    "agent", agent.getRoleName(),
                    "error", e.getMessage()
            ));
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * 构建 Agent 提示词：底层直接分析，非底层汇总下层结果
     */
    private String buildPrompt(int levelIndex, String aggregationPrompt,
                                Map<String, String> lowerResults) {
        if (levelIndex == 0) {
            return "请从你的专业角度分析以下问题，给出详细的评估和建议。";
        }

        StringBuilder prompt = new StringBuilder();
        String header = (aggregationPrompt != null && !aggregationPrompt.isBlank())
                ? aggregationPrompt
                : "请综合以下各方评估，给出汇总分析和决策建议。";
        prompt.append(header).append("\n\n");

        if (lowerResults != null && !lowerResults.isEmpty()) {
            prompt.append("=== 下级评估报告 ===\n\n");
            for (Map.Entry<String, String> entry : lowerResults.entrySet()) {
                prompt.append("【").append(entry.getKey()).append("的评估】\n");
                prompt.append(entry.getValue()).append("\n\n");
            }
        }

        return prompt.toString();
    }

    /**
     * 构建最终结论，从底层到顶层展示各层结果
     */
    private String buildConclusion(SharedContext context,
                                    Map<Integer, Map<String, String>> levelResults,
                                    int levelCount) {
        StringBuilder conclusion = new StringBuilder();
        conclusion.append("=== 分层决策结果 ===\n\n");
        conclusion.append("议题：").append(context.getTopic()).append("\n");
        conclusion.append("层级数：").append(levelCount).append("\n\n");

        for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
            Map<String, String> results = levelResults.get(levelIndex);
            if (results == null || results.isEmpty()) {
                continue;
            }

            String levelLabel = levelIndex == 0 ? "分析层"
                    : (levelIndex == levelCount - 1 ? "决策层" : "汇总层" + levelIndex);
            conclusion.append("### ").append(levelLabel).append("\n\n");

            for (Map.Entry<String, String> entry : results.entrySet()) {
                conclusion.append("**").append(entry.getKey()).append("**\n");
                conclusion.append(entry.getValue()).append("\n\n");
            }
        }

        // 顶层第一个 Agent 的输出作为最终决策摘要
        Map<String, String> topResults = levelResults.get(levelCount - 1);
        if (topResults != null && !topResults.isEmpty()) {
            conclusion.append("---\n### 最终决策\n\n");
            conclusion.append(topResults.values().iterator().next());
        }

        return conclusion.toString();
    }

    // -------------------------------------------------------------------------
    // 流式发言辅助方法
    // -------------------------------------------------------------------------

    /**
     * 根据 SharedContext 是否持有流式回调，选择流式或非流式发言。
     */
    private String speakWithStream(RoleAgent speaker, SharedContext context, String prompt) {
        LLMProvider.EnhancedStreamCallback callback = context.getStreamCallback();
        if (callback != null) {
            return speaker.speakStream(context, prompt, callback);
        }
        return speaker.speak(context, prompt);
    }

    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // 分层决策按层级顺序执行，不使用轮次控制
        return false;
    }

    @Override
    public String getName() {
        return "Hierarchy";
    }

    @Override
    public String getDescription() {
        return "分层决策策略：金字塔式层级结构，底层并行分析，逐层汇报，顶层决策";
    }
}