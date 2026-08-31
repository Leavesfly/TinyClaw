package io.leavesfly.tinyclaw.session;

/**
 * 进度上报出口 - 让长任务能更新会话进度卡而不必依赖整个 {@link SessionManager}。
 *
 * <p>协同与子代理属于高级能力层，它们只需要"把当前阶段报上去"这一件事。
 * 直接注入 {@link SessionManager} 会让这些包拿到会话的全部读写能力，
 * 窄接口把可见的能力限制在实际需要的范围内。</p>
 *
 * <p>实现方需自行容忍会话不存在：任务可能在会话被删除后才上报，这不是错误。</p>
 */
public interface SessionProgressSink {

    /**
     * 上报进度。
     *
     * @param sessionKey 目标会话；为空时应当直接忽略
     * @param progress   当前进度；传 null 表示清除
     */
    void setProgress(String sessionKey, SessionProgress progress);

    /**
     * 清除进度，等价于 {@code setProgress(sessionKey, null)}。
     */
    default void clearProgress(String sessionKey) {
        setProgress(sessionKey, null);
    }

    /**
     * 空实现：未注入出口时使用，使调用方无需到处判空。
     */
    SessionProgressSink NOOP = (sessionKey, progress) -> {
    };
}
