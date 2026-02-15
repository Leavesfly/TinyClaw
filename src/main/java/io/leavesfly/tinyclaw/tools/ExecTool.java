package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.security.SecurityGuard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具。
 * 
 * 允许 AI Agent 执行系统命令，支持跨平台（Windows/Linux/macOS）。
 * 
 * 核心功能：
 * - 执行 Shell 命令并捕获输出
 * - 支持自定义工作目录
 * - 超时控制和进程管理
 * - 集成安全检查机制
 * 
 * 安全警告：
 * 此工具允许执行任意系统命令，请务必配合 SecurityGuard 使用，
 * 避免执行危险命令（如 rm -rf、格式化等）。
 */
public class ExecTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("exec");
    
    private static final int MAX_OUTPUT_LENGTH = 10000;         // 输出最大长度
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;     // 默认超时时间（秒）
    private static final long THREAD_JOIN_TIMEOUT_MS = 1000;    // 线程等待超时（毫秒）
    
    private final SecurityGuard securityGuard;   // 安全守卫（可选）
    private final String workingDir;             // 默认工作目录
    private final long timeoutSeconds;           // 命令超时时间
    
    public ExecTool(String workingDir) {
        this(workingDir, null);
    }
    
    public ExecTool(String workingDir, SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
        this.workingDir = workingDir;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }
    
    @Override
    public String name() {
        return "exec";
    }
    
    @Override
    public String description() {
        return "执行 Shell 命令并返回输出。请谨慎使用。";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> commandParam = new HashMap<>();
        commandParam.put("type", "string");
        commandParam.put("description", "要执行的 Shell 命令");
        properties.put("command", commandParam);
        
        Map<String, Object> workingDirParam = new HashMap<>();
        workingDirParam.put("type", "string");
        workingDirParam.put("description", "命令的可选工作目录");
        properties.put("working_dir", workingDirParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"command"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String command = (String) args.get("command");
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("命令参数是必需的");
        }
        
        // 解析工作目录
        String cwd = resolveWorkingDir((String) args.get("working_dir"));
        
        // 安全检查
        String securityError = performSecurityChecks(command, cwd);
        if (securityError != null) {
            return "错误: " + securityError;
        }
        
        logger.info("Executing command", Map.of("command", command, "cwd", cwd));
        
        // 执行命令并获取结果
        return executeCommand(command, cwd);
    }
    
    /**
     * 解析工作目录。
     * 
     * 优先级：参数指定 > 构造函数指定 > 系统当前目录
     * 
     * @param workingDirArg 参数中的工作目录
     * @return 最终使用的工作目录路径
     */
    private String resolveWorkingDir(String workingDirArg) {
        if (workingDirArg != null && !workingDirArg.isEmpty()) {
            return workingDirArg;
        }
        if (workingDir != null && !workingDir.isEmpty()) {
            return workingDir;
        }
        return System.getProperty("user.dir");
    }
    
    /**
     * 执行安全检查。
     * 
     * @param command 要执行的命令
     * @param cwd 工作目录
     * @return 错误信息，无错误则返回 null
     */
    private String performSecurityChecks(String command, String cwd) {
        // 检查工作目录
        if (securityGuard != null) {
            String error = securityGuard.checkWorkingDir(cwd);
            if (error != null) {
                return error;
            }
        }
        
        // 检查命令安全性
        return guardCommand(command);
    }
    
    /**
     * 执行命令并捕获输出。
     * 
     * @param command 要执行的命令
     * @param cwd 工作目录
     * @return 命令输出结果
     * @throws Exception 执行失败时抛出异常
     */
    private String executeCommand(String command, String cwd) throws Exception {
        // 构建进程
        Process process = buildProcess(command, cwd);
        
        // 捕获输出
        CommandOutput output = captureOutput(process);
        
        // 等待进程完成
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        
        // 等待输出读取线程完成
        output.waitForThreads();
        
        // 处理超时
        if (!finished) {
            process.destroyForcibly();
            return "错误: 命令超时，超过 " + timeoutSeconds + " 秒";
        }
        
        // 构建结果
        return buildResult(output, process.exitValue());
    }
    
    /**
     * 构建命令执行进程。
     * 
     * @param command 要执行的命令
     * @param cwd 工作目录
     * @return 启动的进程对象
     * @throws Exception 启动失败时抛出异常
     */
    private Process buildProcess(String command, String cwd) throws Exception {
        String[] shellCmd = getShellCommand(command);
        
        ProcessBuilder pb = new ProcessBuilder(shellCmd);
        pb.directory(Paths.get(cwd).toFile());
        pb.redirectErrorStream(false);
        
        return pb.start();
    }
    
    /**
     * 获取适合当前操作系统的 Shell 命令。
     * 
     * @param command 原始命令
     * @return Shell 命令数组
     */
    private String[] getShellCommand(String command) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new String[]{"cmd", "/c", command};
        } else {
            return new String[]{"sh", "-c", command};
        }
    }
    
    /**
     * 捕获命令输出。
     * 
     * 使用独立线程读取 stdout 和 stderr，避免缓冲区填满导致的死锁。
     * 
     * @param process 进程对象
     * @return 命令输出对象
     */
    private CommandOutput captureOutput(Process process) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        
        Thread stdoutThread = createOutputThread(process.getInputStream(), stdout, "exec-stdout");
        Thread stderrThread = createOutputThread(process.getErrorStream(), stderr, "exec-stderr");
        
        stdoutThread.start();
        stderrThread.start();
        
        return new CommandOutput(stdout, stderr, stdoutThread, stderrThread);
    }
    
    /**
     * 创建输出读取线程。
     * 
     * @param inputStream 输入流
     * @param output 输出缓冲区
     * @param threadName 线程名称
     * @return 输出读取线程
     */
    private Thread createOutputThread(java.io.InputStream inputStream, 
                                     StringBuilder output, String threadName) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                // 忽略读取异常
            }
        }, threadName);
    }
    
    /**
     * 构建执行结果。
     * 
     * @param output 命令输出
     * @param exitCode 退出代码
     * @return 格式化的结果字符串
     */
    private String buildResult(CommandOutput output, int exitCode) {
        String result = output.getStdout();
        
        // 添加 stderr
        if (!output.getStderr().isEmpty()) {
            result += "\nSTDERR:\n" + output.getStderr();
        }
        
        // 添加退出代码
        if (exitCode != 0) {
            result += "\n退出代码: " + exitCode;
        }
        
        // 处理空输出
        if (result.isEmpty()) {
            result = "(无输出)";
        }
        
        // 截断过长输出
        return truncateIfNeeded(result);
    }
    
    /**
     * 截断过长的输出。
     * 
     * @param result 原始结果
     * @return 可能被截断的结果
     */
    private String truncateIfNeeded(String result) {
        if (result.length() > MAX_OUTPUT_LENGTH) {
            int remaining = result.length() - MAX_OUTPUT_LENGTH;
            return result.substring(0, MAX_OUTPUT_LENGTH) 
                    + "\n... (已截断，还有 " + remaining + " 个字符)";
        }
        return result;
    }
    
    /**
     * 检查命令安全性。
     * 
     * @param command 要执行的命令
     * @return 错误信息，无错误则返回 null
     */
    private String guardCommand(String command) {
        if (securityGuard != null) {
            return securityGuard.checkCommand(command);
        }
        
        // 未启用 SecurityGuard 时发出警告
        logger.warn("命令执行未启用 SecurityGuard，存在安全风险");
        return null;
    }
    
    /**
     * 命令输出封装类。
     */
    private static class CommandOutput {
        private final StringBuilder stdout;
        private final StringBuilder stderr;
        private final Thread stdoutThread;
        private final Thread stderrThread;
        
        CommandOutput(StringBuilder stdout, StringBuilder stderr, 
                     Thread stdoutThread, Thread stderrThread) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdoutThread = stdoutThread;
            this.stderrThread = stderrThread;
        }
        
        String getStdout() {
            synchronized (stdout) {
                return stdout.toString();
            }
        }
        
        String getStderr() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }
        
        void waitForThreads() {
            try {
                stdoutThread.join(THREAD_JOIN_TIMEOUT_MS);
                stderrThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}