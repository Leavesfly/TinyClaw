package io.leavesfly.tinyclaw.mcp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 消息测试
 */
class MCPMessageTest {
    
    @Test
    void testCreateRequest() {
        Map<String, Object> params = new HashMap<>();
        params.put("test", "value");
        
        MCPMessage message = MCPMessage.createRequest("123", "test/method", params);
        
        assertNotNull(message);
        assertEquals("2.0", message.getJsonrpc());
        assertEquals("123", message.getId());
        assertEquals("test/method", message.getMethod());
        assertEquals(params, message.getParams());
        assertTrue(message.isRequest());
        assertFalse(message.isResponse());
        assertFalse(message.isNotification());
    }
    
    @Test
    void testCreateNotification() {
        Map<String, Object> params = new HashMap<>();
        params.put("event", "data");
        
        MCPMessage message = MCPMessage.createNotification("notification/event", params);
        
        assertNotNull(message);
        assertNull(message.getId());
        assertEquals("notification/event", message.getMethod());
        assertTrue(message.isNotification());
        assertFalse(message.isRequest());
        assertFalse(message.isResponse());
    }
    
    @Test
    void testCreateResponse() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        
        MCPMessage message = MCPMessage.createResponse("456", result);
        
        assertNotNull(message);
        assertEquals("456", message.getId());
        assertEquals(result, message.getResult());
        assertTrue(message.isResponse());
        assertFalse(message.isRequest());
        assertFalse(message.isError());
    }
    
    @Test
    void testCreateErrorResponse() {
        MCPMessage message = MCPMessage.createErrorResponse("789", -1, "Test error");
        
        assertNotNull(message);
        assertEquals("789", message.getId());
        assertNotNull(message.getError());
        assertEquals(-1, message.getError().getCode());
        assertEquals("Test error", message.getError().getMessage());
        assertTrue(message.isError());
        assertTrue(message.isResponse());
    }
}
