package io.leavesfly.tinyclaw.hooks;

/**
 * Hook handler 抽象接口。
 *
 * <p>当前版本提供的唯一实现是 {@link CommandHookHandler}，
 * 通过 stdin/stdout JSON 协议调用任意外部可执行脚本。
 * 未来可按需扩展其他实现（如基于 HTTP 的 handler）。</p>
 */
public interface HookHandler {

    /**
     * 执行 handler 并返回决策。
     *
     * <p>实现需保证：异常不要向上抛出，应捕获后返回 {@link HookDecision#cont()}，
     * 避免用户的 hook 脚本挂掉主流程。</p>
     *
     * @param ctx 事件上下文
     * @return 决策，不得返回 null
     */
    HookDecision invoke(HookContext ctx);
}
