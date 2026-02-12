package io.leavesfly.tinyclaw.channels;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.List;
import java.util.Map;

/**
 * 带有通用功能的基础通道实现
 */
public abstract class BaseChannel implements Channel {
    
    protected final TinyClawLogger logger;
    protected final MessageBus bus;
    protected final String name;
    protected final List<String> allowList;
    protected volatile boolean running = false;
    
    public BaseChannel(String name, MessageBus bus, List<String> allowList) {
        this.name = name;
        this.bus = bus;
        this.allowList = allowList;
        this.logger = TinyClawLogger.getLogger("channel." + name);
    }
    
    @Override
    public String name() {
        return name;
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public boolean isAllowed(String senderId) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        return allowList.contains(senderId);
    }
    
    /**
     * 处理传入的消息
     */
    protected void handleMessage(String senderId, String chatId, String content, 
                                  List<String> media, Map<String, String> metadata) {
        if (!isAllowed(senderId)) {
            logger.debug("Message from unauthorized sender ignored", Map.of("sender_id", senderId));
            return;
        }
        
        // 构建会话键：channel:chatId
        String sessionKey = name + ":" + chatId;
        
        InboundMessage msg = new InboundMessage(name, senderId, chatId, content);
        msg.setMedia(media);
        msg.setSessionKey(sessionKey);
        msg.setMetadata(metadata);
        
        bus.publishInbound(msg);
    }
    
    protected void setRunning(boolean running) {
        this.running = running;
    }
}
