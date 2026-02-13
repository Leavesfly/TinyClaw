package io.leavesfly.tinyclaw.security;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Security guard for workspace sandbox and command blacklist
 * 
 * Provides two main security features:
 * 1. Workspace sandbox: Restrict file operations to workspace directory
 * 2. Command blacklist: Block dangerous shell commands
 * 
 * Usage example:
 * <pre>
 *   SecurityGuard guard = new SecurityGuard(workspace, true);
 *   String error = guard.checkFilePath(filePath);
 *   if (error != null) {
 *       throw new SecurityException(error);
 *   }
 * </pre>
 */
public class SecurityGuard {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("security");
    
    private final String workspace;
    private final boolean restrictToWorkspace;
    private final List<Pattern> commandBlacklist;
    
    /**
     * Constructor with default command blacklist
     * 
     * @param workspace workspace directory path
     * @param restrictToWorkspace whether to restrict file access to workspace
     */
    public SecurityGuard(String workspace, boolean restrictToWorkspace) {
        this.workspace = normalizeWorkspacePath(workspace);
        this.restrictToWorkspace = restrictToWorkspace;
        this.commandBlacklist = buildDefaultCommandBlacklist();
        
        logger.info("SecurityGuard initialized", Map.of(
            "workspace", this.workspace,
            "restrictToWorkspace", restrictToWorkspace,
            "blacklistRules", commandBlacklist.size()
        ));
    }
    
    /**
     * Constructor with custom command blacklist
     * 
     * @param workspace workspace directory path
     * @param restrictToWorkspace whether to restrict file access to workspace
     * @param customBlacklist custom command blacklist patterns
     */
    public SecurityGuard(String workspace, boolean restrictToWorkspace, List<String> customBlacklist) {
        this.workspace = normalizeWorkspacePath(workspace);
        this.restrictToWorkspace = restrictToWorkspace;
        this.commandBlacklist = buildCommandBlacklist(customBlacklist);
        
        logger.info("SecurityGuard initialized with custom blacklist", Map.of(
            "workspace", this.workspace,
            "restrictToWorkspace", restrictToWorkspace,
            "blacklistRules", commandBlacklist.size()
        ));
    }
    
    /**
     * Check if a file path is allowed
     * 
     * @param filePath file path to check
     * @return error message if blocked, null if allowed
     */
    public String checkFilePath(String filePath) {
        if (!restrictToWorkspace) {
            return null; // No restriction
        }
        
        if (filePath == null || filePath.isEmpty()) {
            return "File path is required";
        }
        
        try {
            // Resolve to absolute path
            Path absPath = Paths.get(filePath).toAbsolutePath().normalize();
            Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();
            
            // Check if path is within workspace
            if (!absPath.startsWith(workspacePath)) {
                logger.warn("File path blocked (outside workspace)", Map.of(
                    "path", filePath,
                    "resolved", absPath.toString(),
                    "workspace", workspace
                ));
                return String.format(
                    "Access denied: Path '%s' is outside workspace '%s'",
                    filePath, workspace
                );
            }
            
            return null; // Allowed
            
        } catch (Exception e) {
            logger.error("Error checking file path", Map.of("path", filePath, "error", e.getMessage()));
            return "Invalid file path: " + e.getMessage();
        }
    }
    
    /**
     * Check if a command is allowed
     * 
     * @param command shell command to check
     * @return error message if blocked, null if allowed
     */
    public String checkCommand(String command) {
        if (command == null || command.isEmpty()) {
            return "Command is required";
        }
        
        // Check against blacklist
        for (Pattern pattern : commandBlacklist) {
            if (pattern.matcher(command).find()) {
                logger.warn("Command blocked by blacklist", Map.of(
                    "command", command,
                    "pattern", pattern.pattern()
                ));
                return String.format(
                    "Command blocked by safety guard (dangerous pattern detected): %s",
                    pattern.pattern()
                );
            }
        }
        
        return null; // Allowed
    }
    
    /**
     * Check if a working directory is allowed for command execution
     * 
     * @param workingDir working directory path
     * @return error message if blocked, null if allowed
     */
    public String checkWorkingDir(String workingDir) {
        if (!restrictToWorkspace) {
            return null; // No restriction
        }
        
        if (workingDir == null || workingDir.isEmpty()) {
            return null; // Will use default workspace
        }
        
        return checkFilePath(workingDir);
    }
    
    /**
     * Get the workspace path
     */
    public String getWorkspace() {
        return workspace;
    }
    
    /**
     * Check if workspace restriction is enabled
     */
    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }
    
    /**
     * Normalize workspace path
     */
    private String normalizeWorkspacePath(String path) {
        if (path == null || path.isEmpty()) {
            return System.getProperty("user.home") + "/.tinyclaw/workspace";
        }
        
        // Expand ~ to user home
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            logger.error("Failed to normalize workspace path", Map.of("path", path, "error", e.getMessage()));
            return path;
        }
    }
    
    /**
     * Build default command blacklist
     */
    private List<Pattern> buildDefaultCommandBlacklist() {
        List<String> defaultPatterns = List.of(
            // File deletion
            "\\brm\\s+-[rf]{1,2}\\b",
            "\\bdel\\s+/[fq]\\b",
            "\\brmdir\\s+/s\\b",
            
            // Disk operations
            "\\b(format|mkfs|diskpart)\\b\\s",
            "\\bdd\\s+if=",
            ">\\s*/dev/sd[a-z]\\b",
            
            // System operations
            "\\b(shutdown|reboot|poweroff|halt)\\b",
            
            // Fork bomb
            ":\\(\\)\\s*\\{.*\\};\\s*:",
            
            // Network attacks
            "\\b(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh|python|perl|ruby)",
            
            // Sudo/privilege escalation
            "\\b(sudo|su)\\s+",
            
            // Process killing
            "\\bkillall\\s+-9\\b",
            "\\bpkill\\s+-9\\b",
            
            // Cron/scheduled tasks manipulation
            "\\bcrontab\\s+-r\\b",
            
            // Environment variables manipulation (potential security risks)
            "\\bexport\\s+LD_PRELOAD\\b",
            
            // Kernel module operations
            "\\b(insmod|rmmod|modprobe)\\b"
        );
        
        return buildCommandBlacklist(defaultPatterns);
    }
    
    /**
     * Build command blacklist from patterns
     */
    private List<Pattern> buildCommandBlacklist(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                logger.error("Failed to compile blacklist pattern", Map.of("pattern", pattern, "error", e.getMessage()));
            }
        }
        return compiled;
    }
    
    /**
     * Get command blacklist patterns (for debugging)
     */
    public List<String> getBlacklistPatterns() {
        return commandBlacklist.stream()
            .map(Pattern::pattern)
            .toList();
    }
}
