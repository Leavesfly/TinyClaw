package io.leavesfly.tinyclaw.agent.evolution;

/**
 * 反馈类型枚举，用于区分不同来源和性质的反馈。
 *
 * 反馈分为两大类：
 * - 显式反馈：用户主动提供的评价
 * - 隐式反馈：从用户行为中推断的评价
 */
public enum FeedbackType {

    // ==================== 显式反馈 ====================

    /**
     * 点赞（正向）
     */
    THUMBS_UP("explicit", 1.0),

    /**
     * 点踩（负向）
     */
    THUMBS_DOWN("explicit", 0.0),

    /**
     * 用户评分（0-5 星）
     */
    STAR_RATING("explicit", -1.0),  // -1 表示需要从附加数据中获取

    /**
     * 用户文字反馈
     */
    TEXT_COMMENT("explicit", -1.0),

    // ==================== 隐式反馈 ====================

    /**
     * 会话正常完成（用户主动结束或长时间无响应）
     */
    SESSION_COMPLETED("implicit", 0.7),

    /**
     * 用户重试/重新提问（可能表示不满意）
     */
    USER_RETRY("implicit", 0.3),

    /**
     * 工具调用成功
     */
    TOOL_SUCCESS("implicit", 0.8),

    /**
     * 工具调用失败
     */
    TOOL_FAILURE("implicit", 0.2),

    /**
     * 用户快速离开（可能表示问题已解决或放弃）
     */
    QUICK_EXIT("implicit", 0.5),

    /**
     * 长会话（深度交互，可能表示复杂问题或良好体验）
     */
    LONG_SESSION("implicit", 0.6);

    private final String category;
    private final double defaultScore;

    FeedbackType(String category, double defaultScore) {
        this.category = category;
        this.defaultScore = defaultScore;
    }

    /**
     * 获取反馈类别。
     *
     * @return "explicit" 或 "implicit"
     */
    public String getCategory() {
        return category;
    }

    /**
     * 获取默认评分。
     *
     * @return 默认评分，-1.0 表示需要从附加数据中获取
     */
    public double getDefaultScore() {
        return defaultScore;
    }

    /**
     * 是否为显式反馈。
     *
     * @return 显式反馈返回 true
     */
    public boolean isExplicit() {
        return "explicit".equals(category);
    }

    /**
     * 是否为隐式反馈。
     *
     * @return 隐式反馈返回 true
     */
    public boolean isImplicit() {
        return "implicit".equals(category);
    }
}
