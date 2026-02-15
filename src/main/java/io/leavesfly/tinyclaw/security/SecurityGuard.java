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
 * 安全守卫 - 工作空间沙箱和命令黑名单
 * 
 * 提供两个主要的安全特性：
 * 1. 工作空间沙箱：限制文件操作在工作空间目录内
 * 2. 命令黑名单：阻止危险的 shell 命令
 * 
 * 使用示例：
 *   SecurityGuard guard = new SecurityGuard(workspace, true);
 *   String error = guard.checkFilePath(filePath);
 *   if (error != null) {
 *       throw new SecurityException(error);
 *   }
 */
public class SecurityGuard {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("security");
    
    private final String workspace;
    private final boolean restrictToWorkspace;
    private final List<Pattern> commandBlacklist;
    
    /**
     * 构造函数 - 使用默认命令黑名单
     * 
     * @param workspace 工作空间目录路径
     * @param restrictToWorkspace 是否限制文件访问在工作空间内
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
     * 构造函数 - 使用自定义命令黑名单
     * 
     * @param workspace 工作空间目录路径
     * @param restrictToWorkspace 是否限制文件访问在工作空间内
     * @param customBlacklist 自定义命令黑名单模式
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
     * 检查文件路径是否允许访问
     * 
     * @param filePath 待检查的文件路径
     * @return 如果被阻止则返回错误消息，允许则返回 null
     */
    public String checkFilePath(String filePath) {
        if (!restrictToWorkspace) {
            return null; // 无限制
        }
        
        if (filePath == null || filePath.isEmpty()) {
            return "File path is required";
        }
        
        try {
            // 解析为绝对路径
            Path absPath = Paths.get(filePath).toAbsolutePath().normalize();
            Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();
            
            // 检查路径是否在工作空间内
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
            
            return null; // 允许访问
            
        } catch (Exception e) {
            logger.error("Error checking file path", Map.of("path", filePath, "error", e.getMessage()));
            return "Invalid file path: " + e.getMessage();
        }
    }
    
    /**
     * 检查命令是否允许执行
     * 
     * @param command 待检查的 shell 命令
     * @return 如果被阻止则返回错误消息，允许则返回 null
     */
    public String checkCommand(String command) {
        if (command == null || command.isEmpty()) {
            return "Command is required";
        }
        
        // 检查命令是否匹配黑名单
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
        
        return null; // 允许执行
    }
    
    /**
     * 检查工作目录是否允许执行命令
     * 
     * @param workingDir 工作目录路径
     * @return 如果被阻止则返回错误消息，允许则返回 null
     */
    public String checkWorkingDir(String workingDir) {
        if (!restrictToWorkspace) {
            return null; // 无限制
        }
        
        if (workingDir == null || workingDir.isEmpty()) {
            return null; // 将使用默认工作空间
        }
        
        return checkFilePath(workingDir);
    }
    
    /**
     * 获取工作空间路径
     */
    public String getWorkspace() {
        return workspace;
    }
    
    /**
     * 检查是否启用了工作空间限制
     */
    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }
    
    /**
     * 规范化工作空间路径
     */
    private String normalizeWorkspacePath(String path) {
        if (path == null || path.isEmpty()) {
            return System.getProperty("user.home") + "/.tinyclaw/workspace";
        }
        
        // 展开 ~ 为用户主目录
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
     * 构建默认命令黑名单
     */
    private List<Pattern> buildDefaultCommandBlacklist() {
        List<String> defaultPatterns = List.of(
            // 文件删除
            "\\brm\\s+-[rf]{1,2}\\b",
            "\\bdel\\s+/[fq]\\b",
            "\\brmdir\\s+/s\\b",
            
            // 磁盘操作
            "\\b(format|mkfs|diskpart)\\b\\s",
            "\\bdd\\s+if=",
            ">\\s*/dev/sd[a-z]\\b",
            
            // 系统操作
            "\\b(shutdown|reboot|poweroff|halt)\\b",
            
            // Fork 炸弹
            ":\\(\\)\\s*\\{.*\\};\\s*:",
            
            // 网络攻击
            "\\b(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh|python|perl|ruby)",
            
            // Sudo/权限提升
            "\\b(sudo|su)\\s+",
            
            // 进程杀死
            "\\bkillall\\s+-9\\b",
            "\\bpkill\\s+-9\\b",
            
            // Cron/定时任务操作
            "\\bcrontab\\s+-r\\b",
            
            // 环境变量操作（潜在安全风险）
            "\\bexport\\s+LD_PRELOAD\\b",
            
            // 内核模块操作
            "\\b(insmod|rmmod|modprobe)\\b"
        );
        
        return buildCommandBlacklist(defaultPatterns);
    }
    
    /**
     * 从模式构建命令黑名单
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
     * 获取命令黑名单模式（用于调试）
     */
    public List<String> getBlacklistPatterns() {
        return commandBlacklist.stream()
            .map(Pattern::pattern)
            .toList();
    }
}
