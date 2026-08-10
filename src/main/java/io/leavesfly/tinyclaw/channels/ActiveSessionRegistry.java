package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃会话指针注册表 - 记录每个聊天当前指向哪个会话
 *
 * <p>通道地址（channel:chatId）与会话身份是两件事：一个聊天可以先后开启多个会话，
 * {@code /new} 就是把指针指向一个新会话。这个指针此前只存在于通道实例的内存里，
 * 进程重启后丢失，用户会被静默送回最早那个会话，而 {@code /new} 建出来的会话变成
 * 谁也访问不到的孤儿。因此这里把指针持久化到 workspace。</p>
 *
 * <p>未调用 {@link #configure} 时退化为纯内存模式（单元测试场景）。</p>
 */
public final class ActiveSessionRegistry {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("channel.session");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 以 _ 开头：与会话转录文件区分，避免被当成会话文件扫描 */
    private static final String FILE_NAME = "_active-sessions.json";

    /** pointerKey(channel:chatId) -> 当前活跃 sessionKey */
    private static final Map<String, String> POINTERS = new ConcurrentHashMap<>();

    private static volatile Path storageFile;

    private ActiveSessionRegistry() {
    }

    /**
     * 绑定持久化位置并载入已有指针，应在通道启动前调用一次
     *
     * @param sessionDir 会话存储目录
     */
    public static synchronized void configure(String sessionDir) {
        if (sessionDir == null || sessionDir.isEmpty()) {
            return;
        }
        try {
            Path dir = Paths.get(sessionDir);
            Files.createDirectories(dir);
            storageFile = dir.resolve(FILE_NAME);
            load();
        } catch (Exception e) {
            logger.warn("Failed to configure active session registry: " + e.getMessage());
            storageFile = null;
        }
    }

    /**
     * 获取该聊天当前活跃的 sessionKey，没有记录时返回 fallback
     */
    public static String current(String channel, String chatId, String fallback) {
        return POINTERS.getOrDefault(pointerKey(channel, chatId), fallback);
    }

    /**
     * 把该聊天的活跃会话指向新的 sessionKey 并立即落盘
     */
    public static void update(String channel, String chatId, String sessionKey) {
        POINTERS.put(pointerKey(channel, chatId), sessionKey);
        persist();
    }

    private static String pointerKey(String channel, String chatId) {
        return channel + ":" + chatId;
    }

    private static void load() {
        if (storageFile == null || !Files.exists(storageFile)) {
            return;
        }
        try {
            Map<String, String> loaded = MAPPER.readValue(Files.readAllBytes(storageFile),
                    MAPPER.getTypeFactory().constructMapType(
                            HashMap.class, String.class, String.class));
            POINTERS.putAll(loaded);
            logger.info("Loaded active session pointers", Map.of("count", POINTERS.size()));
        } catch (Exception e) {
            logger.warn("Active session pointers unreadable, starting fresh: " + e.getMessage());
        }
    }

    private static synchronized void persist() {
        Path target = storageFile;
        if (target == null) {
            return;
        }
        Path temp = target.resolveSibling(FILE_NAME + "." + System.nanoTime() + ".tmp");
        try {
            Files.write(temp, MAPPER.writeValueAsBytes(POINTERS));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("Failed to persist active session pointers: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }
}
