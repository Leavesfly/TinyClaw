package io.leavesfly.tinyclaw.security;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
    private final List<Path> protectedPaths;
    
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
        this.protectedPaths = buildDefaultProtectedPaths();
        
        logger.info("SecurityGuard initialized", Map.of(
            "workspace", this.workspace,
            "restrictToWorkspace", restrictToWorkspace,
            "blacklistRules", commandBlacklist.size(),
            "protectedPaths", protectedPaths.size()
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
        this.protectedPaths = buildDefaultProtectedPaths();
        
        logger.info("SecurityGuard initialized with custom blacklist", Map.of(
            "workspace", this.workspace,
            "restrictToWorkspace", restrictToWorkspace,
            "blacklistRules", commandBlacklist.size(),
            "protectedPaths", protectedPaths.size()
        ));
    }
    
    /**
     * 检查文件路径是否允许访问
     * 
     * 使用 toRealPath() 解析符号链接，防止通过符号链接绕过工作空间沙箱。
     * 对于尚不存在的路径，回退到对父目录进行 toRealPath() 检查。
     * 
     * @param filePath 待检查的文件路径
     * @return 如果被阻止则返回错误消息，允许则返回 null
     */
    public String checkFilePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "File path is required";
        }
        
        try {
            Path resolvedPath = resolveRealPath(Paths.get(filePath));
            
            // 始终检查受保护文件（无论是否启用 workspace 限制）
            String protectedError = checkProtectedPath(resolvedPath, filePath);
            if (protectedError != null) {
                return protectedError;
            }
            
            if (!restrictToWorkspace) {
                return null; // 无 workspace 限制，且不是受保护文件，放行
            }
            
            Path workspacePath = resolveRealPath(Paths.get(workspace));
            
            // 检查路径是否在工作空间内
            if (!resolvedPath.startsWith(workspacePath)) {
                logger.warn("File path blocked (outside workspace)", Map.of(
                    "path", filePath,
                    "resolved", resolvedPath.toString(),
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
     * 解析路径的真实绝对路径，解析所有符号链接。
     * 
     * 如果路径存在，使用 toRealPath() 解析符号链接。
     * 如果路径不存在（如即将创建的文件），递归向上查找存在的父目录并解析，
     * 然后拼接剩余的路径部分，防止通过在已有目录上创建符号链接来绕过沙箱。
     * 
     * @param path 待解析的路径
     * @return 解析后的真实绝对路径
     * @throws IOException 如果路径解析失败
     */
    private Path resolveRealPath(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath();
        
        // 路径存在时直接解析符号链接
        if (absolutePath.toFile().exists()) {
            return absolutePath.toRealPath();
        }
        
        // 路径不存在时，向上查找存在的祖先目录并解析
        Path current = absolutePath;
        List<String> pendingParts = new ArrayList<>();
        
        while (current != null && !current.toFile().exists()) {
            pendingParts.add(0, current.getFileName().toString());
            current = current.getParent();
        }
        
        if (current == null) {
            // 没有任何祖先目录存在，回退到 normalize
            return absolutePath.normalize();
        }
        
        // 解析存在的祖先目录的真实路径，然后拼接剩余部分
        Path realAncestor = current.toRealPath();
        Path result = realAncestor;
        for (String part : pendingParts) {
            result = result.resolve(part);
        }
        return result.normalize();
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
                // 对于 workspace 内豁免类规则，若命令在 workspace 内执行则放行
                if (isWorkspaceExemptPattern(pattern) && isCommandWithinWorkspace(command)) {
                    logger.info("Command allowed within workspace (exempt pattern)", Map.of(
                        "command", command,
                        "pattern", pattern.pattern()
                    ));
                    continue;
                }
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
     * 判断该黑名单规则是否属于"workspace 内可豁免"类型。
     * 
     * 删除类命令（rm -rf、rmdir /s、del /f）在 workspace 内是合法的构建/清理操作，
     * 允许豁免。磁盘操作、sudo、fork 炸弹等无论在哪都危险，不可豁免。
     */
    private boolean isWorkspaceExemptPattern(Pattern pattern) {
        String patternStr = pattern.pattern();
        return patternStr.contains("rm\\s+-[rf]") 
            || patternStr.contains("del\\s+/[fq]")
            || patternStr.contains("rmdir\\s+/s");
    }
    
    /**
     * 判断命令是否在 workspace 目录内执行。
     * 
     * 检测两种情况：
     * 1. 命令以 "cd <workspace路径>" 开头，说明显式切换到 workspace 下的子目录
     * 2. 命令中出现的所有绝对路径都在 workspace 内
     * 
     * 只要满足其一，即认为命令在 workspace 内执行。
     */
    private boolean isCommandWithinWorkspace(String command) {
        if (workspace == null || workspace.isEmpty()) {
            return false;
        }
        
        try {
            Path resolvedWorkspace = resolveRealPath(Paths.get(workspace));
            
            // 检测 "cd <path>" 模式，判断切换的目录是否在 workspace 内
            Pattern cdPattern = Pattern.compile("(?:^|&&|;|\\|\\|)\\s*cd\\s+([^\\s&;|]+)");
            Matcher cdMatcher = cdPattern.matcher(command);
            while (cdMatcher.find()) {
                String cdPath = cdMatcher.group(1);
                try {
                    Path resolvedCdPath = resolveRealPath(Paths.get(cdPath));
                    if (resolvedCdPath.startsWith(resolvedWorkspace)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // 路径解析失败，跳过此条
                }
            }
            
            // 检测命令中出现的绝对路径，判断是否都在 workspace 内
            Pattern absolutePathPattern = Pattern.compile("(?<![\\w])((?:/[\\w.\\-]+)+)");
            Matcher pathMatcher = absolutePathPattern.matcher(command);
            boolean foundAbsolutePath = false;
            while (pathMatcher.find()) {
                String absolutePath = pathMatcher.group(1);
                foundAbsolutePath = true;
                try {
                    Path resolvedPath = resolveRealPath(Paths.get(absolutePath));
                    if (!resolvedPath.startsWith(resolvedWorkspace)) {
                        return false; // 存在不在 workspace 内的绝对路径，不豁免
                    }
                } catch (Exception ignored) {
                    // 路径解析失败，保守处理：不豁免
                    return false;
                }
            }
            
            // 命令中存在绝对路径且全部在 workspace 内
            return foundAbsolutePath;
            
        } catch (Exception e) {
            logger.warn("Failed to check if command is within workspace", Map.of(
                "command", command,
                "error", e.getMessage()
            ));
            return false;
        }
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
     * 检查路径是否命中受保护文件列表
     * 
     * @param resolvedPath 已解析符号链接的绝对路径
     * @param originalPath 原始路径（用于日志）
     * @return 如果命中受保护文件则返回错误消息，否则返回 null
     */
    private String checkProtectedPath(Path resolvedPath, String originalPath) {
        for (Path protectedPath : protectedPaths) {
            if (resolvedPath.equals(protectedPath)) {
                logger.warn("File path blocked (protected sensitive file)", Map.of(
                    "path", originalPath,
                    "resolved", resolvedPath.toString(),
                    "protectedPath", protectedPath.toString()
                ));
                return String.format(
                    "Access denied: '%s' is a protected sensitive file and cannot be read or modified",
                    originalPath
                );
            }
        }
        return null;
    }
    
    /**
     * 构建默认受保护文件路径列表
     * 
     * 这些文件包含敏感信息（如 API Key），即使在 workspace 内也禁止模型访问。
     * 使用 resolveRealPath 解析符号链接，防止通过符号链接访问受保护文件。
     */
    private List<Path> buildDefaultProtectedPaths() {
        String home = System.getProperty("user.home");
        List<Path> paths = new ArrayList<>();
        try {
            paths.add(resolveRealPath(Paths.get(home, ".tinyclaw", "config.json")));
            paths.add(resolveRealPath(Paths.get(home, ".tinyclaw", ".env")));
        } catch (IOException e) {
            // 回退到 normalize，确保至少有基本保护
            logger.warn("Failed to resolve real paths for protected files, falling back to normalize", 
                Map.of("error", e.getMessage()));
            paths.add(Paths.get(home, ".tinyclaw", "config.json").toAbsolutePath().normalize());
            paths.add(Paths.get(home, ".tinyclaw", ".env").toAbsolutePath().normalize());
        }
        return paths;
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
            "\\b(insmod|rmmod|modprobe)\\b",
            
            // 受保护的敏感配置文件（防止通过 shell 命令访问）
            "\\.tinyclaw[/\\\\]config\\.json",
            "\\.tinyclaw[/\\\\]\\.env"
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
