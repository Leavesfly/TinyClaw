package io.leavesfly.tinyclaw.bus;

/**
 * 最近一次入站联系人记录（对齐 OpenClaw 的 target:"last" 投递语义）。
 *
 * <p>由消息路由层在真实通道（非 cli/system）收到用户消息时更新，
 * 心跳告警在 target=last 时投递回这里记录的 channel/chatId。</p>
 */
public final class LastContact {

    private static String channel;
    private static String chatId;
    private static long updatedAtMs;

    private LastContact() {
    }

    /**
     * 更新最近联系人。
     *
     * @param channel 通道名称
     * @param chatId  聊天 ID
     */
    public static synchronized void update(String channel, String chatId) {
        if (channel == null || channel.isEmpty()) {
            return;
        }
        LastContact.channel = channel;
        LastContact.chatId = chatId;
        LastContact.updatedAtMs = System.currentTimeMillis();
    }

    /**
     * 获取最近联系人。
     *
     * @return [channel, chatId]，尚无记录时返回 null
     */
    public static synchronized String[] get() {
        if (channel == null) {
            return null;
        }
        return new String[]{channel, chatId};
    }

    /**
     * 获取最近联系时间戳（毫秒）。
     *
     * @return 时间戳，尚无记录时返回 0
     */
    public static synchronized long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
