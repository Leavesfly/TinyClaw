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
 * Shell 命令执行工具
 * 允许 Agent 执行系统命令，请谨慎使用
 */
public class ExecTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("exec");
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    
    private final SecurityGuard securityGuard;
    private final String workingDir;
    private final long timeoutSeconds;
    
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
        
        String cwd = (String) args.get("working_dir");
        if (cwd == null || cwd.isEmpty()) {
            cwd = workingDir;
        }
        if (cwd == null || cwd.isEmpty()) {
            cwd = System.getProperty("user.dir");
        }
        
        // 对工作目录进行安全检查
        if (securityGuard != null) {
            String error = securityGuard.checkWorkingDir(cwd);
            if (error != null) {
                return "错误: " + error;
            }
        }
        
        // 检查命令安全性
        String guardError = guardCommand(command);
        if (guardError != null) {
            return "错误: " + guardError;
        }
        
        logger.info("Executing command", Map.of("command", command, "cwd", cwd));
        
        // 根据操作系统决定 Shell
        String[] shellCmd;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            shellCmd = new String[]{"cmd", "/c", command};
        } else {
            shellCmd = new String[]{"sh", "-c", command};
        }
        
        ProcessBuilder pb = new ProcessBuilder(shellCmd);
        pb.directory(Paths.get(cwd).toFile());
        pb.redirectErrorStream(false);
        
        Process process = pb.start();
        
        // 使用独立线程读取输出，避免缓冲区填满导致的死锁
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                // 忽略读取异常
            }
        }, "exec-stdout");
        
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (error) {
                        error.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                // 忽略读取异常
            }
        }, "exec-stderr");
        
        stdoutThread.start();
        stderrThread.start();
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        
        // 等待读取线程完成
        try {
            stdoutThread.join(1000);
            stderrThread.join(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        
        String result = output.toString();
        if (error.length() > 0) {
            result += "\nSTDERR:\n" + error.toString();
        }
        
        if (!finished) {
            process.destroyForcibly();
            return "错误: 命令超时，超过 " + timeoutSeconds + " 秒";
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            result += "\n退出代码: " + exitCode;
        }
        
        if (result.isEmpty()) {
            result = "(无输出)";
        }
        
        // 如果输出过长则截断
        if (result.length() > MAX_OUTPUT_LENGTH) {
            result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n... (已截断，还有 " + (result.length() - MAX_OUTPUT_LENGTH) + " 个字符)";
        }
        
        return result;
    }
    
    private String guardCommand(String command) {
        // 使用 SecurityGuard（如果可用）
        if (securityGuard != null) {
            return securityGuard.checkCommand(command);
        }
        
        // 如果没有 SecurityGuard，则不限制（不推荐）
        logger.warn("命令执行未启用 SecurityGuard，存在安全风险");
        return null;
    }
}