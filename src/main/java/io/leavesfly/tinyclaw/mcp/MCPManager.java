package io.leavesfly.tinyclaw.mcp;

import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.tools.MCPTool;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 管理器
 * 
 * 负责管理所有 MCP 服务器的连接和生命周期
 */
public class MCPManager {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("mcp");
    
    private final MCPServersConfig config;
    private final ToolRegistry toolRegistry;
    private final Map<String, MCPClient> clients;
    private final Map<String, MCPServerInfo> serverInfos;
    
    public MCPManager(MCPServersConfig config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.clients = new ConcurrentHashMap<>();
        this.serverInfos = new ConcurrentHashMap<>();
    }
    
    /**
     * 初始化所有 MCP 服务器连接
     */
    public void initialize() {
        if (config == null || !config.isEnabled()) {
            logger.info("MCP servers disabled");
            return;
        }
        
        List<MCPServersConfig.MCPServerConfig> servers = config.getServers();
        if (servers == null || servers.isEmpty()) {
            logger.info("No MCP servers configured");
            return;
        }
        
        int successCount = 0;
        int failCount = 0;
        
        for (MCPServersConfig.MCPServerConfig serverConfig : servers) {
            if (!serverConfig.isEnabled()) {
                logger.debug("Skipping disabled MCP server", Map.of("name", serverConfig.getName()));
                continue;
            }
            
            try {
                initializeServer(serverConfig);
                successCount++;
            } catch (Exception e) {
                failCount++;
                logger.error("Failed to initialize MCP server", Map.of(
                        "name", serverConfig.getName(),
                        "endpoint", serverConfig.getEndpoint(),
                        "error", e.getMessage()
                ));
            }
        }
        
        logger.info("MCP servers initialized", Map.of(
                "success", successCount,
                "failed", failCount,
                "total", successCount + failCount
        ));
    }
    
    /**
     * 初始化单个 MCP 服务器
     */
    private void initializeServer(MCPServersConfig.MCPServerConfig serverConfig) throws Exception {
        String name = serverConfig.getName();
        String endpoint = serverConfig.getEndpoint();
        String apiKey = serverConfig.getApiKey();
        int timeout = serverConfig.getTimeout();
        
        logger.info("Initializing MCP server", Map.of(
                "name", name,
                "endpoint", endpoint
        ));
        
        // 创建客户端
        MCPClient client = new MCPClient(endpoint, apiKey, timeout);
        
        // 连接到服务器
        client.connect();
        
        // 执行初始化握手
        Map<String, Object> initParams = new HashMap<>();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.put("capabilities", Collections.emptyMap());
        initParams.put("clientInfo", Map.of(
                "name", "TinyClaw",
                "version", "0.1.0"
        ));
        
        MCPMessage initResponse = client.sendRequest("initialize", initParams);
        
        // 解析服务器信息
        MCPServerInfo serverInfo = parseServerInfo(name, initResponse.getResult());
        
        // 发送 initialized 通知
        // 注意: 通知不需要等待响应,这里简化处理
        
        // 获取工具列表
        MCPMessage toolsResponse = client.sendRequest("tools/list", Collections.emptyMap());
        List<MCPServerInfo.ToolInfo> tools = parseTools(toolsResponse.getResult());
        serverInfo.setTools(tools);
        
        // 保存客户端和服务器信息
        clients.put(name, client);
        serverInfos.put(name, serverInfo);
        
        // 创建并注册 MCPTool
        MCPTool mcpTool = new MCPTool(
                name,
                serverConfig.getDescription(),
                client,
                serverInfo
        );
        toolRegistry.register(mcpTool);
        
        logger.info("MCP server initialized successfully", Map.of(
                "name", name,
                "tools_count", tools.size()
        ));
    }
    
    /**
     * 解析服务器信息
     */
    private MCPServerInfo parseServerInfo(String name, Map<String, Object> result) {
        MCPServerInfo info = new MCPServerInfo();
        info.setName(name);
        
        if (result != null) {
            info.setProtocolVersion((String) result.get("protocolVersion"));
            
            @SuppressWarnings("unchecked")
            Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
            if (capabilities != null) {
                info.setCapabilities(capabilities);
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> serverInfoMap = (Map<String, Object>) result.get("serverInfo");
            if (serverInfoMap != null) {
                info.setVersion((String) serverInfoMap.get("version"));
            }
        }
        
        return info;
    }
    
    /**
     * 解析工具列表
     */
    @SuppressWarnings("unchecked")
    private List<MCPServerInfo.ToolInfo> parseTools(Map<String, Object> result) {
        List<MCPServerInfo.ToolInfo> tools = new ArrayList<>();
        
        if (result != null && result.containsKey("tools")) {
            List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");
            
            if (toolsList != null) {
                for (Map<String, Object> toolMap : toolsList) {
                    String name = (String) toolMap.get("name");
                    String description = (String) toolMap.get("description");
                    Map<String, Object> inputSchema = (Map<String, Object>) toolMap.get("inputSchema");
                    
                    MCPServerInfo.ToolInfo toolInfo = new MCPServerInfo.ToolInfo(
                            name,
                            description,
                            inputSchema
                    );
                    tools.add(toolInfo);
                }
            }
        }
        
        return tools;
    }
    
    /**
     * 关闭所有 MCP 服务器连接
     */
    public void shutdown() {
        logger.info("Shutting down MCP servers", Map.of("count", clients.size()));
        
        for (Map.Entry<String, MCPClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            MCPClient client = entry.getValue();
            
            try {
                // 从工具注册表中移除
                toolRegistry.unregister("mcp_" + name);
                
                // 关闭客户端连接
                client.close();
                
                logger.debug("MCP server closed", Map.of("name", name));
                
            } catch (Exception e) {
                logger.error("Failed to close MCP server", Map.of(
                        "name", name,
                        "error", e.getMessage()
                ));
            }
        }
        
        clients.clear();
        serverInfos.clear();
        
        logger.info("All MCP servers shut down");
    }
    
    /**
     * 获取已连接的服务器数量
     */
    public int getConnectedCount() {
        return (int) clients.values().stream()
                .filter(MCPClient::isConnected)
                .count();
    }
    
    /**
     * 获取所有服务器信息
     */
    public Map<String, MCPServerInfo> getServerInfos() {
        return new HashMap<>(serverInfos);
    }
    
    /**
     * 获取指定服务器的客户端
     */
    public Optional<MCPClient> getClient(String name) {
        return Optional.ofNullable(clients.get(name));
    }
    
    /**
     * 检查服务器是否已连接
     */
    public boolean isServerConnected(String name) {
        MCPClient client = clients.get(name);
        return client != null && client.isConnected();
    }
}
