package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所有消息通道的管理器
 * 
 * 负责管理系统中所有可用的消息通道，包括初始化、启动、停止和消息路由：
 * 
 * 核心职责：
 * - 通道初始化：根据配置文件初始化各种消息通道（Telegram、Discord、微信等）
 * - 生命周期管理：统一管理所有通道的启动和停止
 * - 消息路由：将出站消息分发到正确的通道进行发送
 * - 状态监控：跟踪各通道的运行状态
 * 
 * 支持的通道类型：
 * - Telegram：基于Telegram Bot API的即时通讯通道
 * - Discord：基于Discord Bot的聊天通道
 * - WhatsApp：通过桥接服务支持WhatsApp消息
 * - 飞书：企业级协作平台消息通道
 * - 钉钉：阿里巴巴企业通讯平台
 * - QQ：腾讯QQ消息通道
 * - MaixCam：专用摄像头设备通道
 * 
 * 设计特点：
 * - 动态配置：根据配置文件动态决定启用哪些通道
 * - 异步调度：出站消息分发在独立线程中进行
 * - 错误隔离：单个通道的故障不会影响其他通道
 * - 灵活扩展：支持注册自定义通道实现
 *
 */
public class ChannelManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("channels");
    private static final int MAX_SEND_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000L;
    
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final Config config;
    private final OutboundPendingStore pendingStore;
    /** 发送超时“结果不确定”标记：key=channel|chatId，下次成功联系时告警并清除 */
    private final Map<String, UncertainInfo> uncertainSends = new ConcurrentHashMap<>();
    private volatile boolean dispatchRunning = false;
    private final List<Thread> dispatchThreads = new ArrayList<>();
    
    /** 不确定发送标记（次数 + 最近时间） */
    private record UncertainInfo(int count, long atMs) {}
    
    /** 发送失败分类 */
    enum SendFailureKind { RETRYABLE, UNCERTAIN, FATAL }
    
    public ChannelManager(Config config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
        this.pendingStore = new OutboundPendingStore(config.getWorkspacePath());
        initChannels();
    }
    
    /**
     * 通道注册描述符：封装每个通道的“启用条件 + 构造逻辑”，
     * 消除原来 7 个几乎相同的 initXxxChannel 方法。
     */
    private record ChannelDescriptor(String name, java.util.function.Supplier<Boolean> enabledCheck,
                                     java.util.function.Supplier<Channel> factory) {}

    private void initChannels() {
        logger.info("Initializing channel manager");
        
        ChannelsConfig cc = config.getChannels();
        
        // 数据驱动的通道注册表：新增通道只需在此追加一行
        List<ChannelDescriptor> registry = List.of(
            new ChannelDescriptor("telegram",
                () -> cc.getTelegram().isEnabled() && isNotBlank(cc.getTelegram().getToken()),
                () -> new TelegramChannel(cc.getTelegram(), bus)),
            new ChannelDescriptor("discord",
                () -> cc.getDiscord().isEnabled() && isNotBlank(cc.getDiscord().getToken()),
                () -> new DiscordChannel(cc.getDiscord(), bus)),
            new ChannelDescriptor("whatsapp",
                () -> cc.getWhatsapp().isEnabled() && isNotBlank(cc.getWhatsapp().getBridgeUrl()),
                () -> new WhatsAppChannel(cc.getWhatsapp(), bus)),
            new ChannelDescriptor("feishu",
                () -> cc.getFeishu().isEnabled(),
                () -> new FeishuChannel(cc.getFeishu(), bus)),
            new ChannelDescriptor("dingtalk",
                () -> cc.getDingtalk().isEnabled() && isNotBlank(cc.getDingtalk().getClientId()),
                () -> new DingTalkChannel(cc.getDingtalk(), bus)),
            new ChannelDescriptor("qq",
                () -> cc.getQq().isEnabled(),
                () -> new QQChannel(cc.getQq(), bus)),
            new ChannelDescriptor("maixcam",
                () -> cc.getMaixcam().isEnabled(),
                () -> new MaixCamChannel(cc.getMaixcam(), bus))
        );
        
        for (ChannelDescriptor desc : registry) {
            if (!desc.enabledCheck().get()) {
                continue;
            }
            try {
                channels.put(desc.name(), desc.factory().get());
                logger.info(desc.name() + " channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize " + desc.name() + " channel",
                        Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown"));
            }
        }
        
        logger.info("Channel initialization completed", Map.of("enabled_channels", channels.size()));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isEmpty();
    }
    
    /**
     * 启动所有通道
     * 
     * 按照以下顺序启动所有已配置的通道：
     * 1. 启动出站消息调度线程
     * 2. 依次启动每个已注册的通道
     * 3. 记录启动过程中的成功和失败情况
     * 
     * 如果没有任何通道被启用，会记录警告信息。
     * 每个通道的启动都是独立的，一个通道的失败不会影响其他通道。
     */
    public void startAll() {
        if (channels.isEmpty()) {
            logger.warn("No channels enabled");
            return;
        }
        
        logger.info("Starting all channels");
        
        // 重启恢复：上次未送达的消息重投总线补发
        restorePendingOutbound();
        
        // 为每个通道启动独立的出站调度线程，各通道消费者只消费自己通道的消息
        dispatchRunning = true;
        for (String channelName : channels.keySet()) {
            Thread dispatchThread = new Thread(
                () -> dispatchOutboundForChannel(channelName),
                "channel-dispatcher-" + channelName
            );
            dispatchThread.setDaemon(true);
            dispatchThread.start();
            dispatchThreads.add(dispatchThread);
        }
        
        // 启动所有通道
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            Channel channel = entry.getValue();
            
            logger.info("Starting channel", Map.of("channel", channelName));
            try {
                channel.start();
            } catch (Exception e) {
                logger.error("Failed to start channel", Map.of(
                        "channel", channelName,
                        "error", e.getMessage()
                ));
            }
        }
        
        logger.info("All channels started");
    }
    
    /**
     * 停止所有通道
     * 
     * 按照以下顺序优雅地停止所有通道：
     * 1. 停止出站消息调度线程
     * 2. 依次停止每个已启动的通道
     * 3. 记录停止过程中的状态
     * 
     * 使用interrupt()方法通知调度线程退出，
     * 各通道应该实现适当的清理逻辑来处理停止请求。
     */
    public void stopAll() {
        logger.info("Stopping all channels");
        
        dispatchRunning = false;
        for (Thread dispatchThread : dispatchThreads) {
            dispatchThread.interrupt();
        }
        dispatchThreads.clear();
        
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            Channel channel = entry.getValue();
            
            logger.info("Stopping channel", Map.of("channel", channelName));
            try {
                channel.stop();
            } catch (Exception e) {
                logger.error("Error stopping channel", Map.of(
                        "channel", channelName,
                        "error", e.getMessage()
                ));
            }
        }
        
        // 优雅停机：总线中未及发送的消息转储持久化，重启后补发
        for (String channelName : channels.keySet()) {
            List<OutboundMessage> remaining = bus.drainOutbound(channelName);
            if (!remaining.isEmpty()) {
                pendingStore.addAll(remaining);
                logger.info("Persisted undelivered outbound messages on shutdown", Map.of(
                        "channel", channelName,
                        "count", remaining.size()
                ));
            }
        }
        
        logger.info("All channels stopped");
    }
    
    /**
     * 重启恢复：读出上次未送达消息并重投总线。
     */
    private void restorePendingOutbound() {
        List<OutboundMessage> pending = pendingStore.loadAndClear();
        for (OutboundMessage msg : pending) {
            if (channels.containsKey(msg.getChannel())) {
                bus.publishOutbound(msg);
            } else {
                logger.warn("Pending message dropped: channel not enabled", Map.of(
                        "channel", msg.getChannel(),
                        "chat_id", msg.getChatId()
                ));
            }
        }
    }
    
    /**
     * 指定通道的出站消息调度循环
     *
     * 每个通道独立运行此方法，只消费属于自己通道的出站消息，互不干扰。
     * 当 dispatchRunning 为 false 或总线关闭时退出循环。
     *
     * @param channelName 负责调度的通道名称
     */
    private void dispatchOutboundForChannel(String channelName) {
        logger.info("Outbound dispatcher started", Map.of("channel", channelName));

        Channel channel = channels.get(channelName);
        if (channel == null) {
            logger.warn("Dispatcher started for unknown channel, exiting", Map.of("channel", channelName));
            return;
        }

        while (dispatchRunning) {
            try {
                OutboundMessage msg = bus.subscribeOutbound(channelName, 1, java.util.concurrent.TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }
                sendWithPolicy(channelName, channel, msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (io.leavesfly.tinyclaw.bus.BusClosedException e) {
                logger.info("MessageBus closed, stopping dispatcher", Map.of("channel", channelName));
                break;
            } catch (Exception e) {
                logger.error("Error dispatching outbound message", Map.of(
                        "channel", channelName,
                        "error", e.getMessage()
                ));
            }
        }

        logger.info("Outbound dispatcher stopped", Map.of("channel", channelName));
    }

    /**
     * 按失败分类执行发送策略：
     *
     * <ul>
     *   <li>RETRYABLE（连接类错误）：退避重试，耗尽后持久化待重启补发；</li>
     *   <li>UNCERTAIN（超时类错误）：对端可能已送达，不重试防重复，
     *       记录不确定标记，下次成功联系该会话时告警；</li>
     *   <li>FATAL（HTTP 4xx 等）：重试无意义，直接记录。</li>
     * </ul>
     */
    private void sendWithPolicy(String channelName, Channel channel, OutboundMessage msg) {
        Exception lastException = null;

        for (int retry = 0; retry <= MAX_SEND_RETRIES; retry++) {
            try {
                channel.send(msg);
                onSendSuccess(channelName, msg);
                return;
            } catch (Exception e) {
                lastException = e;
                SendFailureKind kind = classifySendFailure(e);
                if (kind == SendFailureKind.UNCERTAIN) {
                    recordUncertain(channelName, msg, e);
                    return;
                }
                if (kind == SendFailureKind.FATAL) {
                    logger.error("Fatal send failure, not retrying", Map.of(
                            "channel", channelName,
                            "chat_id", msg.getChatId(),
                            "error", String.valueOf(e.getMessage())
                    ));
                    return;
                }
                if (retry < MAX_SEND_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 重试耗尽：持久化待重启补发
        pendingStore.add(msg);
        logger.error("Failed to send message after retries, persisted for restart recovery", Map.of(
                "channel", channelName,
                "chat_id", msg.getChatId(),
                "retries", String.valueOf(MAX_SEND_RETRIES),
                "error", lastException != null ? lastException.getMessage() : "unknown"
        ));
    }

    /**
     * 发送成功后检查该会话是否存在不确定标记，有则告警并清除（warn on next contact）。
     */
    private void onSendSuccess(String channelName, OutboundMessage msg) {
        UncertainInfo info = uncertainSends.remove(channelName + "|" + msg.getChatId());
        if (info != null) {
            logger.warn("Previous sends to this chat were uncertain, duplicates possible", Map.of(
                    "channel", channelName,
                    "chat_id", msg.getChatId(),
                    "uncertain_count", String.valueOf(info.count)
            ));
        }
    }

    /**
     * 记录超时类不确定发送。
     */
    private void recordUncertain(String channelName, OutboundMessage msg, Exception e) {
        String key = channelName + "|" + msg.getChatId();
        uncertainSends.compute(key, (k, old) -> old == null
                ? new UncertainInfo(1, System.currentTimeMillis())
                : new UncertainInfo(old.count() + 1, System.currentTimeMillis()));
        logger.warn("Send timed out, result uncertain; not retrying to avoid duplicates", Map.of(
                "channel", channelName,
                "chat_id", msg.getChatId(),
                "error", String.valueOf(e.getMessage())
        ));
    }

    /**
     * 分类发送失败：超时类为 UNCERTAIN，HTTP 4xx 为 FATAL，其余 RETRYABLE。
     *
     * @param e 发送异常
     * @return 失败分类
     */
    static SendFailureKind classifySendFailure(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.io.InterruptedIOException) {
                // SocketTimeoutException 是其子类：超时意味着对端可能已处理
                return SendFailureKind.UNCERTAIN;
            }
            String message = t.getMessage();
            if (message != null && message.contains("HTTP 4")) {
                return SendFailureKind.FATAL;
            }
        }
        return SendFailureKind.RETRYABLE;
    }
    
    /**
     * 根据名称获取通道
     * 
     * 根据通道名称查找已注册的通道实例。
     * 
     * @param name 通道名称（如"telegram"、"discord"等）
     * @return 对应的通道实例，如果未找到则返回空Optional
     */
    public Optional<Channel> getChannel(String name) {
        return Optional.ofNullable(channels.get(name));
    }
    
    /**
     * 获取所有通道的状态
     * 
     * 返回系统中所有已注册通道的当前状态信息，包括：
     * - 是否已启用
     * - 是否正在运行
     * - 连接三态：usable / recovering / blocked
     * - 不确定发送次数（超时未重试、结果未知）
     * - 待重启补发的消息数
     * 
     * 主要用于健康检查和监控面板显示。
     * 
     * @return 包含各通道状态信息的映射
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            Map<String, Object> channelStatus = new HashMap<>();
            channelStatus.put("enabled", true);
            channelStatus.put("running", entry.getValue().isRunning());
            channelStatus.put("state", entry.getValue().connectionState());
            int uncertain = uncertainSends.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(channelName + "|"))
                    .mapToInt(e -> e.getValue().count())
                    .sum();
            channelStatus.put("uncertainSends", uncertain);
            status.put(channelName, channelStatus);
        }
        return status;
    }
    
    /**
     * 待重启补发的消息数量。
     */
    public int getPendingOutboundCount() {
        return pendingStore.size();
    }
    
    /**
     * 获取启用的通道名称列表
     * 
     * 返回当前系统中所有已启用通道的名称列表。
     * 
     * @return 通道名称列表
     */
    public List<String> getEnabledChannels() {
        return new ArrayList<>(channels.keySet());
    }
    
    /**
     * 注册通道
     * 
     * 动态注册一个新的通道实例，允许在运行时扩展系统功能。
     * 
     * @param name 通道名称
     * @param channel 通道实例
     */
    public void registerChannel(String name, Channel channel) {
        channels.put(name, channel);
    }
    
    /**
     * 取消注册通道
     * 
     * 从系统中移除指定名称的通道注册信息。
     * 
     * @param name 要取消注册的通道名称
     */
    public void unregisterChannel(String name) {
        channels.remove(name);
    }
    
    /**
     * 向特定通道发送消息
     * 
     * 直接向指定的通道发送消息，绕过正常的消息总线路由机制。
     * 主要用于系统内部的直接消息发送需求。
     * 
     * @param channelName 目标通道名称
     * @param chatId 聊天ID
     * @param content 消息内容
     * @throws Exception 如果通道不存在或发送失败
     */
    public void sendToChannel(String channelName, String chatId, String content) throws Exception {
        Channel channel = channels.get(channelName);
        if (channel == null) {
            throw new IllegalArgumentException("Channel " + channelName + " not found");
        }
        
        OutboundMessage msg = new OutboundMessage(channelName, chatId, content);
        channel.send(msg);
    }
}