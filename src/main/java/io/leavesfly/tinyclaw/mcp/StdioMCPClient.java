package io.leavesfly.tinyclaw.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Stdio 的 MCP 客户端实现。
 *
 * Stdio 传输协议流程：
 * 1. 客户端启动 MCP Server 子进程
 * 2. 通过 stdin 写入 JSON-RPC 消息（每条消息一行，以换行符分隔）
 * 3. 从 stdout 读取 JSON-RPC 响应（每条消息一行，以换行符分隔）
 * 4. stderr 用于服务器日志输出（不参与协议通信）
 */
public class StdioMCPClient extends AbstractMCPClient {

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    private volatile Process process;
    private volatile BufferedWriter stdinWriter;
    private volatile Thread readerThread;
    private volatile Thread stderrThread;

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
        super(timeoutMs);
        this.command = command;
        this.args = args != null ? args : Collections.emptyList();
        this.env = env != null ? env : Collections.emptyMap();
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

    /**
     * 底层发送 JSON-RPC 消息（写入子进程的 stdin，线程安全）
     */
    @Override
    protected void doSend(String jsonMessage) throws IOException {
        synchronized (writeLock) {
            try {
                stdinWriter.write(jsonMessage);
                stdinWriter.newLine();
                stdinWriter.flush();
            } catch (IOException e) {
                connected = false;
                throw new IOException("Failed to write to MCP server stdin: " + e.getMessage(), e);
            }
        }

        logger.debug("Sent message via stdio", Map.of("command", command));
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
            clearPendingRequests("MCP server process stdout closed");
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
            if (data.length() > MAX_RESPONSE_SIZE) {
                logger.warn("Response too large, ignoring", Map.of(
                        "size", data.length(), "max", MAX_RESPONSE_SIZE));
                return;
            }

            MCPMessage message = objectMapper.readValue(data, MCPMessage.class);
            handleResponse(message);

        } catch (Exception e) {
            logger.error("Failed to parse JSON-RPC message from stdout", Map.of(
                    "error", e.getMessage(), "data", data.substring(0, Math.min(200, data.length()))));
        }
    }

    /**
     * Stdio 特有的关闭清理逻辑
     */
    @Override
    protected void doClose() {
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
