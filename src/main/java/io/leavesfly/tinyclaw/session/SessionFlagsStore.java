package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话整理标记存储（P4）：自定义标题、置顶、归档；P5 扩展会话记忆模式。
 *
 * <p>与 JsonlSessionStore 的消息转录分离：整理标记属于「可丢弃后重建」的偏好类元信息，
 * 单文件 JSON 原子写（{@code workspace/session-flags.json}），不混入不可变转录。
 * 首条消息摘要仍由 SessionMeta.title 提供（默认标题），displayTitle 仅覆盖展示。</p>
 *
 * <p>memoryMode（P5）：DEFAULT 或 OFF。OFF 仅关闭该会话的长期记忆自动检索与自动提取写入，
 * 聊天历史仍正常保存（UI 命名为「不使用长期记忆」，不称为无痕模式）。</p>
 *
 * <p>线程安全：内存 map + 每次变更原子落盘；损坏文件按空处理（丢失仅影响整理标记）。</p>
 */
public class SessionFlagsStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("session");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 会话记忆模式：默认（启用长期记忆）。 */
    public static final String MEMORY_MODE_DEFAULT = "DEFAULT";

    /** 会话记忆模式：不使用长期记忆（聊天历史仍保存）。 */
    public static final String MEMORY_MODE_OFF = "OFF";

    /** 单会话整理标记。 */
    public static final class Flags {
        public String displayTitle;
        public boolean pinned;
        public boolean archived;

        /** 会话记忆模式：null/DEFAULT 启用；OFF 关闭自动检索与提取。旧数据缺失为 null。 */
        public String memoryMode;

        /**
         * 所属项目 id（P4，可为 null）：null 表示无项目。项目存在性由 ProjectStore 校验，
         * 这里只存标记；项目删除后残留的 projectId 视为无项目（指令不再注入）。
         */
        public String projectId;

        Flags() {
        }

        public Flags(String displayTitle, boolean pinned, boolean archived) {
            this.displayTitle = displayTitle;
            this.pinned = pinned;
            this.archived = archived;
            this.memoryMode = MEMORY_MODE_DEFAULT;
        }
    }

    private final Path file;
    private final ConcurrentHashMap<String, Flags> flags = new ConcurrentHashMap<>();

    /**
     * @param storagePath 标记文件路径（如 workspace/session-flags.json）；null 表示纯内存（测试）
     */
    public SessionFlagsStore(String storagePath) {
        this.file = storagePath != null ? Paths.get(storagePath) : null;
        if (this.file != null) {
            load();
        }
    }

    /** 读取某会话的整理标记（无则返回空默认）。 */
    public Flags get(String sessionKey) {
        return flags.computeIfAbsent(sessionKey, k -> new Flags(null, false, false));
    }

    /** 设置自定义标题（空串视为清除，回退默认摘要标题）。 */
    public synchronized void setDisplayTitle(String sessionKey, String title) {
        Flags f = get(sessionKey);
        f.displayTitle = title != null && !title.isBlank() ? title.trim() : null;
        persist();
    }

    /** 置顶开关。 */
    public synchronized void setPinned(String sessionKey, boolean pinned) {
        get(sessionKey).pinned = pinned;
        persist();
    }

    /** 归档开关（归档不删除任何资源）。 */
    public synchronized void setArchived(String sessionKey, boolean archived) {
        get(sessionKey).archived = archived;
        persist();
    }

    /**
     * 会话记忆模式开关（P5）：仅接受 DEFAULT / OFF（大小写不敏感），其他值拋出异常由调用方回 400。
     * 切换实时生效（读写路径的门每次判断时重读本存储）。
     */
    public synchronized void setMemoryMode(String sessionKey, String memoryMode) {
        if (memoryMode == null || memoryMode.isBlank()) {
            get(sessionKey).memoryMode = MEMORY_MODE_DEFAULT;
        } else {
            String normalized = memoryMode.trim().toUpperCase(java.util.Locale.ROOT);
            if (!MEMORY_MODE_DEFAULT.equals(normalized) && !MEMORY_MODE_OFF.equals(normalized)) {
                throw new IllegalArgumentException("memoryMode must be DEFAULT or OFF, got: " + memoryMode);
            }
            get(sessionKey).memoryMode = normalized;
        }
        persist();
    }

    /**
     * 某会话是否关闭长期记忆（memoryMode=OFF）。旧数据/未设置均返回 false（默认启用）。
     */
    public boolean isMemoryOff(String sessionKey) {
        Flags f = flags.get(sessionKey);
        return f != null && MEMORY_MODE_OFF.equals(f.memoryMode);
    }

    /**
     * 设置所属项目 id（P4）：null/空白清除归属。项目存在性由调用方（Handler 层）校验，
     * 这里只写标记；「产生消息后归属固定」的约束同样由调用方执行。
     */
    public synchronized void setProjectId(String sessionKey, String projectId) {
        get(sessionKey).projectId = projectId != null && !projectId.isBlank()
                ? projectId.trim() : null;
        persist();
    }

    /** 会话删除时清理标记。 */
    public synchronized void remove(String sessionKey) {
        flags.remove(sessionKey);
        persist();
    }

    /** 全量视图（SessionsHandler 合并到列表）。 */
    public Map<String, Flags> all() {
        return Map.copyOf(flags);
    }

    // ==================== 持久化 ====================

    private void load() {
        try {
            var root = JsonFileStore.readJson(MAPPER, file,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Flags>>() {
                    }, java.util.Map::of);
            flags.putAll(root);
        } catch (Exception e) {
            logger.warn("Session flags unreadable, starting fresh", Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            JsonFileStore.writeJson(MAPPER, file, flags);
        } catch (IOException e) {
            logger.error("Failed to persist session flags", Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }
}
