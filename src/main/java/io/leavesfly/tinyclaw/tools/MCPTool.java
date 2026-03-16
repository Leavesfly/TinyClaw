package io.leavesfly.tinyclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPClient;
import io.leavesfly.tinyclaw.mcp.MCPManager;
import io.leavesfly.tinyclaw.mcp.MCPMessage;
import io.leavesfly.tinyclaw.mcp.MCPServerInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 直接工具 — 将 MCP 服务器的每个工具直接注册为独立的 Tool。
 *
 * 将 MCP 服务器的每个工具直接暴露给 LLM，MCPTool 让 LLM
 * 直接看到每个 MCP 工具的名称、描述和参数 schema，无需间接调用。
 *
 * 工具名格式：mcp_{serverName}_{toolName}
 * 例如：mcp_my-mcp-server_search_documents
 */
public class MCPTool implements Tool {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final String registeredName;
    private final MCPServerInfo.ToolInfo toolInfo;
    private volatile MCPClient client;
    private final MCPManager manager;

    public MCPTool(String serverName, MCPServerInfo.ToolInfo toolInfo,
                   MCPClient client, MCPManager manager) {
        this.serverName = serverName;
        this.toolName = toolInfo.getName();
        this.registeredName = "mcp_" + serverName + "_" + toolInfo.getName();
        this.toolInfo = toolInfo;
        this.client = client;
        this.manager = manager;
    }

    @Override
    public String name() {
        return registeredName;
    }

    @Override
    public String description() {
        String desc = toolInfo.getDescription();
        return desc != null && !desc.isEmpty()
                ? desc
                : "MCP tool: " + toolName + " (server: " + serverName + ")";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> inputSchema = toolInfo.getInputSchema();
        if (inputSchema != null && !inputSchema.isEmpty()) {
            return inputSchema;
        }
        // 没有 schema 时返回空 object schema
        Map<String, Object> emptySchema = new HashMap<>();
        emptySchema.put("type", "object");
        emptySchema.put("properties", new HashMap<>());
        return emptySchema;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        // 检查连接状态，如果断开则尝试自动重连
        if (!client.isConnected()) {
            try {
                logger.info("MCP server disconnected, attempting reconnect",
                        Map.of("server", serverName, "tool", toolName));
                MCPClient newClient = manager.reconnect(serverName);
                this.client = newClient;
                logger.info("MCP server reconnected successfully",
                        Map.of("server", serverName));
            } catch (Exception reconnectError) {
                logger.error("MCP server reconnect failed", Map.of(
                        "server", serverName, "error", reconnectError.getMessage()));
                return "Error: MCP server '" + serverName + "' disconnected and reconnect failed: "
                        + reconnectError.getMessage();
            }
        }

        // 构造 MCP tools/call 请求参数
        Map<String, Object> mcpParams = new HashMap<>();
        mcpParams.put("name", toolName);
        if (args != null && !args.isEmpty()) {
            mcpParams.put("arguments", args);
        }

        try {
            logger.info("Calling MCP tool", Map.of(
                    "server", serverName,
                    "tool", toolName
            ));

            MCPMessage response = client.sendRequest("tools/call", mcpParams);

            if (response.getResult() != null) {
                return formatResult(response.getResult());
            } else {
                return "MCP tool call succeeded but returned no result";
            }

        } catch (MCPClient.MCPException e) {
            logger.error("MCP tool call failed", Map.of(
                    "server", serverName, "tool", toolName, "error", e.getMessage()));
            return "Error: MCP tool call failed - " + e.getMessage();
        } catch (Exception e) {
            logger.error("MCP tool call error", Map.of(
                    "server", serverName, "tool", toolName, "error", e.getMessage()));
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 更新底层 MCPClient 引用（重连后调用）
     */
    public void updateClient(MCPClient newClient) {
        this.client = newClient;
    }

    /**
     * 获取所属的 MCP 服务器名称
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * 获取 MCP 工具名称
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 格式化 MCP 工具调用结果
     */
    @SuppressWarnings("unchecked")
    private String formatResult(Map<String, Object> result) {
        try {
            if (result.containsKey("content")) {
                Object content = result.get("content");
                if (content instanceof List) {
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
                    StringBuilder resultBuilder = new StringBuilder();
                    for (Map<String, Object> item : contentList) {
                        String type = (String) item.get("type");
                        if ("text".equals(type)) {
                            resultBuilder.append(item.get("text")).append("\n");
                        } else {
                            resultBuilder.append("[").append(type).append("]\n");
                        }
                    }
                    return resultBuilder.toString().trim();
                } else {
                    return String.valueOf(content);
                }
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.warn("Failed to format result", Map.of("error", e.getMessage()));
            return result.toString();
        }
    }
}
