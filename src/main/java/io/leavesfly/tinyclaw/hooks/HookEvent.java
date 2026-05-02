package io.leavesfly.tinyclaw.hooks;

/**
 * Hook 生命周期事件枚举。
 *
 * <p>对齐 Claude Code 的事件命名，最小集涵盖 6 个核心时机：</p>
 * <ul>
 *   <li>{@link #SESSION_START} —— 会话首次创建时触发，可注入上下文</li>
 *   <li>{@link #USER_PROMPT_SUBMIT} —— 用户消息到达但尚未发给 LLM 前，可改写或拒绝</li>
 *   <li>{@link #PRE_TOOL_USE} —— 工具执行前，可 deny / 改写参数</li>
 *   <li>{@link #POST_TOOL_USE} —— 工具执行后，可改写结果、追加上下文</li>
 *   <li>{@link #STOP} —— 本轮回复完成时触发，用于归档、通知</li>
 *   <li>{@link #SESSION_END} —— AgentRuntime 停止时触发，用于收尾</li>
 * </ul>
 */
public enum HookEvent {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    STOP("Stop"),
    SESSION_END("SessionEnd");

    private final String wireName;

    HookEvent(String wireName) {
        this.wireName = wireName;
    }

    /** 返回对外（JSON 配置、handler 协议）使用的事件名，与 Claude Code 一致。 */
    public String wireName() {
        return wireName;
    }

    /** 根据 wire name（如 "PreToolUse"）反查枚举，未命中返回 null。 */
    public static HookEvent fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (HookEvent e : values()) {
            if (e.wireName.equals(name)) {
                return e;
            }
        }
        return null;
    }
}
