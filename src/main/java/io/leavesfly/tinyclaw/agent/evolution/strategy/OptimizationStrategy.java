package io.leavesfly.tinyclaw.agent.evolution.strategy;

import io.leavesfly.tinyclaw.agent.evolution.OptimizationResult;

import java.util.List;

/**
 * Prompt 优化策略接口。
 * <p>
 * 不同的优化策略实现此接口，包括：
 * <ul>
 *   <li>TextGradientStrategy - 文本梯度优化</li>
 *   <li>OPROStrategy - OPRO 历史轨迹引导优化</li>
 *   <li>SelfReflectionStrategy - 自我反思优化</li>
 * </ul>
 */
public interface OptimizationStrategy {

    /**
     * 执行一轮 prompt 优化。
     *
     * @param currentPrompt 当前 prompt
     * @param context       优化上下文（提供 LLM 调用能力、反馈数据等）
     * @return 优化结果，不需要优化时可返回 null 或 noImprovementNeeded 结果
     */
    OptimizationResult optimize(String currentPrompt, OptimizationContext context);

    /**
     * 获取策略名称。
     *
     * @return 策略名称标识
     */
    String name();

    /**
     * 检查当前是否满足优化的前置条件。
     *
     * @param context 优化上下文
     * @return true 表示满足条件可以执行优化
     */
    default boolean canOptimize(OptimizationContext context) {
        return true;
    }
}
