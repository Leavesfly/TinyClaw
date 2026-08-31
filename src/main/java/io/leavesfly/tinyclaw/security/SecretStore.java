package io.leavesfly.tinyclaw.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 凭据保管库 - 让 Agent 能"用"密钥而不"看到"密钥。
 *
 * <h2>要消除的风险路径</h2>
 * <p>在此之前，Agent 需要一个临时密钥时唯一的办法是让用户在聊天里发出来。那条消息会
 * 进入会话转录长期落盘，也会随上下文一路发给模型提供方——一次性凭据由此变成永久泄露面。
 * 保管库把值收在本地文件里，对话与模型上下文中只出现 {@code ${secret:NAME}} 引用。</p>
 *
 * <h2>只写不读</h2>
 * <p>对外 API 只暴露存在性与元信息（{@link SecretInfo}），没有任何"按名取值"的公开方法。
 * 取值能力仅通过包内可见的 {@link #reveal} 提供给同包的 {@link SecretResolver}，
 * 后者只在工具执行的那一瞬间做替换。这样"谁能看到明文"在编译期就被限定为一个类。</p>
 *
 * <h2>出口绑定</h2>
 * <p>每条凭据可声明 {@code allowedHosts}。声明后该凭据只允许被替换进指向这些主机的调用，
 * 避免"给了 A 服务的 key，却被送到 B 服务"——这类误投在 Agent 自主决策下并不罕见。</p>
 */
public class SecretStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("security");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String SECRETS_FILE = "secrets.json";

    private final Path path;
    private final Map<String, SecretEntry> entries = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * @param workspace 工作空间路径；为空时保管库为纯内存模式（进程退出即丢）
     */
    public SecretStore(String workspace) {
        this.path = (workspace == null || workspace.isBlank())
                ? null
                : Paths.get(workspace, SECRETS_FILE);
        load();
    }

    /**
     * 一条凭据的公开元信息，<b>不含值</b>。
     *
     * @param name         引用名，用于 {@code ${secret:NAME}}
     * @param description  用途说明，供 Agent 判断该用哪一条
     * @param allowedHosts 允许送达的主机；空集合表示不限制
     */
    public record SecretInfo(String name, String description, Set<String> allowedHosts) {
    }

    /**
     * 存入或覆盖一条凭据。
     *
     * @param name         引用名，非空
     * @param value        明文值，非空
     * @param allowedHosts 允许送达的主机，null 或空表示不限制
     * @param description  用途说明，可为 null
     */
    public void put(String name, String value, Set<String> allowedHosts, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("凭据名不能为空");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("凭据值不能为空");
        }
        lock.writeLock().lock();
        try {
            SecretEntry entry = new SecretEntry();
            entry.name = name;
            entry.value = value;
            entry.description = description != null ? description : "";
            entry.allowedHosts = allowedHosts != null
                    ? new LinkedHashSet<>(allowedHosts) : new LinkedHashSet<>();
            entry.updatedAt = System.currentTimeMillis();
            entries.put(name, entry);
            persist();
            // 只记名字不记值：结构化日志会落盘，写进去等于换个地方泄露
            logger.info("Secret stored", Map.of("name", name,
                    "allowed_hosts", entry.allowedHosts.size()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除一条凭据，实际删除时返回 true
     */
    public boolean remove(String name) {
        lock.writeLock().lock();
        try {
            if (entries.remove(name) == null) {
                return false;
            }
            persist();
            logger.info("Secret removed", Map.of("name", name));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean has(String name) {
        lock.readLock().lock();
        try {
            return entries.containsKey(name);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 列出全部凭据的元信息（不含值）
     */
    public List<SecretInfo> list() {
        lock.readLock().lock();
        try {
            List<SecretInfo> infos = new ArrayList<>(entries.size());
            for (SecretEntry entry : entries.values()) {
                infos.add(new SecretInfo(entry.name, entry.description,
                        new LinkedHashSet<>(entry.allowedHosts)));
            }
            return infos;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 读取某条凭据声明的允许主机；不存在时返回空集合
     */
    public Set<String> allowedHosts(String name) {
        lock.readLock().lock();
        try {
            SecretEntry entry = entries.get(name);
            return entry != null ? new LinkedHashSet<>(entry.allowedHosts) : Set.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 取出明文值。<b>包内可见</b>，仅供 {@link SecretResolver} 在工具执行边界调用。
     *
     * <p>不要把它改成 public：一旦有第二个调用方，"明文只在工具参数里出现一瞬间"
     * 这个约束就无法再靠阅读代码验证了。</p>
     */
    String reveal(String name) {
        lock.readLock().lock();
        try {
            SecretEntry entry = entries.get(name);
            return entry != null ? entry.value : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 持久化 ====================

    private void load() {
        if (path == null) {
            return;
        }
        Map<String, SecretEntry> loaded = JsonFileStore.readJson(MAPPER, path,
                new TypeReference<LinkedHashMap<String, SecretEntry>>() {
                }, LinkedHashMap::new);
        if (loaded != null) {
            loaded.forEach((key, entry) -> {
                if (entry != null && entry.value != null) {
                    entry.name = key;
                    if (entry.allowedHosts == null) {
                        entry.allowedHosts = new LinkedHashSet<>();
                    }
                    entries.put(key, entry);
                }
            });
        }
    }

    /**
     * 落盘。{@code JsonFileStore} 以 600 创建后再写，因此不存在 644 可读窗口。
     */
    private void persist() {
        if (path == null) {
            return;
        }
        try {
            JsonFileStore.writeJson(MAPPER, path, entries);
        } catch (IOException e) {
            logger.error("Failed to persist secrets", Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 磁盘上的凭据条目。字段包可见，避免 getter 意外把值暴露成公开 API。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SecretEntry {
        public String name;
        public String value;
        public String description;
        public Set<String> allowedHosts = new LinkedHashSet<>();
        public long updatedAt;

        /** 防止误把整个条目打进日志或响应 */
        @JsonIgnore
        @Override
        public String toString() {
            return "SecretEntry{name=" + name + ", value=***}";
        }
    }
}
