package io.leavesfly.tinyclaw.plugins;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 插件发现器。
 *
 * <p>从配置的加载路径与默认目录中发现候选插件目录，逐个交由 {@link ManifestParser} 解析。</p>
 *
 * <p>每个加载根的处理规则：</p>
 * <ul>
 *   <li>若根目录自身即为一个插件（含清单 / SKILL.md / skills 目录）→ 作为单个插件；</li>
 *   <li>否则视为容器目录，扫描其一级子目录作为候选插件。</li>
 * </ul>
 */
public class PluginDiscovery {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    private final ManifestParser parser;

    public PluginDiscovery(ManifestParser parser) {
        this.parser = parser;
    }

    /**
     * 在给定的加载根列表中发现所有候选插件。
     *
     * @param roots 加载根目录绝对路径列表
     * @return 解析成功的插件清单列表（按发现顺序，去重由上层负责）
     */
    public List<PluginManifest> discover(List<String> roots) {
        List<PluginManifest> result = new ArrayList<>();
        if (roots == null) {
            return result;
        }
        for (String root : roots) {
            if (root == null || root.isEmpty()) {
                continue;
            }
            Path rootPath = Path.of(root);
            if (!Files.isDirectory(rootPath)) {
                continue;
            }
            discoverInRoot(rootPath, result);
        }
        return result;
    }

    private void discoverInRoot(Path rootPath, List<PluginManifest> result) {
        // 根目录自身是否为一个插件
        if (isPluginDir(rootPath)) {
            PluginManifest m = parser.parse(rootPath);
            if (m != null) {
                result.add(m);
            }
            return;
        }
        // 否则扫描一级子目录
        try (Stream<Path> children = Files.list(rootPath)) {
            children.filter(Files::isDirectory).forEach(child -> {
                PluginManifest m = parser.parse(child);
                if (m != null) {
                    result.add(m);
                }
            });
        } catch (IOException e) {
            logger.warn("扫描插件目录失败: " + rootPath + " - " + e.getMessage());
        }
    }

    /**
     * 判断目录是否直接构成一个插件（含清单、SKILL.md 或 skills 目录）。
     */
    private boolean isPluginDir(Path dir) {
        return Files.isRegularFile(dir.resolve(".claude-plugin").resolve("plugin.json"))
                || Files.isRegularFile(dir.resolve("openclaw.plugin.json"))
                || Files.isRegularFile(dir.resolve(".codex-plugin").resolve("plugin.json"))
                || Files.isRegularFile(dir.resolve(".cursor-plugin").resolve("plugin.json"))
                || Files.isRegularFile(dir.resolve("SKILL.md"))
                || Files.isDirectory(dir.resolve("skills"));
    }
}
