package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationConfig;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;

import java.util.List;

/**
 * 多 Agent 协同策略接口
 * 定义不同协同模式的执行逻辑
 */
public interface CollaborationStrategy {

    /**
     * 执行协同流程
     *
     * @param context 共享上下文
     * @param agents  参与的 Agent 执行器列表
     * @param config  协同配置
     * @return 最终结论/结果
     */
    String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config);

    /**
     * 判断是否应该结束协同
     *
     * @param context 共享上下文
     * @param config  协同配置
     * @return 如果应该结束返回 true
     */
    boolean shouldTerminate(SharedContext context, CollaborationConfig config);

    /**
     * 获取策略名称
     */
    String getName();

    /**
     * 获取策略描述
     */
    default String getDescription() {
        return getName();
    }
}
