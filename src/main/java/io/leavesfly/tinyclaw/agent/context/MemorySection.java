package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.agent.AgentConstants;
import io.leavesfly.tinyclaw.util.StringUtils;

/**
 * 记忆上下文部分。
 * 根据当前消息和 token 预算智能选取长期记忆内容。
 */
public class MemorySection implements ContextSection {
    
    @Override
    public String name() {
        return "Memory";
    }
    
    @Override
    public String build(SectionContext context) {
        int memoryBudget = calculateMemoryTokenBudget(context.getContextWindow());
        String memoryContext = context.getMemory().getMemoryContext(context.getCurrentMessage(), memoryBudget);
        
        if (StringUtils.isNotBlank(memoryContext)) {
            return "# Memory\n\n" + memoryContext;
        }
        
        return "";
    }
    
    /**
     * 根据上下文窗口大小计算记忆 token 预算。
     */
    private int calculateMemoryTokenBudget(int contextWindow) {
        int budget = contextWindow * AgentConstants.MEMORY_TOKEN_BUDGET_PERCENTAGE / 100;
        return Math.max(AgentConstants.MEMORY_MIN_TOKEN_BUDGET,
                Math.min(AgentConstants.MEMORY_MAX_TOKEN_BUDGET, budget));
    }
}
