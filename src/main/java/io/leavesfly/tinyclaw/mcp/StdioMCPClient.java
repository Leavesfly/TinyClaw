package io.leavesfly.tinyclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 基于 Stdio 的 MCP 客户端实现。
 *
 * Stdio 传输协议流程：
 * 1. 客户端启动 MCP Server 子进程
 * 2. 通过 stdin 写入 JSON-RPC 消息（每条消息一行，以换行符分隔）
 * 3. 从 stdout 读取 JSON-RPC 响应（每条消息一行，以换行符分隔）
 * 4. stderr 用于服务器日志输出（不参与协议通信）
 */
public class StdioMCPClient implements MCPClient {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    private static final int MAX_RESPONSE_SIZE = 10 * 1024 * 1024; // 10MB

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final int timeoutMs;
    private final ObjectMapper objectMapper;

    private volatile boolean connected = false;
    private volatile Process process;
    private volatile BufferedWriter stdinWriter;
    private volatile Thread readerThread;
    private volatile Thread stderrThread;

    private final Map<String, CompletableFuture<MCPMessage>> pendingRequests = new ConcurrentHashMap<>();
    /** 保护 stdin 写入的锁，防止并发写入导致消息交错 */
    private final Object writeLock = new Object();

    /**
     * 创建 Stdio MCP 客户端
     *
     * @param command 可执行命令（如 "npx", "python3", "node"）
     * @param args    命令参数列表
     * @param env     额外环境变量（可为 null）
     * @param timeoutMs 请求超时时间（毫秒）
     */
    public StdioMCPClient(String command, List<String> args, Map<String, String> env, int timeoutMs) {
        this.command = command;
        this.args = args != null ? args : Collections.emptyList();
        this.env = env != null ? env : Collections.emptyMap();
        this.timeoutMs = timeoutMs;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            logger.warn("Already connected to MCP server", Map.of("command", command));
            return;
        }

        if (command == null || command.isEmpty()) {
            throw new IOException("Command cannot be empty for stdio MCP client");
        }

        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        fullCommand.addAll(args);

        ProcessBuilder processBuilder = new ProcessBuilder(fullCommand);
        processBuilder.redirectErrorStream(false); // stderr 单独处理

        // 设置额外环境变量
        Map<String, String> processEnv = processBuilder.environment();
        processEnv.putAll(env);

        logger.info("Starting MCP server process", Map.of(
                "command", String.join(" ", fullCommand)
        ));

        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new IOException("Failed to start MCP server process: " + e.getMessage(), e);
        }

        stdinWriter = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // 启动 stdout 读取线程
        String threadName = "mcp-stdio-reader-" + command;
        readerThread = new Thread(() -> readStdout(process.getInputStream()), threadName);
        readerThread.setDaemon(true);
        readerThread.start();

        // 启动 stderr 日志线程
        stderrThread = new Thread(() -> readStderr(process.getErrorStream()), "mcp-stdio-stderr-" + command);
        stderrThread.setDaemon(true);
        stderrThread.start();

        connected = true;

        logger.info("MCP server process started", Map.of(
                "command", command,
                "pid", process.pid()
        ));
    }

    @Override
    public MCPMessage sendRequest(String method, Map<String, Object> params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }

        String requestId = UUID.randomUUID().toString();
        MCPMessage request = MCPMessage.createRequest(requestId, method, params);

        CompletableFuture<MCPMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            writeMessage(request);

            MCPMessage response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            if (response.isError()) {
                throw new MCPException(
                        response.getError().getCode(),
                        response.getError().getMessage()
                );
            }

            return response;

        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new MCPException(-1, "Request timeout after " + timeoutMs + "ms for method: " + method);
        } catch (MCPException e) {
            throw e;
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw e;
        }
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server");
        }
        MCPMessage notification = MCPMessage.createNotification(method, params);
        writeMessage(notification);
    }

    /**
     * 将 JSON-RPC 消息写入子进程的 stdin（线程安全）
     */
    private void writeMessage(MCPMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message);

        synchronized (writeLock) {
            try {
                stdinWriter.write(json);
                stdinWriter.newLine();
                stdinWriter.flush();
            } catch (IOException e) {
                connected = false;
                throw new IOException("Failed to write to MCP server stdin: " + e.getMessage(), e);
            }
        }

        logger.debug("Sent message via stdio", Map.of(
                "method", message.getMethod() != null ? message.getMethod() : "response",
                "id", message.getId() != null ? message.getId() : "notification"
        ));
    }

    /**
     * 后台线程：持续从子进程 stdout 读取 JSON-RPC 消息
     */
    private void readStdout(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while (connected && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.length() > MAX_RESPONSE_SIZE) {
                    logger.warn("Response too large, ignoring", Map.of(
                            "size", line.length(), "max", MAX_RESPONSE_SIZE));
                    continue;
                }

                handleMessage(line);
            }

        } catch (IOException e) {
            if (connected) {
                logger.error("Stdio stdout read error", Map.of("error", e.getMessage()));
            }
        } finally {
            connected = false;
            for (CompletableFuture<MCPMessage> future : pendingRequests.values()) {
                future.completeExceptionally(new IOException("MCP server process stdout closed"));
            }
            pendingRequests.clear();
        }
    }

    /**
     * 后台线程：读取子进程 stderr 并记录日志
     */
    private void readStderr(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("MCP server stderr", Map.of("command", command, "line", line));
            }

        } catch (IOException e) {
            if (connected) {
                logger.debug("Stdio stderr read ended", Map.of("error", e.getMessage()));
            }
        }
    }

    /**
     * 解析并分发 JSON-RPC 消息
     */
    private void handleMessage(String data) {
        try {
            MCPMessage message = objectMapper.readValue(data, MCPMessage.class);

            if (message.isResponse()) {
                String id = message.getId();
                CompletableFuture<MCPMessage> future = pendingRequests.remove(id);
                if (future != null) {
                    future.complete(message);
                } else {
                    logger.warn("Received response for unknown request", Map.of("id", id));
                }
            } else if (message.isNotification()) {
                logger.debug("Received notification", Map.of("method", message.getMethod()));
            }

        } catch (Exception e) {
            logger.error("Failed to parse JSON-RPC message from stdout", Map.of(
                    "error", e.getMessage(), "data", data.substring(0, Math.min(200, data.length()))));
        }
    }

    @Override
    public void close() {
        if (!connected && process == null) {
            return;
        }

        connected = false;

        for (CompletableFuture<MCPMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("Connection closed"));
        }
        pendingRequests.clear();

        // 关闭 stdin 以通知子进程退出
        if (stdinWriter != null) {
            try {
                stdinWriter.close();
            } catch (IOException ignored) {
            }
        }

        // 等待进程退出
        if (process != null) {
            try {
                boolean exited = process.waitFor(3, TimeUnit.SECONDS);
                if (!exited) {
                    logger.warn("MCP server process did not exit gracefully, destroying", Map.of("command", command));
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }

            logger.info("MCP server process stopped", Map.of(
                    "command", command,
                    "exitCode", process.isAlive() ? "still running" : String.valueOf(process.exitValue())
            ));
        }

        // 等待读取线程结束
        joinThread(readerThread);
        joinThread(stderrThread);
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    private void joinThread(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
