package io.leavesfly.tinyclaw.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 服务器配置测试
 */
class MCPServersConfigTest {
    
    @Test
    void testDefaultConfig() {
        MCPServersConfig config = new MCPServersConfig();
        
        assertFalse(config.isEnabled());
        assertNotNull(config.getServers());
        assertTrue(config.getServers().isEmpty());
    }
    
    @Test
    void testServerConfig() {
        MCPServersConfig.MCPServerConfig serverConfig = new MCPServersConfig.MCPServerConfig();
        
        serverConfig.setName("test-server");
        serverConfig.setDescription("Test MCP Server");
        serverConfig.setEndpoint("http://localhost:3000/sse");
        serverConfig.setApiKey("test-key");
        serverConfig.setEnabled(true);
        serverConfig.setTimeout(5000);
        
        assertEquals("test-server", serverConfig.getName());
        assertEquals("Test MCP Server", serverConfig.getDescription());
        assertEquals("http://localhost:3000/sse", serverConfig.getEndpoint());
        assertEquals("test-key", serverConfig.getApiKey());
        assertTrue(serverConfig.isEnabled());
        assertEquals(5000, serverConfig.getTimeout());
    }
    
    @Test
    void testDefaultTimeout() {
        MCPServersConfig.MCPServerConfig serverConfig = new MCPServersConfig.MCPServerConfig();
        
        assertEquals(30000, serverConfig.getTimeout());
        assertTrue(serverConfig.isEnabled());
    }
}
