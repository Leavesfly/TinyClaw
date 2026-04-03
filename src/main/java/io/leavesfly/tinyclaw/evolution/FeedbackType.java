package io.leavesfly.tinyclaw.evolution;

/**
 * 隐式反馈类型枚举，从用户行为中自动推断的评价。
 */
public enum FeedbackType {

    /**
     * 会话正常完成（用户主动结束或长时间无响应）
     */
    SESSION_COMPLETED(0.7),

    /**
     * 用户重试/重新提问（可能表示不满意）
     */
    USER_RETRY(0.3),

    /**
     * 工具调用成功
     */
    TOOL_SUCCESS(0.8),

    /**
     * 工具调用失败
     */
    TOOL_FAILURE(0.2),

    /**
     * 用户快速离开（可能表示问题已解决或放弃）
     */
    QUICK_EXIT(0.5),

    /**
     * 长会话（深度交互，可能表示复杂问题或良好体验）
     */
    LONG_SESSION(0.6);

    private final double defaultScore;

    FeedbackType(double defaultScore) {
        this.defaultScore = defaultScore;
    }

    /**
     * 获取默认评分。
     *
     * @return 默认评分 (0.0 ~ 1.0)
     */
    public double getDefaultScore() {
        return defaultScore;
    }
}
