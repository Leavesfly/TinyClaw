package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.*;
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
 */
public class ChannelManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("channels");
    
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final Config config;
    private volatile boolean dispatchRunning = false;
    private Thread dispatchThread;
    
    public ChannelManager(Config config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
        initChannels();
    }
    
    private void initChannels() {
        logger.info("Initializing channel manager");
        
        ChannelsConfig channelsConfig = config.getChannels();
        
        initTelegramChannel(channelsConfig);
        initDiscordChannel(channelsConfig);
        initWhatsAppChannel(channelsConfig);
        initFeishuChannel(channelsConfig);
        initDingTalkChannel(channelsConfig);
        initQQChannel(channelsConfig);
        initMaixCamChannel(channelsConfig);
        
        logger.info("Channel initialization completed", Map.of("enabled_channels", channels.size()));
    }
    
    /**
     * 初始化 Telegram 通道
     */
    private void initTelegramChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getTelegram().isEnabled() 
                && channelsConfig.getTelegram().getToken() != null 
                && !channelsConfig.getTelegram().getToken().isEmpty()) {
            try {
                Channel telegram = new TelegramChannel(channelsConfig.getTelegram(), bus);
                channels.put("telegram", telegram);
                logger.info("Telegram channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize Telegram channel", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 初始化 Discord 通道
     */
    private void initDiscordChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getDiscord().isEnabled() 
                && channelsConfig.getDiscord().getToken() != null 
                && !channelsConfig.getDiscord().getToken().isEmpty()) {
            try {
                Channel discord = new DiscordChannel(channelsConfig.getDiscord(), bus);
                channels.put("discord", discord);
                logger.info("Discord channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize Discord channel", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 初始化 WhatsApp 通道
     */
    private void initWhatsAppChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getWhatsapp().isEnabled() 
                && channelsConfig.getWhatsapp().getBridgeUrl() != null 
                && !channelsConfig.getWhatsapp().getBridgeUrl().isEmpty()) {
            try {
                Channel whatsapp = new WhatsAppChannel(channelsConfig.getWhatsapp(), bus);
                channels.put("whatsapp", whatsapp);
                logger.info("WhatsApp channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize WhatsApp channel", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 初始化飞书通道
     */
    private void initFeishuChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getFeishu().isEnabled()) {
            try {
                Channel feishu = new FeishuChannel(channelsConfig.getFeishu(), bus);
                channels.put("feishu", feishu);
                logger.info("Feishu channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize Feishu channel", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 初始化钉钉通道
     */
    private void initDingTalkChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getDingtalk().isEnabled() 
                && channelsConfig.getDingtalk().getClientId() != null 
                && !channelsConfig.getDingtalk().getClientId().isEmpty()) {
            try {
                Channel dingtalk = new DingTalkChannel(channelsConfig.getDingtalk(), bus);
                channels.put("dingtalk", dingtalk);
                logger.info("DingTalk channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize DingTalk channel", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 初始化 QQ 通道
     */
    private void initQQChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getQq().isEnabled()) {
            try {
                Channel qq = new QQChannel(channelsConfig.getQq(), bus);
                channels.put("qq", qq);
                logger.info("QQ channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize QQ channel", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 初始化 MaixCam 通道
     */
    private void initMaixCamChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getMaixcam().isEnabled()) {
            try {
                Channel maixcam = new MaixCamChannel(channelsConfig.getMaixcam(), bus);
                channels.put("maixcam", maixcam);
                logger.info("MaixCam channel enabled successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize MaixCam channel", Map.of("error", e.getMessage()));
            }
        }
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
        
        // 启动出站调度器
        dispatchRunning = true;
        dispatchThread = new Thread(this::dispatchOutbound, "channel-dispatcher");
        dispatchThread.setDaemon(true);
        dispatchThread.start();
        
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
        if (dispatchThread != null) {
            dispatchThread.interrupt();
        }
        
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
        
        logger.info("All channels stopped");
    }
    
    /**
     * 调度出站消息
     * 
     * 在独立线程中运行的消息分发循环：
     * 1. 从消息总线订阅出站消息
     * 2. 根据消息的目标通道查找对应的通道实例
     * 3. 将消息发送到目标通道
     * 4. 处理未知通道的情况
     * 
     * 此方法在守护线程中运行，当dispatchRunning标志被设置为false时退出。
     * 使用while循环持续处理消息，通过InterruptedException处理线程中断。
     */
    private void dispatchOutbound() {
        logger.info("Outbound dispatcher started");
        
        while (dispatchRunning) {
            try {
                OutboundMessage msg = bus.subscribeOutbound();
                if (msg == null) continue;
                
                Channel channel = channels.get(msg.getChannel());
                if (channel == null) {
                    logger.warn("Unknown channel for outbound message", Map.of(
                            "channel", msg.getChannel()
                    ));
                    continue;
                }
                
                channel.send(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error sending message to channel", Map.of("error", e.getMessage()));
            }
        }
        
        logger.info("Outbound dispatcher stopped");
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
     * 
     * 主要用于健康检查和监控面板显示。
     * 
     * @return 包含各通道状态信息的映射
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            Map<String, Object> channelStatus = new HashMap<>();
            channelStatus.put("enabled", true);
            channelStatus.put("running", entry.getValue().isRunning());
            status.put(entry.getKey(), channelStatus);
        }
        return status;
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