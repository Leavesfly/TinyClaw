package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * 团队协作策略
 * 智能混合执行模式：分析任务依赖图，无依赖任务并行执行，有依赖任务串行执行。
 */
public class TeamWorkStrategy implements CollaborationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    private static final int TASK_TIMEOUT_MINUTES = 5;

    /** 公共线程池（由 AgentOrchestrator 统一管理生命周期） */
    private final ExecutorService executor;

    public TeamWorkStrategy(CollaborationExecutorPool executorPool) {
        this.executor = executorPool.getExecutor();
    }
    
    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        List<TeamTask> tasks = config.getTasks();
        
        if (tasks.isEmpty()) {
            return "没有定义团队任务";
        }
        
        logger.info("开始团队协作", Map.of(
                "topic", context.getTopic(),
                "taskCount", tasks.size(),
                "agentCount", agents.size()
        ));
        
        // 构建Agent映射（角色ID -> AgentExecutor）
        Map<String, AgentExecutor> agentMap = buildAgentMap(agents);
        
        // 构建任务映射（任务ID -> TeamTask）
        Map<String, TeamTask> taskMap = tasks.stream()
                .collect(Collectors.toMap(TeamTask::getTaskId, t -> t));
        
        // 按依赖关系分层执行
        while (hasUnfinishedTasks(tasks)) {
            List<TeamTask> executableTasks = findExecutableTasks(tasks, taskMap);
            
            if (executableTasks.isEmpty()) {
                // 存在循环依赖或依赖的任务已失败，将所有阻塞的任务标记为失败
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
            
            logger.info("本轮可执行任务", Map.of("count", executableTasks.size()));
            
            // 并行执行可执行任务
            executeTasksInParallel(executableTasks, agentMap, context);
        }
        
        // 汇总结果
        String conclusion = buildConclusion(tasks, context);
        context.setFinalConclusion(conclusion);
        
        logger.info("团队协作完成", Map.of(
                "completedTasks", tasks.stream().filter(t -> t.getStatus() == TeamTask.TaskStatus.COMPLETED).count(),
                "failedTasks", tasks.stream().filter(t -> t.getStatus() == TeamTask.TaskStatus.FAILED).count()
        ));
        
        return conclusion;
    }
    
    /**
     * 构建Agent映射
     */
    private Map<String, AgentExecutor> buildAgentMap(List<AgentExecutor> agents) {
        Map<String, AgentExecutor> map = new HashMap<>();
        for (AgentExecutor agent : agents) {
            map.put(agent.getRole().getRoleId(), agent);
            map.put(agent.getRole().getRoleName(), agent);
        }
        return map;
    }
    
    /**
     * 检查是否有未完成的任务
     */
    private boolean hasUnfinishedTasks(List<TeamTask> tasks) {
        return tasks.stream().anyMatch(t -> !t.isFinished());
    }
    
    /**
     * 找出所有可执行的任务（依赖已满足且状态为PENDING）
     */
    private List<TeamTask> findExecutableTasks(List<TeamTask> tasks, Map<String, TeamTask> taskMap) {
        return tasks.stream()
                .filter(t -> t.getStatus() == TeamTask.TaskStatus.PENDING)
                .filter(t -> areDependenciesSatisfied(t, taskMap))
                .collect(Collectors.toList());
    }
    
    /**
     * 检查任务的依赖是否已满足
     */
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
    
    /**
     * 检测待执行任务之间是否存在循环依赖
     */
    private boolean detectCyclicDependency(List<TeamTask> pendingTasks, Map<String, TeamTask> taskMap) {
        Set<String> pendingIds = pendingTasks.stream()
                .map(TeamTask::getTaskId)
                .collect(Collectors.toSet());

        // 如果某个 pending 任务的所有依赖都在 pending 集合中，说明存在循环
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

    /**
     * 并行执行任务
     */
    private void executeTasksInParallel(List<TeamTask> tasks, Map<String, AgentExecutor> agentMap, 
                                         SharedContext context) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (TeamTask task : tasks) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                executeTask(task, agentMap, context);
            }, executor);
            futures.add(future);
        }
        
        // 等待所有任务完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("任务执行超时或异常", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 执行单个任务
     */
    private void executeTask(TeamTask task, Map<String, AgentExecutor> agentMap, SharedContext context) {
        task.markStarted();
        
        AgentRole assignee = task.getAssignee();
        if (assignee == null) {
            task.markFailed("未分配执行者");
            return;
        }
        
        AgentExecutor agent = agentMap.get(assignee.getRoleId());
        if (agent == null) {
            agent = agentMap.get(assignee.getRoleName());
        }
        
        if (agent == null) {
            task.markFailed("找不到对应的Agent: " + assignee.getRoleName());
            return;
        }
        
        try {
            logger.info("执行任务", Map.of(
                    "taskId", task.getTaskId(),
                    "taskName", task.getTaskName(),
                    "assignee", agent.getRoleName()
            ));
            
            // 构建任务提示
            String taskPrompt = buildTaskPrompt(task, context);
            String result = agent.answer(taskPrompt);
            
            task.markCompleted(result);
            
            // 记录到共享上下文
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
            logger.error("任务执行失败", Map.of(
                    "taskId", task.getTaskId(),
                    "error", e.getMessage()
            ));
        }
    }
    
    /**
     * 构建任务提示
     */
    private String buildTaskPrompt(TeamTask task, SharedContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【协同目标】").append(context.getTopic()).append("\n\n");
        prompt.append("【你的任务】").append(task.getTaskName()).append("\n");
        
        if (task.getDescription() != null && !task.getDescription().isEmpty()) {
            prompt.append("【任务描述】").append(task.getDescription()).append("\n");
        }
        
        // 如果有依赖任务的结果，添加上下文
        if (task.hasDependencies() && !context.getHistory().isEmpty()) {
            prompt.append("\n【相关上下文】\n");
            prompt.append(context.buildHistoryText());
        }
        
        prompt.append("\n请完成你的任务并给出详细结果。");
        
        return prompt.toString();
    }
    
    /**
     * 构建最终结论
     */
    private String buildConclusion(List<TeamTask> tasks, SharedContext context) {
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
    
    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // 团队协作不使用轮次控制，依靠任务完成状态判断
        return false;
    }

    @Override
    public String getName() {
        return "TeamWork";
    }

    @Override
    public String getDescription() {
        return "团队协作策略：智能混合执行模式，分析任务依赖图，无依赖任务并行执行";
    }
}
