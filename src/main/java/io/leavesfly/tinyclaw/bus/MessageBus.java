package io.leavesfly.tinyclaw.bus;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 消息总线 - 用于在通道和Agent之间路由消息
 * 
 * 这是整个TinyClaw系统的核心通信中枢，采用发布-订阅模式实现组件间解耦：
 * 
 * 核心功能：
 * - 入站消息路由：从各种通道接收用户消息并传递给Agent处理
 * - 出站消息路由：将Agent的响应发送到正确的输出通道
 * - 通道处理器注册：为不同通道注册对应的处理器
 * - 消息队列管理：使用有界阻塞队列防止内存溢出
 * 
 * 设计特点：
 * - 线程安全：使用ConcurrentHashMap和线程安全的队列实现
 * - 异步处理：支持阻塞和非阻塞两种消息消费模式
 * - 流量控制：队列满时会丢弃消息并记录警告
 * - 生命周期管理：支持优雅关闭和资源清理
 * 
 * 使用场景：
 * 1. 通道层：各个消息通道通过publishInbound发送消息
 * 2. Agent层：AgentLoop通过consumeInbound接收消息进行处理
 * 3. 响应路由：Agent通过publishOutbound发送响应，通道通过subscribeOutbound接收
 *
 */
public class MessageBus {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("bus");
    
    // 队列大小配置（可以通过系统属性覆盖）
    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final int INBOUND_QUEUE_SIZE = Integer.getInteger(
        "tinyclaw.bus.inbound.queue.size", DEFAULT_QUEUE_SIZE
    );
    private static final int OUTBOUND_QUEUE_SIZE = Integer.getInteger(
        "tinyclaw.bus.outbound.queue.size", DEFAULT_QUEUE_SIZE
    );
    
    private final LinkedBlockingQueue<InboundMessage> inbound;
    private final LinkedBlockingQueue<OutboundMessage> outbound;
    private final Map<String, Function<InboundMessage, Void>> handlers;
    
    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>(INBOUND_QUEUE_SIZE);
        this.outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_SIZE);
        this.handlers = new ConcurrentHashMap<>();
        
        logger.info("MessageBus initialized", Map.of(
            "inbound_queue_size", INBOUND_QUEUE_SIZE,
            "outbound_queue_size", OUTBOUND_QUEUE_SIZE
        ));
    }
    
    /**
     * 发布入站消息到总线
     * 
     * 将来自外部通道的用户消息发布到入站队列中，供Agent处理。
     * 如果队列已满，消息会被丢弃并记录警告日志。
     * 
     * @param message 要发布的入站消息
     */
    public void publishInbound(InboundMessage message) {
        if (!inbound.offer(message)) {
            logger.warn("Inbound queue full, dropping message: " + message);
            return;
        }
        logger.debug("Published inbound message", Map.of(
                "channel", message.getChannel(),
                "chat_id", message.getChatId(),
                "queue_size", inbound.size()
        ));
    }
    
    /**
     * 从总线消费入站消息（阻塞式）
     * 
     * 阻塞式地从入站队列中取出消息，如果没有消息可用则会一直等待。
     * 这是AgentLoop主循环中使用的主要方法。
     * 
     * @return 下一条入站消息
     * @throws InterruptedException 如果线程在等待期间被中断
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }
    
    /**
     * 带超时的消费入站消息
     * 
     * 在指定时间内尝试从入站队列获取消息，超时后返回null。
     * 适用于需要定期检查其他条件的场景。
     * 
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息对象或null（如果超时）
     * @throws InterruptedException 如果线程在等待期间被中断
     */
    public InboundMessage consumeInbound(long timeout, TimeUnit unit) throws InterruptedException {
        return inbound.poll(timeout, unit);
    }
    
    /**
     * 发布出站消息到总线
     * 
     * 将Agent生成的响应消息发布到出站队列中，供相应的通道发送给用户。
     * 如果队列已满，消息会被丢弃并记录警告日志。
     * 
     * @param message 要发布的出站消息
     */
    public void publishOutbound(OutboundMessage message) {
        if (!outbound.offer(message)) {
            logger.warn("Outbound queue full, dropping message: " + message);
            return;
        }
        logger.debug("Published outbound message", Map.of(
                "channel", message.getChannel(),
                "chat_id", message.getChatId(),
                "queue_size", outbound.size()
        ));
    }
    
    /**
     * 订阅出站消息（阻塞式）
     * 
     * 阻塞式地从出站队列中取出消息，供通道层发送给用户。
     * 通道管理器使用此方法获取需要发送的响应消息。
     * 
     * @return 下一条出站消息
     * @throws InterruptedException 如果线程在等待期间被中断
     */
    public OutboundMessage subscribeOutbound() throws InterruptedException {
        return outbound.take();
    }
    
    /**
     * 带超时的订阅出站消息
     * 
     * 在指定时间内尝试从出站队列获取消息，超时后返回null。
     * 适用于需要定期执行其他任务的通道实现。
     * 
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息对象或null（如果超时）
     * @throws InterruptedException 如果线程在等待期间被中断
     */
    public OutboundMessage subscribeOutbound(long timeout, TimeUnit unit) throws InterruptedException {
        return outbound.poll(timeout, unit);
    }
    
    /**
     * 为特定通道注册处理器
     * 
     * 注册特定通道的消息处理器函数，用于处理该通道的特殊逻辑。
     * 处理器会在消息被正式处理前调用。
     * 
     * @param channel 通道名称
     * @param handler 消息处理器函数
     */
    public void registerHandler(String channel, Function<InboundMessage, Void> handler) {
        handlers.put(channel, handler);
        logger.debug("Registered handler for channel: " + channel);
    }
    
    /**
     * 获取特定通道的处理器
     * 
     * 根据通道名称获取已注册的消息处理器函数。
     * 
     * @param channel 通道名称
     * @return 对应的处理器函数，如果未找到则返回null
     */
    public Function<InboundMessage, Void> getHandler(String channel) {
        return handlers.get(channel);
    }
    
    /**
     * 检查是否有待处理的入站消息
     * 
     * 检查入站队列是否为空，用于快速判断是否有消息需要处理。
     * 
     * @return 如果有待处理消息返回true，否则返回false
     */
    public boolean hasInbound() {
        return !inbound.isEmpty();
    }
    
    /**
     * 获取待处理入站消息的数量
     * 
     * 返回当前入站队列中的消息数量，用于监控和负载均衡。
     * 
     * @return 队列中的消息数量
     */
    public int getInboundSize() {
        return inbound.size();
    }
    
    /**
     * 获取待处理出站消息的数量
     * 
     * 返回当前出站队列中的消息数量，用于监控响应处理状态。
     * 
     * @return 队列中的消息数量
     */
    public int getOutboundSize() {
        return outbound.size();
    }
    
    /**
     * 清除所有待处理消息
     * 
     * 清空入站和出站队列中的所有消息，用于系统重置或紧急情况处理。
     * 此操作不可逆，请谨慎使用。
     */
    public void clear() {
        inbound.clear();
        outbound.clear();
        logger.debug("Message bus cleared");
    }
    
    /**
     * 关闭消息总线（不再接受新消息）
     * 
     * 执行消息总线的优雅关闭流程：
     * 1. 清空所有待处理消息
     * 2. 记录关闭日志
     * 
     * 注意：在生产环境中，可能需要实现更复杂的关闭信号机制
     * 来通知所有消费者优雅退出。
     */
    public void close() {
        // 对于阻塞队列，我们只清除它们
        // 在生产系统中，我们可能想要发出关闭信号
        clear();
        logger.info("Message bus closed");
    }
}
