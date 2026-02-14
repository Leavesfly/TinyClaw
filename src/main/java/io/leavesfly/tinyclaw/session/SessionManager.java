package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * - 懒加载：会话数据按需从磁盘加载到内存
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
    private final String storagePath;
    
    public SessionManager(String storagePath) {
        this.sessions = new ConcurrentHashMap<>();
        this.storagePath = storagePath;
        
        if (storagePath != null && !storagePath.isEmpty()) {
            try {
                Files.createDirectories(Paths.get(storagePath));
                loadSessions();
            } catch (IOException e) {
                logger.warn("Failed to create session storage directory: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取或创建会话（通过键）
     * 
     * 如果指定键的会话已存在则返回现有会话，
     * 否则创建新的会话实例并缓存。
     * 
     * @param key 会话键（通常是"channel:chat_id"格式）
     * @return 对应的会话实例
     */
    public Session getOrCreate(String key) {
        return sessions.computeIfAbsent(key, k -> {
            Session session = new Session(k);
            logger.debug("Created new session: " + key);
            return session;
        });
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
     * 获取会话的消息历史
     */
    public List<Message> getHistory(String sessionKey) {
        Session session = sessions.get(sessionKey);
        return session != null ? session.getHistory() : List.of();
    }
    
    /**
     * 获取会话的摘要
     */
    public String getSummary(String sessionKey) {
        Session session = sessions.get(sessionKey);
        return session != null ? session.getSummary() : "";
    }
    
    /**
     * 设置会话的摘要
     */
    public void setSummary(String sessionKey, String summary) {
        Session session = sessions.get(sessionKey);
        if (session != null) {
            session.setSummary(summary);
            session.setUpdated(Instant.now());
        }
    }
    
    /**
     * 截断会话的历史记录
     */
    public void truncateHistory(String sessionKey, int keepLast) {
        Session session = sessions.get(sessionKey);
        if (session != null) {
            session.truncateHistory(keepLast);
        }
    }
    
    /**
     * 保存会话到磁盘
     */
    public void save(Session session) {
        if (storagePath == null || storagePath.isEmpty()) {
            return;
        }
        
        try {
            // 将 session key 转换为安全的文件名（Windows 不支持冒号）
            String safeFileName = toSafeFileName(session.getKey());
            String sessionFile = Paths.get(storagePath, safeFileName + ".json").toString();
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(session);
            Files.writeString(Paths.get(sessionFile), json);
            logger.debug("Saved session: " + session.getKey());
        } catch (IOException e) {
            logger.error("Failed to save session: " + session.getKey(), Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 从磁盘加载所有会话
     */
    private void loadSessions() {
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
                String content = Files.readString(file.toPath());
                Session session = objectMapper.readValue(content, Session.class);
                sessions.put(session.getKey(), session);
                logger.debug("Loaded session: " + session.getKey());
            } catch (IOException e) {
                logger.warn("Failed to load session from file: " + file.getName());
            }
        }
        
        logger.info("Loaded " + sessions.size() + " sessions from storage");
    }
    
    /**
     * 获取所有会话键
     */
    public java.util.Set<String> getSessionKeys() {
        return sessions.keySet();
    }
    
    /**
     * 删除会话
     */
    public void deleteSession(String key) {
        Session removed = sessions.remove(key);
        if (removed != null && storagePath != null) {
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
    
    /**
     * 从安全文件名还原 session key
     * 注意：这是一个近似还原，无法完美还原所有情况
     */
    private String fromSafeFileName(String safeFileName) {
        // 将单个下划线还原为冒号（第一个出现的下划线）
        return safeFileName.replaceFirst("_", ":");
    }
}