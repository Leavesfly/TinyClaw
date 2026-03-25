package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.*;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态路由策略
 * <p>由 Router Agent 根据当前上下文动态选择下一个发言者，而非预定义的固定轮流发言。
 * 适合开放式协作场景，Agent 之间可以自主决定协作流程。
 *
 * <p>核心流程：
 * <ol>
 *   <li>Router Agent 分析当前对话上下文</li>
 *   <li>Router Agent 输出下一个应该发言的角色名称（通过 [NEXT:角色名] 标记）</li>
 *   <li>被选中的 Agent 发言</li>
 *   <li>重复直到 Router Agent 输出 [CONCLUDE] 标记或达到最大轮次</li>
 * </ol>
 *
 * <p>对标 AutoGen 的动态对话路由和 LangGraph 的状态图路由。
 */
public class DynamicRoutingStrategy implements CollaborationStrategy {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("collaboration");

    /** 路由指令提取正则：[NEXT:角色名] */
    private static final Pattern NEXT_PATTERN = Pattern.compile("\\[NEXT[:：]\\s*([^\\]]+)\\]");

    /** 结束标记 */
    private static final Pattern CONCLUDE_PATTERN = Pattern.compile("\\[CONCLUDE\\]");

    /** 执行上下文（用于创建 Router Agent） */
    private final ExecutionContext executionContext;

    public DynamicRoutingStrategy(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    @Override
    public String execute(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        if (agents.isEmpty()) {
            return "动态路由至少需要 1 个参与者";
        }

        // 创建或使用配置中指定的 Router Agent
        RoleAgent routerAgent = createRouterAgent(config, agents);

        logger.info("开始动态路由协同", Map.of(
                "topic", context.getTopic(),
                "participants", agents.size(),
                "maxRounds", config.getMaxRounds()
        ));

        while (!shouldTerminate(context, config)) {
            context.nextRound();

            // 1. Router Agent 决定下一个发言者（Router 的决策不需要流式输出给用户）
            String routingDecision = routerAgent.speak(context, buildRoutingPrompt(agents, context));
            context.addMessageSilent(
                    AgentMessage.builder(routerAgent.getAgentId(), routerAgent.getRoleName(), routingDecision)
                            .type(AgentMessage.MessageType.SYSTEM)
                            .build()
            );

            logger.info("Router 决策", Map.of(
                    "round", context.getCurrentRound(),
                    "decision", routingDecision.length() > 100
                            ? routingDecision.substring(0, 100) + "..."
                            : routingDecision
            ));

            // 2. 检查是否应该结束
            if (CONCLUDE_PATTERN.matcher(routingDecision).find()) {
                logger.info("Router 决定结束协同");
                break;
            }

            // 3. 解析下一个发言者
            String nextRoleName = extractNextRole(routingDecision);
            if (nextRoleName == null) {
                logger.warn("无法解析路由指令，使用轮询兜底", Map.of(
                        "round", context.getCurrentRound()
                ));
                // 兜底：按轮次取模选择
                int index = (context.getCurrentRound() - 1) % agents.size();
                nextRoleName = agents.get(index).getRoleName();
            }

            // 4. 找到对应的 Agent 并让其发言
            RoleAgent selectedAgent = findAgentByRole(agents, nextRoleName);
            if (selectedAgent == null) {
                logger.warn("未找到角色，跳过本轮", Map.of("roleName", nextRoleName));
                context.addMessage(AgentMessage.system("未找到角色 [" + nextRoleName + "]，跳过本轮"));
                continue;
            }

            String response = speakWithStream(selectedAgent, context, null);
            addMessageWithStream(context, selectedAgent.getAgentId(), selectedAgent.getRoleName(), response);

            logger.info("Agent 发言", Map.of(
                    "round", context.getCurrentRound(),
                    "speaker", selectedAgent.getRoleName(),
                    "responseLength", response.length()
            ));
        }

        // 构建最终结论
        String conclusion = buildConclusion(context, routerAgent);
        context.setFinalConclusion(conclusion);

        logger.info("动态路由协同完成", Map.of(
                "totalRounds", context.getCurrentRound(),
                "totalMessages", context.getHistory().size()
        ));

        return conclusion;
    }

    /**
     * 创建 Router Agent
     * <p>优先使用配置中指定的 routerRole，否则使用默认的路由角色定义。
     */
    private RoleAgent createRouterAgent(CollaborationConfig config, List<RoleAgent> agents) {
        AgentRole routerRole = config.getRouterRole();
        if (routerRole == null) {
            // 构建参与者列表描述
            StringBuilder participantsDesc = new StringBuilder();
            for (RoleAgent agent : agents) {
                AgentRole role = agent.getRole();
                participantsDesc.append("- ").append(role.getRoleName());
                if (role.getDescription() != null && !role.getDescription().isEmpty()) {
                    participantsDesc.append(": ").append(role.getDescription());
                }
                participantsDesc.append("\n");
            }

            String defaultRouterPrompt = "你是一个协同路由器（Router），负责协调多个 Agent 之间的对话。\n\n"
                    + "你的职责：\n"
                    + "1. 分析当前对话上下文和协同目标\n"
                    + "2. 决定下一个最适合发言的角色\n"
                    + "3. 在回复末尾用 [NEXT:角色名] 标记指定下一个发言者\n"
                    + "4. 当你认为讨论已经充分、目标已达成时，用 [CONCLUDE] 标记结束协同\n\n"
                    + "可选择的参与者：\n" + participantsDesc + "\n"
                    + "决策原则：\n"
                    + "- 确保每个参与者都有机会发言\n"
                    + "- 当某个观点需要特定专业角色回应时，优先选择该角色\n"
                    + "- 避免同一个角色连续发言超过 2 次\n"
                    + "- 当讨论陷入重复时，主动引导或结束";

            routerRole = AgentRole.of("Router", defaultRouterPrompt)
                    .withDescription("协同路由器，负责动态选择下一个发言者");
        }

        return executionContext.createAgentExecutor(routerRole);
    }

    /**
     * 构建路由提示词
     */
    private String buildRoutingPrompt(List<RoleAgent> agents, SharedContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析当前对话进展，决定下一步应该由哪个角色发言。\n\n");

        // 列出可选角色
        prompt.append("可选角色：");
        for (int i = 0; i < agents.size(); i++) {
            if (i > 0) prompt.append("、");
            prompt.append(agents.get(i).getRoleName());
        }
        prompt.append("\n\n");

        // 统计各角色发言次数
        prompt.append("各角色已发言次数：\n");
        for (RoleAgent agent : agents) {
            long count = context.getMessagesByRole(agent.getRoleName()).size();
            prompt.append("- ").append(agent.getRoleName()).append(": ").append(count).append(" 次\n");
        }

        prompt.append("\n请在回复末尾用 [NEXT:角色名] 指定下一个发言者，");
        prompt.append("或用 [CONCLUDE] 结束协同。");

        return prompt.toString();
    }

    /**
     * 从路由决策中提取下一个发言者的角色名
     */
    private String extractNextRole(String decision) {
        if (decision == null) return null;
        Matcher matcher = NEXT_PATTERN.matcher(decision);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 根据角色名查找 RoleAgent
     */
    private RoleAgent findAgentByRole(List<RoleAgent> agents, String roleName) {
        // 精确匹配
        for (RoleAgent agent : agents) {
            if (roleName.equals(agent.getRoleName())) {
                return agent;
            }
        }
        // 模糊匹配（包含关系）
        for (RoleAgent agent : agents) {
            if (agent.getRoleName().contains(roleName) || roleName.contains(agent.getRoleName())) {
                return agent;
            }
        }
        return null;
    }

    /**
     * 构建最终结论（由 Router Agent 总结）
     */
    private String buildConclusion(SharedContext context, RoleAgent routerAgent) {
        String summaryPrompt = "协同讨论已结束。请综合所有参与者的观点，给出最终的结论和总结。\n"
                + "要求：\n"
                + "1. 概述讨论的核心议题\n"
                + "2. 总结各方的主要观点\n"
                + "3. 给出综合结论和建议";

        String conclusion = speakWithStream(routerAgent, context, summaryPrompt);
        addMessageWithStream(context, routerAgent.getAgentId(), routerAgent.getRoleName(), conclusion);
        return conclusion;
    }

    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        if (context.getCurrentRound() >= config.getMaxRounds()) {
            return true;
        }
        if (config.getTimeoutMs() > 0 && context.getElapsedTime() > config.getTimeoutMs()) {
            return true;
        }
        // Token 预算检查
        if (context.isTokenBudgetExceeded()) {
            return true;
        }
        return false;
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
        return prompt != null ? speaker.speak(context, prompt) : speaker.speak(context);
    }

    /**
     * 根据是否已流式输出过，选择静默或普通方式添加消息到历史。
     */
    private void addMessageWithStream(SharedContext context, String agentId, String roleName, String content) {
        if (context.getStreamCallback() != null) {
            context.addMessageSilent(new AgentMessage(agentId, roleName, content));
        } else {
            context.addMessage(agentId, roleName, content);
        }
    }

    @Override
    public String getName() {
        return "DynamicRouting";
    }

    @Override
    public String getDescription() {
        return "动态路由策略：Router Agent 根据上下文动态选择下一个发言者，支持自主协作流程";
    }
}
