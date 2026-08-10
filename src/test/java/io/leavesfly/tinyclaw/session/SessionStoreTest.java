package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.providers.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonlSessionStore 与 SessionManager 的持久化行为测试
 *
 * <h2>覆盖重点（均为曾经存在的缺陷）</h2>
 * <ul>
 *   <li>增量 append 后重新加载，内容完整一致</li>
 *   <li>解析失败的文件被隔离保留，不被新会话覆盖销毁</li>
 *   <li>不同 key 不会因为文件名替换规则而落到同一文件</li>
 *   <li>会话列表按最后更新时间排序，而非哈希顺序</li>
 *   <li>删除后的会话不会被持有旧引用的异步任务写回复活</li>
 *   <li>旧的单文件 JSON 会话能自动迁移且保留备份</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=SessionStoreTest
 * </pre>
 */
@DisplayName("会话持久化测试")
class SessionStoreTest {

    @Test
    @DisplayName("增量落盘: 多次 append 后重新加载内容完整")
    void appendOnly_ReloadKeepsEverything(@TempDir Path dir) throws IOException {
        String key = "telegram:123";

        SessionManager first = new SessionManager(dir.toString());
        first.addMessage(key, "user", "hello");
        first.save(key);
        first.addMessage(key, "assistant", "hi");
        first.save(key);
        first.addToolCallRecord(key, new ToolCallRecord("read_file", "{}", "ok", true, 1));
        first.compactContext(key, "summary text", 1);
        first.save(key);
        first.close();

        SessionManager reloaded = new SessionManager(dir.toString());
        List<Message> history = reloaded.getHistory(key);

        assertEquals(2, history.size());
        assertEquals("hello", history.get(0).getContent());
        assertEquals("hi", history.get(1).getContent());
        assertNotNull(history.get(0).getId(), "消息 id 应被持久化");
        assertNotNull(history.get(0).getTimestamp(), "消息时间戳应被持久化");
        assertEquals("summary text", reloaded.getSummary(key));
        assertEquals(1, reloaded.getToolCallRecords(key).size());
        // 上下文起点被持久化：压缩状态在重启后保持
        assertEquals(1, reloaded.getContextMessages(key).size());
    }

    @Test
    @DisplayName("增量落盘: 只追加新增内容，不重写已有行")
    void appendOnly_DoesNotRewriteExistingLines(@TempDir Path dir) throws IOException {
        String key = "cli:direct";
        SessionManager manager = new SessionManager(dir.toString());

        manager.addMessage(key, "user", "first");
        manager.save(key);
        long sizeAfterFirst = transcriptSize(dir);

        manager.addMessage(key, "assistant", "second");
        manager.save(key);
        long sizeAfterSecond = transcriptSize(dir);

        assertTrue(sizeAfterSecond > sizeAfterFirst, "第二次落盘应在原文件末尾追加");

        // 重复保存无新增内容时不应产生任何写入
        manager.save(key);
        assertEquals(sizeAfterSecond, transcriptSize(dir), "无增量时不应重复写入");
    }

    @Test
    @DisplayName("损坏隔离: 无法解析的文件被移入 corrupt 保留，不被覆盖")
    void corruptFile_IsQuarantinedNotDestroyed(@TempDir Path dir) throws IOException {
        String key = "web:default";
        SessionManager manager = new SessionManager(dir.toString());
        manager.addMessage(key, "user", "important history");
        manager.save(key);
        manager.close();

        // 模拟文件损坏
        Path transcript = findTranscript(dir);
        String garbage = "!!! not json at all !!!";
        Files.writeString(transcript, garbage);

        SessionManager reopened = new SessionManager(dir.toString());
        // 读不出来时不应抛异常，而是当作新会话
        assertTrue(reopened.getHistory(key).isEmpty());
        reopened.addMessage(key, "user", "brand new");
        reopened.save(key);
        reopened.close();

        // 原始内容必须被保留在 corrupt 目录，而不是被新会话覆盖销毁
        Path corruptDir = dir.resolve("corrupt");
        assertTrue(Files.isDirectory(corruptDir), "应创建 corrupt 隔离目录");
        try (Stream<Path> files = Files.list(corruptDir)) {
            List<Path> quarantined = files.toList();
            assertEquals(1, quarantined.size());
            assertEquals(garbage, Files.readString(quarantined.get(0)));
        }
    }

    @Test
    @DisplayName("文件名映射: 不同 key 不共用同一个文件")
    void fileNaming_AvoidsCollision(@TempDir Path dir) throws IOException {
        SessionManager manager = new SessionManager(dir.toString());

        // 旧实现把 ':' 替换为 '_'，这两个 key 会落到同一个文件互相覆盖
        manager.addMessage("web:default", "user", "from colon key");
        manager.save("web:default");
        manager.addMessage("web_default", "user", "from underscore key");
        manager.save("web_default");
        manager.close();

        SessionManager reloaded = new SessionManager(dir.toString());
        assertEquals("from colon key", reloaded.getHistory("web:default").get(0).getContent());
        assertEquals("from underscore key", reloaded.getHistory("web_default").get(0).getContent());
    }

    @Test
    @DisplayName("会话列表: 按最后更新时间倒序，且不加载会话正文")
    void listMeta_SortedByUpdatedDesc(@TempDir Path dir) throws IOException, InterruptedException {
        SessionManager manager = new SessionManager(dir.toString());

        manager.addMessage("a:1", "user", "oldest");
        manager.save("a:1");
        Thread.sleep(5);
        manager.addMessage("b:2", "user", "middle");
        manager.save("b:2");
        Thread.sleep(5);
        manager.addMessage("c:3", "user", "newest");
        manager.save("c:3");
        manager.close();

        SessionManager reloaded = new SessionManager(dir.toString());
        List<SessionMeta> metas = reloaded.listMeta();

        assertEquals(3, metas.size());
        assertEquals("c:3", metas.get(0).getKey());
        assertEquals("a:1", metas.get(2).getKey());
        assertEquals("newest", metas.get(0).getTitle());
        assertEquals(1, metas.get(0).getMessageCount());
    }

    @Test
    @DisplayName("删除会话: 持有旧引用的异步任务不会把它写回复活")
    void deletedSession_IsNotResurrected(@TempDir Path dir) throws IOException {
        String key = "telegram:456";
        SessionManager manager = new SessionManager(dir.toString());

        Session stale = manager.getOrCreate(key);
        stale.addMessage("user", "hello");
        manager.save(key);

        manager.deleteSession(key);

        // 模拟摘要线程仍持有引用并尝试落盘
        stale.setSummary("late summary");
        manager.save(stale);

        assertFalse(manager.exists(key), "已删除的会话不应被重新写回");
        assertTrue(manager.listMeta().isEmpty());
    }

    @Test
    @DisplayName("旧格式迁移: 单文件 JSON 自动转为 JSONL 并保留备份")
    void legacyJson_IsMigrated(@TempDir Path dir) throws IOException {
        String key = "telegram:999";
        String legacyJson = """
                {"key":"telegram:999",\
                "messages":[{"role":"user","content":"legacy question"},\
                {"role":"assistant","content":"legacy answer"}],\
                "summary":"legacy summary",\
                "created":"2024-01-01T00:00:00Z",\
                "updated":"2024-01-02T00:00:00Z",\
                "toolCallRecords":[]}""";
        Files.writeString(dir.resolve("telegram_999.json"), legacyJson, StandardCharsets.UTF_8);

        SessionManager manager = new SessionManager(dir.toString());
        List<Message> history = manager.getHistory(key);

        assertEquals(2, history.size());
        assertEquals("legacy question", history.get(0).getContent());
        assertEquals("legacy summary", manager.getSummary(key));

        // 旧文件被重命名保留，而不是原地删除
        assertTrue(Files.exists(dir.resolve("telegram_999.json.migrated")));
        assertFalse(Files.exists(dir.resolve("telegram_999.json")));

        // 迁移后可继续增量写入
        manager.addMessage(key, "user", "after migration");
        manager.save(key);
        manager.close();

        SessionManager reloaded = new SessionManager(dir.toString());
        assertEquals(3, reloaded.getHistory(key).size());
    }

    @Test
    @DisplayName("索引重建: 索引文件丢失后能从转录恢复会话列表")
    void index_RebuildsFromTranscripts(@TempDir Path dir) throws IOException {
        SessionManager manager = new SessionManager(dir.toString());
        manager.addMessage("a:1", "user", "one");
        manager.save("a:1");
        manager.close();

        Files.deleteIfExists(dir.resolve("_index.json"));

        SessionManager reloaded = new SessionManager(dir.toString());
        List<SessionMeta> metas = reloaded.listMeta();

        assertEquals(1, metas.size());
        assertEquals("a:1", metas.get(0).getKey());
        assertEquals(1, metas.get(0).getMessageCount());
    }

    @Test
    @DisplayName("内存模式: storagePath 为空时不落盘但功能可用")
    void memoryOnlyMode_Works() {
        SessionManager manager = new SessionManager(null);

        manager.addMessage("x:1", "user", "in memory");
        manager.save("x:1");

        assertEquals(1, manager.getHistory("x:1").size());
        assertEquals(1, manager.listMeta().size());
        assertTrue(manager.exists("x:1"));
    }

    // ==================== 辅助方法 ====================

    private Path findTranscript(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未找到 jsonl 转录文件"));
        }
    }

    private long transcriptSize(Path dir) throws IOException {
        return Files.size(findTranscript(dir));
    }
}
