package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.providers.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 会话类单元测试
 *
 * <h2>学习目标</h2>
 * <ul>
 *   <li>测试 POJO 类的属性和方法</li>
 *   <li>验证时间戳的自动更新</li>
 *   <li>理解历史记录截断逻辑</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=SessionTest
 * </pre>
 */
@DisplayName("Session 会话类测试")
class SessionTest {

    private Session session;

    @BeforeEach
    void setUp() {
        session = new Session("test:session1");
    }

    // ==================== 构造函数测试 ====================

    @Test
    @DisplayName("构造函数: 无参构造初始化默认值")
    void constructor_NoArgs_InitializesDefaults() {
        Session s = new Session();
        
        assertNull(s.getKey());
        assertNotNull(s.getMessages());
        assertTrue(s.getMessages().isEmpty());
        assertNull(s.getSummary());
        assertNotNull(s.getCreated());
        assertNotNull(s.getUpdated());
    }

    @Test
    @DisplayName("构造函数: 带 key 参数初始化")
    void constructor_WithKey_SetsKey() {
        Session s = new Session("telegram:123");
        
        assertEquals("telegram:123", s.getKey());
        assertNotNull(s.getCreated());
    }

    // ==================== Getter/Setter 测试 ====================

    @Test
    @DisplayName("getKey/setKey: 正确获取和设置 key")
    void keyGetterSetter_Works() {
        session.setKey("discord:456");
        assertEquals("discord:456", session.getKey());
    }

    @Test
    @DisplayName("getSummary/setSummary: 正确获取和设置摘要")
    void summaryGetterSetter_Works() {
        assertNull(session.getSummary());
        
        session.setSummary("This is a conversation summary");
        assertEquals("This is a conversation summary", session.getSummary());
    }

    @Test
    @DisplayName("getCreated/setCreated: 正确获取和设置创建时间")
    void createdGetterSetter_Works() {
        Instant now = Instant.now();
        session.setCreated(now);
        assertEquals(now, session.getCreated());
    }

    @Test
    @DisplayName("getUpdated/setUpdated: 正确获取和设置更新时间")
    void updatedGetterSetter_Works() {
        Instant now = Instant.now();
        session.setUpdated(now);
        assertEquals(now, session.getUpdated());
    }

    // ==================== addMessage 测试 ====================

    @Test
    @DisplayName("addMessage: 添加简单消息")
    void addMessage_SimpleMessage_AddsToHistory() {
        Instant before = Instant.now();
        
        session.addMessage("user", "Hello");
        session.addMessage("assistant", "Hi there!");
        
        List<Message> messages = session.getMessages();
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Hello", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Hi there!", messages.get(1).getContent());
        
        // 验证更新时间被更新
        assertTrue(session.getUpdated().compareTo(before) >= 0);
    }

    @Test
    @DisplayName("addFullMessage: 添加完整消息对象")
    void addFullMessage_MessageObject_AddsToHistory() {
        Message msg = new Message("user", "Test message");
        
        session.addFullMessage(msg);
        
        assertEquals(1, session.getMessages().size());
        assertEquals(msg, session.getMessages().get(0));
    }

    // ==================== getHistory 测试 ====================

    @Test
    @DisplayName("getHistory: 返回消息历史的副本")
    void getHistory_ReturnsCopy() {
        session.addMessage("user", "msg1");
        session.addMessage("assistant", "msg2");
        
        List<Message> history = session.getHistory();
        
        assertEquals(2, history.size());
        
        // 修改返回的列表不应影响原始消息
        history.clear();
        assertEquals(2, session.getMessages().size());
    }

    @Test
    @DisplayName("getHistory: 空会话返回空列表")
    void getHistory_EmptySession_ReturnsEmptyList() {
        List<Message> history = session.getHistory();
        
        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    // ==================== truncateHistory 测试 ====================

    @Test
    @DisplayName("truncateHistory: 保留最后 N 条消息")
    void truncateHistory_KeepsLastN() {
        for (int i = 1; i <= 10; i++) {
            session.addMessage("user", "msg" + i);
        }
        assertEquals(10, session.getMessages().size());
        
        session.truncateHistory(3);
        
        assertEquals(3, session.getMessages().size());
        assertEquals("msg8", session.getMessages().get(0).getContent());
        assertEquals("msg9", session.getMessages().get(1).getContent());
        assertEquals("msg10", session.getMessages().get(2).getContent());
    }

    @Test
    @DisplayName("truncateHistory: 消息数小于等于 N 时不截断")
    void truncateHistory_LessThanN_NoChange() {
        session.addMessage("user", "msg1");
        session.addMessage("assistant", "msg2");
        
        session.truncateHistory(5);
        
        assertEquals(2, session.getMessages().size());
    }

    @Test
    @DisplayName("truncateHistory: 更新时间被更新")
    void truncateHistory_UpdatesTimestamp() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            session.addMessage("user", "msg" + i);
        }
        Instant beforeTruncate = session.getUpdated();
        Thread.sleep(10); // 确保时间差
        
        session.truncateHistory(2);
        
        assertTrue(session.getUpdated().isAfter(beforeTruncate));
    }

    @Test
    @DisplayName("truncateHistory: N=0 保留空列表")
    void truncateHistory_ZeroKeep_EmptiesList() {
        session.addMessage("user", "msg1");
        session.addMessage("assistant", "msg2");
        
        session.truncateHistory(0);
        
        // 由于条件是 size > keepLast，当 keepLast=0 时会截断
        // 但 subList(size-0, size) = subList(2, 2) = 空列表
        assertTrue(session.getMessages().isEmpty());
    }

    // ==================== setMessages 测试 ====================

    @Test
    @DisplayName("setMessages: 替换整个消息列表")
    void setMessages_ReplacesAllMessages() {
        session.addMessage("user", "old");
        
        List<Message> newMessages = List.of(
                new Message("user", "new1"),
                new Message("assistant", "new2")
        );
        session.setMessages(new java.util.ArrayList<>(newMessages));
        
        assertEquals(2, session.getMessages().size());
        assertEquals("new1", session.getMessages().get(0).getContent());
    }
}
