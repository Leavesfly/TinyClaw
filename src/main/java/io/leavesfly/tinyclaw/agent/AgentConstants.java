package io.leavesfly.tinyclaw.agent;

/**
 * Agent 相关常量配置
 *
 * 参数基准：参考主流大模型（qwen3-max 200K、Claude 3.5 200K、GPT-4o 128K）的
 * 上下文窗口与最佳实践进行设定。
 */
public final class AgentConstants {

    private AgentConstants() {
        // Utility class
    }

    // -------------------------------------------------------------------------
    // LLM 调用参数
    // -------------------------------------------------------------------------

    /**
     * 单次 LLM 输出的最大 token 数。
     * 主流模型（GPT-4o / Claude 3.5 / Qwen3-Max）的输出上限均为 8192~16384，
     * 取 16384 可充分利用模型能力，同时避免超出大多数模型的输出限制。
     */
    public static final int DEFAULT_MAX_TOKENS = 16384;

    /**
     * 默认上下文窗口大小（token 数）。
     * 取主流模型中较保守的 GPT-4o 128K 作为默认值，
     * 实际使用时应由 ModelsConfig 中的 maxContextSize 覆盖。
     */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /**
     * 对话型 Agent 的默认温度，平衡创造性与确定性。
     */
    public static final double DEFAULT_TEMPERATURE = 0.7;

    // -------------------------------------------------------------------------
    // 摘要触发阈值
    // -------------------------------------------------------------------------

    /**
     * 触发摘要的消息数量阈值（仅统计 user/assistant 消息）。
     * 设为 40 条，避免工具调用密集场景下频繁触发摘要。
     */
    public static final int SUMMARIZE_MESSAGE_THRESHOLD = 40;

    /**
     * 触发摘要的 token 占比阈值（相对于上下文窗口）。
     * 75% 是业界常见值，在上下文窗口被正确初始化后可合理触发。
     */
    public static final int SUMMARIZE_TOKEN_PERCENTAGE = 75;

    /**
     * 摘要后保留的最近消息条数，保持对话连贯性。
     */
    public static final int RECENT_MESSAGES_TO_KEEP = 6;

    /**
     * 触发分批摘要的消息数量阈值。
     * 超过此值时将消息拆成两批分别摘要再合并，避免单次摘要 prompt 过长。
     */
    public static final int BATCH_SUMMARIZE_THRESHOLD = 20;

    // -------------------------------------------------------------------------
    // 摘要生成参数
    // -------------------------------------------------------------------------

    /**
     * 摘要任务的最大输出 token 数。
     * 4096 可容纳对长对话（40+ 条消息）的完整摘要，避免截断丢失关键上下文。
     */
    public static final int SUMMARY_MAX_TOKENS = 1024 * 4;

    /**
     * 摘要任务的温度，低温度保证摘要的准确性和一致性。
     */
    public static final double SUMMARY_TEMPERATURE = 0.3;

    // -------------------------------------------------------------------------
    // 记忆系统参数
    // -------------------------------------------------------------------------

    /**
     * 记忆上下文的 token 预算（占上下文窗口的百分比）。
     * 20% 在 128K 窗口下约为 25600 token，足够放入丰富的长期记忆，
     * 同时为系统提示词、对话历史和工具定义留出充足空间。
     */
    public static final int MEMORY_TOKEN_BUDGET_PERCENTAGE = 20;

    /**
     * 记忆上下文的最小 token 预算。
     * 1024 token 可容纳数条关键记忆条目。
     */
    public static final int MEMORY_MIN_TOKEN_BUDGET = 1024;

    /**
     * 记忆上下文的最大 token 预算。
     * 16384 token 约占 128K 窗口的 12.5%，在大上下文模型上可承载丰富的记忆内容。
     */
    public static final int MEMORY_MAX_TOKEN_BUDGET = 4096 * 4;
}
