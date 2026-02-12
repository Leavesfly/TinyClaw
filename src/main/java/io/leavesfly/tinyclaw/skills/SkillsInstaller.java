package io.leavesfly.tinyclaw.skills;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能安装器 - 从 GitHub 仓库安装技能
 * 
 * 提供从远程 GitHub 仓库克隆技能到本地工作空间的功能。
 * 支持多种仓库格式，并能自动检测和处理安装过程中的错误。
 * 
 * 核心功能：
 * - GitHub 仓库克隆：支持多种格式（owner/repo、完整 URL 等）
 * - 进度追踪：记录正在安装的技能，防止重复安装
 * - 错误处理：提供详细的错误信息和恢复建议
 * - 依赖检查：验证 git 命令是否可用
 * 
 * 支持的仓库格式：
 * - owner/repo（简短格式）
 * - owner/repo/subdir（指定子目录）
 * - https://github.com/owner/repo（完整 URL）
 * - git@github.com:owner/repo（SSH 格式）
 * 
 * 安装流程：
 * 1. 解析仓库地址，提取技能名称
 * 2. 检查目标目录是否已存在
 * 3. 克隆仓库到临时目录
 * 4. 复制技能文件到目标位置
 * 5. 清理临时文件
 * 
 * 使用场景：
 * - 从社区技能仓库安装新技能
 * - 更新现有技能
 * - 批量安装多个技能
 */
public class SkillsInstaller {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("skills");
    
    // GitHub 基础 URL
    private static final String GITHUB_BASE_URL = "https://github.com/";
    
    // 工作空间路径
    private final String workspace;
    
    // 技能目录路径
    private final String skillsDir;
    
    // 正在安装中的技能，防止并发重复安装
    private final Map<String, Boolean> installing = new ConcurrentHashMap<>();
    
    /**
     * 创建技能安装器
     * 
     * @param workspace 工作空间路径
     */
    public SkillsInstaller(String workspace) {
        this.workspace = workspace;
        this.skillsDir = Paths.get(workspace, "skills").toString();
    }
    
    /**
     * 从 GitHub 仓库安装技能
     * 
     * 这是主要的安装方法，支持多种输入格式：
     * - "owner/repo" - 安装整个仓库作为技能
     * - "owner/repo/skill-name" - 安装仓库中的特定技能目录
     * - 完整的 GitHub URL
     * 
     * 安装流程：
     * 1. 解析并验证仓库地址
     * 2. 检查 git 是否可用
     * 3. 克隆仓库到临时目录
     * 4. 复制技能文件到工作空间
     * 5. 清理临时文件
     * 
     * @param repoSpecifier 仓库说明符（owner/repo 或完整 URL）
     * @return 安装结果消息
     * @throws Exception 安装过程中出现的错误
     */
    public String install(String repoSpecifier) throws Exception {
        // 解析仓库信息
        RepoInfo repoInfo = parseRepoSpecifier(repoSpecifier);
        String skillName = repoInfo.skillName;
        
        // 防止重复安装
        if (installing.putIfAbsent(skillName, true) != null) {
            throw new Exception("技能 '" + skillName + "' 正在安装中，请稍候...");
        }
        
        try {
            // 检查是否已安装
            Path targetPath = Paths.get(skillsDir, skillName);
            if (Files.exists(targetPath)) {
                throw new Exception("技能 '" + skillName + "' 已存在。请先使用 'skills remove " + skillName + "' 移除后再安装。");
            }
            
            // 检查 git 是否可用
            if (!isGitAvailable()) {
                throw new Exception("git 命令不可用。请确保已安装 git 并添加到 PATH 环境变量中。");
            }
            
            logger.info("开始安装技能", Map.of(
                "repo", repoInfo.repoUrl,
                "skill", skillName
            ));
            
            // 创建临时目录
            Path tempDir = Files.createTempDirectory("tinyclaw-skill-");
            
            try {
                // 克隆仓库
                cloneRepository(repoInfo.repoUrl, tempDir.toString());
                
                // 确定源目录（可能是仓库根目录或子目录）
                Path sourceDir = tempDir;
                if (repoInfo.subdir != null && !repoInfo.subdir.isEmpty()) {
                    sourceDir = tempDir.resolve(repoInfo.subdir);
                }
                
                // 验证源目录是否存在 SKILL.md
                Path skillFile = sourceDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    throw new Exception("仓库中未找到 SKILL.md 文件。请确保这是一个有效的技能仓库。");
                }
                
                // 确保目标目录存在
                Files.createDirectories(targetPath.getParent());
                
                // 复制技能文件
                copyDirectory(sourceDir, targetPath);
                
                logger.info("技能安装成功", Map.of(
                    "skill", skillName,
                    "path", targetPath.toString()
                ));
                
                return "✓ 技能 '" + skillName + "' 安装成功！";
                
            } finally {
                // 清理临时目录
                deleteDirectory(tempDir.toFile());
            }
            
        } finally {
            installing.remove(skillName);
        }
    }
    
    /**
     * 解析仓库说明符
     * 
     * 支持的格式：
     * - "owner/repo" -> https://github.com/owner/repo, skill: repo
     * - "owner/repo/skill-name" -> https://github.com/owner/repo, skill: skill-name, subdir: skill-name
     * - "https://github.com/owner/repo" -> 完整 URL, skill: repo
     * - "git@github.com:owner/repo" -> SSH URL, skill: repo
     * 
     * @param specifier 仓库说明符
     * @return 解析后的仓库信息
     * @throws Exception 如果格式无效
     */
    private RepoInfo parseRepoSpecifier(String specifier) throws Exception {
        if (specifier == null || specifier.trim().isEmpty()) {
            throw new Exception("仓库说明符不能为空");
        }
        
        specifier = specifier.trim();
        
        RepoInfo info = new RepoInfo();
        
        // 处理完整 URL
        if (specifier.startsWith("https://") || specifier.startsWith("http://")) {
            info.repoUrl = specifier;
            String path = specifier.replaceFirst("^https?://github\\.com/", "");
            String[] parts = path.split("/");
            info.skillName = parts.length >= 2 ? parts[1].replace(".git", "") : parts[0];
        }
        // 处理 SSH URL
        else if (specifier.startsWith("git@")) {
            info.repoUrl = specifier;
            String path = specifier.replaceFirst("^git@github\\.com:", "");
            String[] parts = path.split("/");
            info.skillName = parts.length >= 1 ? parts[0].replace(".git", "") : "unknown";
        }
        // 处理简短格式 owner/repo 或 owner/repo/subdir
        else {
            String[] parts = specifier.split("/");
            if (parts.length < 2) {
                throw new Exception("无效的仓库格式。使用格式: owner/repo 或 owner/repo/skill-name");
            }
            
            info.repoUrl = GITHUB_BASE_URL + parts[0] + "/" + parts[1];
            info.skillName = parts.length >= 3 ? parts[2] : parts[1];
            info.subdir = parts.length >= 3 ? parts[2] : null;
        }
        
        return info;
    }
    
    /**
     * 检查 git 命令是否可用
     * 
     * @return 如果 git 可用返回 true
     */
    private boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                .redirectErrorStream(true)
                .start();
            
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 克隆 Git 仓库
     * 
     * 使用 git clone 命令将远程仓库克隆到指定目录。
     * 使用 --depth 1 参数进行浅克隆以节省时间和空间。
     * 
     * @param repoUrl 仓库 URL
     * @param targetDir 目标目录
     * @throws Exception 克隆失败时抛出
     */
    private void cloneRepository(String repoUrl, String targetDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "git", "clone", "--depth", "1", repoUrl, targetDir
        );
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        // 读取输出
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            String errorMsg = output.toString();
            if (errorMsg.contains("not found") || errorMsg.contains("404")) {
                throw new Exception("仓库不存在或无访问权限: " + repoUrl);
            } else if (errorMsg.contains("Authentication failed")) {
                throw new Exception("认证失败。请检查仓库访问权限。");
            } else {
                throw new Exception("克隆仓库失败: " + errorMsg);
            }
        }
    }
    
    /**
     * 递归复制目录
     * 
     * @param source 源目录
     * @param target 目标目录
     * @throws IOException 复制失败时抛出
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
            .forEach(sourcePath -> {
                try {
                    Path relativePath = source.relativize(sourcePath);
                    Path targetPath = target.resolve(relativePath);
                    
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        // 跳过 .git 目录
                        if (sourcePath.toString().contains(".git")) {
                            return;
                        }
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("复制文件失败: " + e.getMessage(), e);
                }
            });
    }
    
    /**
     * 递归删除目录
     * 
     * @param dir 要删除的目录
     */
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }
    
    /**
     * 仓库信息内部类
     * 
     * 用于存储解析后的仓库信息。
     */
    private static class RepoInfo {
        String repoUrl;     // 完整的仓库 URL
        String skillName;   // 技能名称
        String subdir;      // 子目录（可选）
    }
}
