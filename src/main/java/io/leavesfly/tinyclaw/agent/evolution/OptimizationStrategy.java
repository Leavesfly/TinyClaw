package io.leavesfly.tinyclaw.agent.evolution;

/**
 * 优化策略枚举，定义不同的 Prompt 优化算法。
 *
 * 每种策略有不同的特点和适用场景：
 * - TEXT_GRADIENT：基于文本梯度的微调，适合精细优化
 * - EVO_PROMPT：进化算法驱动，适合探索性优化
 * - MIPRO：多指标迭代优化，适合多目标平衡
 */
public enum OptimizationStrategy {

    /**
     * 基于文本梯度的微调（默认）
     *
     * 工作原理：
     * 1. 分析反馈中的问题模式
     * 2. 使用 LLM 生成文本梯度（优化建议）
     * 3. 应用梯度到当前 Prompt
     *
     * 优点：精确、可解释、改动可控
     * 缺点：可能陷入局部最优
     */
    TEXT_GRADIENT("text_gradient", "基于文本梯度的微调"),

    /**
     * 进化算法驱动的变体生成
     *
     * 工作原理：
     * 1. 生成多个 Prompt 变体（突变、交叉）
     * 2. 评估每个变体的表现
     * 3. 选择最优变体进入下一代
     *
     * 优点：探索性强、不易陷入局部最优
     * 缺点：需要更多评估样本、收敛较慢
     */
    EVO_PROMPT("evo_prompt", "进化算法驱动的变体生成"),

    /**
     * 多指标迭代提示优化 (MIPRO)
     *
     * 工作原理：
     * 1. 定义多个优化目标（准确性、简洁性、安全性等）
     * 2. 使用贝叶斯优化平衡多目标
     * 3. 迭代生成和评估 Prompt 变体
     *
     * 优点：多目标平衡、适合复杂场景
     * 缺点：配置复杂、需要明确定义目标
     */
    MIPRO("mipro", "多指标迭代提示优化");

    private final String code;
    private final String description;

    OptimizationStrategy(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从代码解析策略。
     *
     * @param code 策略代码
     * @return 策略枚举，未找到时返回 TEXT_GRADIENT
     */
    public static OptimizationStrategy fromCode(String code) {
        if (code == null) {
            return TEXT_GRADIENT;
        }
        for (OptimizationStrategy strategy : values()) {
            if (strategy.code.equalsIgnoreCase(code)) {
                return strategy;
            }
        }
        return TEXT_GRADIENT;
    }
}
