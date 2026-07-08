package io.leavesfly.tinyclaw.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.config.MCPServersConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 组件适配器测试。
 */
class McpComponentAdapterTest {

    private final McpComponentAdapter adapter = new McpComponentAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    private PluginManifest manifestWithMcp(String mcpJson) throws Exception {
        PluginManifest m = new PluginManifest();
        m.setId("myplugin");
        m.setMcpServers(mapper.readTree(mcpJson));
        return m;
    }

    @Test
    void testConvertStdioServerWithVariableSubstitution() throws Exception {
        PluginManifest m = manifestWithMcp(
                "{ \"echo\": { \"command\": \"node\", " +
                "\"args\": [\"${CLAUDE_PLUGIN_ROOT}/server.js\"], " +
                "\"env\": { \"DATA\": \"${CLAUDE_PLUGIN_DATA}\" } } }");
        VariableResolver resolver = new VariableResolver("/root", "/data", "/w", null);

        List<MCPServersConfig.MCPServerConfig> servers = adapter.adapt(m, resolver);

        assertEquals(1, servers.size());
        MCPServersConfig.MCPServerConfig cfg = servers.get(0);
        assertEquals("plugin:myplugin:echo", cfg.getName());
        assertEquals("stdio", cfg.getType());
        assertEquals("node", cfg.getCommand());
        assertEquals("/root/server.js", cfg.getArgs().get(0));
        assertEquals("/data", cfg.getEnv().get("DATA"));
        assertTrue(cfg.isEnabled());
    }

    @Test
    void testConvertHttpServer() throws Exception {
        PluginManifest m = manifestWithMcp(
                "{ \"api\": { \"type\": \"http\", \"url\": \"https://mcp.example.com\" } }");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        List<MCPServersConfig.MCPServerConfig> servers = adapter.adapt(m, resolver);

        assertEquals(1, servers.size());
        MCPServersConfig.MCPServerConfig cfg = servers.get(0);
        assertEquals("streamable-http", cfg.getType());
        assertEquals("https://mcp.example.com", cfg.getEndpoint());
    }

    @Test
    void testSseIsDefaultForUrlWithoutType() throws Exception {
        PluginManifest m = manifestWithMcp(
                "{ \"api\": { \"url\": \"https://mcp.example.com/sse\" } }");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        List<MCPServersConfig.MCPServerConfig> servers = adapter.adapt(m, resolver);

        assertEquals("sse", servers.get(0).getType());
    }

    @Test
    void testServerWithoutCommandOrUrlSkipped() throws Exception {
        PluginManifest m = manifestWithMcp("{ \"broken\": { \"foo\": \"bar\" } }");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        List<MCPServersConfig.MCPServerConfig> servers = adapter.adapt(m, resolver);

        assertTrue(servers.isEmpty());
    }

    @Test
    void testNoMcpServersReturnsEmpty() {
        PluginManifest m = new PluginManifest();
        m.setId("p");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        assertTrue(adapter.adapt(m, resolver).isEmpty());
    }
}
