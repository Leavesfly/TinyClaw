package io.leavesfly.tinyclaw.collaboration.strategy;

import io.leavesfly.tinyclaw.collaboration.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 统一任务策略
 * <p>合并了原 TeamWorkStrategy 和 HierarchyStrategy，通过 {@link CollaborationConfig.TasksStyle} 区分：
 * <ul>
 *   <li>PARALLEL（默认）— 扁平并行：分析任务依赖图，无依赖任务并行执行，有依赖任务串行执行</li>
 *   <li>HIERARCHY — 层级汇报：金字塔式决策结构，底层 Agent 并行分析，逐层汇报，顶层决策</li>
 * </ul>
 */
public class TasksStrategy extends AbstractCollaborationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    private static final int TASK_TIMEOUT_MINUTES = 5;
    private static final int LEVEL_TIMEOUT_MINUTES = 5;

    /** 公共线程池（由 AgentOrchestrator 统一管理生命周期） */
    private final ExecutorService executor;

    /** 执行上下文（层级模式需要用来创建 Agent，可为 null） */
    private ExecutionContext executionContext;

    public TasksStrategy(CollaborationExecutorPool executorPool) {
        this.executor = executorPool.getExecutor();
    }

    /**
     * 注入执行上下文（层级模式需要）
     */
    public void setExecutionContext(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    @Override
    public String execute(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        if (isHierarchyStyle(config)) {
            return executeHierarchy(context, config);
        }
        return executeParallel(context, agents, config);
    }

    private boolean isHierarchyStyle(CollaborationConfig config) {
        return config.getTasksStyle() == CollaborationConfig.TasksStyle.HIERARCHY;
    }

    // =========================================================================
    // PARALLEL 模式（原 TeamWorkStrategy）
    // =========================================================================

    private String executeParallel(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        List<TeamTask> tasks = config.getTasks();

        if (tasks.isEmpty()) {
            return "没有定义团队任务";
        }

        logger.info("开始并行任务执行", Map.of(
                "topic", context.getTopic(),
                "taskCount", tasks.size(),
                "agentCount", agents.size()
        ));

        Map<String, RoleAgent> agentMap = buildAgentMap(agents);
        Map<String, TeamTask> taskMap = tasks.stream()
                .collect(Collectors.toMap(TeamTask::getTaskId, t -> t));

        while (hasUnfinishedTasks(tasks)) {
            List<TeamTask> executableTasks = findExecutableTasks(tasks, taskMap);

            if (executableTasks.isEmpty()) {
                List<TeamTask> blockedTasks = tasks.stream()
                        .filter(t -> t.getStatus() == TeamTask.TaskStatus.PENDING)
                        .collect(Collectors.toList());

                List<String> blockedTaskIds = blockedTasks.stream()
                        .map(TeamTask::getTaskId)
                        .collect(Collectors.toList());

                String failureReason = detectCyclicDependency(blockedTasks, taskMap)
                        ? "循环依赖导致无法执行，涉及任务: " + blockedTaskIds
                        : "前置依赖任务失败导致无法执行";

                for (TeamTask blocked : blockedTasks) {
                    blocked.markFailed(failureReason);
                }

                logger.error("任务调度阻塞", Map.of(
                        "blockedTasks", blockedTaskIds,
                        "reason", failureReason
                ));
                break;
            }

            executeTasksInParallel(executableTasks, agentMap, context);
        }

        String conclusion = buildParallelConclusion(tasks, context);
        context.setFinalConclusion(conclusion);

        logger.info("并行任务执行完成", Map.of(
                "completedTasks", tasks.stream().filter(t -> t.getStatus() == TeamTask.TaskStatus.COMPLETED).count(),
                "failedTasks", tasks.stream().filter(t -> t.getStatus() == TeamTask.TaskStatus.FAILED).count()
        ));

        return conclusion;
    }

    private Map<String, RoleAgent> buildAgentMap(List<RoleAgent> agents) {
        Map<String, RoleAgent> map = new HashMap<>();
        for (RoleAgent agent : agents) {
            map.put(agent.getRole().getRoleId(), agent);
            map.put(agent.getRole().getRoleName(), agent);
        }
        return map;
    }

    private boolean hasUnfinishedTasks(List<TeamTask> tasks) {
        return tasks.stream().anyMatch(t -> !t.isFinished());
    }

    private List<TeamTask> findExecutableTasks(List<TeamTask> tasks, Map<String, TeamTask> taskMap) {
        return tasks.stream()
                .filter(t -> t.getStatus() == TeamTask.TaskStatus.PENDING)
                .filter(t -> areDependenciesSatisfied(t, taskMap))
                .collect(Collectors.toList());
    }

    private boolean areDependenciesSatisfied(TeamTask task, Map<String, TeamTask> taskMap) {
        if (!task.hasDependencies()) {
            return true;
        }
        for (String depId : task.getDependsOn()) {
            TeamTask depTask = taskMap.get(depId);
            if (depTask == null || depTask.getStatus() != TeamTask.TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private boolean detectCyclicDependency(List<TeamTask> pendingTasks, Map<String, TeamTask> taskMap) {
        Set<String> pendingIds = pendingTasks.stream()
                .map(TeamTask::getTaskId)
                .collect(Collectors.toSet());

        for (TeamTask task : pendingTasks) {
            if (task.hasDependencies()) {
                boolean allDepsArePending = task.getDependsOn().stream()
                        .allMatch(depId -> {
                            TeamTask dep = taskMap.get(depId);
                            return dep != null && pendingIds.contains(dep.getTaskId());
                        });
                if (allDepsArePending) {
                    return true;
                }
            }
        }
        return false;
    }

    private void executeTasksInParallel(List<TeamTask> tasks, Map<String, RoleAgent> agentMap,
                                        SharedContext context) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (TeamTask task : tasks) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                executeTask(task, agentMap, context);
            }, executor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("任务执行超时或异常", Map.of("error", e.getMessage()));
        }
    }

    private void executeTask(TeamTask task, Map<String, RoleAgent> agentMap, SharedContext context) {
        task.markStarted();

        AgentRole assignee = task.getAssignee();
        if (assignee == null) {
            task.markFailed("未分配执行者");
            return;
        }

        RoleAgent agent = agentMap.get(assignee.getRoleId());
        if (agent == null) {
            agent = agentMap.get(assignee.getRoleName());
        }
        if (agent == null) {
            task.markFailed("找不到对应的Agent: " + assignee.getRoleName());
            return;
        }

        try {
            String taskPrompt = buildTaskPrompt(task, context);
            String result = agent.answer(taskPrompt);

            task.markCompleted(result);

            synchronized (context) {
                context.addMessage(agent.getAgentId(), agent.getRoleName(),
                        "【任务: " + task.getTaskName() + "】\n" + result);
            }

            logger.info("任务完成", Map.of(
                    "taskId", task.getTaskId(),
                    "executionTime", task.getExecutionTime()
            ));
        } catch (Exception e) {
            task.markFailed(e.getMessage());
            logger.error("任务执行失败", Map.of("taskId", task.getTaskId(), "error", e.getMessage()));
        }
    }

    private String buildTaskPrompt(TeamTask task, SharedContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【协同目标】").append(context.getTopic()).append("\n\n");
        prompt.append("【你的任务】").append(task.getTaskName()).append("\n");

        if (task.getDescription() != null && !task.getDescription().isEmpty()) {
            prompt.append("【任务描述】").append(task.getDescription()).append("\n");
        }

        if (task.hasDependencies() && !context.getHistory().isEmpty()) {
            prompt.append("\n【相关上下文】\n");
            prompt.append(context.buildHistoryText());
        }

        prompt.append("\n请完成你的任务并给出详细结果。");
        return prompt.toString();
    }

    private String buildParallelConclusion(List<TeamTask> tasks, SharedContext context) {
        StringBuilder conclusion = new StringBuilder();
        conclusion.append("=== 团队协作结果汇总 ===\n\n");
        conclusion.append("目标：").append(context.getTopic()).append("\n\n");

        int completed = 0;
        int failed = 0;

        for (TeamTask task : tasks) {
            conclusion.append("【").append(task.getTaskName()).append("】");
            if (task.getStatus() == TeamTask.TaskStatus.COMPLETED) {
                conclusion.append(" ✓ 完成\n");
                conclusion.append(task.getResult()).append("\n\n");
                completed++;
            } else if (task.getStatus() == TeamTask.TaskStatus.FAILED) {
                conclusion.append(" ✗ 失败: ").append(task.getResult()).append("\n\n");
                failed++;
            } else {
                conclusion.append(" ○ 未执行\n\n");
            }
        }

        conclusion.append("---\n");
        conclusion.append("完成: ").append(completed).append(" / 失败: ").append(failed);
        conclusion.append(" / 总计: ").append(tasks.size());
        return conclusion.toString();
    }

    // =========================================================================
    // HIERARCHY 模式（原 HierarchyStrategy）
    // =========================================================================

    private String executeHierarchy(SharedContext context, CollaborationConfig config) {
        HierarchyConfig hierarchy = config.getHierarchy();

        if (hierarchy == null || !hierarchy.isValid()) {
            return "未配置分层决策层级";
        }

        int levelCount = hierarchy.getLevelCount();

        logger.info("开始分层决策", Map.of(
                "topic", context.getTopic(),
                "levels", levelCount
        ));

        Map<Integer, Map<String, String>> levelResults = new HashMap<>();

        for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
            List<AgentRole> levelRoles = hierarchy.getLevelAgents(levelIndex);

            logger.info("执行层级", Map.of("level", levelIndex, "agents", levelRoles.size()));

            List<RoleAgent> levelAgents = createLevelAgents(levelRoles);
            Map<String, String> lowerResults = levelIndex > 0 ? levelResults.get(levelIndex - 1) : null;
            String aggregationPrompt = hierarchy.getAggregationPrompt(levelIndex);

            Map<String, String> results = executeLevelInParallel(
                    levelAgents, context, levelIndex, aggregationPrompt, lowerResults);

            levelResults.put(levelIndex, results);

            for (Map.Entry<String, String> entry : results.entrySet()) {
                context.addMessage("level-" + levelIndex, entry.getKey(), entry.getValue());
            }
        }

        Map<String, String> topResults = levelResults.get(levelCount - 1);
        String conclusion = (topResults != null && !topResults.isEmpty())
                ? buildHierarchyConclusion(context, levelResults, levelCount)
                : "分层决策未能产生有效结论";

        context.setFinalConclusion(conclusion);

        logger.info("分层决策完成", Map.of(
                "levels", levelCount,
                "totalMessages", context.getHistory().size()
        ));

        return conclusion;
    }

    private List<RoleAgent> createLevelAgents(List<AgentRole> roles) {
        List<RoleAgent> agents = new ArrayList<>();
        for (AgentRole role : roles) {
            agents.add(executionContext.createAgentExecutor(role));
        }
        return agents;
    }

    private Map<String, String> executeLevelInParallel(List<RoleAgent> agents,
                                                       SharedContext context,
                                                       int levelIndex,
                                                       String aggregationPrompt,
                                                       Map<String, String> lowerResults) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (RoleAgent agent : agents) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                String result = executeLevelAgent(agent, context, levelIndex, aggregationPrompt, lowerResults);
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

    private String executeLevelAgent(RoleAgent agent, SharedContext context,
                                     int levelIndex, String aggregationPrompt,
                                     Map<String, String> lowerResults) {
        try {
            String prompt = buildLevelPrompt(levelIndex, aggregationPrompt, lowerResults);
            String result = speakWithStream(agent, context, prompt);

            logger.info("Agent执行完成", Map.of(
                    "agent", agent.getRoleName(),
                    "level", levelIndex,
                    "resultLength", result.length()
            ));

            return result;
        } catch (Exception e) {
            logger.error("Agent执行失败", Map.of("agent", agent.getRoleName(), "error", e.getMessage()));
            return "执行失败: " + e.getMessage();
        }
    }

    private String buildLevelPrompt(int levelIndex, String aggregationPrompt,
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

    private String buildHierarchyConclusion(SharedContext context,
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

        Map<String, String> topResults = levelResults.get(levelCount - 1);
        if (topResults != null && !topResults.isEmpty()) {
            conclusion.append("---\n### 最终决策\n\n");
            conclusion.append(topResults.values().iterator().next());
        }

        return conclusion.toString();
    }

    // =========================================================================
    // CollaborationStrategy 接口实现
    // =========================================================================

    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        return false;
    }

    @Override
    public String getName() {
        return "Tasks";
    }

    @Override
    public String getDescription() {
        return "统一任务策略：支持扁平并行执行和层级汇报决策两种任务执行风格";
    }
}
