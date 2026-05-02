package io.leavesfly.tinyclaw.hooks;

import java.util.Collections;
import java.util.List;

/**
 * 单条 hook 配置项：一个事件上按 matcher 过滤后挂载的一组 handler。
 *
 * <p>对应 JSON 配置结构：</p>
 * <pre>
 * {
 *   "matcher": "exec",
 *   "hooks": [
 *     { "type": "command", "command": "/path/to/block-rm.sh", "timeoutMs": 5000 }
 *   ]
 * }
 * </pre>
 *
 * <p>一个事件下可配置多个 HookEntry，由 {@link HookDispatcher} 顺序执行：
 * 只要 matcher 命中，对应的所有 handler 都会依次执行。</p>
 */
public final class HookEntry {

    private final HookMatcher matcher;
    private final List<HookHandler> handlers;

    public HookEntry(HookMatcher matcher, List<HookHandler> handlers) {
        if (matcher == null) {
            throw new IllegalArgumentException("matcher must not be null");
        }
        if (handlers == null || handlers.isEmpty()) {
            throw new IllegalArgumentException("handlers must not be null or empty");
        }
        this.matcher = matcher;
        this.handlers = Collections.unmodifiableList(handlers);
    }

    public HookMatcher getMatcher() {
        return matcher;
    }

    public List<HookHandler> getHandlers() {
        return handlers;
    }
}
