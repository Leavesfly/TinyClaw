package io.leavesfly.tinyclaw.heartbeat;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 心跳服务 - 用于周期性检查和主动行动的定时任务
 * 
 * HeartbeatService 提供了一个可配置的定时心跳机制，用于定期执行自定义的检查逻辑。
 * 该服务支持通过回调函数实现灵活的心跳处理，并提供了完善的启动、停止和状态管理功能。
 * 
 * 主要功能：
 * - 可配置的执行间隔（秒级）
 * - 支持启用/禁用开关
 * - 线程安全的启动和停止控制
 * - 自定义心跳回调处理函数
 * - 自动读取和构建心跳提示信息
 * - 完善的日志记录和错误处理
 * 
 * 使用示例：
 * HeartbeatService service = new HeartbeatService(
 *     "/path/to/workspace",
 *     prompt -> { return "处理结果"; },
 *     60,  // 每60秒执行一次
 *     true // 启用服务
 * );
 * service.start();  // 启动服务
 * service.stop();   // 停止服务
 */
public class HeartbeatService {
    
    /** 心跳服务专用日志记录器 */
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("heartbeat");
    
    /** 工作空间路径，用于存储心跳相关的文件（如 HEARTBEAT.md、heartbeat.log） */
    private final String workspace;
    
    /** 心跳回调函数，每次心跳周期执行时调用 */
    private final Function<String, String> onHeartbeat;
    
    /** 心跳执行间隔（秒） */
    private final int intervalSeconds;
    
    /** 服务是否启用 */
    private final boolean enabled;
    
    /** 可重入锁，用于保证线程安全的启动和停止操作 */
    private final ReentrantLock lock = new ReentrantLock();
    
    /** 服务运行状态标志 */
    private volatile boolean running = false;
    
    /** 心跳执行线程 */
    private Thread heartbeatThread;
    
    /**
     * 构造 HeartbeatService 实例
     * 
     * @param workspace 工作空间路径，用于存储心跳相关文件
     * @param onHeartbeat 心跳回调函数，接收构建的提示字符串，返回处理结果
     * @param intervalSeconds 心跳执行间隔（秒）
     * @param enabled 是否启用该服务
     */
    public HeartbeatService(String workspace, Function<String, String> onHeartbeat, 
                           int intervalSeconds, boolean enabled) {
        this.workspace = workspace;
        this.onHeartbeat = onHeartbeat;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
    }
    
    /**
     * 启动心跳服务
     * 
     * 该方法会创建一个守护线程来定期执行心跳检查。
     * 如果服务已经在运行，则直接返回。如果服务被禁用，则抛出异常。
     * 
     * @throws IllegalStateException 如果服务被禁用
     * @throws Exception 如果启动过程中发生其他错误
     */
    public void start() throws Exception {
        lock.lock();
        try {
            // 如果服务已在运行，直接返回
            if (running) return;
            
            // 检查服务是否启用
            if (!enabled) {
                throw new IllegalStateException("Heartbeat service is disabled");
            }
            
            // 标记服务为运行状态
            running = true;
            // 创建并启动守护线程
            heartbeatThread = new Thread(this::runLoop, "heartbeat-service");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
            
            logger.info("Heartbeat service started", Map.of("interval_seconds", intervalSeconds));
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 停止心跳服务
     * 
     * 该方法会中断心跳线程并标记服务为停止状态。
     * 如果服务未在运行，则直接返回。
     */
    public void stop() {
        lock.lock();
        try {
            // 如果服务未在运行，直接返回
            if (!running) return;
            
            // 标记服务为停止状态
            running = false;
            // 中断心跳线程
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
            }
            logger.info("Heartbeat service stopped");
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 心跳主循环
     * 
     * 该方法在独立的守护线程中运行，按照配置的间隔定期执行心跳检查。
     * 如果线程被中断或发生异常，则会退出循环。
     */
    private void runLoop() {
        while (running) {
            try {
                // 等待指定的间隔时间
                Thread.sleep(intervalSeconds * 1000L);
                // 执行心跳检查
                checkHeartbeat();
            } catch (InterruptedException e) {
                // 线程被中断，退出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 记录心跳错误，但继续运行
                logger.error("Heartbeat error", Map.of("error", e.getMessage()));
            }
        }
    }
    
    /**
     * 执行心跳检查
     * 
     * 构建心跳提示信息并调用回调函数进行处理。
     * 如果回调执行失败，则记录错误日志。
     */
    private void checkHeartbeat() {
        // 检查服务状态
        if (!enabled || !running) return;
        
        // 构建心跳提示信息
        String prompt = buildPrompt();
        
        // 调用回调函数处理心跳
        if (onHeartbeat != null) {
            try {
                String result = onHeartbeat.apply(prompt);
                logger.debug("Heartbeat completed", Map.of("result_length", 
                        result != null ? result.length() : 0));
            } catch (Exception e) {
                // 回调执行失败，记录到日志文件
                log("Heartbeat error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 构建心跳提示信息
     * 
     * 从工作空间的 memory/HEARTBEAT.md 文件中读取额外的上下文信息，
     * 并结合当前时间构建完整的心跳提示字符串。
     * 
     * @return 构建好的心跳提示字符串
     */
    private String buildPrompt() {
        // 读取心跳记忆文件
        Path notesFile = Paths.get(workspace, "memory", "HEARTBEAT.md");
        String notes = "";
        
        if (Files.exists(notesFile)) {
            try {
                notes = Files.readString(notesFile);
            } catch (IOException e) {
                // 忽略读取错误，使用空字符串
            }
        }
        
        // 格式化当前时间
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        
        // 构建并返回提示信息
        return String.format("""
            # Heartbeat Check
            
            Current time: %s
            
            检查 if there are any tasks I should be aware of or actions I should take.
            Review the memory file for any important updates or changes.
            Be proactive in identifying potential issues or improvements.
            
            %s
            """, now, notes);
    }
    
    /**
     * 记录日志到文件
     * 
     * 将日志消息追加写入到工作空间的 memory/heartbeat.log 文件中，
     * 每条消息都带有时间戳。
     * 
     * @param message 要记录的日志消息
     */
    private void log(String message) {
        Path logFile = Paths.get(workspace, "memory", "heartbeat.log");
        try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.write(String.format("[%s] %s%n", timestamp, message));
        } catch (IOException e) {
            // 忽略写入错误
        }
    }
    
    /**
     * 检查心跳服务是否正在运行
     * 
     * @return 如果服务正在运行返回 true，否则返回 false
     */
    public boolean isRunning() { return running; }
}