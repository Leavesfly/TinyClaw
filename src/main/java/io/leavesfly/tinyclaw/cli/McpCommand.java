package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.mcp.MCPClient;
import io.leavesfly.tinyclaw.mcp.MCPManager;
import io.leavesfly.tinyclaw.mcp.MCPMessage;
import io.leavesfly.tinyclaw.mcp.MCPServerInfo;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP 管理命令
 * 
 * 提供 MCP 服务器的管理和测试功能
 */
public class McpCommand extends CliCommand {
    
    @Override
    public String name() {
        return "mcp";
    }
    
    @Override
    public String description() {
        return "管理 MCP 服务器连接";
    }
    
    @Override
    public int execute(String[] args) {
        if (args.length < 2) {
            printHelp();
            return 1;
        }
        
        String subCommand = args[1];
        
        try {
            switch (subCommand) {
                case "list":
                    listServers();
                    return 0;
                case "test":
                    if (args.length < 3) {
                        System.out.println("用法: tinyclaw mcp test <server-name>");
                        return 1;
                    }
                    testServer(args[2]);
                    return 0;
                case "tools":
                    if (args.length < 3) {
                        System.out.println("用法: tinyclaw mcp tools <server-name>");
                        return 1;
                    }
                    listTools(args[2]);
                    return 0;
                default:
                    System.out.println("未知的子命令: " + subCommand);
                    printHelp();
                    return 1;
            }
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            logger.error("MCP command failed", Map.of("error", e.getMessage()));
            return 1;
        }
    }
    
    /**
     * 列出所有 MCP 服务器
     */
    private void listServers() {
        try {
            Config config = ConfigLoader.load();
            MCPServersConfig mcpConfig = config.getMcpServers();
            
            if (mcpConfig == null || !mcpConfig.isEnabled()) {
                System.out.println("MCP 服务器功能未启用");
                return;
            }
            
            if (mcpConfig.getServers().isEmpty()) {
                System.out.println("未配置任何 MCP 服务器");
                return;
            }
            
            System.out.println("已配置的 MCP 服务器:\n");
            
            for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
                String status = server.isEnabled() ? "✓ 已启用" : "✗ 已禁用";
                System.out.println("名称: " + server.getName());
                System.out.println("  状态: " + status);
                System.out.println("  描述: " + server.getDescription());
                System.out.println("  端点: " + server.getEndpoint());
                System.out.println("  超时: " + server.getTimeout() + "ms");
                System.out.println();
            }
            
        } catch (Exception e) {
            System.err.println("读取配置失败: " + e.getMessage());
            logger.error("Failed to list MCP servers", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 测试 MCP 服务器连接
     */
    private void testServer(String name) {
        System.out.println("正在测试 MCP 服务器: " + name + "...\n");
        
        try {
            Config config = ConfigLoader.load();
            MCPServersConfig mcpConfig = config.getMcpServers();
            
            if (mcpConfig == null || !mcpConfig.isEnabled()) {
                System.err.println("MCP 服务器功能未启用");
                return;
            }
            
            // 查找指定的服务器配置
            MCPServersConfig.MCPServerConfig serverConfig = null;
            for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
                if (server.getName().equals(name)) {
                    serverConfig = server;
                    break;
                }
            }
            
            if (serverConfig == null) {
                System.err.println("未找到服务器配置: " + name);
                return;
            }
            
            if (!serverConfig.isEnabled()) {
                System.out.println("⚠️  服务器已禁用");
            }
            
            // 创建客户端并连接
            MCPClient client = new MCPClient(
                    serverConfig.getEndpoint(),
                    serverConfig.getApiKey(),
                    serverConfig.getTimeout()
            );
            
            System.out.println("1. 连接到服务器...");
            client.connect();
            System.out.println("   ✓ 连接成功");
            
            // 执行初始化握手
            System.out.println("\n2. 执行初始化握手...");
            Map<String, Object> initParams = new HashMap<>();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.put("capabilities", Collections.emptyMap());
            initParams.put("clientInfo", Map.of(
                    "name", "TinyClaw",
                    "version", "0.1.0"
            ));
            
            MCPMessage initResponse = client.sendRequest("initialize", initParams);
            System.out.println("   ✓ 初始化成功");
            
            // 显示服务器信息
            if (initResponse.getResult() != null) {
                Map<String, Object> result = initResponse.getResult();
                System.out.println("\n3. 服务器信息:");
                
                String protocolVersion = (String) result.get("protocolVersion");
                if (protocolVersion != null) {
                    System.out.println("   协议版本: " + protocolVersion);
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
                if (serverInfo != null) {
                    String serverName = (String) serverInfo.get("name");
                    String serverVersion = (String) serverInfo.get("version");
                    if (serverName != null) {
                        System.out.println("   服务器名称: " + serverName);
                    }
                    if (serverVersion != null) {
                        System.out.println("   服务器版本: " + serverVersion);
                    }
                }
            }
            
            // 获取工具列表
            System.out.println("\n4. 获取工具列表...");
            MCPMessage toolsResponse = client.sendRequest("tools/list", Collections.emptyMap());
            
            if (toolsResponse.getResult() != null) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> tools = 
                        (java.util.List<Map<String, Object>>) toolsResponse.getResult().get("tools");
                
                if (tools != null && !tools.isEmpty()) {
                    System.out.println("   ✓ 找到 " + tools.size() + " 个工具");
                    System.out.println("\n   工具列表:");
                    for (Map<String, Object> tool : tools) {
                        String toolName = (String) tool.get("name");
                        String toolDesc = (String) tool.get("description");
                        System.out.println("   - " + toolName);
                        if (toolDesc != null && !toolDesc.isEmpty()) {
                            System.out.println("     " + toolDesc);
                        }
                    }
                } else {
                    System.out.println("   该服务器未提供任何工具");
                }
            }
            
            // 关闭连接
            client.close();
            System.out.println("\n✓ 测试完成");
            
        } catch (Exception e) {
            System.err.println("\n✗ 测试失败: " + e.getMessage());
            logger.error("MCP server test failed", Map.of(
                    "server", name,
                    "error", e.getMessage()
            ));
        }
    }
    
    /**
     * 列出服务器提供的工具
     */
    private void listTools(String name) {
        try {
            Config config = ConfigLoader.load();
            MCPServersConfig mcpConfig = config.getMcpServers();
            
            if (mcpConfig == null || !mcpConfig.isEnabled()) {
                System.err.println("MCP 服务器功能未启用");
                return;
            }
            
            // 临时创建 MCPManager 来获取工具列表
            ToolRegistry tempRegistry = new ToolRegistry();
            MCPManager manager = new MCPManager(mcpConfig, tempRegistry);
            manager.initialize();
            
            MCPServerInfo serverInfo = manager.getServerInfos().get(name);
            
            if (serverInfo == null) {
                System.err.println("未找到服务器或连接失败: " + name);
                manager.shutdown();
                return;
            }
            
            System.out.println("MCP 服务器 '" + name + "' 提供的工具:\n");
            
            if (serverInfo.getTools().isEmpty()) {
                System.out.println("该服务器未提供任何工具");
            } else {
                for (MCPServerInfo.ToolInfo tool : serverInfo.getTools()) {
                    System.out.println("工具名称: " + tool.getName());
                    System.out.println("  描述: " + (tool.getDescription() != null ? tool.getDescription() : "无描述"));
                    
                    if (tool.getInputSchema() != null) {
                        System.out.println("  参数:");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> properties = (Map<String, Object>) tool.getInputSchema().get("properties");
                        if (properties != null) {
                            for (String paramName : properties.keySet()) {
                                System.out.println("    - " + paramName);
                            }
                        }
                    }
                    
                    System.out.println();
                }
            }
            
            manager.shutdown();
            
        } catch (Exception e) {
            System.err.println("获取工具列表失败: " + e.getMessage());
            logger.error("Failed to list MCP tools", Map.of(
                    "server", name,
                    "error", e.getMessage()
            ));
        }
    }
}
