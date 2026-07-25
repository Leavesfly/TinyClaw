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
 * MemoryStore 数据安全相关的单元测试。
 *
 * 覆盖场景：
 * - 原子写入与重新加载的往返一致性
 * - 损坏 JSON 文件的备份保护（不静默清空历史记忆）
 * - diff 式替换保留整合期间新增的条目
 */
class MemoryStoreTest {

    @TempDir
    Path workspace;

    @Test
    void saveAndReloadRoundtrip() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addEntry("用户偏好深色主题", 0.8, List.of("preference"), "user_explicit");
        store.addEntry("项目使用 Java 17", 0.6, List.of("project"), "session_summary");

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
        store.addEntry("新记忆", 0.5, List.of(), "test");
        assertEquals(1, listCorruptBackups(memoryDir).size());
        assertEquals(1, new MemoryStore(workspace.toString()).getEntries().size());
    }

    @Test
    void replaceEntriesPreservesConcurrentAdds() {
        MemoryStore store = new MemoryStore(workspace.toString());
        store.addEntry("旧记忆A", 0.5, List.of(), "session_summary");
        store.addEntry("旧记忆B", 0.5, List.of(), "session_summary");

        // 模拟整合快照：仅包含 A、B
        Set<String> snapshotIds = store.getEntries().stream()
                .map(MemoryEntry::getId)
                .collect(Collectors.toSet());

        // 模拟 LLM 整合期间并发写入的新记忆
        store.addEntry("整合期间新增的记忆C", 0.7, List.of(), "session_summary");

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
        store.addEntry("高频访问记忆", 0.9, List.of("hot"), "test");

        MemoryEntry entry = store.getEntries().get(0);
        entry.recordAccess();
        entry.recordAccess();
        store.flush();

        MemoryEntry reloaded = new MemoryStore(workspace.toString()).getEntries().get(0);
        assertEquals(2, reloaded.getAccessCount(), "flush 后访问计数应持久化");
    }

    private List<Path> listCorruptBackups(Path memoryDir) throws IOException {
        try (Stream<Path> stream = Files.list(memoryDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().contains(".corrupt."))
                    .collect(Collectors.toList());
        }
    }
}
