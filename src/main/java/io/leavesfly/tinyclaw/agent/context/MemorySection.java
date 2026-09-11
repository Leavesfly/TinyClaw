package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.agent.AgentConstants;
import io.leavesfly.tinyclaw.util.StringUtils;

/**
 * 记忆上下文部分。
 * 根据当前消息、可见归属域和 token 预算智能选取长期记忆内容。
 *
 * <p>P5：{@link SectionContext#getPresetMemoryContext()} 非 null 时（ContextBuilder 已在
 * 会话维度一次性计算，含 memoryMode OFF 场景）直接使用预设文本，不再重算——
 * 保证「本轮使用记忆」登记清单与真实注入内容同源。</p>
 */
public class MemorySection implements ContextSection {

    @Override
    public String name() {
        return "Memory";
    }

    @Override
    public String build(SectionContext context) {
        String preset = context.getPresetMemoryContext();
        if (preset != null) {
            return StringUtils.isNotBlank(preset) ? "# Memory\n\n" + preset : "";
        }

        int memoryBudget = calculateMemoryTokenBudget(context.getContextWindow());
        String memoryContext = context.getMemory().getMemoryContext(
                context.getCurrentMessage(), memoryBudget, context.getMemoryScopes());

        if (StringUtils.isNotBlank(memoryContext)) {
            return "# Memory\n\n" + memoryContext;
        }

        return "";
    }

    /**
     * 根据上下文窗口大小计算记忆 token 预算。
     *
     * <p>P5 公共化：ContextBuilder 会话维度的预计算使用同一公式，
     * 避免预算口径分叉导致选中清单与实际注入不一致。</p>
     */
    public static int calculateMemoryTokenBudget(int contextWindow) {
        int budget = contextWindow * AgentConstants.MEMORY_TOKEN_BUDGET_PERCENTAGE / 100;
        return Math.max(AgentConstants.MEMORY_MIN_TOKEN_BUDGET,
                Math.min(AgentConstants.MEMORY_MAX_TOKEN_BUDGET, budget));
    }
}
