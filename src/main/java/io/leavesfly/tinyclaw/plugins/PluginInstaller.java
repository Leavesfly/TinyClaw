package io.leavesfly.tinyclaw.plugins;

import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * 插件安装器。
 *
 * <p>支持从以下来源安装插件到本地插件目录（{@code ~/.tinyclaw/plugins/<id>}）：</p>
 * <ul>
 *   <li>本地路径：{@code ./my-plugin}、绝对路径</li>
 *   <li>GitHub 简短：{@code owner/repo}、{@code owner/repo/subdir}</li>
 *   <li>git 链接：{@code git:https://host/x/y.git@ref}、{@code https://github.com/owner/repo}</li>
 * </ul>
 *
 * <p>安装流程：拉取（git clone / 本地复制）→ 定位插件清单 → 复制到本地插件目录。
 * 详见设计文档 §8.5。marketplace 来源的选装留待后续增强。</p>
 */
public class PluginInstaller {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    private static final String GITHUB_BASE = "https://github.com/";

    private final ManifestParser parser = new ManifestParser();

    /** 本地插件安装根目录。 */
    private final Path pluginsRoot;

    public PluginInstaller() {
        this.pluginsRoot = Paths.get(ConfigLoader.expandHome("~/.tinyclaw/plugins"));
    }

    public PluginInstaller(String pluginsRoot) {
        this.pluginsRoot = Paths.get(pluginsRoot);
    }

    /**
     * 安装插件。
     *
     * @param specifier 安装说明符（本地路径 / owner/repo / git:URL@ref）
     * @return 安装结果消息
     * @throws Exception 安装失败
     */
    public String install(String specifier) throws Exception {
        if (specifier == null || specifier.trim().isEmpty()) {
            throw new IllegalArgumentException("安装说明符不能为空");
        }
        specifier = specifier.trim();

        Path sourceDir;
        Path tempDir = null;
        try {
            if (isLocalPath(specifier)) {
                sourceDir = resolveSource(specifier, null);
            } else {
                tempDir = Files.createTempDirectory("tinyclaw-plugin-");
                sourceDir = resolveSource(specifier, tempDir);
            }

            // 定位插件清单
            PluginManifest manifest = parser.parse(sourceDir);
            if (manifest == null) {
                throw new IOException("未在来源中找到有效插件（缺少 .claude-plugin/plugin.json 或 SKILL.md）");
            }

            // 复制到本地插件目录
            Files.createDirectories(pluginsRoot);
            Path target = pluginsRoot.resolve(sanitizeId(manifest.getId()));
            if (Files.exists(target)) {
                deleteRecursively(target);
            }
            copyRecursively(sourceDir, target);

            logger.info("插件安装成功", java.util.Map.of(
                    "plugin", manifest.getId(),
                    "target", target.toString()));
            return "✓ 插件已安装: " + manifest.getId() + " → " + target
                    + "\n  请在配置 plugins.allow 中加入该 id 并重启以启用。";
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * 解析安装来源为本地目录（本地路径直接返回；git 来源拉取到 {@code tempDir}）。
     *
     * <p>供 {@link MarketplaceManager} 复用：拉取市场仓库或单插件仓库。</p>
     *
     * @param specifier 来源说明符
     * @param tempDir   git 来源的临时拉取目录（本地来源可为 null）
     * @return 包含内容的目录
     */
    Path resolveSource(String specifier, Path tempDir) throws Exception {
        if (isLocalPath(specifier)) {
            Path sourceDir = Paths.get(ConfigLoader.expandHome(specifier)).toAbsolutePath().normalize();
            if (!Files.isDirectory(sourceDir)) {
                throw new IOException("本地路径不存在或不是目录: " + sourceDir);
            }
            return sourceDir;
        }
        return fetchFromGit(specifier, tempDir);
    }

    /**
     * 从 git 来源拉取到临时目录，返回包含插件的目录（可能是子目录）。
     */
    private Path fetchFromGit(String specifier, Path tempDir) throws Exception {
        String ref = null;
        String subdir = null;
        String repoUrl;

        // 拆分显式前缀 git:
        String spec = specifier.startsWith("git:") ? specifier.substring(4) : specifier;

        // 拆分 @ref
        int atIndex = spec.lastIndexOf('@');
        if (atIndex > 0 && spec.indexOf("://") < atIndex && !spec.startsWith("git@")) {
            ref = spec.substring(atIndex + 1);
            spec = spec.substring(0, atIndex);
        }

        if (spec.startsWith("http://") || spec.startsWith("https://") || spec.startsWith("git@")) {
            repoUrl = spec;
        } else {
            // owner/repo 或 owner/repo/subdir 简短格式
            String[] parts = spec.split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("无法识别的插件来源: " + specifier);
            }
            repoUrl = GITHUB_BASE + parts[0] + "/" + parts[1];
            if (parts.length > 2) {
                subdir = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            }
        }

        runGitClone(repoUrl, ref, tempDir);

        Path result = tempDir;
        if (subdir != null) {
            Path sub = tempDir.resolve(subdir).normalize();
            if (!sub.startsWith(tempDir) || !Files.isDirectory(sub)) {
                throw new IOException("仓库中的子目录不存在或越界: " + subdir);
            }
            result = sub;
        }
        return result;
    }

    /**
     * 执行 git clone（浅克隆，可指定 ref）。
     */
    private void runGitClone(String repoUrl, String ref, Path targetDir) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of(
                "git", "clone", "--depth", "1"));
        if (ref != null && !ref.isEmpty()) {
            cmd.add("--branch");
            cmd.add(ref);
        }
        cmd.add(repoUrl);
        cmd.add(targetDir.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("git clone 失败 (exit=" + exit + "): " + output.trim());
        }
    }

    /**
     * 判断是否为本地路径来源。
     */
    private boolean isLocalPath(String specifier) {
        return specifier.startsWith("./") || specifier.startsWith("/")
                || specifier.startsWith("~") || specifier.startsWith("../");
    }

    void copyRecursively(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    logger.warn("删除文件失败: " + p);
                }
            });
        }
    }

    String sanitizeId(String id) {
        return id == null ? "unknown" : id.replaceAll("[^a-zA-Z0-9_-]", "-");
    }
}
