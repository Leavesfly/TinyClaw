package io.leavesfly.tinyclaw.session;

import java.util.List;

/**
 * 会话存储抽象 - 把「会话存在哪」与「会话怎么用」解耦
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>{@link #load} 读不出内容时返回 null，且**不得**破坏原始文件——损坏文件应被隔离保留，
 *       避免上层新建空会话后把它覆盖掉；</li>
 *   <li>{@link #persist} 只写入尚未落盘的增量，必须保证进程崩溃后已写入的部分依然可读；</li>
 *   <li>{@link #listMeta} 不得触发任何会话正文的加载。</li>
 * </ul>
 */
public interface SessionStore {

    /**
     * 加载会话，不存在或无法解析时返回 null
     */
    Session load(String key);

    /**
     * 把会话尚未落盘的增量写入存储
     */
    void persist(Session session);

    /**
     * 删除会话及其存储文件
     */
    void delete(String key);

    /**
     * 列出所有会话的元信息，按最后更新时间倒序
     */
    List<SessionMeta> listMeta();

    /**
     * 存储中是否存在该会话
     */
    boolean exists(String key);

    /**
     * 刷盘尚未持久化的索引数据（进程退出前调用）
     */
    void flush();

    /**
     * 纯内存实现：storagePath 未配置时使用，会话只存在于 SessionManager 缓存中。
     */
    SessionStore NOOP = new SessionStore() {

        @Override
        public Session load(String key) {
            return null;
        }

        @Override
        public void persist(Session session) {
            session.markFullyPersisted();
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public List<SessionMeta> listMeta() {
            return List.of();
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public void flush() {
        }
    };
}
