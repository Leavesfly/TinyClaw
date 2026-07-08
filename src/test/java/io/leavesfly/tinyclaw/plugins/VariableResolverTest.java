package io.leavesfly.tinyclaw.plugins;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 变量替换器测试。
 */
class VariableResolverTest {

    @Test
    void testResolvesPluginRootAndData() {
        VariableResolver resolver = new VariableResolver(
                "/plugins/my", "/data/my", "/workspace", null);

        assertEquals("/plugins/my/server.js", resolver.resolve("${CLAUDE_PLUGIN_ROOT}/server.js"));
        assertEquals("/data/my", resolver.resolve("${CLAUDE_PLUGIN_DATA}"));
        assertEquals("/workspace/x", resolver.resolve("${CLAUDE_PROJECT_DIR}/x"));
    }

    @Test
    void testResolvesUserConfig() {
        Map<String, Object> userConfig = new HashMap<>();
        userConfig.put("api_endpoint", "https://api.example.com");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", userConfig);

        assertEquals("https://api.example.com/v1",
                resolver.resolve("${user_config.api_endpoint}/v1"));
    }

    @Test
    void testUnknownVariableKeptAsIs() {
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);
        // 未知的 user_config 键与未知变量保持原样
        assertEquals("${user_config.missing}", resolver.resolve("${user_config.missing}"));
    }

    @Test
    void testNullAndPlainStrings() {
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);
        assertNull(resolver.resolve(null));
        assertEquals("no-variables-here", resolver.resolve("no-variables-here"));
    }

    @Test
    void testMultipleVariablesInOneString() {
        VariableResolver resolver = new VariableResolver("/root", "/data", "/w", null);
        assertEquals("/root/bin --data /data",
                resolver.resolve("${CLAUDE_PLUGIN_ROOT}/bin --data ${CLAUDE_PLUGIN_DATA}"));
    }
}
