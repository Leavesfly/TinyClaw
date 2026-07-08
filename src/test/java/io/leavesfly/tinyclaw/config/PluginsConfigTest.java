package io.leavesfly.tinyclaw.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件系统配置测试。
 */
class PluginsConfigTest {

    @Test
    void testDefaults() {
        PluginsConfig config = new PluginsConfig();

        assertFalse(config.isEnabled());
        assertNotNull(config.getAllow());
        assertTrue(config.getAllow().isEmpty());
        assertNotNull(config.getDeny());
        assertNotNull(config.getLoad());
        assertNotNull(config.getLoad().getPaths());
        assertNotNull(config.getBridge());
        assertFalse(config.getBridge().isEnabled());
        assertEquals("node", config.getBridge().getNodePath());
        assertNotNull(config.getEntries());
    }

    @Test
    void testAllowEmptyMeansAllPluginsAllowed() {
        PluginsConfig config = new PluginsConfig();
        // allow 为空 → 默认允许
        assertTrue(config.isPluginAllowed("any-plugin"));
    }

    @Test
    void testDenyTakesPrecedenceOverAllow() {
        PluginsConfig config = new PluginsConfig();
        config.setAllow(List.of("p1", "p2"));
        config.setDeny(List.of("p2"));

        assertTrue(config.isPluginAllowed("p1"));
        // deny 优先级最高
        assertFalse(config.isPluginAllowed("p2"));
    }

    @Test
    void testAllowListIsExclusive() {
        PluginsConfig config = new PluginsConfig();
        config.setAllow(List.of("p1"));

        assertTrue(config.isPluginAllowed("p1"));
        // allow 非空时不在列表内的插件被拒绝
        assertFalse(config.isPluginAllowed("p2"));
    }

    @Test
    void testEntryDisabledRejectsPlugin() {
        PluginsConfig config = new PluginsConfig();
        PluginsConfig.PluginEntry entry = new PluginsConfig.PluginEntry();
        entry.setEnabled(false);
        config.getEntries().put("p1", entry);

        assertFalse(config.isPluginAllowed("p1"));
    }

    @Test
    void testNullPluginIdRejected() {
        PluginsConfig config = new PluginsConfig();
        assertFalse(config.isPluginAllowed(null));
    }

    @Test
    void testEntryDefaults() {
        PluginsConfig.PluginEntry entry = new PluginsConfig.PluginEntry();
        assertTrue(entry.isEnabled());
        assertNotNull(entry.getConfig());
    }
}
