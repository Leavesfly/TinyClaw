package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.providers.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionManager.forkSession 单元测试。
 *
 * <h2>覆盖重点</h2>
 * <ul>
 *   <li>fork 只复制截断点之前的消息，源会话转录保持不变（不可变转录原则）</li>
 *   <li>复制的消息克隆为全新 id，不与源会话共享</li>
 *   <li>越界截断点被夹取；源会话不存在返回 null</li>
 *   <li>对分支再次派生时 key 后缀不无限堆叠</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=SessionManagerForkTest
 * </pre>
 */
@DisplayName("SessionManager fork 派生会话测试")
class SessionManagerForkTest {

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = SessionManager.withStore(SessionStore.NOOP);
        Session s = manager.getOrCreate("web:src");
        s.addFullMessage(Message.user("第一问"));
        s.addFullMessage(Message.assistant("第一答"));
        s.addFullMessage(Message.user("第二问"));
        s.addFullMessage(Message.assistant("第二答"));
    }

    @Test
    @DisplayName("fork: 复制截断点之前的消息，源会话保持不变")
    void fork_copiesPrefixAndLeavesSourceIntact() {
        String newKey = manager.forkSession("web:src", 2, null);

        assertNotNull(newKey);
        assertTrue(newKey.startsWith("web:src-r"), "分支 key 应基于源 key 派生: " + newKey);

        List<Message> fork = manager.getHistory(newKey);
        assertEquals(2, fork.size(), "只复制 [0,2) 两条消息");
        assertEquals("user", fork.get(0).getRole());
        assertEquals("第一问", fork.get(0).getContent());
        assertEquals("assistant", fork.get(1).getRole());

        // 源会话完整保留，未被截断
        assertEquals(4, manager.getHistory("web:src").size(), "源会话必须不可变");
    }

    @Test
    @DisplayName("fork: 复制消息克隆为全新 id，不与源共享")
    void fork_clonesMessageIds() {
        String newKey = manager.forkSession("web:src", 2, null);
        List<Message> src = manager.getHistory("web:src");
        List<Message> fork = manager.getHistory(newKey);

        assertNotNull(fork.get(0).getId(), "分支消息应已补齐 id");
        assertNotEquals(src.get(0).getId(), fork.get(0).getId(), "分支消息应使用全新 id");
    }

    @Test
    @DisplayName("fork: 越界截断点被夹取到转录长度")
    void fork_clampsOutOfRangeCut() {
        String newKey = manager.forkSession("web:src", 999, null);
        assertEquals(4, manager.getHistory(newKey).size(), "cut 超过长度时复制整段");
    }

    @Test
    @DisplayName("fork: 源会话不存在返回 null")
    void fork_missingSourceReturnsNull() {
        assertNull(manager.forkSession("web:nope", 1, null));
    }

    @Test
    @DisplayName("fork: 对分支再派生，key 后缀不堆叠")
    void fork_reforkDoesNotStackSuffix() {
        String first = manager.forkSession("web:src", 2, null);
        String second = manager.forkSession(first, 1, null);

        assertNotNull(second);
        // 形如 web:src-rXXXX；若后缀堆叠会出现第二段 -r…，正则将不匹配
        assertTrue(second.matches("web:src-r[0-9a-z]+"),
                "分支后缀应为单段 -r…，不应堆叠: " + second);
    }
}
