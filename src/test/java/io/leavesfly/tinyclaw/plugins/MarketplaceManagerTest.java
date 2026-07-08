package io.leavesfly.tinyclaw.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件市场管理器测试。
 *
 * <p>通过 {@link TempDir} 隔离市场缓存根与插件安装根，避免污染真实 ~/.tinyclaw。</p>
 */
class MarketplaceManagerTest {

    /**
     * 在 {@code root} 下构建一个含单插件（相对路径来源）的本地市场目录。
     */
    private Path buildLocalMarketplace(Path root) throws IOException {
        Path market = root.resolve("my-market-src");
        Path meta = Files.createDirectories(market.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("marketplace.json"), "{\n" +
                "  \"name\": \"my-plugins\",\n" +
                "  \"owner\": { \"name\": \"me\" },\n" +
                "  \"plugins\": [\n" +
                "    { \"name\": \"quality-review-plugin\", \"source\": \"./plugins/quality-review-plugin\", \"description\": \"qr\" }\n" +
                "  ]\n" +
                "}");
        // 插件目录
        Path plugin = Files.createDirectories(
                market.resolve("plugins").resolve("quality-review-plugin").resolve(".claude-plugin"));
        Files.writeString(plugin.resolve("plugin.json"),
                "{ \"name\": \"quality-review-plugin\", \"version\": \"1.0.0\" }");
        Path skill = Files.createDirectories(
                market.resolve("plugins").resolve("quality-review-plugin").resolve("skills").resolve("qr"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: qr\n---\n# QR");
        return market;
    }

    private MarketplaceManager newManager(Path base) {
        Path marketRoot = base.resolve("marketplaces");
        Path pluginsRoot = base.resolve("plugins");
        return new MarketplaceManager(marketRoot, new PluginInstaller(pluginsRoot.toString()));
    }

    @Test
    void testAddListRemoveMarketplace(@TempDir Path base) throws Exception {
        Path market = buildLocalMarketplace(base);
        MarketplaceManager mgr = newManager(base);

        MarketplaceManifest added = mgr.add(market.toString());
        assertNotNull(added);
        assertEquals("my-plugins", added.getName());
        assertEquals(1, added.getPlugins().size());

        List<MarketplaceManifest> list = mgr.list();
        assertEquals(1, list.size());
        assertEquals("my-plugins", list.get(0).getName());

        assertNotNull(mgr.getMarketplace("my-plugins"));

        assertTrue(mgr.remove("my-plugins"));
        assertTrue(mgr.list().isEmpty());
        assertFalse(mgr.remove("my-plugins"));
    }

    @Test
    void testInstallPluginFromMarketplace(@TempDir Path base) throws Exception {
        Path market = buildLocalMarketplace(base);
        MarketplaceManager mgr = newManager(base);
        mgr.add(market.toString());

        String result = mgr.installPlugin("quality-review-plugin", "my-plugins");
        assertTrue(result.contains("quality-review-plugin"));

        // 插件已落到隔离的插件根
        Path installed = base.resolve("plugins").resolve("quality-review-plugin")
                .resolve(".claude-plugin").resolve("plugin.json");
        assertTrue(Files.isRegularFile(installed));
    }

    @Test
    void testInstallSearchAllUniqueMatch(@TempDir Path base) throws Exception {
        Path market = buildLocalMarketplace(base);
        MarketplaceManager mgr = newManager(base);
        mgr.add(market.toString());

        String result = mgr.installPlugin("quality-review-plugin");
        assertTrue(result.contains("quality-review-plugin"));
    }

    @Test
    void testInstallUnknownPluginThrows(@TempDir Path base) throws Exception {
        Path market = buildLocalMarketplace(base);
        MarketplaceManager mgr = newManager(base);
        mgr.add(market.toString());

        Exception ex = assertThrows(Exception.class,
                () -> mgr.installPlugin("nope", "my-plugins"));
        assertTrue(ex.getMessage().contains("未找到插件"));
    }

    @Test
    void testInstallFromUnknownMarketplaceThrows(@TempDir Path base) {
        MarketplaceManager mgr = newManager(base);
        Exception ex = assertThrows(Exception.class,
                () -> mgr.installPlugin("x", "no-such-market"));
        assertTrue(ex.getMessage().contains("未注册的市场"));
    }

    @Test
    void testResolveSpecifierForGithubAndUrl(@TempDir Path base) throws Exception {
        MarketplaceManager mgr = newManager(base);
        MarketplaceManifest mf = new MarketplaceManifest();
        mf.setName("m");
        mf.setRootDir(base);

        MarketplaceManifest.MarketplaceEntry gh = new MarketplaceManifest.MarketplaceEntry();
        gh.setName("gh");
        MarketplaceManifest.MarketplaceSource ghSrc = new MarketplaceManifest.MarketplaceSource();
        ghSrc.setType(MarketplaceManifest.MarketplaceSource.Type.GITHUB);
        ghSrc.setRepo("owner/repo");
        ghSrc.setRef("v1.0.0");
        gh.setSource(ghSrc);
        assertEquals("owner/repo@v1.0.0", mgr.resolveSpecifier(mf, gh));

        MarketplaceManifest.MarketplaceEntry url = new MarketplaceManifest.MarketplaceEntry();
        url.setName("u");
        MarketplaceManifest.MarketplaceSource urlSrc = new MarketplaceManifest.MarketplaceSource();
        urlSrc.setType(MarketplaceManifest.MarketplaceSource.Type.URL);
        urlSrc.setUrl("https://gitlab.com/t/p.git");
        url.setSource(urlSrc);
        assertEquals("git:https://gitlab.com/t/p.git", mgr.resolveSpecifier(mf, url));
    }

    @Test
    void testResolveSpecifierNpmUnsupported(@TempDir Path base) {
        MarketplaceManager mgr = newManager(base);
        MarketplaceManifest mf = new MarketplaceManifest();
        mf.setRootDir(base);
        MarketplaceManifest.MarketplaceEntry npm = new MarketplaceManifest.MarketplaceEntry();
        npm.setName("n");
        MarketplaceManifest.MarketplaceSource s = new MarketplaceManifest.MarketplaceSource();
        s.setType(MarketplaceManifest.MarketplaceSource.Type.NPM);
        s.setPkg("@acme/p");
        npm.setSource(s);

        Exception ex = assertThrows(IOException.class, () -> mgr.resolveSpecifier(mf, npm));
        assertTrue(ex.getMessage().contains("npm"));
    }
}
