package io.leavesfly.tinyclaw.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 会话整理标记存储测试：标题覆盖、置顶/归档开关、持久化重建、删除清理。
 */
class SessionFlagsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("标题设置与清除：空串回退默认摘要标题")
    void displayTitle_SetAndClear() {
        SessionFlagsStore store = new SessionFlagsStore(null);
        store.setDisplayTitle("web:1", "季度报告");
        assertEquals("季度报告", store.get("web:1").displayTitle);

        store.setDisplayTitle("web:1", "  ");
        assertNull(store.get("web:1").displayTitle, "空串视为清除");
        assertFalse(store.get("web:2").archived);
    }

    @Test
    @DisplayName("置顶与归档独立开关")
    void pinnedAndArchived_Toggle() {
        SessionFlagsStore store = new SessionFlagsStore(null);
        store.setPinned("web:1", true);
        store.setArchived("web:1", true);
        assertTrue(store.get("web:1").pinned);
        assertTrue(store.get("web:1").archived);

        store.setPinned("web:1", false);
        assertFalse(store.get("web:1").pinned);
        assertTrue(store.get("web:1").archived, "置顶取消不影响归档");
    }

    @Test
    @DisplayName("持久化：重启后标记重建")
    void persistence_Restores() {
        Path file = tempDir.resolve("session-flags.json");
        SessionFlagsStore first = new SessionFlagsStore(file.toString());
        first.setDisplayTitle("web:1", "置顶会话");
        first.setPinned("web:1", true);
        first.setArchived("web:2", true);

        // 模拟重启
        SessionFlagsStore restarted = new SessionFlagsStore(file.toString());
        assertEquals("置顶会话", restarted.get("web:1").displayTitle);
        assertTrue(restarted.get("web:1").pinned);
        assertTrue(restarted.get("web:2").archived);
        assertFalse(restarted.get("web:3").pinned, "无标记会话返回默认空");
    }

    @Test
    @DisplayName("删除会话清理对应标记，不影响其他会话")
    void remove_CleansOnlyTarget() {
        SessionFlagsStore store = new SessionFlagsStore(null);
        store.setPinned("web:1", true);
        store.setPinned("web:2", true);
        store.remove("web:1");
        assertFalse(store.get("web:1").pinned);
        assertTrue(store.get("web:2").pinned);
    }

    @Test
    @DisplayName("损坏文件按空处理，不抛异常")
    void corruptedFile_StartsFresh() throws Exception {
        Path file = tempDir.resolve("flags.json");
        java.nio.file.Files.writeString(file, "not-json{");
        SessionFlagsStore store = new SessionFlagsStore(file.toString());
        assertFalse(store.get("web:1").pinned);
        store.setPinned("web:1", true); // 可继续写入修复
        assertTrue(store.get("web:1").pinned);
    }
}
