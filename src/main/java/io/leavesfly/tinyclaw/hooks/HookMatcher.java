package io.leavesfly.tinyclaw.hooks;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hook 匹配器，按工具名称过滤。
 *
 * <p>匹配规则（优先级从高到低）：</p>
 * <ul>
 *   <li>{@code null} 或空串 —— 匹配所有工具调用（等价于 {@code "*"}）</li>
 *   <li>{@code "*"} —— 匹配所有工具调用</li>
 *   <li>正则表达式 —— 如 {@code "Edit|Write"}、{@code "exec"}、{@code "web_.*"}，完全匹配工具名</li>
 * </ul>
 *
 * <p>对于非工具类事件（SessionStart、UserPromptSubmit、Stop、SessionEnd），
 * 工具名传入 null，此时只有 {@code "*"} 或空 matcher 会命中。</p>
 */
public final class HookMatcher {

    /** 特殊值：匹配所有。 */
    private static final HookMatcher MATCH_ALL = new HookMatcher("*", null);

    private final String raw;
    private final Pattern compiled;

    private HookMatcher(String raw, Pattern compiled) {
        this.raw = raw;
        this.compiled = compiled;
    }

    /**
     * 构造匹配器。空串或 null 或 "*" 均视为匹配所有。
     * 非法正则会在这里抛 {@link PatternSyntaxException}，由上游捕获后降级为不加载该 hook。
     */
    public static HookMatcher of(String pattern) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) {
            return MATCH_ALL;
        }
        Pattern p = Pattern.compile(pattern);
        return new HookMatcher(pattern, p);
    }

    public boolean matchAll() {
        return compiled == null;
    }

    /**
     * 判断给定的工具名是否被当前 matcher 命中。
     *
     * @param toolName 工具名，可能为 null（非工具类事件）
     * @return matchAll 时始终 true；否则在 toolName 非空时做正则 full match
     */
    public boolean matches(String toolName) {
        if (matchAll()) {
            return true;
        }
        if (toolName == null) {
            return false;
        }
        return compiled.matcher(toolName).matches();
    }

    public String raw() {
        return raw;
    }
}
