package io.leavesfly.tinyclaw.memory;

import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 记忆归属域，为记忆条目与主题文件划定可见边界。
 *
 * <p>{@link MemoryStore} 是 workspace 级单实例，被所有通道、所有用户共享。若不划定归属域，
 * 任意用户在任意聊天里产生的记忆都会被注入到其他用户的系统提示词中。归属域把"谁的记忆"
 * 显式落到条目上，读取时只取当前请求可见的那几个域。</p>
 *
 * <p>三类域：</p>
 * <ul>
 *   <li>{@link #GLOBAL}：Agent 自身的全局知识，对所有会话可见；未标注域的历史条目也归入此域</li>
 *   <li>用户域 {@code u:<channel>:<senderId>}：归属某个发言人，同一人跨聊天、跨会话可见</li>
 *   <li>聊天域 {@code c:<channel>:<chatId>}：归属某个聊天（群或私聊），仅该聊天内可见</li>
 * </ul>
 *
 * <p>前缀用于隔离命名空间：某些平台的 senderId 与 chatId 取值可能相同，无前缀会误判为同一域。</p>
 */
public final class MemoryScope {

    /** 全局域，对所有会话可见 */
    public static final String GLOBAL = "_global";

    private static final String USER_PREFIX = "u:";
    private static final String CHAT_PREFIX = "c:";

    private MemoryScope() {
    }

    /**
     * 构造用户域。channel 或 senderId 缺失时退化为全局域。
     */
    public static String ofUser(String channel, String senderId) {
        if (StringUtils.isBlank(channel) || StringUtils.isBlank(senderId)) {
            return GLOBAL;
        }
        return USER_PREFIX + channel + ":" + senderId;
    }

    /**
     * 构造聊天域。channel 或 chatId 缺失时退化为全局域。
     */
    public static String ofChat(String channel, String chatId) {
        if (StringUtils.isBlank(channel) || StringUtils.isBlank(chatId)) {
            return GLOBAL;
        }
        return CHAT_PREFIX + channel + ":" + chatId;
    }

    /**
     * 从 sessionKey 反推聊天域。
     *
     * <p>sessionKey 形如 {@code channel:chatId}，{@code /new} 指令产生的形如
     * {@code channel:chatId:timestamp}，因此取前两段即可稳定还原同一个聊天域——同一聊天
     * 多次 /new 之后的记忆仍归入同一域。</p>
     *
     * <p>仅供拿不到原始 channel/chatId 的调用方使用（如会话摘要、反馈进化）。前提是 chatId
     * 自身不含冒号，当前所有已接入通道均满足。</p>
     */
    public static String ofSessionKey(String sessionKey) {
        if (StringUtils.isBlank(sessionKey)) {
            return GLOBAL;
        }
        String[] parts = sessionKey.split(":");
        if (parts.length < 2) {
            return GLOBAL;
        }
        return ofChat(parts[0], parts[1]);
    }

    /**
     * 计算一次请求可见的域集合：全局域 + 发言人的用户域 + 当前聊天域。
     *
     * <p>身份信息缺失时对应的域会退化为 {@link #GLOBAL} 并被集合自动去重，
     * 因此最坏情况等价于"只看全局域"，不会意外放宽可见范围。</p>
     */
    public static Set<String> visibleScopes(String channel, String senderId, String chatId) {
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(GLOBAL);
        scopes.add(ofUser(channel, senderId));
        scopes.add(ofChat(channel, chatId));
        return scopes;
    }

    /**
     * 仅全局域可见，作为拿不到身份信息时的安全默认。
     */
    public static Set<String> globalOnly() {
        return Set.of(GLOBAL);
    }

    /**
     * 归一化域标识，null 与空白统一为全局域。
     */
    public static String normalize(String scope) {
        return StringUtils.isBlank(scope) ? GLOBAL : scope;
    }

    /**
     * 把域标识转换为可用作目录名的安全字符串，供主题文件按域分目录存放。
     *
     * <p>全局域返回 null，表示直接使用 topics 根目录——既有主题文件因此无需迁移即可
     * 继续作为全局主题被读取。</p>
     *
     * <p>非法字符替换后可能产生同名（如 {@code a_b} 与 {@code a:b}），故附加域标识的
     * 哈希后缀保证不同域必然落在不同目录。</p>
     */
    public static String toDirName(String scope) {
        String normalized = normalize(scope);
        if (GLOBAL.equals(normalized)) {
            return null;
        }
        String safe = normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe + "-" + Integer.toHexString(normalized.hashCode());
    }
}
