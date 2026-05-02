package io.leavesfly.tinyclaw.hooks;

/**
 * Hook handler 返回的决策。
 *
 * <p>两种决策语义：</p>
 * <ul>
 *   <li>{@link Permission#ALLOW} —— 放行（默认），后续流程继续</li>
 *   <li>{@link Permission#DENY} —— 阻塞该动作，{@link #getReason()} 作为反馈回灌给模型或返回给用户</li>
 * </ul>
 *
 * <p>另外支持以下非阻塞型修改（均在 ALLOW 下生效）：</p>
 * <ul>
 *   <li>{@link #getModifiedInput()} —— PreToolUse 可改写工具入参</li>
 *   <li>{@link #getModifiedOutput()} —— PostToolUse 可改写工具结果</li>
 *   <li>{@link #getModifiedPrompt()} —— UserPromptSubmit 可改写 prompt</li>
 *   <li>{@link #getAdditionalContext()} —— 注入额外上下文，由切点自行决定落地方式
 *       （如 SessionStart 追加到 summary，其他事件追加为 system 消息）</li>
 * </ul>
 */
public final class HookDecision {

    public enum Permission {
        ALLOW,
        DENY
    }

    private static final HookDecision CONTINUE = new HookDecision(Permission.ALLOW, null, null, null, null, null);

    private final Permission permission;
    private final String reason;
    private final java.util.Map<String, Object> modifiedInput;
    private final String modifiedOutput;
    private final String modifiedPrompt;
    private final String additionalContext;

    private HookDecision(Permission permission, String reason,
                         java.util.Map<String, Object> modifiedInput,
                         String modifiedOutput,
                         String modifiedPrompt,
                         String additionalContext) {
        this.permission = permission == null ? Permission.ALLOW : permission;
        this.reason = reason;
        this.modifiedInput = modifiedInput;
        this.modifiedOutput = modifiedOutput;
        this.modifiedPrompt = modifiedPrompt;
        this.additionalContext = additionalContext;
    }

    /** handler 未给出明确决策时使用，等同 ALLOW 且无副作用。 */
    public static HookDecision cont() {
        return CONTINUE;
    }

    public static HookDecision allow() {
        return CONTINUE;
    }

    public static HookDecision deny(String reason) {
        return new HookDecision(Permission.DENY, reason, null, null, null, null);
    }

    public static HookDecision modifyInput(java.util.Map<String, Object> newInput) {
        return new HookDecision(Permission.ALLOW, null, newInput, null, null, null);
    }

    public static HookDecision modifyOutput(String newOutput) {
        return new HookDecision(Permission.ALLOW, null, null, newOutput, null, null);
    }

    public static HookDecision modifyPrompt(String newPrompt) {
        return new HookDecision(Permission.ALLOW, null, null, null, newPrompt, null);
    }

    public static HookDecision addContext(String context) {
        return new HookDecision(Permission.ALLOW, null, null, null, null, context);
    }

    /**
     * 包级工厂：同时携带多种累积修改。
     *
     * <p>仅供 {@link HookDispatcher} 聚合多个 handler 的非 DENY 决策时使用。
     * 所有参数均可为 null，全 null 时等价于 {@link #cont()}。业务代码应优先使用
     * 单字段工厂（{@link #modifyInput}、{@link #modifyOutput} 等）而不是直接调用本方法。</p>
     *
     * @param modifiedInput     累积后的最终 toolInput，null 表示未修改
     * @param modifiedOutput    累积后的最终 toolOutput，null 表示未修改
     * @param modifiedPrompt    累积后的最终 prompt，null 表示未修改
     * @param additionalContext 多个 handler 的 additionalContext 拼接结果，null/空 表示无
     */
    static HookDecision combined(java.util.Map<String, Object> modifiedInput,
                                 String modifiedOutput,
                                 String modifiedPrompt,
                                 String additionalContext) {
        if (modifiedInput == null && modifiedOutput == null
                && modifiedPrompt == null
                && (additionalContext == null || additionalContext.isEmpty())) {
            return CONTINUE;
        }
        String ctx = (additionalContext == null || additionalContext.isEmpty()) ? null : additionalContext;
        return new HookDecision(Permission.ALLOW, null, modifiedInput, modifiedOutput, modifiedPrompt, ctx);
    }

    public Permission getPermission() {
        return permission;
    }

    public String getReason() {
        return reason;
    }

    public java.util.Map<String, Object> getModifiedInput() {
        return modifiedInput;
    }

    public String getModifiedOutput() {
        return modifiedOutput;
    }

    public String getModifiedPrompt() {
        return modifiedPrompt;
    }

    public String getAdditionalContext() {
        return additionalContext;
    }

    public boolean isDeny() {
        return permission == Permission.DENY;
    }
}
