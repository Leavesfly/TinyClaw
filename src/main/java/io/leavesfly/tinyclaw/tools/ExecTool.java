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
    // 已废弃：使用 SecurityGuard.checkCommand() 代替
    private final Pattern[] denyPatterns;
    
    public ExecTool(String workingDir) {
        this(workingDir, null);
    }
    
    public ExecTool(String workingDir, SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
        this.workingDir = workingDir;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        
        // 危险命令模式（旧版，使用 SecurityGuard 代替）
        this.denyPatterns = new Pattern[]{
                Pattern.compile("\\brm\\s+-[rf]{1,2}\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bdel\\s+/[fq]\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\brmdir\\s+/s\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\b(format|mkfs|diskpart)\\b\\s", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bdd\\s+if=", Pattern.CASE_INSENSITIVE),
                Pattern.compile(">\\s*/dev/sd[a-z]\\b"),
                Pattern.compile("\\b(shutdown|reboot|poweroff)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile(":\\(\\)\\s*\\{.*\\};\\s*:")
        };
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
        
        // 读取标准输出
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        // 读取标准错误
        StringBuilder error = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line).append("\n");
            }
        }
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        
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
        
        // 退到旧版模式匹配
        String lower = command.toLowerCase();
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(lower).find()) {
                return "命令被安全防护阻止（检测到危险模式）";
            }
        }
        return null;
    }
}