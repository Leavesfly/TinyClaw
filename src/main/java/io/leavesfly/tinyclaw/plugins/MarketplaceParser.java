package io.leavesfly.tinyclaw.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 插件市场清单解析器。
 *
 * <p>解析 {@code <root>/.claude-plugin/marketplace.json}，产出 {@link MarketplaceManifest}。
 * 兼容 Claude Code 的市场目录格式，宽容未知字段，纯静态读取，不执行任何代码。</p>
 *
 * <p>支持的插件 {@code source} 形态：</p>
 * <ul>
 *   <li>相对路径字符串（{@code "./plugins/foo"} 或裸名 {@code "foo"}，配合 metadata.pluginRoot）</li>
 *   <li>{@code { "source": "github", "repo": "owner/repo", "ref"?, "sha"? }}</li>
 *   <li>{@code { "source": "url", "url": "...", "ref"?, "sha"? }}</li>
 *   <li>{@code { "source": "git-subdir", "url": "...", "path": "...", "ref"?, "sha"? }}</li>
 *   <li>{@code { "source": "npm", "package": "...", "version"? }}</li>
 * </ul>
 */
public class MarketplaceParser {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析给定市场目录。
     *
     * @param marketplaceDir 市场根目录（含 {@code .claude-plugin/marketplace.json}）
     * @return 解析出的 {@link MarketplaceManifest}，无法识别时返回 null
     */
    public MarketplaceManifest parse(Path marketplaceDir) {
        if (marketplaceDir == null || !Files.isDirectory(marketplaceDir)) {
            return null;
        }
        Path file = marketplaceDir.resolve(".claude-plugin").resolve("marketplace.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                return null;
            }

            MarketplaceManifest m = new MarketplaceManifest();
            m.setRootDir(marketplaceDir.toAbsolutePath().normalize());
            m.setName(textOrNull(root, "name"));

            // description / version：顶层与 metadata 均兼容
            JsonNode metadata = root.path("metadata");
            m.setDescription(firstNonNull(textOrNull(root, "description"), textOrNull(metadata, "description")));
            m.setVersion(firstNonNull(textOrNull(root, "version"), textOrNull(metadata, "version")));
            m.setPluginRoot(textOrNull(metadata, "pluginRoot"));

            JsonNode plugins = root.path("plugins");
            if (plugins.isArray()) {
                for (JsonNode entryNode : plugins) {
                    MarketplaceManifest.MarketplaceEntry entry = parseEntry(entryNode);
                    if (entry != null) {
                        m.addPlugin(entry);
                    }
                }
            }
            return m;
        } catch (IOException e) {
            logger.error("解析 marketplace.json 失败", java.util.Map.of(
                    "file", file.toString(), "error", String.valueOf(e.getMessage())));
            return null;
        }
    }

    private MarketplaceManifest.MarketplaceEntry parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String name = textOrNull(node, "name");
        if (name == null || name.isEmpty()) {
            logger.warn("市场插件条目缺少 name，已跳过");
            return null;
        }
        MarketplaceManifest.MarketplaceEntry entry = new MarketplaceManifest.MarketplaceEntry();
        entry.setName(name);
        entry.setDescription(textOrNull(node, "description"));
        entry.setVersion(textOrNull(node, "version"));
        entry.setSource(parseSource(node.get("source")));
        return entry;
    }

    /**
     * 解析 source 字段（string | object）。
     */
    private MarketplaceManifest.MarketplaceSource parseSource(JsonNode node) {
        MarketplaceManifest.MarketplaceSource src = new MarketplaceManifest.MarketplaceSource();
        if (node == null || node.isNull()) {
            return src; // UNKNOWN
        }
        if (node.isTextual()) {
            src.setType(MarketplaceManifest.MarketplaceSource.Type.RELATIVE);
            src.setPath(node.asText());
            return src;
        }
        if (!node.isObject()) {
            return src;
        }
        String kind = textOrNull(node, "source");
        String ref = textOrNull(node, "ref");
        String sha = textOrNull(node, "sha");
        src.setRef(ref);
        src.setSha(sha);
        if (kind == null) {
            return src; // UNKNOWN
        }
        switch (kind) {
            case "github" -> {
                src.setType(MarketplaceManifest.MarketplaceSource.Type.GITHUB);
                src.setRepo(textOrNull(node, "repo"));
            }
            case "url" -> {
                src.setType(MarketplaceManifest.MarketplaceSource.Type.URL);
                src.setUrl(textOrNull(node, "url"));
            }
            case "git-subdir" -> {
                src.setType(MarketplaceManifest.MarketplaceSource.Type.GIT_SUBDIR);
                src.setUrl(textOrNull(node, "url"));
                src.setSubdir(textOrNull(node, "path"));
            }
            case "npm" -> {
                src.setType(MarketplaceManifest.MarketplaceSource.Type.NPM);
                src.setPkg(textOrNull(node, "package"));
            }
            default -> {
                // 兼容 { "source": "./local/path" } 形态
                if (kind.startsWith("./") || kind.startsWith("/")) {
                    src.setType(MarketplaceManifest.MarketplaceSource.Type.RELATIVE);
                    src.setPath(kind);
                }
            }
        }
        return src;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node != null && node.has(field) && node.get(field).isTextual()) {
            return node.get(field).asText();
        }
        return null;
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
