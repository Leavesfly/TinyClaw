package io.leavesfly.tinyclaw.session;

/**
 * 会话搜索命中项。
 *
 * <h2>messageIndex 的语义</h2>
 * <p>它是<b>完整转录</b>中的绝对下标，与 {@link SessionManager#getHistory} 的下标口径一致，
 * 也与 {@link ToolCallRecord#getMessageIndex()} 同源。前端据此可以直接跳到那条消息，
 * 而不需要再做一次全量比对。若这里改用上下文视图的相对下标，压缩过的会话就会跳错位置。</p>
 *
 * @param sessionKey   命中所在会话
 * @param messageIndex 命中消息在完整转录中的绝对下标
 * @param role         消息角色，前端据此决定展示样式
 * @param snippet      命中片段（含前后若干字符的上下文）
 * @param title        会话标题预览，便于结果列表直接展示
 */
public record SessionSearchHit(String sessionKey,
                               int messageIndex,
                               String role,
                               String snippet,
                               String title) {
}
