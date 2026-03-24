package io.leavesfly.tinyclaw.agent.context;

/**
 * 系统提示词的一个组成部分。
 * 实现此接口可向 Agent 的系统提示词中添加自定义内容段。
 */
public interface ContextSection {
    /**
     * 返回该 section 的名称，用于日志和调试
     */
    String name();
    
    /**
     * 构建该 section 的内容。
     * 返回 null 或空字符串表示跳过该 section
     */
    String build(SectionContext context);
}
