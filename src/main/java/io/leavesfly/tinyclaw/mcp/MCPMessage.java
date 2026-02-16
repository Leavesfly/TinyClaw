package io.leavesfly.tinyclaw.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * MCP 协议消息定义
 * 
 * 实现 JSON-RPC 2.0 格式的 MCP 协议消息
 * 
 * 支持的消息类型:
 * - Request: 客户端发送的请求消息
 * - Response: 服务器返回的响应消息
 * - Notification: 通知消息(无需响应)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MCPMessage {
    
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("method")
    private String method;
    
    @JsonProperty("params")
    private Map<String, Object> params;
    
    @JsonProperty("result")
    private Map<String, Object> result;
    
    @JsonProperty("error")
    private MCPError error;
    
    public MCPMessage() {
    }
    
    /**
     * 创建请求消息
     */
    public static MCPMessage createRequest(String id, String method, Map<String, Object> params) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.method = method;
        msg.params = params;
        return msg;
    }
    
    /**
     * 创建通知消息(无 id)
     */
    public static MCPMessage createNotification(String method, Map<String, Object> params) {
        MCPMessage msg = new MCPMessage();
        msg.method = method;
        msg.params = params;
        return msg;
    }
    
    /**
     * 创建响应消息
     */
    public static MCPMessage createResponse(String id, Map<String, Object> result) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.result = result;
        return msg;
    }
    
    /**
     * 创建错误响应消息
     */
    public static MCPMessage createErrorResponse(String id, int code, String message) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.error = new MCPError(code, message);
        return msg;
    }
    
    /**
     * 判断是否为请求消息
     */
    public boolean isRequest() {
        return id != null && method != null;
    }
    
    /**
     * 判断是否为响应消息
     */
    public boolean isResponse() {
        return id != null && method == null;
    }
    
    /**
     * 判断是否为通知消息
     */
    public boolean isNotification() {
        return id == null && method != null;
    }
    
    /**
     * 判断是否为错误响应
     */
    public boolean isError() {
        return error != null;
    }
    
    // Getters and Setters
    
    public String getJsonrpc() {
        return jsonrpc;
    }
    
    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getMethod() {
        return method;
    }
    
    public void setMethod(String method) {
        this.method = method;
    }
    
    public Map<String, Object> getParams() {
        return params;
    }
    
    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
    
    public Map<String, Object> getResult() {
        return result;
    }
    
    public void setResult(Map<String, Object> result) {
        this.result = result;
    }
    
    public MCPError getError() {
        return error;
    }
    
    public void setError(MCPError error) {
        this.error = error;
    }
    
    /**
     * MCP 错误对象
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MCPError {
        @JsonProperty("code")
        private int code;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("data")
        private Object data;
        
        public MCPError() {
        }
        
        public MCPError(int code, String message) {
            this.code = code;
            this.message = message;
        }
        
        public int getCode() {
            return code;
        }
        
        public void setCode(int code) {
            this.code = code;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public Object getData() {
            return data;
        }
        
        public void setData(Object data) {
            this.data = data;
        }
    }
}
