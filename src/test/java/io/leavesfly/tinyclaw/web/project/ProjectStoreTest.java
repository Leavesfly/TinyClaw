package io.leavesfly.tinyclaw.web.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectStore} 项目空间存储测试（P4）。
 *
 * <p>覆盖：CRUD 往返与持久化重建、revision 单调递增、资料引用增删与上限、
 * 归档不删资源、删除只解除引用、名称/指令长度钳制、空名拒绝。</p>
 */
@DisplayName("ProjectStore 项目存储测试")
class ProjectStoreTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("创建/修改/归档往返，重启后从磁盘重建")
    void crudRoundtrip_PersistedAcrossRestart() {
        ProjectStore store = new ProjectStore(
                workspace.resolve("projects.json").toString());
        ProjectStore.Project created = store.create("TinyClaw 升级", "回复一律使用中文");
        assertNotNull(created.id);
        assertEquals("TinyClaw 升级", created.name);
        assertEquals("回复一律使用中文", created.instructions);
        assertEquals(1, created.revision);
        assertFalse(created.archived);

        store.update(created.id, "TinyClaw Web 升级", null);
        store.setArchived(created.id, true);

        // 重启重建
        ProjectStore reloaded = new ProjectStore(
                workspace.resolve("projects.json").toString());
        ProjectStore.Project p = reloaded.get(created.id);
        assertEquals("TinyClaw Web 升级", p.name);
        assertEquals("回复一律使用中文", p.instructions, "未更新的指令保持不变");
        assertTrue(p.archived, "归档重启后保持");
        assertTrue(p.revision >= 2);
    }

    @Test
    @DisplayName("revision 单调递增且重启后继续递增不回退")
    void revision_MonotonicAcrossRestart() {
        ProjectStore store = new ProjectStore(
                workspace.resolve("projects.json").toString());
        ProjectStore.Project a = store.create("A", null);
        ProjectStore.Project b = store.create("B", null);
        store.update(a.id, "A2", null);

        ProjectStore reloaded = new ProjectStore(
                workspace.resolve("projects.json").toString());
        long maxBefore = Math.max(reloaded.get(a.id).revision, reloaded.get(b.id).revision);
        ProjectStore.Project c = reloaded.create("C", null);
        assertTrue(c.revision > maxBefore, "重启后新 revision 必须大于历史最大值");
    }

    @Test
    @DisplayName("资料引用增删幂等；引用解除不删物理附件（Store 只管引用）")
    void resources_AddRemoveIdempotent() {
        ProjectStore store = new ProjectStore(workspace.resolve("projects.json").toString());
        ProjectStore.Project p = store.create("P", null);

        store.addResource(p.id, "abc123def456");
        store.addResource(p.id, "abc123def456"); // 幂等
        assertEquals(1, store.get(p.id).resourceCount());

        store.removeResource(p.id, "abc123def456");
        assertEquals(0, store.get(p.id).resourceCount());
        store.removeResource(p.id, "abc123def456"); // 再删无异常
    }

    @Test
    @DisplayName("非法 attachmentId 拒绝；超过上限拒绝")
    void resources_IllegalIdAndLimitRejected() {
        ProjectStore store = new ProjectStore(null); // 纯内存
        ProjectStore.Project p = store.create("P", null);
        assertThrows(IllegalArgumentException.class, () -> store.addResource(p.id, "../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.addResource(p.id, "a b"));
        assertThrows(IllegalArgumentException.class, () -> store.addResource(p.id, ""));

        ProjectStore.Project big = store.create("Big", null);
        for (int i = 0; i < 100; i++) {
            store.addResource(big.id, String.format("att%04d000000", i));
        }
        assertThrows(IllegalStateException.class, () -> store.addResource(big.id, "attX000000000"));
    }

    @Test
    @DisplayName("删除项目：返回解除的引用；附件物理文件与会话转录不动（由调用方语义保证）")
    void delete_ReturnsReleasedResources() {
        ProjectStore store = new ProjectStore(workspace.resolve("projects.json").toString());
        ProjectStore.Project p = store.create("P", null);
        store.addResource(p.id, "aaa111bbb222");
        store.addResource(p.id, "ccc333ddd444");

        List<String> released = store.delete(p.id);
        assertEquals(List.of("aaa111bbb222", "ccc333ddd444"), released);
        assertFalse(store.exists(p.id));
        assertThrows(IllegalArgumentException.class, () -> store.get(p.id));
    }

    @Test
    @DisplayName("空名拒绝；名称与指令超长钳制")
    void create_ValidatesNameAndClampsLength() {
        ProjectStore store = new ProjectStore(null);
        assertThrows(IllegalArgumentException.class, () -> store.create("  ", null));
        assertThrows(IllegalArgumentException.class, () -> store.create(null, null));

        ProjectStore.Project longOne = store.create("x".repeat(300), "y".repeat(10000));
        assertEquals(100, longOne.name.length());
        assertEquals(8000, longOne.instructions.length());
    }

    @Test
    @DisplayName("副本隔离：get/list 返回副本，外部修改不影响内存态")
    void copies_IsolatedFromInternalState() {
        ProjectStore store = new ProjectStore(workspace.resolve("projects.json").toString());
        ProjectStore.Project p = store.create("P", "指令");
        p.name = "外部改的";
        p.instructions = null;
        assertEquals("P", store.get(p.id).name, "外部修改不得写回存储");
        assertEquals("指令", store.get(p.id).instructions);
    }

    @Test
    @DisplayName("损坏文件按空处理，不抛异常")
    void corruptFile_StartsFresh() throws Exception {
        java.nio.file.Files.writeString(workspace.resolve("projects.json"), "not json");
        ProjectStore store = new ProjectStore(workspace.resolve("projects.json").toString());
        assertEquals(0, store.list().size());
        ProjectStore.Project p = store.create("AfterCorrupt", null);
        assertEquals("AfterCorrupt", store.get(p.id).name);
    }

    @Test
    @DisplayName("列表按创建时间正序；归档项目仍在列表中")
    void list_CreationOrderAndArchivedIncluded() throws InterruptedException {
        ProjectStore store = new ProjectStore(null);
        store.create("First", null);
        Thread.sleep(5);
        store.create("Second", null);
        Thread.sleep(5);
        ProjectStore.Project third = store.create("Third", null);
        store.setArchived(third.id, true);

        List<ProjectStore.Project> all = store.list();
        assertEquals(List.of("First", "Second", "Third"),
                all.stream().map(p -> p.name).toList());
    }

    @Test
    @DisplayName("projectDomain 安全字符化：危险字符替换为下划线")
    void projectDomain_Sanitized() {
        String d1 = io.leavesfly.tinyclaw.memory.MemoryScope.projectDomain("proj_1");
        String d2 = io.leavesfly.tinyclaw.memory.MemoryScope.projectDomain("../../etc");
        assertEquals("p:proj_1", d1);
        assertEquals("p:______etc", d2, "路径分隔符必须被替换，防拼接越权");
        assertNull(io.leavesfly.tinyclaw.memory.MemoryScope.projectDomain(null));
        assertNull(io.leavesfly.tinyclaw.memory.MemoryScope.projectDomain("   "));
    }
}
