package io.leavesfly.tinyclaw.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次记忆上下文构建的结果快照（P5：上下文透明度）。
 *
 * <p>除最终注入系统提示词的文本外，还携带<b>实际选中</b>的结构化记忆条目与主题文件名，
 * 供 Web 控制台展示「本轮使用过的记忆」。它是 {@link MemoryStore#getMemoryContext}
 * 的同源产物——选中清单与真实模型输入来自同一次计算，不做访问次数推断。</p>
 *
 * <p>会话 memoryMode 为 OFF 时返回 {@link #disabled()}：无文本、无条目、disabled=true。</p>
 */
public final class MemorySelection {

    /** 选中的结构化记忆条目摘要（字段完整，供聊天内纠正直接编辑）。 */
    public static final class Item {
        public final String id;
        public final String content;
        public final String scope;
        public final String source;
        public final double importance;
        public final List<String> tags;

        Item(String id, String content, String scope, String source,
             double importance, List<String> tags) {
            this.id = id;
            this.content = content;
            this.scope = scope;
            this.source = source;
            this.importance = importance;
            this.tags = tags != null ? Collections.unmodifiableList(new ArrayList<>(tags)) : List.of();
        }
    }

    /** 注入系统提示词的记忆文本（可能为空串，表示没有可注入内容）。 */
    public final String text;

    /** 实际选中的结构化记忆条目（已按注入顺序排列）。 */
    public final List<Item> entries;

    /** 实际注入的主题文件名（topics 层）。 */
    public final List<String> topics;

    /** memoryMode=OFF 标志：该会话本轮不使用长期记忆。 */
    public final boolean disabled;

    /** text 的 token 估算值（与 Provider 实际用量分开标注）。 */
    public final int estimatedTokens;

    public MemorySelection(String text, List<Item> entries, List<String> topics,
                           boolean disabled, int estimatedTokens) {
        this.text = text != null ? text : "";
        this.entries = entries != null ? Collections.unmodifiableList(new ArrayList<>(entries))
                : List.of();
        this.topics = topics != null ? Collections.unmodifiableList(new ArrayList<>(topics))
                : List.of();
        this.disabled = disabled;
        this.estimatedTokens = estimatedTokens;
    }

    /** OFF 会话的空选择。 */
    public static MemorySelection disabled() {
        return new MemorySelection("", List.of(), List.of(), true, 0);
    }
}
