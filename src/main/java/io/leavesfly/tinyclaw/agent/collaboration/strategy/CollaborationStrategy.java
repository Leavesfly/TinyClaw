package io.leavesfly.tinyclaw.agent.collaboration.strategy;

import io.leavesfly.tinyclaw.agent.collaboration.AgentExecutor;
import io.leavesfly.tinyclaw.agent.collaboration.CollaborationConfig;
import io.leavesfly.tinyclaw.agent.collaboration.SharedContext;

import java.util.List;

/**
 * 多Agent协同策略接口
 * 定义不同协同模式的执行逻辑
 */
public interface CollaborationStrategy {
    
    /**
     * 执行协同流程
     * 
     * @param context 共享上下文
     * @param agents 参与的Agent执行器列表
     * @param config 协同配置
     * @return 最终结论/结果
     */
    String execute(SharedContext context, List<AgentExecutor> agents, CollaborationConfig config);
    
    /**
     * 判断是否应该结束协同
     * 
     * @param context 共享上下文
     * @param config 协同配置
     * @return 如果应该结束返回true
     */
    boolean shouldTerminate(SharedContext context, CollaborationConfig config);
    
    /**
     * 获取下一个发言的Agent
     * 
     * @param context 共享上下文
     * @param agents Agent列表
     * @return 下一个发言的Agent，如果没有则返回null
     */
    AgentExecutor getNextSpeaker(SharedContext context, List<AgentExecutor> agents);
    
    /**
     * 获取策略名称
     * 
     * @return 策略名称
     */
    String getName();
    
    /**
     * 获取策略描述
     * 
     * @return 策略描述
     */
    default String getDescription() {
        return getName();
    }
}
