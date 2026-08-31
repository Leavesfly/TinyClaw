package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.providers.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话身份、进度卡与全文检索测试。
 *
 * <p>覆盖三条最容易写错的语义：认领不抢占、legacy 会话不被静默隐藏、
 * 搜索命中的下标必须与 {@code getHistory} 同一口径。</p>
 */
class SessionIdentityAndSearchTest {

    // ==================== 身份与可见性 ====================

    @Test
    void claimOwnerIsIdempotentAndDoesNotSteal() {
        Session session = new Session("telegram:group1");

        assertTrue(session.claimOwner("u:telegram:alice"));
        assertFalse(session.claimOwner("u:telegram:bob"), "已有归属不应被后来者抢占");
        assertFalse(session.claimOwner("u:telegram:alice"), "重复认领应视为无改动");
        assertEquals("u:telegram:alice", session.getOwner());
    }

    @Test
    void claimOwnerIgnoresBlankCandidate() {
        Session session = new Session("cli:default");

        assertFalse(session.claimOwner(null));
        assertFalse(session.claimOwner("  "));
        assertNull(session.getOwner());
    }

    @Test
    void legacySessionWithoutOwnerStaysVisibleToEveryone() {
        Session session = new Session("telegram:old");

        assertTrue(session.isVisibleTo("u:telegram:anyone"),
                "本特性上线前的会话没有归属，不能因此消失");
    }

    @Test
    void privateSessionIsVisibleOnlyToOwnerAndMembers() {
        Session session = new Session("telegram:dm");
        session.claimOwner("u:telegram:alice");
        session.setVisibility(SessionVisibility.PRIVATE);

        assertTrue(session.isVisibleTo("u:telegram:alice"));
        assertFalse(session.isVisibleTo("u:telegram:bob"));

        assertTrue(session.addMember("u:telegram:bob"));
        assertTrue(session.isVisibleTo("u:telegram:bob"));
        assertFalse(session.addMember("u:telegram:bob"), "重复添加成员应返回 false");
    }

    @Test
    void sharedSessionIsVisibleToAnyIdentifiedViewer() {
        Session session = new Session("telegram:group");
        session.claimOwner("u:telegram:alice");
        session.setVisibility(SessionVisibility.SHARED);

        assertTrue(session.isVisibleTo("u:telegram:stranger"));
    }

    @Test
    void viewerWithoutIdentitySeesEverything() {
        Session session = new Session("telegram:dm");
        session.claimOwner("u:telegram:alice");
        session.setVisibility(SessionVisibility.PRIVATE);

        assertTrue(session.isVisibleTo(null), "未声明身份的调用方不过滤，否则控制台会变空");
        assertTrue(session.isVisibleTo(""));
    }

    @Test
    void identitySurvivesReload(@TempDir Path dir) throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(dir.toString());
        Session session = new Session("telegram:dm");
        session.addMessage("user", "hello");
        session.claimOwner("u:telegram:alice");
        session.setVisibility(SessionVisibility.SHARED);
        session.addMember("u:telegram:bob");
        store.persist(session);
        store.flush();

        Session loaded = new JsonlSessionStore(dir.toString()).load("telegram:dm");

        assertNotNull(loaded);
        assertEquals("u:telegram:alice", loaded.getOwner());
        assertEquals(SessionVisibility.SHARED, loaded.getVisibility());
        assertTrue(loaded.getMembers().contains("u:telegram:bob"));
    }

    @Test
    void visibilityChangeAloneStillPersists(@TempDir Path dir) throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(dir.toString());
        Session session = new Session("cli:default");
        session.addMessage("user", "hi");
        session.claimOwner("u:cli:me");
        store.persist(session);

        // 只改可见性，没有任何新消息：identityDirty 必须能独立触发一次落盘
        session.setVisibility(SessionVisibility.SHARED);
        assertTrue(session.hasPendingChanges(), "身份变更应被视为待落盘");
        store.persist(session);
        store.flush();

        Session loaded = new JsonlSessionStore(dir.toString()).load("cli:default");
        assertNotNull(loaded);
        assertEquals(SessionVisibility.SHARED, loaded.getVisibility());
    }

    @Test
    void listMetaFiltersByViewer(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());

        sessions.addMessage("telegram:a", "user", "alice private");
        sessions.claimOwner("telegram:a", "u:telegram:alice", SessionVisibility.PRIVATE);
        sessions.addMessage("telegram:b", "user", "bob private");
        sessions.claimOwner("telegram:b", "u:telegram:bob", SessionVisibility.PRIVATE);
        sessions.addMessage("telegram:c", "user", "group shared");
        sessions.claimOwner("telegram:c", "u:telegram:bob", SessionVisibility.SHARED);
        sessions.addMessage("telegram:legacy", "user", "no owner");

        List<String> aliceKeys = sessions.listMeta("u:telegram:alice").stream()
                .map(SessionMeta::getKey).toList();

        assertTrue(aliceKeys.contains("telegram:a"), "自己的私有会话可见");
        assertTrue(aliceKeys.contains("telegram:c"), "共享会话可见");
        assertTrue(aliceKeys.contains("telegram:legacy"), "无归属的历史会话可见");
        assertFalse(aliceKeys.contains("telegram:b"), "他人的私有会话不可见");

        assertEquals(4, sessions.listMeta(null).size(), "无身份调用方不过滤");
    }

    // ==================== 进度卡 ====================

    @Test
    void progressClampsStepsIntoRange() {
        SessionProgress bounded = SessionProgress.of("跑", "细节", 99, 5);
        assertEquals(5, bounded.completedSteps(), "已完成步数不应超过总数");
        assertTrue(bounded.hasKnownTotal());

        SessionProgress unknown = SessionProgress.of("跑", "细节");
        assertFalse(unknown.hasKnownTotal());
        assertEquals(0, unknown.totalSteps());
    }

    @Test
    void progressRoundTripsThroughManager(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        sessions.addMessage("cli:default", "user", "start");

        sessions.setProgress("cli:default", SessionProgress.of("协同执行中", "3 个角色"));
        SessionProgress read = sessions.getProgress("cli:default");

        assertNotNull(read);
        assertEquals("协同执行中", read.phase());

        sessions.clearProgress("cli:default");
        assertNull(sessions.getProgress("cli:default"), "清除后应为 null");
    }

    @Test
    void progressIsDiscardedOnProcessRestart(@TempDir Path dir) throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(dir.toString());
        Session session = new Session("cli:default");
        session.addMessage("user", "hi");
        session.setProgress(SessionProgress.of("协同执行中", "任务"));
        store.persist(session);
        store.flush();

        // 新建 store 等价于进程重启：上次的进度描述的是已经不存在的任务
        JsonlSessionStore reopened = new JsonlSessionStore(dir.toString());
        SessionMeta meta = reopened.listMeta().stream()
                .filter(m -> "cli:default".equals(m.getKey()))
                .findFirst()
                .orElseThrow();

        assertNull(meta.getProgress(), "重启后不应留下永不前进的进度卡");
    }

    @Test
    void progressDoesNotMarkTranscriptDirty() {
        Session session = new Session("cli:default");
        session.markFullyPersisted();

        session.setProgress(SessionProgress.of("跑", ""));

        assertFalse(session.hasPendingChanges(), "进度不进转录，不应触发转录写盘");
    }

    // ==================== 全文检索 ====================

    @Test
    void searchHitIndexMatchesGetHistory(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        sessions.addMessage("cli:default", "user", "第一条无关内容");
        sessions.addMessage("cli:default", "assistant", "第二条也无关");
        sessions.addMessage("cli:default", "user", "请帮我查一下 OpenClaw 的发布说明");
        sessions.save("cli:default");

        List<SessionSearchHit> hits = sessions.search("openclaw", 10, null);

        assertEquals(1, hits.size());
        SessionSearchHit hit = hits.get(0);
        assertEquals("cli:default", hit.sessionKey());

        List<Message> history = sessions.getHistory("cli:default");
        assertTrue(hit.messageIndex() >= 0 && hit.messageIndex() < history.size());
        assertTrue(history.get(hit.messageIndex()).getContent().contains("OpenClaw"),
                "命中下标必须能在完整转录里定位到那条消息");
        assertEquals("user", hit.role());
        assertTrue(hit.snippet().contains("OpenClaw"));
    }

    @Test
    void searchIsCaseInsensitiveAndBounded(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        for (int i = 0; i < 12; i++) {
            sessions.addMessage("cli:default", "user", "needle " + i);
        }
        sessions.save("cli:default");

        assertEquals(3, sessions.search("NEEDLE", 3, null).size(), "结果必须有界");
        assertEquals(12, sessions.search("needle", 100, null).size());
    }

    @Test
    void searchFindsUnpersistedMessages(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        // 不调 save：消息只在内存里
        sessions.addMessage("cli:default", "user", "刚说完的话也要能搜到");

        List<SessionSearchHit> hits = sessions.search("刚说完", 10, null);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).messageIndex());
    }

    @Test
    void searchDoesNotReturnDuplicatesForPersistedMessages(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        sessions.addMessage("cli:default", "user", "unique-token");
        sessions.save("cli:default");

        // 已落盘的消息同时存在于文件与内存缓存，必须按 (key, index) 去重
        assertEquals(1, sessions.search("unique-token", 10, null).size());
    }

    @Test
    void searchRespectsViewerVisibility(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        sessions.addMessage("telegram:bob", "user", "secret-token");
        sessions.claimOwner("telegram:bob", "u:telegram:bob", SessionVisibility.PRIVATE);
        sessions.save("telegram:bob");

        assertTrue(sessions.search("secret-token", 10, "u:telegram:alice").isEmpty(),
                "他人私有会话的内容不应被搜到");
        assertEquals(1, sessions.search("secret-token", 10, "u:telegram:bob").size());
        assertEquals(1, sessions.search("secret-token", 10, null).size());
    }

    @Test
    void blankQueryReturnsNothing(@TempDir Path dir) throws Exception {
        SessionManager sessions = new SessionManager(dir.toString());
        sessions.addMessage("cli:default", "user", "content");

        assertTrue(sessions.search(null, 10, null).isEmpty());
        assertTrue(sessions.search("   ", 10, null).isEmpty());
    }
}
