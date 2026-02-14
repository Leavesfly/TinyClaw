package io.leavesfly.tinyclaw.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageBus 消息总线单元测试
 *
 * <h2>学习目标</h2>
 * <ul>
 *   <li>理解并发组件的测试方法</li>
 *   <li>学习 @BeforeEach 初始化测试环境</li>
 *   <li>掌握 @Timeout 防止测试阻塞</li>
 *   <li>使用 CountDownLatch 进行多线程测试同步</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=MessageBusTest
 * </pre>
 */
@DisplayName("MessageBus 消息总线测试")
class MessageBusTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new MessageBus();
    }

    // ==================== 入站消息测试 ====================

    @Test
    @DisplayName("publishInbound: 发布消息后可以消费")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void publishInbound_ValidMessage_CanBeConsumed() throws InterruptedException {
        InboundMessage msg = new InboundMessage("telegram", "user123", "chat456", "Hello");
        
        bus.publishInbound(msg);
        
        assertTrue(bus.hasInbound(), "队列中应有消息");
        assertEquals(1, bus.getInboundSize(), "入站队列大小应为1");
        
        InboundMessage consumed = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(consumed);
        assertEquals("telegram", consumed.getChannel());
        assertEquals("user123", consumed.getSenderId());
        assertEquals("chat456", consumed.getChatId());
        assertEquals("Hello", consumed.getContent());
    }

    @Test
    @DisplayName("consumeInbound: 超时返回 null")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void consumeInbound_Timeout_ReturnsNull() throws InterruptedException {
        InboundMessage msg = bus.consumeInbound(100, TimeUnit.MILLISECONDS);
        assertNull(msg, "超时后应返回null");
    }

    @Test
    @DisplayName("publishInbound: FIFO 顺序")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void publishInbound_MultipleMessages_FIFOOrder() throws InterruptedException {
        bus.publishInbound(new InboundMessage("ch", "u", "c", "msg1"));
        bus.publishInbound(new InboundMessage("ch", "u", "c", "msg2"));
        bus.publishInbound(new InboundMessage("ch", "u", "c", "msg3"));
        
        assertEquals(3, bus.getInboundSize());
        assertEquals("msg1", bus.consumeInbound(100, TimeUnit.MILLISECONDS).getContent());
        assertEquals("msg2", bus.consumeInbound(100, TimeUnit.MILLISECONDS).getContent());
        assertEquals("msg3", bus.consumeInbound(100, TimeUnit.MILLISECONDS).getContent());
    }

    // ==================== 出站消息测试 ====================

    @Test
    @DisplayName("publishOutbound: 发布消息后可以订阅")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void publishOutbound_ValidMessage_CanBeSubscribed() throws InterruptedException {
        OutboundMessage msg = new OutboundMessage("discord", "chat789", "Response");
        
        bus.publishOutbound(msg);
        
        assertEquals(1, bus.getOutboundSize(), "出站队列大小应为1");
        
        OutboundMessage subscribed = bus.subscribeOutbound(1, TimeUnit.SECONDS);
        assertNotNull(subscribed);
        assertEquals("discord", subscribed.getChannel());
        assertEquals("chat789", subscribed.getChatId());
        assertEquals("Response", subscribed.getContent());
    }

    @Test
    @DisplayName("subscribeOutbound: 超时返回 null")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void subscribeOutbound_Timeout_ReturnsNull() throws InterruptedException {
        OutboundMessage msg = bus.subscribeOutbound(100, TimeUnit.MILLISECONDS);
        assertNull(msg, "超时后应返回null");
    }

    // ==================== Handler 注册测试 ====================

    @Test
    @DisplayName("registerHandler: 可以注册和获取处理器")
    void registerHandler_ValidHandler_CanBeRetrieved() {
        AtomicInteger counter = new AtomicInteger(0);
        bus.registerHandler("test-channel", msg -> {
            counter.incrementAndGet();
            return null;
        });
        
        assertNotNull(bus.getHandler("test-channel"));
        assertNull(bus.getHandler("unknown-channel"));
        
        // 验证处理器可以被调用
        bus.getHandler("test-channel").apply(new InboundMessage("test-channel", "u", "c", "test"));
        assertEquals(1, counter.get());
    }

    // ==================== clear / close 测试 ====================

    @Test
    @DisplayName("clear: 清空所有队列")
    void clear_ClearsAllQueues() {
        bus.publishInbound(new InboundMessage("ch", "u", "c", "msg1"));
        bus.publishOutbound(new OutboundMessage("ch", "c", "resp1"));
        
        assertEquals(1, bus.getInboundSize());
        assertEquals(1, bus.getOutboundSize());
        
        bus.clear();
        
        assertEquals(0, bus.getInboundSize());
        assertEquals(0, bus.getOutboundSize());
        assertFalse(bus.hasInbound());
    }

    @Test
    @DisplayName("close: 关闭总线清空队列")
    void close_ClearsQueues() {
        bus.publishInbound(new InboundMessage("ch", "u", "c", "msg1"));
        
        bus.close();
        
        assertEquals(0, bus.getInboundSize());
    }

    // ==================== 并发测试 ====================

    @Test
    @DisplayName("并发: 多生产者单消费者")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrent_MultipleProducersSingleConsumer() throws InterruptedException {
        int numProducers = 5;
        int messagesPerProducer = 20;
        CountDownLatch producerLatch = new CountDownLatch(numProducers);
        AtomicInteger consumed = new AtomicInteger(0);
        
        // 启动消费者
        Thread consumer = new Thread(() -> {
            while (consumed.get() < numProducers * messagesPerProducer) {
                try {
                    InboundMessage msg = bus.consumeInbound(100, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        consumed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        consumer.start();
        
        // 启动生产者
        ExecutorService executor = Executors.newFixedThreadPool(numProducers);
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                for (int j = 0; j < messagesPerProducer; j++) {
                    bus.publishInbound(new InboundMessage("ch", "producer" + producerId, "c", "msg" + j));
                }
                producerLatch.countDown();
            });
        }
        
        producerLatch.await();
        consumer.join(3000);
        executor.shutdown();
        
        assertEquals(numProducers * messagesPerProducer, consumed.get(), 
                "所有消息都应被消费");
    }

    @Test
    @DisplayName("hasInbound: 空队列返回 false")
    void hasInbound_EmptyQueue_ReturnsFalse() {
        assertFalse(bus.hasInbound());
    }

    @Test
    @DisplayName("hasInbound: 有消息返回 true")
    void hasInbound_WithMessage_ReturnsTrue() {
        bus.publishInbound(new InboundMessage("ch", "u", "c", "msg"));
        assertTrue(bus.hasInbound());
    }
}
