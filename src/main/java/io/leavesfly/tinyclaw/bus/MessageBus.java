package io.leavesfly.tinyclaw.bus;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息总线 - 用于在通道和 Agent 之间路由消息
 *
 * 这是整个 TinyClaw 系统的核心通信中枢，采用发布-订阅模式实现组件间解耦：
 *
 * 核心功能：
 * - 入站消息路由：从各种通道接收用户消息并传递给 Agent 处理
 * - 出站消息路由：将 Agent 的响应按 channel 路由到对应的输出通道（per-channel 独立队列）
 * - 消息队列管理：使用有界阻塞队列防止内存溢出
 *
 * 设计特点：
 * - 线程安全：使用 ConcurrentHashMap 和线程安全的队列实现
 * - 异步处理：支持阻塞和非阻塞两种消息消费模式
 * - 流量控制：队列满时会丢弃消息并记录警告
 * - 精准路由：出站消息按 channel 分队列存储，各通道消费者只取自己的消息，避免无效轮询
 * - 生命周期管理：支持立即关闭（close）和优雅排空关闭（drainAndClose）
 *
 * 使用场景：
 * 1. 通道层：各个消息通道通过 publishInbound 发送消息，通过 subscribeOutbound(channel) 接收响应
 * 2. Agent 层：AgentLoop 通过 consumeInbound 接收消息进行处理
 * 3. 响应路由：Agent 通过 publishOutbound 发送响应，消息自动路由到对应通道的队列
 */
public class MessageBus {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("bus");

    // 队列大小配置（可以通过系统属性覆盖）
    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final int INBOUND_QUEUE_SIZE = Integer.getInteger(
        "tinyclaw.bus.inbound.queue.size", DEFAULT_QUEUE_SIZE
    );
    private static final int OUTBOUND_QUEUE_SIZE_PER_CHANNEL = Integer.getInteger(
        "tinyclaw.bus.outbound.queue.size", DEFAULT_QUEUE_SIZE
    );

    private final LinkedBlockingQueue<InboundMessage> inbound;

    /**
     * 按 channel 分隔的出站队列，每个通道独立消费，避免跨通道消息互相干扰。
     * key 为 channel 名称，value 为该通道的出站消息队列。
     */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<OutboundMessage>> outboundByChannel;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong droppedInboundCount = new AtomicLong(0);
    private final AtomicLong droppedOutboundCount = new AtomicLong(0);

    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>(INBOUND_QUEUE_SIZE);
        this.outboundByChannel = new ConcurrentHashMap<>();

        logger.info("MessageBus initialized", Map.of(
            "inbound_queue_size", INBOUND_QUEUE_SIZE,
            "outbound_queue_size_per_channel", OUTBOUND_QUEUE_SIZE_PER_CHANNEL
        ));
    }

    /**
     * 发布入站消息到总线
     *
     * 将来自外部通道的用户消息发布到入站队列中，供 Agent 处理。
     * 如果队列已满，消息会被丢弃并记录错误日志。
     *
     * @param message 要发布的入站消息
     * @throws BusClosedException 如果总线已关闭
     */
    public void publishInbound(InboundMessage message) {
        if (closed.get()) {
            throw new BusClosedException("MessageBus is closed, cannot publish inbound message");
        }
        if (!inbound.offer(message)) {
            long dropped = droppedInboundCount.incrementAndGet();
            logger.error("Inbound queue full, dropping message", Map.of(
                "channel", message.getChannel(),
                "chat_id", message.getChatId(),
                "queue_size", inbound.size(),
                "total_dropped", dropped
            ));
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
     * 这是 AgentLoop 主循环中使用的主要方法。
     *
     * @return 下一条入站消息，永远不会返回 null
     * @throws InterruptedException 如果线程在等待期间被中断
     * @throws BusClosedException 如果总线已关闭且队列为空
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        while (true) {
            InboundMessage message = inbound.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                return message;
            }
            if (closed.get()) {
                throw new BusClosedException("MessageBus is closed");
            }
        }
    }

    /**
     * 带超时的消费入站消息
     *
     * 在指定时间内尝试从入站队列获取消息，超时后返回 null。
     * 适用于需要定期检查其他条件的场景。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息对象或 null（如果超时）
     * @throws InterruptedException 如果线程在等待期间被中断
     */
    public InboundMessage consumeInbound(long timeout, TimeUnit unit) throws InterruptedException {
        return inbound.poll(timeout, unit);
    }

    /**
     * 发布出站消息到总线
     *
     * 将 Agent 生成的响应消息路由到对应 channel 的出站队列中，供该通道的消费者发送给用户。
     * 如果对应通道的队列已满，消息会被丢弃并记录错误日志。
     *
     * @param message 要发布的出站消息
     * @throws BusClosedException 如果总线已关闭
     */
    public void publishOutbound(OutboundMessage message) {
        if (closed.get()) {
            throw new BusClosedException("MessageBus is closed, cannot publish outbound message");
        }
        LinkedBlockingQueue<OutboundMessage> channelQueue = getOrCreateOutboundQueue(message.getChannel());
        if (!channelQueue.offer(message)) {
            long dropped = droppedOutboundCount.incrementAndGet();
            logger.error("Outbound queue full, dropping message", Map.of(
                "channel", message.getChannel(),
                "chat_id", message.getChatId(),
                "queue_size", channelQueue.size(),
                "total_dropped", dropped
            ));
            return;
        }
        logger.debug("Published outbound message", Map.of(
            "channel", message.getChannel(),
            "chat_id", message.getChatId(),
            "queue_size", channelQueue.size()
        ));
    }

    /**
     * 订阅指定通道的出站消息（阻塞式）
     *
     * 阻塞式地从指定 channel 的出站队列中取出消息，供该通道发送给用户。
     * 各通道消费者只消费自己通道的消息，互不干扰。
     *
     * @param channel 通道名称
     * @return 下一条出站消息，永远不会返回 null
     * @throws InterruptedException 如果线程在等待期间被中断
     * @throws BusClosedException 如果总线已关闭且队列为空
     */
    public OutboundMessage subscribeOutbound(String channel) throws InterruptedException {
        LinkedBlockingQueue<OutboundMessage> channelQueue = getOrCreateOutboundQueue(channel);
        while (true) {
            OutboundMessage message = channelQueue.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                return message;
            }
            if (closed.get()) {
                throw new BusClosedException("MessageBus is closed");
            }
        }
    }

    /**
     * 带超时的订阅指定通道出站消息
     *
     * 在指定时间内尝试从指定 channel 的出站队列获取消息，超时后返回 null。
     * 适用于需要定期执行其他任务的通道实现。
     *
     * @param channel 通道名称
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息对象或 null（如果超时）
     * @throws InterruptedException 如果线程在等待期间被中断
     */
    public OutboundMessage subscribeOutbound(String channel, long timeout, TimeUnit unit) throws InterruptedException {
        return getOrCreateOutboundQueue(channel).poll(timeout, unit);
    }

    /**
     * 检查是否有待处理的入站消息
     *
     * @return 如果有待处理消息返回 true，否则返回 false
     */
    public boolean hasInbound() {
        return !inbound.isEmpty();
    }

    /**
     * 获取待处理入站消息的数量
     *
     * @return 队列中的消息数量
     */
    public int getInboundSize() {
        return inbound.size();
    }

    /**
     * 获取指定通道待处理出站消息的数量
     *
     * @param channel 通道名称
     * @return 该通道队列中的消息数量
     */
    public int getOutboundSize(String channel) {
        LinkedBlockingQueue<OutboundMessage> channelQueue = outboundByChannel.get(channel);
        return channelQueue == null ? 0 : channelQueue.size();
    }

    /**
     * 获取所有已注册出站通道的名称集合
     *
     * @return 通道名称集合
     */
    public Set<String> getRegisteredChannels() {
        return outboundByChannel.keySet();
    }

    /**
     * 清除所有待处理消息
     *
     * 清空入站队列和所有通道的出站队列，用于系统重置或紧急情况处理。
     * 此操作不可逆，请谨慎使用。
     */
    public void clear() {
        inbound.clear();
        outboundByChannel.values().forEach(LinkedBlockingQueue::clear);
        logger.debug("Message bus cleared");
    }

    /**
     * 立即关闭消息总线
     *
     * 立即设置关闭标志并清空所有队列，队列中未处理的消息将被丢弃。
     * 适用于需要快速停止的场景（如强制退出）。
     * 关闭后所有阻塞的消费者将收到 BusClosedException。
     */
    public void close() {
        closed.set(true);
        long totalDroppedInbound = droppedInboundCount.get();
        long totalDroppedOutbound = droppedOutboundCount.get();
        clear();
        logger.info("MessageBus closed (immediate)", Map.of(
            "total_dropped_inbound", totalDroppedInbound,
            "total_dropped_outbound", totalDroppedOutbound
        ));
    }

    /**
     * 优雅关闭消息总线（等待队列排空）
     *
     * 设置关闭标志后等待入站队列和所有出站队列排空，或超时后强制关闭。
     * 适用于需要确保所有消息都被处理完毕的场景（如正常退出）。
     *
     * @param timeout 最长等待时间
     * @param unit 时间单位
     * @throws InterruptedException 如果等待期间被中断
     */
    public void drainAndClose(long timeout, TimeUnit unit) throws InterruptedException {
        closed.set(true);
        long deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < deadlineMs) {
            boolean allEmpty = inbound.isEmpty()
                && outboundByChannel.values().stream().allMatch(LinkedBlockingQueue::isEmpty);
            if (allEmpty) {
                break;
            }
            Thread.sleep(100);
        }

        long remainingInbound = inbound.size();
        long remainingOutbound = outboundByChannel.values().stream().mapToLong(LinkedBlockingQueue::size).sum();
        clear();

        logger.info("MessageBus closed (drain)", Map.of(
            "remaining_inbound_discarded", remainingInbound,
            "remaining_outbound_discarded", remainingOutbound,
            "total_dropped_inbound", droppedInboundCount.get(),
            "total_dropped_outbound", droppedOutboundCount.get()
        ));
    }

    /**
     * 检查消息总线是否已关闭
     *
     * @return 如果已关闭返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 获取累计丢弃的入站消息数量
     *
     * @return 丢弃的入站消息总数
     */
    public long getDroppedInboundCount() {
        return droppedInboundCount.get();
    }

    /**
     * 获取累计丢弃的出站消息数量
     *
     * @return 丢弃的出站消息总数
     */
    public long getDroppedOutboundCount() {
        return droppedOutboundCount.get();
    }

    private LinkedBlockingQueue<OutboundMessage> getOrCreateOutboundQueue(String channel) {
        return outboundByChannel.computeIfAbsent(
            channel,
            key -> new LinkedBlockingQueue<>(OUTBOUND_QUEUE_SIZE_PER_CHANNEL)
        );
    }
}
