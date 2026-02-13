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
 * Tool for executing shell commands
 */
public class ExecTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("exec");
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    
    private final SecurityGuard securityGuard;
    private final String workingDir;
    private final long timeoutSeconds;
    // Deprecated: use SecurityGuard.checkCommand() instead
    private final Pattern[] denyPatterns;
    
    public ExecTool(String workingDir) {
        this(workingDir, null);
    }
    
    public ExecTool(String workingDir, SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
        this.workingDir = workingDir;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        
        // Dangerous command patterns (legacy, use SecurityGuard instead)
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
        return "执行 a shell command and return its output. Use with caution.";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> commandParam = new HashMap<>();
        commandParam.put("type", "string");
        commandParam.put("description", "The shell command to execute");
        properties.put("command", commandParam);
        
        Map<String, Object> workingDirParam = new HashMap<>();
        workingDirParam.put("type", "string");
        workingDirParam.put("description", "Optional working directory for the command");
        properties.put("working_dir", workingDirParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"command"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String command = (String) args.get("command");
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        
        String cwd = (String) args.get("working_dir");
        if (cwd == null || cwd.isEmpty()) {
            cwd = workingDir;
        }
        if (cwd == null || cwd.isEmpty()) {
            cwd = System.getProperty("user.dir");
        }
        
        // Security check for working directory
        if (securityGuard != null) {
            String error = securityGuard.checkWorkingDir(cwd);
            if (error != null) {
                return "Error: " + error;
            }
        }
        
        // 检查 command safety
        String guardError = guardCommand(command);
        if (guardError != null) {
            return "Error: " + guardError;
        }
        
        logger.info("Executing command", Map.of("command", command, "cwd", cwd));
        
        // Determine shell based on OS
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
        
        // Read stdout
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        // Read stderr
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
            return "Error: Command timed out after " + timeoutSeconds + " seconds";
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            result += "\nExit code: " + exitCode;
        }
        
        if (result.isEmpty()) {
            result = "(no output)";
        }
        
        // Truncate if too long
        if (result.length() > MAX_OUTPUT_LENGTH) {
            result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated, " + (result.length() - MAX_OUTPUT_LENGTH) + " more chars)";
        }
        
        return result;
    }
    
    private String guardCommand(String command) {
        // Use SecurityGuard if available
        if (securityGuard != null) {
            return securityGuard.checkCommand(command);
        }
        
        // Fallback to legacy pattern matching
        String lower = command.toLowerCase();
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(lower).find()) {
                return "Command blocked by safety guard (dangerous pattern detected)";
            }
        }
        return null;
    }
}