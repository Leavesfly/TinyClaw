package io.leavesfly.tinyclaw.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件市场清单解析器测试。
 */
class MarketplaceParserTest {

    private final MarketplaceParser parser = new MarketplaceParser();

    private Path writeMarketplace(Path dir, String json) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("marketplace.json"), json);
        return dir;
    }

    @Test
    void testParseRelativeAndGithubSources(@TempDir Path dir) throws IOException {
        writeMarketplace(dir, "{\n" +
                "  \"name\": \"my-plugins\",\n" +
                "  \"owner\": { \"name\": \"me\" },\n" +
                "  \"plugins\": [\n" +
                "    { \"name\": \"local-one\", \"source\": \"./plugins/local-one\", \"description\": \"d1\" },\n" +
                "    { \"name\": \"gh-one\", \"source\": { \"source\": \"github\", \"repo\": \"owner/repo\", \"ref\": \"v2.0.0\" } }\n" +
                "  ]\n" +
                "}");

        MarketplaceManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals("my-plugins", m.getName());
        assertEquals(2, m.getPlugins().size());

        MarketplaceManifest.MarketplaceEntry local = m.findPlugin("local-one");
        assertNotNull(local);
        assertEquals(MarketplaceManifest.MarketplaceSource.Type.RELATIVE, local.getSource().getType());
        assertEquals("./plugins/local-one", local.getSource().getPath());

        MarketplaceManifest.MarketplaceEntry gh = m.findPlugin("gh-one");
        assertNotNull(gh);
        assertEquals(MarketplaceManifest.MarketplaceSource.Type.GITHUB, gh.getSource().getType());
        assertEquals("owner/repo", gh.getSource().getRepo());
        assertEquals("v2.0.0", gh.getSource().getRef());
    }

    @Test
    void testMetadataPluginRootAndDescription(@TempDir Path dir) throws IOException {
        writeMarketplace(dir, "{\n" +
                "  \"name\": \"m\",\n" +
                "  \"metadata\": { \"description\": \"desc\", \"version\": \"1.2.0\", \"pluginRoot\": \"./plugins\" },\n" +
                "  \"plugins\": [ { \"name\": \"bare\", \"source\": \"formatter\" } ]\n" +
                "}");

        MarketplaceManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals("desc", m.getDescription());
        assertEquals("1.2.0", m.getVersion());
        assertEquals("./plugins", m.getPluginRoot());
        // 裸名相对路径
        assertEquals("formatter", m.findPlugin("bare").getSource().getPath());
    }

    @Test
    void testUrlAndGitSubdirAndNpmSources(@TempDir Path dir) throws IOException {
        writeMarketplace(dir, "{\n" +
                "  \"name\": \"m\",\n" +
                "  \"plugins\": [\n" +
                "    { \"name\": \"u\", \"source\": { \"source\": \"url\", \"url\": \"https://gitlab.com/t/p.git\" } },\n" +
                "    { \"name\": \"s\", \"source\": { \"source\": \"git-subdir\", \"url\": \"owner/mono\", \"path\": \"tools/p\" } },\n" +
                "    { \"name\": \"n\", \"source\": { \"source\": \"npm\", \"package\": \"@acme/p\" } }\n" +
                "  ]\n" +
                "}");

        MarketplaceManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals(MarketplaceManifest.MarketplaceSource.Type.URL, m.findPlugin("u").getSource().getType());
        assertEquals("https://gitlab.com/t/p.git", m.findPlugin("u").getSource().getUrl());

        MarketplaceManifest.MarketplaceSource sub = m.findPlugin("s").getSource();
        assertEquals(MarketplaceManifest.MarketplaceSource.Type.GIT_SUBDIR, sub.getType());
        assertEquals("owner/mono", sub.getUrl());
        assertEquals("tools/p", sub.getSubdir());

        assertEquals(MarketplaceManifest.MarketplaceSource.Type.NPM, m.findPlugin("n").getSource().getType());
        assertEquals("@acme/p", m.findPlugin("n").getSource().getPkg());
    }

    @Test
    void testNoMarketplaceJsonReturnsNull(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("readme.txt"), "not a marketplace");
        assertNull(parser.parse(dir));
    }

    @Test
    void testEntryMissingNameSkipped(@TempDir Path dir) throws IOException {
        writeMarketplace(dir, "{\n" +
                "  \"name\": \"m\",\n" +
                "  \"plugins\": [ { \"source\": \"./x\" }, { \"name\": \"ok\", \"source\": \"./y\" } ]\n" +
                "}");

        MarketplaceManifest m = parser.parse(dir);

        assertNotNull(m);
        // 缺 name 的条目被跳过
        assertEquals(1, m.getPlugins().size());
        assertEquals("ok", m.getPlugins().get(0).getName());
    }
}
