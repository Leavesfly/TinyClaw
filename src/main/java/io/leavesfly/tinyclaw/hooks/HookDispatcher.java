package io.leavesfly.tinyclaw.hooks;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 调度器：系统对外的统一门面，由各切点调用 {@link #fire(HookEvent, HookContext)} 触发 hook。
 *
 * <h3>聚合规则</h3>
 * <p>同一事件下可能配置多条 HookEntry，每条 HookEntry 包含若干 HookHandler。
 * 本调度器按配置文件中的出现顺序依次执行所有命中 matcher 的 handler，规则如下：</p>
 * <ul>
 *   <li><b>DENY 短路</b>：任一 handler 返回 {@link HookDecision#deny(String)}，立即停止后续 handler
 *       并将该决策作为聚合结果返回，reason 取该 handler 的 reason。</li>
 *   <li><b>modifyInput 累积</b>：后一个 handler 收到的 {@link HookContext#getToolInput()} 是
 *       前一个 handler 修改后的结果；最终返回的决策携带最终版 input。</li>
 *   <li><b>modifyPrompt / modifyOutput 累积</b>：同 modifyInput，后者覆盖前者。</li>
 *   <li><b>additionalContext 累加</b>：多个 handler 返回的上下文以 {@code "\n\n"} 拼接。</li>
 *   <li><b>handler 抛异常</b>：按 fail-open 处理——handler 实现本身应吞掉异常返回 cont，
 *       但为防御起见这里再套一层 try/catch，记 warn 日志。</li>
 * </ul>
 *
 * <h3>空注册表短路</h3>
 * <p>若当前事件无任何 hook 配置，{@link #fire} 会直接返回 {@link HookDecision#cont()}，
 * 不构造任何对象，保证未启用 hook 时零额外开销。</p>
 *
 * <h3>线程安全</h3>
 * <p>{@link HookRegistry} 构造后不可变；本类无可变状态。可被多线程并发调用。</p>
 */
public final class HookDispatcher {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("hooks");

    private final HookRegistry registry;

    public HookDispatcher(HookRegistry registry) {
        this.registry = registry == null ? HookRegistry.EMPTY : registry;
    }

    /** 返回一个什么也不做的 dispatcher（等价于无配置场景），便于注入/测试。 */
    public static HookDispatcher noop() {
        return new HookDispatcher(HookRegistry.EMPTY);
    }

    public HookRegistry getRegistry() {
        return registry;
    }

    /**
     * 触发一次事件。
     *
     * @param event 事件类型，不能为 null
     * @param ctx   事件上下文，不能为 null；其 {@link HookContext#getEvent()} 可与 event 不同
     *              （以 event 参数为准）
     * @return 聚合后的决策，保证非 null
     */
    public HookDecision fire(HookEvent event, HookContext ctx) {
        if (event == null || ctx == null) {
            return HookDecision.cont();
        }
        List<HookEntry> entries = registry.getEntries(event);
        if (entries.isEmpty()) {
            return HookDecision.cont();
        }

        // 累积状态
        Map<String, Object> currentInput = ctx.getToolInput();
        String currentPrompt = ctx.getPrompt();
        String currentOutput = ctx.getToolOutput();
        StringBuilder aggregatedContext = null; // 懒创建

        for (HookEntry entry : entries) {
            if (!entry.getMatcher().matches(ctx.getToolName())) {
                continue;
            }
            for (HookHandler handler : entry.getHandlers()) {
                // 每次构造新的 HookContext，把最新的累积状态传给下一个 handler
                HookContext currentCtx = HookContext.builder(event)
                        .sessionKey(ctx.getSessionKey())
                        .prompt(currentPrompt)
                        .toolName(ctx.getToolName())
                        .toolInput(currentInput)
                        .toolOutput(currentOutput)
                        .extra(ctx.getExtra())
                        .build();

                HookDecision decision;
                try {
                    decision = handler.invoke(currentCtx);
                } catch (RuntimeException e) {
                    // 防御性兜底：handler 实现违规抛了异常，fail-open 继续
                    logger.warn("Hook handler threw exception, fail-open", Map.of(
                            "event", event.wireName(),
                            "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    continue;
                }
                if (decision == null) {
                    continue;
                }

                // DENY 立即短路
                if (decision.isDeny()) {
                    logger.info("Hook denied action", Map.of(
                            "event", event.wireName(),
                            "tool", ctx.getToolName() == null ? "n/a" : ctx.getToolName(),
                            "reason", decision.getReason() == null ? "(no reason)" : decision.getReason()));
                    return decision;
                }

                // 累积修改
                if (decision.getModifiedInput() != null) {
                    currentInput = Collections.unmodifiableMap(new LinkedHashMap<>(decision.getModifiedInput()));
                }
                if (decision.getModifiedPrompt() != null) {
                    currentPrompt = decision.getModifiedPrompt();
                }
                if (decision.getModifiedOutput() != null) {
                    currentOutput = decision.getModifiedOutput();
                }
                if (decision.getAdditionalContext() != null && !decision.getAdditionalContext().isEmpty()) {
                    if (aggregatedContext == null) {
                        aggregatedContext = new StringBuilder();
                    } else {
                        aggregatedContext.append("\n\n");
                    }
                    aggregatedContext.append(decision.getAdditionalContext());
                }
            }
        }

        // 无任何修改，直接返回 cont
        boolean inputChanged = currentInput != ctx.getToolInput();
        boolean promptChanged = !java.util.Objects.equals(currentPrompt, ctx.getPrompt());
        boolean outputChanged = !java.util.Objects.equals(currentOutput, ctx.getToolOutput());
        boolean hasContext = aggregatedContext != null && aggregatedContext.length() > 0;

        if (!inputChanged && !promptChanged && !outputChanged && !hasContext) {
            return HookDecision.cont();
        }

        // 把所有累积的修改组合成一个 HookDecision，供切点按需读取自己关心的字段。
        return HookDecision.combined(
                inputChanged ? currentInput : null,
                outputChanged ? currentOutput : null,
                promptChanged ? currentPrompt : null,
                hasContext ? aggregatedContext.toString() : null);
    }
}
