package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 - 处理对话持久化
 * 
 * 负责管理用户与Agent之间的对话会话，包括：
 * 
 * 核心功能：
 * - 会话创建与获取：按需创建新会话或获取现有会话
 * - 消息历史管理：维护每一会话的完整对话历史
 * - 持久化存储：将会话数据保存到磁盘防止丢失
 * - 内存缓存：使用内存缓存提高访问性能
 * - 摘要管理：维护会话摘要以优化长对话处理
 * 
 * 设计特点：
 * - 线程安全：使用ConcurrentHashMap确保并发访问安全
 * - 懒加载：启动时仅建立会话键索引，会话内容首次访问时才从磁盘加载
 * - 自动保存：关键操作后自动持久化会话状态
 * - 错误恢复：具备从存储故障中恢复的能力
 * 
 * 数据结构：
 * - Session：单个会话的数据容器
 * - Message：单条消息记录
 * - 会话键格式：通常为"channel:chat_id"格式
 * 
 * 使用场景：
 * 1. Agent处理用户消息时获取对应的会话上下文
 * 2. 系统重启后恢复之前的对话状态
 * 3. 多用户并发访问时隔离各自的会话数据
 * 4. 长期运行的服务中管理大量活跃会话
 */
public class SessionManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("session");
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    private final Map<String, Session> sessions;
    private final Set<String> knownKeys;   // 磁盘上存在的会话键索引（含未加载的）
    private final String storagePath;
    
    public SessionManager(String storagePath) {
        this.sessions = new ConcurrentHashMap<>();
        this.knownKeys = ConcurrentHashMap.newKeySet();
        this.storagePath = storagePath;
        
        if (storagePath != null && !storagePath.isEmpty()) {
            try {
                Files.createDirectories(Paths.get(storagePath));
                indexSessions();
            } catch (IOException e) {
                logger.warn("Failed to create session storage directory: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取或创建会话（通过键）
     * 
     * 如果指定键的会话已在内存则直接返回；如果磁盘上存在则懒加载；
     * 否则创建新的会话实例并缓存。
     * 
     * @param key 会话键（通常是"channel:chat_id"格式）
     * @return 对应的会话实例
     */
    public Session getOrCreate(String key) {
        return sessions.computeIfAbsent(key, k -> {
            Session loaded = loadSessionFromDisk(k);
            if (loaded != null) {
                return loaded;
            }
            Session session = new Session(k);
            knownKeys.add(k);
            logger.debug("Created new session: " + key);
            return session;
        });
    }
    
    /**
     * 获取已存在的会话（内存优先，未加载时尝试从磁盘懒加载），不存在返回 null
     */
    private Session getLoaded(String key) {
        Session session = sessions.get(key);
        if (session != null) {
            return session;
        }
        if (!knownKeys.contains(key)) {
            return null;
        }
        return sessions.computeIfAbsent(key, this::loadSessionFromDisk);
    }
    
    /**
     * 添加简单消息到会话
     */
    public void addMessage(String sessionKey, String role, String content) {
        Session session = getOrCreate(sessionKey);
        session.addMessage(role, content);
    }
    
    /**
     * 添加完整消息（包括工具调用）到会话
     */
    public void addFullMessage(String sessionKey, Message message) {
        Session session = getOrCreate(sessionKey);
        session.addFullMessage(message);
    }

    /**
     * 添加工具调用记录到会话。
     * afterAssistantIndex 由调用方传入，表示该工具调用发生在第几条 assistant 消息之后。
     */
    public void addToolCallRecord(String sessionKey, ToolCallRecord record) {
        Session session = getOrCreate(sessionKey);
        session.addToolCallRecord(record);
    }

    /**
     * 获取当前 session 中 assistant 消息的数量，用于计算 afterAssistantIndex。
     */
    public int countAssistantMessages(String sessionKey) {
        Session session = getLoaded(sessionKey);
        return session != null ? session.countAssistantMessages() : 0;
    }

    /**
     * 获取会话的工具调用记录列表
     */
    public java.util.List<ToolCallRecord> getToolCallRecords(String sessionKey) {
        Session session = getLoaded(sessionKey);
        return session != null ? session.getToolCallRecords() : java.util.List.of();
    }
    
    /**
     * 获取会话的消息历史
     */
    public List<Message> getHistory(String sessionKey) {
        Session session = getLoaded(sessionKey);
        return session != null ? session.getHistory() : List.of();
    }
    
    /**
     * 获取会话的摘要
     */
    public String getSummary(String sessionKey) {
        Session session = getLoaded(sessionKey);
        return session != null ? session.getSummary() : "";
    }
    
    /**
     * 设置会话的摘要
     */
    public void setSummary(String sessionKey, String summary) {
        Session session = getLoaded(sessionKey);
        if (session != null) {
            session.setSummary(summary);
            session.setUpdated(Instant.now());
        }
    }
    
    /**
     * 截断会话的历史记录
     */
    public void truncateHistory(String sessionKey, int keepLast) {
        Session session = getLoaded(sessionKey);
        if (session != null) {
            session.truncateHistory(keepLast);
        }
    }
    
    /**
     * 保存会话到磁盘（原子写：先写临时文件再 rename，防止进程崩溃截断 JSON）
     */
    public void save(Session session) {
        if (storagePath == null || storagePath.isEmpty()) {
            return;
        }
        
        try {
            // 将 session key 转换为安全的文件名（Windows 不支持冒号）
            String safeFileName = toSafeFileName(session.getKey());
            Path target = Paths.get(storagePath, safeFileName + ".json");
            // 紧凑格式序列化（比 pretty-print 减少 ~40% IO，会话文件无需人工阅读）
            String json = objectMapper.writeValueAsString(session);
            
            // 原子写：先写临时文件再重命名，避免进程中途退出留下半个 JSON
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // 设置文件权限为 600（仅所有者可读写，防止其他用户读取会话内容）
            setOwnerOnlyPermissions(target);
            knownKeys.add(session.getKey());
            logger.debug("Saved session: " + session.getKey());
        } catch (IOException e) {
            logger.error("Failed to save session: " + session.getKey(), Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 启动时建立会话键索引（仅提取 key 字段，不保留消息内容），
     * 避免全量会话常驻内存；会话内容在首次访问时懒加载
     */
    private void indexSessions() {
        if (storagePath == null) {
            return;
        }
        
        File storageDir = new File(storagePath);
        if (!storageDir.exists() || !storageDir.isDirectory()) {
            return;
        }
        
        File[] files = storageDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            try {
                String key = objectMapper.readTree(file).path("key").asText(null);
                if (key != null && !key.isEmpty()) {
                    knownKeys.add(key);
                }
            } catch (IOException e) {
                logger.warn("Failed to index session file: " + file.getName());
            }
        }
        
        logger.info("Indexed " + knownKeys.size() + " sessions from storage");
    }
    
    /**
     * 从磁盘加载单个会话，文件不存在或解析失败返回 null
     */
    private Session loadSessionFromDisk(String key) {
        if (storagePath == null || storagePath.isEmpty()) {
            return null;
        }
        
        Path path = Paths.get(storagePath, toSafeFileName(key) + ".json");
        if (!Files.exists(path)) {
            return null;
        }
        
        try {
            String content = Files.readString(path);
            Session session = objectMapper.readValue(content, Session.class);
            knownKeys.add(session.getKey());
            logger.debug("Loaded session: " + session.getKey());
            return session;
        } catch (IOException e) {
            logger.warn("Failed to load session from file: " + path.getFileName());
            return null;
        }
    }
    
    /**
     * 获取所有会话键（含磁盘上尚未加载的会话）
     */
    public java.util.Set<String> getSessionKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>(knownKeys);
        keys.addAll(sessions.keySet());
        return keys;
    }
    
    /**
     * 删除会话
     */
    public void deleteSession(String key) {
        Session removed = sessions.remove(key);
        boolean known = knownKeys.remove(key);
        if ((removed != null || known) && storagePath != null) {
            try {
                String safeFileName = toSafeFileName(key);
                Files.deleteIfExists(Paths.get(storagePath, safeFileName + ".json"));
                logger.debug("Deleted session: " + key);
            } catch (IOException e) {
                logger.warn("Failed to delete session file: " + key);
            }
        }
    }
    
    /**
     * 设置文件权限为 owner-only (600)。
     * 在非 POSIX 文件系统（如 Windows）上静默跳过。
     */
    private void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // 非 POSIX 文件系统（Windows）或权限设置失败，静默忽略
        }
    }

    /**
     * 将 session key 转换为安全的文件名
     * 将不安全字符（如冒号、斜杠）替换为下划线
     */
    private String toSafeFileName(String key) {
        if (key == null) {
            return "unknown";
        }
        // 替换文件名中的不安全字符: : / \ * ? " < > |
        return key.replaceAll("[:/\\\\*?\"<>|]", "_");
    }
}