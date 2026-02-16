package io.leavesfly.tinyclaw.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPClient;
import io.leavesfly.tinyclaw.mcp.MCPMessage;
import io.leavesfly.tinyclaw.mcp.MCPServerInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 工具
 * 
 * 将单个 MCP 服务器封装为一个统一的工具
 * LLM 通过指定 tool_name 和 tool_params 来调用 MCP 服务器提供的具体工具
 */
public class MCPTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String serverName;
    private final String serverDescription;
    private final MCPClient client;
    private final MCPServerInfo serverInfo;
    
    public MCPTool(String serverName, String serverDescription, MCPClient client, MCPServerInfo serverInfo) {
        this.serverName = serverName;
        this.serverDescription = serverDescription;
        this.client = client;
        this.serverInfo = serverInfo;
    }
    
    @Override
    public String name() {
        return "mcp_" + serverName;
    }
    
    @Override
    public String description() {
        StringBuilder desc = new StringBuilder();
        desc.append(serverDescription);
        
        // 添加可用工具列表
        if (serverInfo != null && !serverInfo.getTools().isEmpty()) {
            desc.append("\n\n可用工具:\n");
            for (MCPServerInfo.ToolInfo tool : serverInfo.getTools()) {
                desc.append("- ").append(tool.getName());
                if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
                    desc.append(": ").append(tool.getDescription());
                }
                desc.append("\n");
            }
        }
        
        return desc.toString();
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        // tool_name 参数
        Map<String, Object> toolNameProp = new HashMap<>();
        toolNameProp.put("type", "string");
        toolNameProp.put("description", "要调用的 MCP 工具名称");
        
        // 添加可选的枚举值(工具名称列表)
        if (serverInfo != null && !serverInfo.getTools().isEmpty()) {
            List<String> toolNames = serverInfo.getTools().stream()
                    .map(MCPServerInfo.ToolInfo::getName)
                    .collect(Collectors.toList());
            toolNameProp.put("enum", toolNames);
        }
        
        properties.put("tool_name", toolNameProp);
        
        // tool_params 参数
        Map<String, Object> toolParamsProp = new HashMap<>();
        toolParamsProp.put("type", "object");
        toolParamsProp.put("description", "工具参数 (JSON 对象)");
        properties.put("tool_params", toolParamsProp);
        
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("tool_name", "tool_params"));
        
        return schema;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // 检查连接状态
        if (!client.isConnected()) {
            return "错误: MCP 服务器 '" + serverName + "' 未连接";
        }
        
        // 解析参数
        String toolName = (String) args.get("tool_name");
        Object toolParamsObj = args.get("tool_params");
        
        if (toolName == null || toolName.isEmpty()) {
            return "错误: 缺少必需参数 'tool_name'";
        }
        
        // 验证工具是否存在
        if (serverInfo != null && !isToolAvailable(toolName)) {
            return "错误: 工具 '" + toolName + "' 在 MCP 服务器 '" + serverName + "' 上不可用";
        }
        
        // 构造 MCP 请求参数
        Map<String, Object> mcpParams = new HashMap<>();
        mcpParams.put("name", toolName);
        
        // 转换 tool_params
        Map<String, Object> toolParams = convertToMap(toolParamsObj);
        if (toolParams != null && !toolParams.isEmpty()) {
            mcpParams.put("arguments", toolParams);
        }
        
        try {
            logger.info("Calling MCP tool", Map.of(
                    "server", serverName,
                    "tool", toolName
            ));
            
            // 调用 MCP 服务器的 tools/call 方法
            MCPMessage response = client.sendRequest("tools/call", mcpParams);
            
            // 处理响应
            if (response.getResult() != null) {
                return formatResult(response.getResult());
            } else {
                return "MCP 工具调用成功,但没有返回结果";
            }
            
        } catch (MCPClient.MCPException e) {
            logger.error("MCP tool call failed", Map.of(
                    "server", serverName,
                    "tool", toolName,
                    "error", e.getMessage()
            ));
            return "错误: MCP 工具调用失败 - " + e.getMessage();
        } catch (Exception e) {
            logger.error("MCP tool call error", Map.of(
                    "server", serverName,
                    "tool", toolName,
                    "error", e.getMessage()
            ));
            return "错误: " + e.getMessage();
        }
    }
    
    /**
     * 检查工具是否可用
     */
    private boolean isToolAvailable(String toolName) {
        return serverInfo.getTools().stream()
                .anyMatch(tool -> tool.getName().equals(toolName));
    }
    
    /**
     * 转换参数为 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(Object obj) {
        if (obj == null) {
            return new HashMap<>();
        }
        
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        
        // 尝试通过 JSON 转换
        try {
            String json = objectMapper.writeValueAsString(obj);
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("Failed to convert tool params", Map.of("error", e.getMessage()));
            return new HashMap<>();
        }
    }
    
    /**
     * 格式化返回结果
     */
    private String formatResult(Map<String, Object> result) {
        try {
            // 检查是否有 content 字段
            if (result.containsKey("content")) {
                Object content = result.get("content");
                if (content instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
                    StringBuilder sb = new StringBuilder();
                    
                    for (Map<String, Object> item : contentList) {
                        String type = (String) item.get("type");
                        if ("text".equals(type)) {
                            sb.append(item.get("text")).append("\n");
                        } else {
                            sb.append("[").append(type).append("]\n");
                        }
                    }
                    
                    return sb.toString().trim();
                } else {
                    return String.valueOf(content);
                }
            }
            
            // 否则返回整个结果的 JSON 表示
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            
        } catch (Exception e) {
            logger.warn("Failed to format result", Map.of("error", e.getMessage()));
            return result.toString();
        }
    }
    
    /**
     * 获取服务器信息
     */
    public MCPServerInfo getServerInfo() {
        return serverInfo;
    }
}
