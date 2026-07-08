package io.leavesfly.tinyclaw.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import io.leavesfly.tinyclaw.config.MCPServersConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 组件适配器。
 *
 * <p>把插件清单中归一化的 {@code mcpServers}（serverName→config）转换为 TinyClaw 的
 * {@link MCPServersConfig.MCPServerConfig} 列表，交由现有 {@code MCPManager} 连接。
 * 这是插件运行时能力兼容的核心路径，不新增任何协议。</p>
 *
 * <p>server 命名空间化为 {@code plugin:<pluginId>:<serverName>}，与用户 MCP 隔离。
 * 命令、参数、环境变量中的 {@code ${CLAUDE_PLUGIN_ROOT}} 等变量会被 {@link VariableResolver} 替换。</p>
 */
public class McpComponentAdapter {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    /**
     * 将插件的 mcpServers 转换为 MCPServerConfig 列表。
     *
     * @param manifest 插件清单（含归一化 mcpServers 节点）
     * @param resolver 变量替换器
     * @return 转换后的 server 配置列表（可能为空）
     */
    public List<MCPServersConfig.MCPServerConfig> adapt(PluginManifest manifest, VariableResolver resolver) {
        List<MCPServersConfig.MCPServerConfig> result = new ArrayList<>();
        if (manifest == null || !manifest.hasMcpServers()) {
            return result;
        }

        JsonNode servers = manifest.getMcpServers();
        servers.fieldNames().forEachRemaining(serverKey -> {
            JsonNode node = servers.get(serverKey);
            if (node == null || !node.isObject()) {
                return;
            }
            try {
                MCPServersConfig.MCPServerConfig cfg = convert(manifest.getId(), serverKey, node, resolver);
                if (cfg != null) {
                    result.add(cfg);
                }
            } catch (Exception e) {
                logger.warn("转换插件 MCP server 失败: " + serverKey + " - " + e.getMessage());
            }
        });
        return result;
    }

    /**
     * 转换单个 MCP server 节点。
     */
    private MCPServersConfig.MCPServerConfig convert(String pluginId, String serverKey,
                                                     JsonNode node, VariableResolver resolver) {
        MCPServersConfig.MCPServerConfig cfg = new MCPServersConfig.MCPServerConfig();
        cfg.setName("plugin:" + pluginId + ":" + serverKey);
        cfg.setDescription("Plugin " + pluginId + " MCP server: " + serverKey);
        cfg.setEnabled(true);

        String command = text(node, "command");
        String url = firstText(node, "url", "endpoint");
        String declaredType = text(node, "type");

        if (command != null && !command.isEmpty()) {
            // stdio 传输
            cfg.setType("stdio");
            cfg.setCommand(resolver.resolve(command));
            cfg.setArgs(resolveArgs(node.get("args"), resolver));
            cfg.setEnv(resolveEnv(node.get("env"), resolver));
        } else if (url != null && !url.isEmpty()) {
            // http / sse 传输
            cfg.setType("streamable-http".equalsIgnoreCase(declaredType)
                    || "http".equalsIgnoreCase(declaredType) ? "streamable-http" : "sse");
            cfg.setEndpoint(resolver.resolve(url));
            String apiKey = firstText(node, "apiKey", "api_key");
            if (apiKey != null) {
                cfg.setApiKey(resolver.resolve(apiKey));
            }
        } else {
            logger.warn("插件 MCP server 缺少 command 或 url，已跳过: " + serverKey);
            return null;
        }
        return cfg;
    }

    private List<String> resolveArgs(JsonNode argsNode, VariableResolver resolver) {
        List<String> args = new ArrayList<>();
        if (argsNode != null && argsNode.isArray()) {
            for (JsonNode item : argsNode) {
                args.add(resolver.resolve(item.asText()));
            }
        }
        return args;
    }

    private Map<String, String> resolveEnv(JsonNode envNode, VariableResolver resolver) {
        Map<String, String> env = new LinkedHashMap<>();
        if (envNode != null && envNode.isObject()) {
            envNode.fieldNames().forEachRemaining(k ->
                    env.put(k, resolver.resolve(envNode.get(k).asText())));
        }
        return env;
    }

    private String text(JsonNode node, String field) {
        return node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
