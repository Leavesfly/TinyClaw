package io.leavesfly.tinyclaw.hooks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hook 事件上下文。
 *
 * <p>不可变数据容器，作为 JSON 载荷通过 stdin 喂给 handler。
 * 字段对齐 Claude Code 的约定：{@code hookEventName}、{@code sessionKey}、
 * {@code tool_name}、{@code tool_input}、{@code tool_output}、{@code prompt}。</p>
 *
 * <p>通过 {@link Builder} 构造，未设置的字段不会出现在 JSON 中。</p>
 */
public final class HookContext {

    private final HookEvent event;
    private final String sessionKey;
    private final String prompt;
    private final String toolName;
    private final Map<String, Object> toolInput;
    private final String toolOutput;
    private final Map<String, Object> extra;

    private HookContext(Builder b) {
        this.event = b.event;
        this.sessionKey = b.sessionKey;
        this.prompt = b.prompt;
        this.toolName = b.toolName;
        this.toolInput = b.toolInput == null ? null : Collections.unmodifiableMap(b.toolInput);
        this.toolOutput = b.toolOutput;
        this.extra = b.extra == null ? null : Collections.unmodifiableMap(b.extra);
    }

    public HookEvent getEvent() {
        return event;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getToolInput() {
        return toolInput;
    }

    public String getToolOutput() {
        return toolOutput;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    /**
     * 序列化成 Claude Code 风格的 JSON 载荷 Map，仅包含非空字段。
     * 由 {@link HookDispatcher} 转 JSON 字符串后写入 handler stdin。
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        if (event != null) {
            p.put("hookEventName", event.wireName());
        }
        if (sessionKey != null) {
            p.put("sessionKey", sessionKey);
        }
        if (prompt != null) {
            p.put("prompt", prompt);
        }
        if (toolName != null) {
            p.put("tool_name", toolName);
        }
        if (toolInput != null) {
            p.put("tool_input", toolInput);
        }
        if (toolOutput != null) {
            p.put("tool_output", toolOutput);
        }
        if (extra != null) {
            p.putAll(extra);
        }
        return p;
    }

    public static Builder builder(HookEvent event) {
        return new Builder(event);
    }

    public static final class Builder {
        private final HookEvent event;
        private String sessionKey;
        private String prompt;
        private String toolName;
        private Map<String, Object> toolInput;
        private String toolOutput;
        private Map<String, Object> extra;

        private Builder(HookEvent event) {
            this.event = event;
        }

        public Builder sessionKey(String v) {
            this.sessionKey = v;
            return this;
        }

        public Builder prompt(String v) {
            this.prompt = v;
            return this;
        }

        public Builder toolName(String v) {
            this.toolName = v;
            return this;
        }

        public Builder toolInput(Map<String, Object> v) {
            this.toolInput = v;
            return this;
        }

        public Builder toolOutput(String v) {
            this.toolOutput = v;
            return this;
        }

        public Builder extra(Map<String, Object> v) {
            this.extra = v;
            return this;
        }

        public HookContext build() {
            return new HookContext(this);
        }
    }
}
