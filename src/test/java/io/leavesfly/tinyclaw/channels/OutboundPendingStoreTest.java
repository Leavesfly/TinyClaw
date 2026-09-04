package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OutboundPendingStore 单元测试：跨重启保留未送达出站消息。
 */
@DisplayName("OutboundPendingStore 未送达消息持久化测试")
class OutboundPendingStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("add/loadAndClear: 往返保留字段并清空存储")
    void roundTrip_PreservesFieldsAndClears() {
        OutboundPendingStore store = new OutboundPendingStore(tempDir.toString());
        OutboundMessage msg = new OutboundMessage("telegram", "chat1", "hello", "telegram:chat1");
        msg.setMessageType(OutboundMessage.MessageType.MARKDOWN);

        store.add(msg);
        assertEquals(1, store.size());

        List<OutboundMessage> loaded = store.loadAndClear();
        assertEquals(1, loaded.size());
        OutboundMessage m = loaded.get(0);
        assertEquals("telegram", m.getChannel());
        assertEquals("chat1", m.getChatId());
        assertEquals("hello", m.getContent());
        assertEquals("telegram:chat1", m.getSessionKey());
        assertEquals(OutboundMessage.MessageType.MARKDOWN, m.getMessageType());

        assertTrue(store.loadAndClear().isEmpty(), "读出后应清空");
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("上限 200：超出丢弃最旧的")
    void cap_DropsOldest() {
        OutboundPendingStore store = new OutboundPendingStore(tempDir.toString());
        List<OutboundMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            msgs.add(new OutboundMessage("telegram", "chat", "msg-" + i));
        }
        store.addAll(msgs);

        assertEquals(200, store.size());
        List<OutboundMessage> loaded = store.loadAndClear();
        assertEquals("msg-5", loaded.get(0).getContent(), "最旧的 5 条应被丢弃");
        assertEquals("msg-204", loaded.get(199).getContent());
    }

    @Test
    @DisplayName("新实例可读回旧实例写入的消息（跨重启）")
    void newInstance_ReadsPreviousWrites() {
        OutboundPendingStore first = new OutboundPendingStore(tempDir.toString());
        first.add(new OutboundMessage("feishu", "oc_1", "pending"));

        OutboundPendingStore second = new OutboundPendingStore(tempDir.toString());
        List<OutboundMessage> loaded = second.loadAndClear();
        assertEquals(1, loaded.size());
        assertEquals("pending", loaded.get(0).getContent());
    }
}
