package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.mcp.MCPClient;
import io.leavesfly.tinyclaw.mcp.SSEMCPClient;
import io.leavesfly.tinyclaw.mcp.StdioMCPClient;
import io.leavesfly.tinyclaw.mcp.MCPManager;
import io.leavesfly.tinyclaw.mcp.MCPMessage;
import io.leavesfly.tinyclaw.mcp.MCPServerInfo;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP 管理命令
 *
 * 提供 MCP 服务器的管理和测试功能，支持子命令：
 * - list：列出所有已配置的 MCP 服务器
 * - test：测试指定服务器的连接和握手
 * - tools：列出指定服务器提供的工具
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
            return switch (subCommand) {
                case "list" -> {
                    listServers();
                    yield 0;
                }
                case "test" -> executeWithServerName(args, this::testServer);
                case "tools" -> executeWithServerName(args, this::listTools);
                default -> {
                    System.out.println("未知的子命令: " + subCommand);
                    printHelp();
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            logger.error("MCP command failed", Map.of("error", e.getMessage()));
            return 1;
        }
    }

    /**
     * 校验参数并执行需要 server-name 的子命令
     */
    private int executeWithServerName(String[] args, ServerNameAction action) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: tinyclaw mcp " + args[1] + " <server-name>");
            return 1;
        }
        action.run(args[2]);
        return 0;
    }

    @FunctionalInterface
    private interface ServerNameAction {
        void run(String serverName) throws Exception;
    }

    // ── 公共配置加载 ──────────────────────────────────────────

    /**
     * 加载并校验 MCP 配置，返回已启用的 MCPServersConfig
     *
     * @return MCPServersConfig，加载失败或未启用时返回 null 并打印提示
     */
    private MCPServersConfig loadMcpConfig() {
        Config config = loadConfig();
        if (config == null) {
            return null;
        }

        MCPServersConfig mcpConfig = config.getMcpServers();
        if (mcpConfig == null || !mcpConfig.isEnabled()) {
            System.err.println("MCP 服务器功能未启用");
            return null;
        }
        return mcpConfig;
    }

    /**
     * 从配置中查找指定名称的服务器
     *
     * @return 匹配的服务器配置，未找到时返回 null 并打印提示
     */
    private MCPServersConfig.MCPServerConfig findServerConfig(MCPServersConfig mcpConfig, String name) {
        return mcpConfig.getServers().stream()
                .filter(server -> server.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    System.err.println("未找到服务器配置: " + name);
                    return null;
                });
    }

    // ── list 子命令 ──────────────────────────────────────────

    private void listServers() {
        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        if (mcpConfig.getServers().isEmpty()) {
            System.out.println("未配置任何 MCP 服务器");
            return;
        }

        System.out.println("已配置的 MCP 服务器:\n");

        for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
            String status = server.isEnabled() ? "✓ 已启用" : "✗ 已禁用";
            String type = server.isStdio() ? "stdio" : "sse";
            System.out.println("名称: " + server.getName());
            System.out.println("  状态: " + status);
            System.out.println("  类型: " + type);
            System.out.println("  描述: " + server.getDescription());
            if (server.isStdio()) {
                System.out.println("  命令: " + server.getCommand());
                if (server.getArgs() != null && !server.getArgs().isEmpty()) {
                    System.out.println("  参数: " + String.join(" ", server.getArgs()));
                }
            } else {
                System.out.println("  端点: " + server.getEndpoint());
            }
            System.out.println("  超时: " + server.getTimeout() + "ms");
            System.out.println();
        }
    }

    // ── test 子命令 ──────────────────────────────────────────

    private void testServer(String name) throws Exception {
        System.out.println("正在测试 MCP 服务器: " + name + "...\n");

        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        MCPServersConfig.MCPServerConfig serverConfig = findServerConfig(mcpConfig, name);
        if (serverConfig == null) {
            return;
        }

        if (!serverConfig.isEnabled()) {
            System.out.println("⚠️  服务器已禁用");
        }

        MCPClient client;
        if (serverConfig.isStdio()) {
            client = new StdioMCPClient(
                    serverConfig.getCommand(),
                    serverConfig.getArgs(),
                    serverConfig.getEnv(),
                    serverConfig.getTimeout()
            );
        } else {
            client = new SSEMCPClient(
                    serverConfig.getEndpoint(),
                    serverConfig.getApiKey(),
                    serverConfig.getTimeout()
            );
        }

        try {
            connectAndInitialize(client);
            fetchAndPrintTools(client);
            System.out.println("\n✓ 测试完成");
        } finally {
            client.close();
        }
    }

    /**
     * 连接服务器并执行初始化握手，打印服务器信息
     */
    private void connectAndInitialize(MCPClient client) throws Exception {
        System.out.println("1. 连接到服务器...");
        client.connect();
        System.out.println("   ✓ 连接成功");

        System.out.println("\n2. 执行初始化握手...");
        Map<String, Object> initParams = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Collections.emptyMap(),
                "clientInfo", Map.of("name", "TinyClaw", "version", VERSION)
        );

        MCPMessage initResponse = client.sendRequest("initialize", initParams);
        System.out.println("   ✓ 初始化成功");

        // 发送 initialized 通知（MCP 协议要求）
        client.sendNotification("notifications/initialized", Collections.emptyMap());

        printServerInfo(initResponse);
    }

    /**
     * 从初始化响应中提取并打印服务器信息
     */
    private void printServerInfo(MCPMessage initResponse) {
        if (initResponse.getResult() == null) {
            return;
        }

        Map<String, Object> result = initResponse.getResult();
        System.out.println("\n3. 服务器信息:");

        String protocolVersion = (String) result.get("protocolVersion");
        if (protocolVersion != null) {
            System.out.println("   协议版本: " + protocolVersion);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        if (serverInfo != null) {
            printIfPresent("   服务器名称: ", (String) serverInfo.get("name"));
            printIfPresent("   服务器版本: ", (String) serverInfo.get("version"));
        }
    }

    /**
     * 获取远程工具列表并打印
     */
    private void fetchAndPrintTools(MCPClient client) throws Exception {
        System.out.println("\n4. 获取工具列表...");
        MCPMessage toolsResponse = client.sendRequest("tools/list", Collections.emptyMap());

        if (toolsResponse.getResult() == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) toolsResponse.getResult().get("tools");

        if (tools == null || tools.isEmpty()) {
            System.out.println("   该服务器未提供任何工具");
            return;
        }

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
    }

    // ── tools 子命令 ─────────────────────────────────────────

    private void listTools(String name) throws Exception {
        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        ToolRegistry tempRegistry = new ToolRegistry();
        MCPManager manager = new MCPManager(mcpConfig, tempRegistry);

        try {
            manager.initialize();

            MCPServerInfo serverInfo = manager.getServerInfos().get(name);
            if (serverInfo == null) {
                System.err.println("未找到服务器或连接失败: " + name);
                return;
            }

            System.out.println("MCP 服务器 '" + name + "' 提供的工具:\n");

            if (serverInfo.getTools().isEmpty()) {
                System.out.println("该服务器未提供任何工具");
                return;
            }

            for (MCPServerInfo.ToolInfo tool : serverInfo.getTools()) {
                System.out.println("工具名称: " + tool.getName());
                System.out.println("  描述: " + (tool.getDescription() != null ? tool.getDescription() : "无描述"));
                printToolParameters(tool);
                System.out.println();
            }
        } finally {
            manager.shutdown();
        }
    }

    /**
     * 打印工具的参数列表
     */
    private void printToolParameters(MCPServerInfo.ToolInfo tool) {
        if (tool.getInputSchema() == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getInputSchema().get("properties");
        if (properties == null || properties.isEmpty()) {
            return;
        }

        System.out.println("  参数:");
        for (String paramName : properties.keySet()) {
            System.out.println("    - " + paramName);
        }
    }

    // ── 工具方法 ─────────────────────────────────────────────

    private void printIfPresent(String label, String value) {
        if (value != null) {
            System.out.println(label + value);
        }
    }
}
