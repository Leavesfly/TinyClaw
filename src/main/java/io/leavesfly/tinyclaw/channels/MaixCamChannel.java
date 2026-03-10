package io.leavesfly.tinyclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.leavesfly.tinyclaw.bus.InboundMessage;
import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.config.ChannelsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MaixCam 通道实现 - 基于 TCP Socket 的自定义协议
 * 
 * 提供与 MaixCam AI 摄像头设备的通信能力：
 * - TCP 服务器监听设备连接
 * - 人员检测事件处理
 * - JSON 协议通信
 * - 多设备连接支持
 * 
 * 核心流程：
 * 1. 启动 TCP 服务器监听指定端口
 * 2. 接受 MaixCam 设备的连接
 * 3. 接收设备推送的事件（人员检测、心跳、状态等）
 * 4. 解析事件并发布到消息总线
 * 5. 向设备发送控制命令
 * 
 * 支持的消息类型：
 * - person_detected：人员检测事件
 * - heartbeat：心跳消息
 * - status：状态更新
 * - command：控制命令（出站）
 * 
 * 配置要求：
 * - Host：监听地址
 * - Port：监听端口
 */
public class MaixCamChannel extends BaseChannel {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("maixcam");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ChannelsConfig.MaixCamConfig config;
    private ServerSocket serverSocket;
    private volatile boolean serverRunning = false;
    
    // 已连接的客户端
    private final Map<Socket, PrintWriter> clients = new ConcurrentHashMap<>();
    
    /**
     * 创建 MaixCam 通道
     * 
     * @param config MaixCam 配置
     * @param bus 消息总线
     */
    public MaixCamChannel(ChannelsConfig.MaixCamConfig config, MessageBus bus) {
        super("maixcam", bus, config.getAllowFrom());
        this.config = config;
    }
    
    @Override
    public void start() throws Exception {
        logger.info("正在启动 MaixCam 通道...");
        
        String host = config.getHost() != null ? config.getHost() : "0.0.0.0";
        int port = config.getPort() > 0 ? config.getPort() : 8080;
        
        serverSocket = new ServerSocket(port);
        serverRunning = true;
        setRunning(true);
        
        logger.info("MaixCam 服务器已启动", Map.of(
            "host", host,
            "port", port
        ));
        
        // 启动连接接收线程
        Thread acceptThread = new Thread(this::acceptConnections, "maixcam-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }
    
    @Override
    public void stop() {
        logger.info("正在停止 MaixCam 通道...");
        serverRunning = false;
        setRunning(false);
        
        // 关闭所有客户端连接
        for (Socket client : clients.keySet()) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        clients.clear();
        
        // 关闭服务器
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        
        logger.info("MaixCam 通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("MaixCam 通道未运行");
        }
        
        if (clients.isEmpty()) {
            logger.warn("没有已连接的 MaixCam 设备");
            throw new Exception("没有已连接的 MaixCam 设备");
        }
        
        // 构建响应消息
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "command");
        response.put("timestamp", System.currentTimeMillis() / 1000.0);
        response.put("message", message.getContent());
        response.put("chat_id", message.getChatId());
        
        String jsonMessage = objectMapper.writeValueAsString(response);
        
        // 发送给所有连接的设备
        Exception lastError = null;
        for (Map.Entry<Socket, PrintWriter> entry : clients.entrySet()) {
            try {
                entry.getValue().println(jsonMessage);
            } catch (Exception e) {
                logger.error("发送消息到设备失败", Map.of(
                    "client", entry.getKey().getRemoteSocketAddress().toString(),
                    "error", e.getMessage()
                ));
                lastError = e;
            }
        }
        
        if (lastError != null) {
            throw lastError;
        }
    }
    
    /**
     * 接受客户端连接
     */
    private void acceptConnections() {
        while (serverRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                logger.info("新的 MaixCam 设备连接", Map.of(
                    "remote_addr", client.getRemoteSocketAddress().toString()
                ));
                
                clients.put(client, new PrintWriter(client.getOutputStream(), true));
                
                // 启动处理线程
                Thread handlerThread = new Thread(() -> handleClient(client), "maixcam-handler");
                handlerThread.setDaemon(true);
                handlerThread.start();
                
            } catch (IOException e) {
                if (serverRunning) {
                    logger.error("接受连接时出错", Map.of("error", e.getMessage()));
                }
            }
        }
    }
    
    /**
     * 处理客户端连接
     */
    private void handleClient(Socket client) {
        String clientAddr = client.getRemoteSocketAddress().toString();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
            String line;
            while (serverRunning && (line = reader.readLine()) != null) {
                processMessage(line, client);
            }
        } catch (IOException e) {
            logger.debug("客户端连接断开", Map.of("client", clientAddr));
        } finally {
            clients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }
    
    /**
     * 处理收到的消息
     */
    private void processMessage(String messageJson, Socket client) {
        try {
            JsonNode msg = objectMapper.readTree(messageJson);
            
            String type = msg.path("type").asText("");
            
            switch (type) {
                case "person_detected":
                    handlePersonDetection(msg);
                    break;
                case "heartbeat":
                    logger.debug("收到 MaixCam 心跳");
                    break;
                case "status":
                    handleStatusUpdate(msg);
                    break;
                default:
                    logger.warn("未知的消息类型", Map.of("type", type));
            }
        } catch (Exception e) {
            logger.error("解析消息时出错", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 处理人员检测事件
     */
    private void handlePersonDetection(JsonNode msg) {
        String senderId = "maixcam";
        String chatId = "default";
        
        JsonNode data = msg.path("data");
        
        String className = data.path("class_name").asText("person");
        double score = data.path("score").asDouble(0);
        double x = data.path("x").asDouble(0);
        double y = data.path("y").asDouble(0);
        double w = data.path("w").asDouble(0);
        double h = data.path("h").asDouble(0);
        
        String content = String.format(
            "📷 检测到人员！\n类型: %s\n置信度: %.2f%%\n位置: (%.0f, %.0f)\n尺寸: %.0fx%.0f",
            className, score * 100, x, y, w, h
        );
        
        // 构建元数据
        Map<String, String> metadata = new HashMap<>();
        metadata.put("timestamp", String.valueOf((long) msg.path("timestamp").asDouble(0)));
        metadata.put("class_name", className);
        metadata.put("score", String.format("%.2f", score));
        metadata.put("x", String.format("%.0f", x));
        metadata.put("y", String.format("%.0f", y));
        metadata.put("w", String.format("%.0f", w));
        metadata.put("h", String.format("%.0f", h));
        
        logger.info("收到人员检测事件", Map.of(
            "class", className,
            "score", score
        ));
        
        // 通过父类统一处理权限校验和消息发布
        handleMessage(senderId, chatId, content, null, metadata);
    }
    
    /**
     * 处理状态更新
     */
    private void handleStatusUpdate(JsonNode msg) {
        logger.info("收到 MaixCam 状态更新", Map.of(
            "status", msg.path("data").toString()
        ));
    }
}
