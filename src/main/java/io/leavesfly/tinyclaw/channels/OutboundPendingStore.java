package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 未送达出站消息的持久化存储，保证已接受消息跨重启保留。
 *
 * <p>两类消息会进入此处：优雅停机时总线中未及发送的消息；
 * 重试耗尽仍失败的消息。启动时由 {@link ChannelManager} 读回并重投总线补发。</p>
 *
 * <p>超时类"结果不确定"的消息不进入此处——对端可能已送达，
 * 重启补发会产生重复消息。</p>
 */
public class OutboundPendingStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("channels");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PENDING = 200;  // 上限，超出丢弃最旧的

    private final Path path;

    public OutboundPendingStore(String workspace) {
        this.path = Paths.get(workspace, "bus", "outbound-pending.json");
    }

    /**
     * 持久化记录（不直接序列化 OutboundMessage，避免其 final 的 createdAt 反序列化问题）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PendingRecord {
        private String channel;
        private String chatId;
        private String content;
        private String sessionKey;
        private String messageType;

        public PendingRecord() {}

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }

        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getSessionKey() { return sessionKey; }
        public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }

        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }
    }

    /**
     * 追加一条未送达消息。
     */
    public synchronized void add(OutboundMessage message) {
        addAll(List.of(message));
    }

    /**
     * 追加多条未送达消息，超过上限时丢弃最旧的。
     */
    public synchronized void addAll(List<OutboundMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<PendingRecord> records = read();
        for (OutboundMessage m : messages) {
            records.add(toRecord(m));
        }
        if (records.size() > MAX_PENDING) {
            records = new ArrayList<>(records.subList(records.size() - MAX_PENDING, records.size()));
        }
        write(records);
    }

    /**
     * 读出全部待补发消息并清空存储。
     *
     * @return 待补发消息列表，无则返回空列表
     */
    public synchronized List<OutboundMessage> loadAndClear() {
        List<PendingRecord> records = read();
        if (records.isEmpty()) {
            return List.of();
        }
        write(new ArrayList<>());
        List<OutboundMessage> messages = new ArrayList<>();
        for (PendingRecord r : records) {
            messages.add(toMessage(r));
        }
        logger.info("Loaded pending outbound messages for restart recovery", Map.of(
                "count", messages.size()));
        return messages;
    }

    /**
     * 当前待补发消息数量。
     */
    public synchronized int size() {
        return read().size();
    }

    private List<PendingRecord> read() {
        try {
            if (!Files.exists(path)) {
                return new ArrayList<>();
            }
            String json = Files.readString(path);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<PendingRecord> records = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(ArrayList.class, PendingRecord.class));
            return records != null ? new ArrayList<>(records) : new ArrayList<>();
        } catch (Exception e) {
            logger.warn("Failed to read pending outbound store, using empty",
                    Map.of("error", String.valueOf(e.getMessage())));
            return new ArrayList<>();
        }
    }

    private void write(List<PendingRecord> records) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            JsonFileStore.writeAtomic(path, json);
        } catch (Exception e) {
            logger.error("Failed to write pending outbound store",
                    Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static PendingRecord toRecord(OutboundMessage m) {
        PendingRecord r = new PendingRecord();
        r.setChannel(m.getChannel());
        r.setChatId(m.getChatId());
        r.setContent(m.getContent());
        r.setSessionKey(m.getSessionKey());
        r.setMessageType(m.getMessageType() != null ? m.getMessageType().name() : null);
        return r;
    }

    private static OutboundMessage toMessage(PendingRecord r) {
        OutboundMessage m = new OutboundMessage(r.getChannel(), r.getChatId(), r.getContent(), r.getSessionKey());
        if (r.getMessageType() != null) {
            try {
                m.setMessageType(OutboundMessage.MessageType.valueOf(r.getMessageType()));
            } catch (IllegalArgumentException ignored) {
                // 未知类型退化为纯文本
            }
        }
        return m;
    }
}
