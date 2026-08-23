package io.leavesfly.tinyclaw.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryStore 数据安全与归属隔离相关的单元测试。
 *
 * 覆盖场景：
 * - 原子写入与重新加载的往返一致性
 * - 损坏 JSON 文件的备份保护（不静默清空历史记忆）
 * - diff 式替换保留整合期间新增的条目
 * - 归属域隔离：不可见域的条目与主题不得进入上下文
 * - 索引层不得携带记忆原文
 */
class MemoryStoreTest {

    @TempDir
    Path workspace;

    @Test
    void saveAndReloadRoundtrip() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addGlobalEntry("用户偏好深色主题", 0.8, List.of("preference"), "user_explicit");
        store.addGlobalEntry("项目使用 Java 17", 0.6, List.of("project"), "session_summary");

        // 用新实例重新加载，验证持久化完整性
        MemoryStore reloaded = new MemoryStore(workspace.toString());
        List<MemoryEntry> entries = reloaded.getEntries();
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.getContent().contains("深色主题")));
        assertTrue(entries.stream().anyMatch(e -> "session_summary".equals(e.getSource())));
    }

    @Test
    void corruptFileIsBackedUpInsteadOfSilentWipe() throws IOException {
        Path memoryDir = workspace.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoriesFile = memoryDir.resolve("MEMORIES.json");
        String garbage = "{ this is not valid json ]";
        Files.writeString(memoriesFile, garbage);

        MemoryStore store = new MemoryStore(workspace.toString());
        assertTrue(store.getEntries().isEmpty(), "损坏文件应加载为空列表");

        // 损坏文件应被备份为 .corrupt.<时间戳>，原始内容可供人工恢复
        List<Path> backups = listCorruptBackups(memoryDir);
        assertEquals(1, backups.size(), "应存在一个损坏备份文件");
        assertEquals(garbage, Files.readString(backups.get(0)));

        // 后续写入不应破坏备份
        store.addGlobalEntry("新记忆", 0.5, List.of(), "test");
        assertEquals(1, listCorruptBackups(memoryDir).size());
        assertEquals(1, new MemoryStore(workspace.toString()).getEntries().size());
    }

    @Test
    void replaceEntriesPreservesConcurrentAdds() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addGlobalEntry("旧记忆A", 0.5, List.of(), "session_summary");
        store.addGlobalEntry("旧记忆B", 0.5, List.of(), "session_summary");

        // 模拟整合快照：仅包含 A、B
        Set<String> snapshotIds = store.getEntries().stream()
                .map(MemoryEntry::getId)
                .collect(Collectors.toSet());

        // 模拟 LLM 整合期间并发写入的新记忆
        store.addGlobalEntry("整合期间新增的记忆C", 0.7, List.of(), "session_summary");

        // diff 式替换：移除快照条目，加入整合结果
        MemoryEntry consolidated = new MemoryEntry("整合后的记忆D", 0.6, List.of(), "evolution_consolidate");
        store.replaceEntries(snapshotIds, List.of(consolidated));

        List<String> contents = store.getEntries().stream()
                .map(MemoryEntry::getContent)
                .collect(Collectors.toList());
        assertEquals(2, contents.size());
        assertTrue(contents.contains("整合期间新增的记忆C"), "整合期间新增的条目不应被覆盖");
        assertTrue(contents.contains("整合后的记忆D"));
        assertFalse(contents.contains("旧记忆A"));
        assertFalse(contents.contains("旧记忆B"));
    }

    @Test
    void flushPersistsAccessCounts() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addGlobalEntry("高频访问记忆", 0.9, List.of("hot"), "test");

        MemoryEntry entry = store.getEntries().get(0);
        entry.recordAccess();
        entry.recordAccess();
        store.flush();

        MemoryEntry reloaded = new MemoryStore(workspace.toString()).getEntries().get(0);
        assertEquals(2, reloaded.getAccessCount(), "flush 后访问计数应持久化");
    }

    // ==================== 归属域隔离 ====================

    @Test
    void memoryContextOnlyExposesVisibleScopes() {
        MemoryStore store = new MemoryStore(workspace.toString());
        String aliceScope = MemoryScope.ofUser("feishu", "alice");
        String bobScope = MemoryScope.ofUser("dingtalk", "bob");

        store.addEntry(aliceScope, "alice 的银行卡尾号是 1234", 0.9, List.of("secret"), "user_explicit");
        store.addEntry(bobScope, "bob 在做支付重构", 0.9, List.of("project"), "user_explicit");
        store.addGlobalEntry("工具调用失败时应先检查参数", 0.9, List.of("lesson"), "evolution_feedback");

        String bobContext = store.getMemoryContext("重构 银行卡 工具", 2048,
                MemoryScope.visibleScopes("dingtalk", "bob", "chat-x"));

        assertTrue(bobContext.contains("bob 在做支付重构"), "自己域的记忆应可见");
        assertTrue(bobContext.contains("工具调用失败"), "全局域记忆应对所有人可见");
        assertFalse(bobContext.contains("1234"), "其他用户域的记忆绝不得注入");
    }

    @Test
    void defaultOverloadFallsBackToGlobalScopeOnly() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addEntry(MemoryScope.ofChat("qq", "group-1"), "群内私密讨论", 0.9, List.of(), "session_summary");
        store.addGlobalEntry("可共享的通用知识", 0.9, List.of(), "user_explicit");

        // 不传可见域的重载是安全默认：只能看到全局域
        String context = store.getMemoryContext("私密 知识", 2048);

        assertTrue(context.contains("可共享的通用知识"));
        assertFalse(context.contains("群内私密讨论"), "拿不到身份时不得泄露域内记忆");
    }

    @Test
    void legacyEntriesWithoutScopeAreTreatedAsGlobal() throws IOException {
        Path memoryDir = workspace.resolve("memory");
        Files.createDirectories(memoryDir);
        // 模拟引入归属域之前落盘的历史数据：没有 scope 字段
        Files.writeString(memoryDir.resolve("MEMORIES.json"), """
                [ {
                  "id" : "legacy-1",
                  "content" : "历史遗留的记忆",
                  "importance" : 0.7,
                  "createdAt" : "2026-01-01T00:00:00Z",
                  "lastAccessedAt" : "2026-01-01T00:00:00Z",
                  "accessCount" : 1,
                  "tags" : [ "legacy" ],
                  "source" : "session_summary"
                } ]
                """);

        MemoryStore store = new MemoryStore(workspace.toString());
        List<MemoryEntry> entries = store.getEntries();
        assertEquals(1, entries.size());
        assertEquals(MemoryScope.GLOBAL, entries.get(0).getScope(),
                "无 scope 字段的历史条目应归入全局域，保持原有可见行为");
    }

    @Test
    void topicsAreIsolatedByScope() {
        MemoryStore store = new MemoryStore(workspace.toString());
        String aliceScope = MemoryScope.ofUser("feishu", "alice");

        store.writeTopic(aliceScope, "user-preferences", "- alice 偏好用 Kotlin");
        store.writeTopic("project-patterns", "- 项目约定使用 Maven");

        assertEquals(List.of("user-preferences"), store.listTopics(aliceScope));
        assertEquals(List.of("project-patterns"), store.listTopics(),
                "域内主题目录不得被当成全局主题列出");

        String bobContext = store.getMemoryContext("preferences patterns", 2048,
                MemoryScope.visibleScopes("feishu", "bob", "chat-y"));
        assertFalse(bobContext.contains("Kotlin"), "其他用户域的主题不得注入");
        assertTrue(bobContext.contains("Maven"));
    }

    @Test
    void rebuildIndexDoesNotLeakMemoryContent() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addEntry(MemoryScope.ofUser("feishu", "alice"),
                "alice 的住址是西湖区某小区", 1.0, List.of("secret"), "user_explicit");

        store.rebuildIndex();

        String index = store.readIndex();
        assertFalse(index.contains("西湖区"),
                "MEMORY.md 无条件注入所有会话，不得写入任何记忆原文");
        assertTrue(index.contains("Total: 1 entries"), "统计量仍应保留");
    }

    private List<Path> listCorruptBackups(Path memoryDir) throws IOException {
        try (Stream<Path> stream = Files.list(memoryDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().contains(".corrupt."))
                    .collect(Collectors.toList());
        }
    }
}
