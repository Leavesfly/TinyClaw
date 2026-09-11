package io.leavesfly.tinyclaw.web.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 成果存储测试：登记、版本快照、会话隔离、外部改动检测、非法 ID。
 */
class ArtifactStoreTest {

    @TempDir
    Path tempDir;

    private ArtifactStore newStore() {
        return new ArtifactStore(tempDir.resolve("artifacts").toString());
    }

    @Test
    @DisplayName("写入登记：同文件多次写递增 revision 并保留版本快照")
    void record_SameFileIncrementsRevision() throws Exception {
        ArtifactStore store = newStore();
        Path target = tempDir.resolve("report.md");
        Files.writeString(target, "# v1");

        store.record("web:default", target.toString());
        ArtifactRecord r1 = store.listBySession("web:default").get(0);
        assertEquals(1, r1.revision);

        Files.writeString(target, "# v2 内容更新");
        store.record("web:default", target.toString());
        ArtifactRecord r2 = store.listBySession("web:default").get(0);
        assertEquals(2, r2.revision, "同文件多次写应复用成果条目并递增版本");
        assertEquals(r1.id, r2.id);

        // 版本快照可回放：rev1 是旧内容，rev0（当前）是新内容
        assertEquals("# v1", new String(store.readContent(r1.id, 1)).replace("\n", ""));
        assertEquals("# v2 内容更新", new String(store.readContent(r1.id, 0)));
    }

    @Test
    @DisplayName("会话隔离：成果列表只含本会话记录")
    void listBySession_Isolated() throws Exception {
        ArtifactStore store = newStore();
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "A");
        Files.writeString(b, "B");
        store.record("web:one", a.toString());
        store.record("web:two", b.toString());

        List<ArtifactRecord> one = store.listBySession("web:one");
        assertEquals(1, one.size());
        assertEquals("a.txt", one.get(0).name);
        assertTrue(store.listBySession("web:other").isEmpty());
    }

    @Test
    @DisplayName("原文件删除后：登记仍在，读取当前版本报「已移除」")
    void deletedFile_StillListedButUnreadable() throws Exception {
        ArtifactStore store = newStore();
        Path target = tempDir.resolve("gone.md");
        Files.writeString(target, "content");
        store.record("web:default", target.toString());
        ArtifactRecord record = store.listBySession("web:default").get(0);

        Files.delete(target);

        // 登记信息保留
        assertNotNull(store.get(record.id));
        // 当前版本读取失败，历史快照仍可读
        assertThrows(IOException.class, () -> store.readContent(record.id, 0));
        assertEquals("content", new String(store.readContent(record.id, 1)));
    }

    @Test
    @DisplayName("版本列表：外部改动通过 hash 变化暴露")
    void versions_DetectsExternalModification() throws Exception {
        ArtifactStore store = newStore();
        Path target = tempDir.resolve("doc.md");
        Files.writeString(target, "original");
        store.record("web:default", target.toString());
        ArtifactRecord record = store.listBySession("web:default").get(0);

        // 外部（非 Agent）修改文件
        Files.writeString(target, "tampered externally");

        List<Map<String, Object>> versions = store.versions(record.id);
        assertEquals(1, versions.size());
        assertTrue((Boolean) versions.get(0).get("currentHashChanged"),
                "外部改动应通过 hash 差异暴露，不冒充登记版本");
    }

    @Test
    @DisplayName("record 静默容错：目标路径不存在不抛异常")
    void record_MissingPath_Silent() {
        ArtifactStore store = newStore();
        store.record("web:default", tempDir.resolve("never-exists.txt").toString());
        assertTrue(store.listBySession("web:default").isEmpty(), "读不到内容则不登记");
    }

    @Test
    @DisplayName("非法成果 ID（路径遍历尝试）被拒绝")
    void illegalId_Rejected() {
        ArtifactStore store = newStore();
        assertThrows(IOException.class, () -> store.readContent("../../etc/passwd", 0));
        assertThrows(IOException.class, () -> store.readContent("short", 0));
        assertThrows(IOException.class, () -> store.readTextContent("zzzzzzzzzzzz", 0));
    }

    @Test
    @DisplayName("持久化恢复：重启后登记与快照仍在")
    void restart_RestoresRecords() throws Exception {
        Path storeDir = tempDir.resolve("artifacts2");
        ArtifactStore first = new ArtifactStore(storeDir.toString());
        Path target = tempDir.resolve("keep.txt");
        Files.writeString(target, "kept content");
        first.record("web:default", target.toString());
        String id = first.listBySession("web:default").get(0).id;

        // 模拟重启
        ArtifactStore restarted = new ArtifactStore(storeDir.toString());
        List<ArtifactRecord> records = restarted.listBySession("web:default");
        assertEquals(1, records.size(), "重启后登记应恢复");
        assertEquals(id, records.get(0).id);
        assertEquals("kept content", new String(restarted.readContent(id, 1)));
    }

    @Test
    @DisplayName("null 会话按 global 登记仍可查询")
    void nullSession_RecordedAsGlobal() throws Exception {
        ArtifactStore store = newStore();
        Path target = tempDir.resolve("g.txt");
        Files.writeString(target, "global write");
        store.record(null, target.toString());
        assertEquals(1, store.listBySession("global").size());
        assertFalse(store.listBySession("global").get(0).name.isEmpty());
    }
}
