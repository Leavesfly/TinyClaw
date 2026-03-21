package io.leavesfly.tinyclaw.skills;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    // 复用 HttpClient，避免每次下载都重新建立连接池
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    
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
     * 安装技能，支持多种来源：
     * - GitHub 仓库（owner/repo、owner/repo/skill-name、完整 GitHub URL）
     * - 压缩包 URL（.zip 或 .tar.gz 直链，需要 name 参数指定技能名称，格式：url|skill-name）
     *
     * @param specifier 安装说明符
     * @return 安装结果消息
     * @throws Exception 安装过程中出现的错误
     */
    public String install(String specifier) throws Exception {
        // 识别是否为压缩包 URL 安装（非 GitHub 的 http/https URL，或带 | 分隔符的格式）
        if (isArchiveUrl(specifier)) {
            return installFromArchiveUrl(specifier);
        }

        // 走 GitHub 克隆安装流程
        return installFromGitHub(specifier);
    }

    /**
     * 判断是否为压缩包 URL 安装格式。
     *
     * 支持两种格式：
     * - "https://example.com/skill.zip|skill-name"（带 | 分隔技能名）
     * - "https://example.com/skill.zip"（URL 路径部分以 .zip/.tar.gz/.tgz 结尾，技能名从文件名推断）
     *
     * 注意：URL 可能携带 query string（如 ?ticket=xxx），判断后缀时需去掉 query 部分。
     */
    private boolean isArchiveUrl(String specifier) {
        if (specifier == null) {
            return false;
        }
        // 带 | 分隔符的格式：url|skill-name
        if (specifier.contains("|")) {
            String urlPart = specifier.split("\\|")[0].trim();
            return urlPart.startsWith("http://") || urlPart.startsWith("https://");
        }
        // 非 GitHub 的 http/https URL，且路径部分以压缩包后缀结尾
        if ((specifier.startsWith("http://") || specifier.startsWith("https://"))
                && !specifier.contains("github.com")) {
            return hasArchiveSuffix(stripQuery(specifier));
        }
        return false;
    }

    /**
     * 去掉 URL 中的 query string（? 及之后的部分）和 fragment（# 及之后的部分）。
     */
    private String stripQuery(String url) {
        int cutIndex = url.indexOf('?');
        if (cutIndex < 0 || (url.indexOf('#') >= 0 && url.indexOf('#') < cutIndex)) {
            cutIndex = url.indexOf('#');
        }
        return cutIndex >= 0 ? url.substring(0, cutIndex) : url;
    }

    /**
     * 判断路径是否以压缩包后缀结尾（不区分大小写）。
     */
    private boolean hasArchiveSuffix(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    /**
     * 从压缩包 URL 安装技能。
     *
     * 支持格式：
     * - "https://example.com/skill.zip|my-skill"（推荐，明确指定技能名）
     * - "https://example.com/my-skill.zip"（技能名从文件名推断）
     */
    private String installFromArchiveUrl(String specifier) throws Exception {
        String archiveUrl;
        String skillName;

        if (specifier.contains("|")) {
            String[] parts = specifier.split("\\|", 2);
            archiveUrl = parts[0].trim();
            skillName = parts[1].trim();
        } else {
            archiveUrl = specifier.trim();
            // 从 URL 路径部分的文件名推断技能名（先去掉 query string，再去掉压缩包后缀）
            String urlPath = stripQuery(archiveUrl);
            String fileName = urlPath.substring(urlPath.lastIndexOf('/') + 1);
            skillName = fileName.replaceAll("(?i)\\.(tar\\.gz|tgz|zip)$", "");
        }

        if (skillName.isEmpty()) {
            throw new Exception("无法推断技能名称，请使用 'url|skill-name' 格式明确指定技能名。");
        }

        final String finalArchiveUrl = archiveUrl;
        final String finalSkillName = skillName;
        return runInstall(skillName, tempDir -> {
            logger.info("从压缩包 URL 安装技能", Map.of("url", finalArchiveUrl, "skill", finalSkillName));

            Path archiveFile = tempDir.resolve("archive" + detectArchiveSuffix(finalArchiveUrl));
            downloadFile(finalArchiveUrl, archiveFile);

            Path extractDir = tempDir.resolve("extracted");
            Files.createDirectories(extractDir);
            extractArchive(archiveFile, extractDir);

            Path skillSourceDir = findSkillDirectory(extractDir);
            if (skillSourceDir == null) {
                throw new Exception("压缩包中未找到 SKILL.md 文件。请确保这是一个有效的技能压缩包。");
            }
            return skillSourceDir;
        }, "（来源：" + archiveUrl + "）");
    }

    /**
     * 从 GitHub 仓库克隆安装技能。
     *
     * 支持格式：
     * - "owner/repo" - 安装整个仓库作为技能
     * - "owner/repo/skill-name" - 安装仓库中的特定子目录
     * - 完整的 GitHub URL 或 SSH URL
     */
    private String installFromGitHub(String repoSpecifier) throws Exception {
        RepoInfo repoInfo = parseRepoSpecifier(repoSpecifier);

        if (!isGitAvailable()) {
            throw new Exception("git 命令不可用。请确保已安装 git 并添加到 PATH 环境变量中。");
        }

        return runInstall(repoInfo.skillName, tempDir -> {
            logger.info("从 GitHub 安装技能", Map.of("repo", repoInfo.repoUrl, "skill", repoInfo.skillName));

            cloneRepository(repoInfo.repoUrl, tempDir.toString());

            Path sourceDir = (repoInfo.subdir != null && !repoInfo.subdir.isEmpty())
                    ? tempDir.resolve(repoInfo.subdir)
                    : tempDir;

            if (!Files.exists(sourceDir.resolve("SKILL.md"))) {
                throw new Exception("仓库中未找到 SKILL.md 文件。请确保这是一个有效的技能仓库。");
            }
            return sourceDir;
        }, "");
    }

    /**
     * 安装流程公共模板：防重复安装 + 临时目录管理 + 文件复制。
     *
     * 将技能特定的"准备源目录"逻辑通过 {@code prepareSourceDir} 回调注入，
     * 避免在每个 install* 方法中重复编写防重、临时目录、清理等样板代码。
     *
     * @param skillName        技能名称
     * @param prepareSourceDir 回调：在临时目录中准备好技能源目录并返回其路径
     * @param successSuffix    成功消息的附加说明（如来源 URL）
     * @return 安装成功消息
     */
    private String runInstall(String skillName, SkillSourcePreparer prepareSourceDir, String successSuffix)
            throws Exception {
        if (installing.putIfAbsent(skillName, true) != null) {
            throw new Exception("技能 '" + skillName + "' 正在安装中，请稍候...");
        }

        try {
            Path targetPath = Paths.get(skillsDir, skillName);
            if (Files.exists(targetPath)) {
                throw new Exception("技能 '" + skillName + "' 已存在。请先使用 'skills remove " + skillName + "' 移除后再安装。");
            }

            Path tempDir = Files.createTempDirectory("tinyclaw-skill-");
            try {
                Path sourceDir = prepareSourceDir.prepare(tempDir);
                Files.createDirectories(targetPath.getParent());
                copyDirectory(sourceDir, targetPath);

                logger.info("技能安装成功", Map.of("skill", skillName, "path", targetPath.toString()));
                return "✓ 技能 '" + skillName + "' 安装成功！" + successSuffix;
            } finally {
                deleteDirectory(tempDir);
            }
        } finally {
            installing.remove(skillName);
        }
    }

    /**
     * 技能源目录准备回调接口，供 {@link #runInstall} 使用。
     */
    @FunctionalInterface
    private interface SkillSourcePreparer {
        Path prepare(Path tempDir) throws Exception;
    }

    /**
     * 从 URL 下载文件到指定路径。
     *
     * @param url        下载地址
     * @param targetFile 保存路径
     * @throws Exception 下载失败时抛出
     */
    private void downloadFile(String url, Path targetFile) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new Exception("下载失败，HTTP 状态码: " + response.statusCode() + "，URL: " + url);
        }

        try (InputStream inputStream = response.body()) {
            Files.copy(inputStream, targetFile);
        }
    }

    /**
     * 根据 URL 推断压缩包后缀（.zip、.tar.gz、.tgz）。
     * 会先去掉 query string 再判断，以正确处理带参数的下载链接。
     */
    private String detectArchiveSuffix(String url) {
        String path = stripQuery(url).toLowerCase();
        if (path.endsWith(".tar.gz")) {
            return ".tar.gz";
        }
        if (path.endsWith(".tgz")) {
            return ".tgz";
        }
        return ".zip";
    }

    /**
     * 解压压缩包到目标目录，支持 .zip 和 .tar.gz/.tgz 格式。
     *
     * @param archiveFile 压缩包文件路径
     * @param targetDir   解压目标目录
     * @throws Exception 解压失败时抛出
     */
    private void extractArchive(Path archiveFile, Path targetDir) throws Exception {
        String fileName = archiveFile.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".zip")) {
            extractZip(archiveFile, targetDir);
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            extractTarGz(archiveFile, targetDir);
        } else {
            throw new Exception("不支持的压缩格式，仅支持 .zip 和 .tar.gz/.tgz");
        }
    }

    /**
     * 解压 ZIP 格式压缩包。
     */
    private void extractZip(Path archiveFile, Path targetDir) throws Exception {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archiveFile)))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // 防止 Zip Slip 攻击
                if (!entryPath.startsWith(targetDir)) {
                    throw new Exception("检测到非法的压缩包路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zipInputStream, entryPath);
                }
                zipInputStream.closeEntry();
            }
        }
    }

    /**
     * 解压 tar.gz 格式压缩包（通过系统 tar 命令）。
     */
    private void extractTarGz(Path archiveFile, Path targetDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "tar", "-xzf", archiveFile.toAbsolutePath().toString(),
                "-C", targetDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("解压 tar.gz 失败: " + output);
        }
    }

    /**
     * 在解压目录中递归查找包含 SKILL.md 的目录。
     * 优先返回最浅层的匹配目录。
     *
     * @param rootDir 搜索根目录
     * @return 包含 SKILL.md 的目录，未找到返回 null
     */
    private Path findSkillDirectory(Path rootDir) throws IOException {
        // 先检查根目录本身
        if (Files.exists(rootDir.resolve("SKILL.md"))) {
            return rootDir;
        }
        // 递归查找子目录（BFS 风格，优先浅层）
        try (var stream = Files.list(rootDir)) {
            for (Path child : stream.filter(Files::isDirectory).toList()) {
                Path result = findSkillDirectory(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
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
        // 处理 SSH URL（格式：git@github.com:owner/repo.git）
        else if (specifier.startsWith("git@")) {
            info.repoUrl = specifier;
            String path = specifier.replaceFirst("^git@github\\.com:", "");
            String[] parts = path.split("/");
            // parts[0] 是 owner，parts[1] 是 repo（去掉 .git 后缀）
            info.skillName = parts.length >= 2 ? parts[1].replace(".git", "") : parts[0].replace(".git", "");
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
     * 如果 HTTPS 方式因网络问题失败，自动回退到 SSH 方式重试。
     * 
     * @param repoUrl 仓库 URL
     * @param targetDir 目标目录
     * @throws Exception 克隆失败时抛出
     */
    private void cloneRepository(String repoUrl, String targetDir) throws Exception {
        String httpsError = executeGitClone(repoUrl, targetDir);

        if (httpsError == null) {
            return;
        }

        // HTTPS 失败且是网络连接问题，尝试 SSH 方式
        boolean isNetworkError = httpsError.contains("Failed to connect")
                || httpsError.contains("Could not resolve host")
                || httpsError.contains("Connection refused")
                || httpsError.contains("Connection timed out")
                || httpsError.contains("Couldn't connect to server");

        String sshUrl = convertToSshUrl(repoUrl);
        if (isNetworkError && sshUrl != null) {
            logger.info("HTTPS clone failed, retrying with SSH", Map.of(
                    "https_url", repoUrl,
                    "ssh_url", sshUrl
            ));

            // 清理 HTTPS 失败留下的目录内容
            deleteDirectory(Paths.get(targetDir));
            Files.createDirectories(Paths.get(targetDir));

            String sshError = executeGitClone(sshUrl, targetDir);
            if (sshError == null) {
                return;
            }

            // SSH 也失败，抛出包含两种方式错误信息的异常
            throw new Exception("克隆仓库失败。\n"
                    + "HTTPS (" + repoUrl + "): " + httpsError.trim() + "\n"
                    + "SSH (" + sshUrl + "): " + sshError.trim() + "\n\n"
                    + "请检查网络连接，或配置 git 代理：\n"
                    + "  git config --global http.proxy http://代理地址:端口\n"
                    + "  git config --global https.proxy http://代理地址:端口");
        }

        // 非网络问题或无法转换为 SSH URL，直接报错
        if (httpsError.contains("not found") || httpsError.contains("404")) {
            throw new Exception("仓库不存在或无访问权限: " + repoUrl);
        } else if (httpsError.contains("Authentication failed")) {
            throw new Exception("认证失败。请检查仓库访问权限。");
        } else {
            throw new Exception("克隆仓库失败: " + httpsError);
        }
    }

    /**
     * 执行 git clone 命令
     * 
     * @param url 仓库 URL（HTTPS 或 SSH）
     * @param targetDir 目标目录
     * @return 如果成功返回 null，失败返回错误信息
     */
    private String executeGitClone(String url, String targetDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", url, targetDir
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 ? null : output.toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * 将 HTTPS GitHub URL 转换为 SSH URL
     * 
     * @param httpsUrl HTTPS 格式的 URL
     * @return SSH 格式的 URL，如果无法转换返回 null
     */
    private String convertToSshUrl(String httpsUrl) {
        if (httpsUrl == null) {
            return null;
        }
        // https://github.com/owner/repo -> git@github.com:owner/repo.git
        if (httpsUrl.startsWith("https://github.com/")) {
            String path = httpsUrl.substring("https://github.com/".length());
            // 移除末尾的 / 和 .git
            path = path.replaceAll("/+$", "").replaceAll("\\.git$", "");
            return "git@github.com:" + path + ".git";
        }
        if (httpsUrl.startsWith("http://github.com/")) {
            String path = httpsUrl.substring("http://github.com/".length());
            path = path.replaceAll("/+$", "").replaceAll("\\.git$", "");
            return "git@github.com:" + path + ".git";
        }
        return null;
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
            .filter(sourcePath -> {
                // 跳过 .git 目录及其所有子路径（精确匹配路径组件，避免误过滤含 .git 字符串的合法文件）
                Path relative = source.relativize(sourcePath);
                for (Path component : relative) {
                    if (".git".equals(component.toString())) {
                        return false;
                    }
                }
                return true;
            })
            .forEach(sourcePath -> {
                try {
                    Path relativePath = source.relativize(sourcePath);
                    Path targetPath = target.resolve(relativePath);
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("复制文件失败: " + e.getMessage(), e);
                }
            });
    }
    
    /**
     * 递归删除目录及其所有内容。
     *
     * @param dir 要删除的目录
     */
    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        logger.warn("删除临时文件失败", Map.of("path", path.toString(), "error", e.getMessage()));
                    }
                });
        } catch (IOException e) {
            logger.warn("清理临时目录失败", Map.of("dir", dir.toString(), "error", e.getMessage()));
        }
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
