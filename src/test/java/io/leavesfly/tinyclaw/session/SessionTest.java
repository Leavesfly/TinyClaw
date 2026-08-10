package io.leavesfly.tinyclaw.session;

import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 会话类单元测试
 *
 * <h2>覆盖重点</h2>
 * <ul>
 *   <li>消息入库时自动补齐 id / timestamp</li>
 *   <li>非破坏式上下文压缩：完整转录保留、上下文起点前移</li>
 *   <li>压缩边界基于快照，不吞掉摘要期间新增的消息</li>
 *   <li>压缩不破坏 tool_calls / tool 消息的配对</li>
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
        assertEquals(0, s.getContextStartIndex());
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
        assertEquals("Test message", session.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("addFullMessage: 入库时补齐 id 与 timestamp")
    void addFullMessage_StampsIdentity() {
        Message msg = new Message("user", "Test message");
        assertNull(msg.getId());
        assertNull(msg.getTimestamp());

        session.addFullMessage(msg);

        assertNotNull(msg.getId());
        assertNotNull(msg.getTimestamp());
    }

    @Test
    @DisplayName("addFullMessage: 已有 id 的消息不被覆盖")
    void addFullMessage_KeepsExistingIdentity() {
        Message msg = new Message("user", "Test");
        msg.setId("fixed-id");
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        msg.setTimestamp(ts);

        session.addFullMessage(msg);

        assertEquals("fixed-id", msg.getId());
        assertEquals(ts, msg.getTimestamp());
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

    // ==================== 非破坏式压缩测试 ====================

    @Test
    @DisplayName("compactContext: 前移上下文起点但完整保留转录")
    void compactContext_KeepsFullTranscript() {
        for (int i = 1; i <= 10; i++) {
            session.addMessage("user", "msg" + i);
        }

        assertTrue(session.compactContext("summary of msg1-7", 7));

        // 完整转录不变，历史不被销毁
        assertEquals(10, session.getHistory().size());
        assertEquals("msg1", session.getHistory().get(0).getContent());

        // 上下文只包含起点之后的消息
        List<Message> context = session.getContextMessages();
        assertEquals(3, context.size());
        assertEquals("msg8", context.get(0).getContent());
        assertEquals("msg10", context.get(2).getContent());
        assertEquals("summary of msg1-7", session.getSummary());
        assertEquals(7, session.getContextStartIndex());
    }

    @Test
    @DisplayName("compactContext: 起点只增不减，过期的压缩请求被忽略")
    void compactContext_MonotonicStartIndex() {
        for (int i = 1; i <= 10; i++) {
            session.addMessage("user", "msg" + i);
        }

        assertTrue(session.compactContext("s1", 7));
        // 更早的边界（如迟到的摘要任务）不应回退上下文起点
        assertFalse(session.compactContext("s2", 3));

        assertEquals(7, session.getContextStartIndex());
        assertEquals("s1", session.getSummary());
    }

    @Test
    @DisplayName("compactContext: 摘要期间新增的消息不会被压缩掉")
    void compactContext_DoesNotSwallowMessagesAddedDuringSummarize() {
        for (int i = 1; i <= 10; i++) {
            session.addMessage("user", "msg" + i);
        }

        // 模拟摘要开始时的快照：总长 10，保留最后 3 条 -> 边界 7
        Session.ContextSnapshot snapshot = session.snapshotContext();
        assertEquals(10, snapshot.totalMessages());
        int boundary = snapshot.totalMessages() - 3;

        // 摘要进行中，对话继续追加
        session.addMessage("user", "msg11");
        session.addMessage("assistant", "msg12");

        assertTrue(session.compactContext("summary", boundary));

        // 上下文里必须包含摘要期间新增的消息，不能被划入已压缩区间
        List<String> contextContents = session.getContextMessages().stream()
                .map(Message::getContent).toList();
        assertTrue(contextContents.contains("msg11"));
        assertTrue(contextContents.contains("msg12"));
        assertEquals(5, contextContents.size()); // msg8..msg12
    }

    @Test
    @DisplayName("compactContext: 不破坏 tool_calls 与 tool 消息的配对")
    void compactContext_PreservesToolPairing() {
        session.addMessage("user", "do something");
        Message assistantWithTools = Message.assistant("calling tool");
        assistantWithTools.setToolCalls(List.of(new ToolCall("call-1", "write_file", Map.of())));
        session.addFullMessage(assistantWithTools);
        session.addFullMessage(Message.tool("call-1", "done"));
        session.addMessage("assistant", "finished");

        // 边界 2 恰好落在 tool 消息上，应回退到包含 assistant(tool_calls)
        session.compactContext("summary", 2);

        List<Message> context = session.getContextMessages();
        assertEquals("assistant", context.get(0).getRole());
        assertNotNull(context.get(0).getToolCalls());
        assertFalse(context.get(0).getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("compactContext: 边界超出转录长度时被裁剪")
    void compactContext_ClampsBeyondSize() {
        session.addMessage("user", "msg1");
        session.addMessage("assistant", "msg2");

        assertTrue(session.compactContext("summary", 99));

        assertEquals(2, session.getContextStartIndex());
        assertTrue(session.getContextMessages().isEmpty());
        assertEquals(2, session.getHistory().size());
    }

    // ==================== 快照与工具调用记录 ====================

    @Test
    @DisplayName("snapshotContext: 起点、总长与上下文消息一致对应")
    void snapshotContext_IsConsistent() {
        for (int i = 1; i <= 5; i++) {
            session.addMessage("user", "msg" + i);
        }
        session.compactContext("s", 2);

        Session.ContextSnapshot snapshot = session.snapshotContext();

        assertEquals(2, snapshot.startIndex());
        assertEquals(5, snapshot.totalMessages());
        assertEquals(3, snapshot.contextMessages().size());
    }

    @Test
    @DisplayName("addToolCallRecord: 压缩后记录的绝对下标依然有效")
    void toolCallRecord_IndexStaysValidAfterCompaction() {
        session.addMessage("user", "q");
        session.addMessage("assistant", "a");
        session.addToolCallRecord(new ToolCallRecord("write_file", "{}", "ok", true, 1));

        session.compactContext("summary", 1);

        // 记录指向的 assistant 消息仍在完整转录的下标 1 上
        assertEquals(1, session.getToolCallRecords().get(0).getMessageIndex());
        assertEquals("assistant", session.getHistory().get(1).getRole());
    }

    // ==================== setMessages 测试 ====================

    @Test
    @DisplayName("setMessages: 替换整个消息列表")
    void setMessages_ReplacesAllMessages() {
        session.addMessage("user", "old");

        List<Message> newMessages = List.of(
                Message.user("new1"),
                Message.assistant("new2")
        );
        session.setMessages(new java.util.ArrayList<>(newMessages));

        assertEquals(2, session.getMessages().size());
        assertEquals("new1", session.getMessages().get(0).getContent());
    }
}
