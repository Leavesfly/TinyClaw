package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.agent.collaboration.HierarchyConfig.HierarchyLevel;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 分层决策策略（层级汇报型）
 * 多层金字塔式决策结构，底层Agent分析，逐层汇报，顶层决策
 */
public class HierarchyStrategy implements CollaborationStrategy {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");
    
    /** 线程池用于并行执行同层任务 */
    private final ExecutorService executor;
    
    /** LLM Provider用于创建层级Agent */
    private LLMProvider provider;
    
    /** 工具注册表 */
    private ToolRegistry tools;
    
    /** 工作空间路径 */
    private String workspace;
    
    /** 默认模型 */
    private String model;
    
    /** 最大迭代次数 */
    private int maxIterations;
    
    public HierarchyStrategy() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("hierarchy-pool-" + t.getId());
            return t;
        });
    }
    
    /**
     * 设置执行环境（在execute前调用）
     */
    public void setExecutionContext(LLMProvider provider, ToolRegistry tools, 
                                     String workspace, String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
    }
    
    @Override
    public String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config) {
        HierarchyConfig hierarchy = config.getHierarchy();
        
        if (hierarchy == null || hierarchy.getLevels().isEmpty()) {
            return "未配置分层决策层级";
        }
        
        int levelCount = hierarchy.getLevelCount();
        
        logger.info("开始分层决策", Map.of(
                "topic", context.getTopic(),
                "levels", levelCount
        ));
        
        // 存储每层的结果
        Map<Integer, Map<String, String>> levelResults = new HashMap<>();
        
        // 从底层开始，逐层执行
        for (int level = 0; level < levelCount; level++) {
            HierarchyLevel hierarchyLevel = hierarchy.getLevel(level);
            if (hierarchyLevel == null) {
                continue;
            }
            
            logger.info("执行层级", Map.of("level", level, "agents", hierarchyLevel.getAgents().size()));
            
            // 为该层创建Agent执行器
            List<AgentExecutor> levelAgents = createLevelAgents(hierarchyLevel.getAgents());
            
            // 获取下层结果（用于汇总）
            Map<String, String> lowerLevelResults = level > 0 ? levelResults.get(level - 1) : null;
            
            // 执行该层（同层并行）
            Map<String, String> results = executeLevelInParallel(
                    levelAgents, context, hierarchyLevel, lowerLevelResults);
            
            levelResults.put(level, results);
            
            // 记录该层结果到共享上下文
            for (Map.Entry<String, String> entry : results.entrySet()) {
                context.addMessage("level-" + level, entry.getKey(), entry.getValue());
            }
        }
        
        // 获取顶层结果作为最终结论
        Map<String, String> topResults = levelResults.get(levelCount - 1);
        String conclusion;
        if (topResults != null && !topResults.isEmpty()) {
            conclusion = buildConclusion(context, levelResults, levelCount);
        } else {
            conclusion = "分层决策未能产生有效结论";
        }
        
        context.setFinalConclusion(conclusion);
        
        logger.info("分层决策完成", Map.of(
                "levels", levelCount,
                "totalMessages", context.getHistory().size()
        ));
        
        return conclusion;
    }
    
    /**
     * 为指定层级创建Agent执行器
     */
    private List<AgentExecutor> createLevelAgents(List<AgentRole> roles) {
        List<AgentExecutor> agents = new ArrayList<>();
        for (AgentRole role : roles) {
            agents.add(new AgentExecutor(role, provider, tools, workspace, model, maxIterations));
        }
        return agents;
    }
    
    /**
     * 并行执行同层Agent
     */
    private Map<String, String> executeLevelInParallel(List<AgentExecutor> agents, 
                                                        SharedContext context,
                                                        HierarchyLevel level,
                                                        Map<String, String> lowerResults) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (AgentExecutor agent : agents) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                String result = executeAgent(agent, context, level, lowerResults);
                results.put(agent.getRoleName(), result);
            }, executor);
            futures.add(future);
        }
        
        // 等待所有Agent完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("层级执行超时或异常", Map.of("error", e.getMessage()));
        }
        
        return results;
    }
    
    /**
     * 执行单个Agent
     */
    private String executeAgent(AgentExecutor agent, SharedContext context, 
                                 HierarchyLevel level, Map<String, String> lowerResults) {
        try {
            String prompt = buildPrompt(context, level, lowerResults);
            String result = agent.speak(context, prompt);
            
            logger.info("Agent执行完成", Map.of(
                    "agent", agent.getRoleName(),
                    "level", level.getLevel(),
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
     * 构建Agent提示词
     */
    private String buildPrompt(SharedContext context, HierarchyLevel level, 
                                Map<String, String> lowerResults) {
        StringBuilder prompt = new StringBuilder();
        
        if (level.getLevel() == 0) {
            // 底层：直接分析问题
            prompt.append("请从你的专业角度分析以下问题，给出详细的评估和建议。\n");
        } else {
            // 非底层：汇总下层结果
            String aggregation = level.getAggregationPrompt();
            if (aggregation != null && !aggregation.isEmpty()) {
                prompt.append(aggregation).append("\n\n");
            } else {
                prompt.append("请综合以下各方评估，给出汇总分析和决策建议。\n\n");
            }
            
            // 添加下层结果
            if (lowerResults != null && !lowerResults.isEmpty()) {
                prompt.append("=== 下级评估报告 ===\n\n");
                for (Map.Entry<String, String> entry : lowerResults.entrySet()) {
                    prompt.append("【").append(entry.getKey()).append("的评估】\n");
                    prompt.append(entry.getValue()).append("\n\n");
                }
            }
        }
        
        return prompt.toString();
    }
    
    /**
     * 构建最终结论
     */
    private String buildConclusion(SharedContext context, Map<Integer, Map<String, String>> levelResults, 
                                    int levelCount) {
        StringBuilder conclusion = new StringBuilder();
        conclusion.append("=== 分层决策结果 ===\n\n");
        conclusion.append("议题：").append(context.getTopic()).append("\n");
        conclusion.append("层级数：").append(levelCount).append("\n\n");
        
        // 从底层到顶层展示
        for (int level = 0; level < levelCount; level++) {
            Map<String, String> results = levelResults.get(level);
            if (results == null || results.isEmpty()) {
                continue;
            }
            
            String levelName = level == 0 ? "分析层" : (level == levelCount - 1 ? "决策层" : "汇总层" + level);
            conclusion.append("### ").append(levelName).append("\n\n");
            
            for (Map.Entry<String, String> entry : results.entrySet()) {
                conclusion.append("**").append(entry.getKey()).append("**\n");
                conclusion.append(entry.getValue()).append("\n\n");
            }
        }
        
        // 最终决策（取顶层结果）
        Map<String, String> topResults = levelResults.get(levelCount - 1);
        if (topResults != null && !topResults.isEmpty()) {
            conclusion.append("---\n");
            conclusion.append("### 最终决策\n\n");
            // 取第一个顶层Agent的结果作为最终决策
            String finalDecision = topResults.values().iterator().next();
            conclusion.append(finalDecision);
        }
        
        return conclusion.toString();
    }
    
    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // 分层决策按层级执行，不使用轮次控制
        return false;
    }
    
    @Override
    public AgentExecutor getNextSpeaker(SharedContext context, List<AgentExecutor> agents) {
        // 分层决策不使用轮流发言机制
        return null;
    }
    
    @Override
    public String getName() {
        return "Hierarchy";
    }
    
    @Override
    public String getDescription() {
        return "分层决策策略：金字塔式层级结构，底层分析，逐层汇报，顶层决策";
    }
    
    /**
     * 关闭线程池
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
