package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.*;

/**
 * WhatsApp 通道实现 - 基于 WhatsApp Bridge WebSocket API
 * 
 * 提供通过 WhatsApp Bridge 服务发送和接收消息的能力：
 * - WebSocket 连接到 Bridge 服务
 * - 文本消息收发
 * - 媒体消息支持
 * 
 * 核心流程：
 * 1. 通过 WebSocket 连接到 WhatsApp Bridge 服务
 * 2. 接收 Bridge 转发的 WhatsApp 消息
 * 3. 解析消息内容并发布到消息总线
 * 4. 通过 WebSocket 发送出站消息
 * 
 * 配置要求：
 * - Bridge URL：WhatsApp Bridge 服务的 WebSocket 地址
 */
public class WhatsAppChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("whatsapp");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ChannelsConfig.WhatsAppConfig config;
    private WebSocketClient wsClient;
    private volatile boolean connected = false;
    
    /**
     * 创建 WhatsApp 通道
     * 
     * @param config WhatsApp 配置
     * @param bus 消息总线
     */
    public WhatsAppChannel(ChannelsConfig.WhatsAppConfig config, MessageBus bus) {
        super("whatsapp", bus, config.getAllowFrom());
        this.config = config;
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动 WhatsApp 通道...", Map.of("bridge_url", config.getBridgeUrl()));
        
        if (config.getBridgeUrl() == null || config.getBridgeUrl().isEmpty()) {
            throw new Exception("WhatsApp Bridge URL 为空");
        }
        
        URI uri = new URI(config.getBridgeUrl());
        
        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected = true;
                logger.info("WhatsApp Bridge 连接成功");
            }
            
            @Override
            public void onMessage(String message) {
                handleIncomingMessage(message);
            }
            
            @Override
            public void onClose(int code, String reason, boolean remote) {
                connected = false;
                logger.warn("WhatsApp Bridge 连接关闭", Map.of(
                    "code", code,
                    "reason", reason,
                    "remote", remote
                ));
            }
            
            @Override
            public void onError(Exception ex) {
                logger.error("WhatsApp Bridge 连接错误", Map.of("error", ex.getMessage()));
            }
        };
        
        wsClient.connect();
        
        // 等待连接
        int attempts = 0;
        while (!connected && attempts < 30) {
            Thread.sleep(500);
            attempts++;
        }
        
        if (!connected) {
            throw new Exception("连接 WhatsApp Bridge 超时");
        }
        
        setRunning(true);
        logger.info("WhatsApp 通道已启动");
    }
    
    @Override
    public void stop() {
        logger.info("正在停止 WhatsApp 通道...");
        setRunning(false);
        connected = false;
        
        if (wsClient != null) {
            wsClient.close();
        }
        
        logger.info("WhatsApp 通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
        if (!isRunning() || !connected) {
            throw new IllegalStateException("WhatsApp 通道未运行");
        }
        
        // 构建消息
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "message");
        payload.put("to", message.getChatId());
        payload.put("content", message.getContent());
        
        String jsonPayload = objectMapper.writeValueAsString(payload);
        wsClient.send(jsonPayload);
        
        logger.debug("WhatsApp 消息已发送", Map.of("chat_id", message.getChatId()));
    }
    
    /**
     * 处理接收到的消息
     */
    private void handleIncomingMessage(String message) {
        try {
            JsonNode msg = objectMapper.readTree(message);
            
            String msgType = msg.path("type").asText("");
            if (!"message".equals(msgType)) {
                return;
            }
            
            String senderId = msg.path("from").asText(null);
            if (senderId == null || senderId.isEmpty()) {
                return;
            }
            
            // 权限检查
            if (!isAllowed(senderId)) {
                logger.warn("消息被拒绝（不在允许列表）", Map.of("sender_id", senderId));
                return;
            }
            
            String chatId = msg.path("chat").asText(senderId);
            String content = msg.path("content").asText("");
            
            // 提取媒体路径
            List<String> mediaPaths = new ArrayList<>();
            JsonNode mediaNode = msg.path("media");
            if (mediaNode.isArray()) {
                for (JsonNode m : mediaNode) {
                    mediaPaths.add(m.asText());
                }
            }
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            if (msg.has("id")) {
                metadata.put("message_id", msg.get("id").asText());
            }
            if (msg.has("from_name")) {
                metadata.put("user_name", msg.get("from_name").asText());
            }
            
            logger.info("收到 WhatsApp 消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "preview", StringUtils.truncate(content, 50)
            ));
            
            // 发布到消息总线
            InboundMessage inboundMsg = new InboundMessage(
                "whatsapp",
                senderId,
                chatId,
                content
            );
            inboundMsg.setMedia(mediaPaths);
            inboundMsg.setMetadata(metadata);
            bus.publishInbound(inboundMsg);
            
        } catch (Exception e) {
            logger.error("处理 WhatsApp 消息时出错", Map.of("error", e.getMessage()));
        }
    }
    
}
