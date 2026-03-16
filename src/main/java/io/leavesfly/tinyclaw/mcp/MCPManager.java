package io.leavesfly.tinyclaw.mcp;

import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.tools.MCPTool;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.io.IOException;
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
    private final Map<String, MCPServersConfig.MCPServerConfig> serverConfigs;
    
    public MCPManager(MCPServersConfig config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.clients = new ConcurrentHashMap<>();
        this.serverInfos = new ConcurrentHashMap<>();
        this.serverConfigs = new ConcurrentHashMap<>();
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
        int timeout = serverConfig.getTimeout();
        
        // 按传输类型创建客户端
        MCPClient client = createClient(serverConfig);
        
        String transportType = serverConfig.isStdio() ? "stdio"
                : serverConfig.isStreamableHttp() ? "streamable-http" : "sse";
        logger.info("Initializing MCP server", Map.of(
                "name", name,
                "type", transportType
        ));
        
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
        
        // 发送 initialized 通知（MCP 协议要求在 initialize 响应后发送）
        client.sendNotification("notifications/initialized", Collections.emptyMap());
        
        // 获取工具列表
        MCPMessage toolsResponse = client.sendRequest("tools/list", Collections.emptyMap());
        List<MCPServerInfo.ToolInfo> tools = parseTools(toolsResponse.getResult());
        serverInfo.setTools(tools);
        
        // 保存客户端和服务器信息
        clients.put(name, client);
        serverInfos.put(name, serverInfo);
        
        // 保存原始配置（用于重连）
        serverConfigs.put(name, serverConfig);
        
        // 将每个 MCP 工具直接注册为独立的 Tool，让 LLM 直接调用
        for (MCPServerInfo.ToolInfo toolInfo : tools) {
            MCPTool directTool = new MCPTool(name, toolInfo, client, this);
            toolRegistry.register(directTool);
            logger.debug("Registered MCP tool", Map.of(
                    "server", name,
                    "tool", directTool.name()
            ));
        }
        
        logger.info("MCP server initialized successfully", Map.of(
                "name", name,
                "tools_count", tools.size()
        ));
    }
    
    /**
     * 根据配置类型创建对应的 MCPClient 实例
     */
    private MCPClient createClient(MCPServersConfig.MCPServerConfig serverConfig) throws IOException {
        if (serverConfig.isStdio()) {
            String command = serverConfig.getCommand();
            if (command == null || command.isEmpty()) {
                throw new IOException("Stdio MCP server '" + serverConfig.getName() + "' requires 'command' configuration");
            }
            return new StdioMCPClient(
                    command,
                    serverConfig.getArgs(),
                    serverConfig.getEnv(),
                    serverConfig.getTimeout()
            );
        } else if (serverConfig.isStreamableHttp()) {
            String endpoint = serverConfig.getEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                throw new IOException("Streamable HTTP MCP server '" + serverConfig.getName() + "' requires 'endpoint' configuration");
            }
            return new StreamableHttpMCPClient(
                    endpoint,
                    serverConfig.getApiKey(),
                    serverConfig.getTimeout()
            );
        } else {
            String endpoint = serverConfig.getEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                throw new IOException("SSE MCP server '" + serverConfig.getName() + "' requires 'endpoint' configuration");
            }
            return new SSEMCPClient(
                    endpoint,
                    serverConfig.getApiKey(),
                    serverConfig.getTimeout()
            );
        }
    }
    
    /**
     * 重新连接指定名称的 MCP 服务器（由 MCPTool 在检测到断连时调用）
     *
     * @return 重连后的新 MCPClient 实例
     */
    public MCPClient reconnect(String name) throws Exception {
        MCPServersConfig.MCPServerConfig serverConfig = serverConfigs.get(name);
        if (serverConfig == null) {
            throw new IOException("No configuration found for MCP server: " + name);
        }
        
        // 关闭旧客户端
        MCPClient oldClient = clients.remove(name);
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (Exception ignored) {
            }
        }
        
        logger.info("Reconnecting MCP server", Map.of("name", name));
        
        // 创建新客户端并重新握手
        MCPClient newClient = createClient(serverConfig);
        newClient.connect();
        
        // 重新执行初始化握手
        Map<String, Object> initParams = new HashMap<>();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.put("capabilities", Collections.emptyMap());
        initParams.put("clientInfo", Map.of(
                "name", "TinyClaw",
                "version", "0.1.0"
        ));
        
        MCPMessage initResponse = newClient.sendRequest("initialize", initParams);
        MCPServerInfo serverInfo = parseServerInfo(name, initResponse.getResult());
        
        newClient.sendNotification("notifications/initialized", Collections.emptyMap());
        
        MCPMessage toolsResponse = newClient.sendRequest("tools/list", Collections.emptyMap());
        List<MCPServerInfo.ToolInfo> tools = parseTools(toolsResponse.getResult());
        serverInfo.setTools(tools);
        
        clients.put(name, newClient);
        serverInfos.put(name, serverInfo);
        
        // 更新所有已注册的 MCPTool 的 client 引用
        for (MCPServerInfo.ToolInfo toolInfo : tools) {
            String registeredName = "mcp_" + name + "_" + toolInfo.getName();
            toolRegistry.get(registeredName).ifPresent(tool -> {
                if (tool instanceof MCPTool directTool) {
                    directTool.updateClient(newClient);
                }
            });
        }
        
        logger.info("MCP server reconnected successfully", Map.of(
                "name", name,
                "tools_count", tools.size()
        ));
        
        return newClient;
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
                // 从工具注册表中移除该服务器的所有工具
                MCPServerInfo info = serverInfos.get(name);
                if (info != null && info.getTools() != null) {
                    for (MCPServerInfo.ToolInfo toolInfo : info.getTools()) {
                        toolRegistry.unregister("mcp_" + name + "_" + toolInfo.getName());
                    }
                }
                
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
