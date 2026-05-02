package io.leavesfly.tinyclaw.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Hook 配置加载器：从 JSON 文件构建 {@link HookRegistry}。
 *
 * <h3>默认配置路径</h3>
 * <p>{@code ~/.tinyclaw/hooks.json}；文件不存在时返回 {@link HookRegistry#EMPTY}，完全零开销。</p>
 *
 * <h3>配置文件格式</h3>
 * <pre>
 * {
 *   "hooks": {
 *     "PreToolUse": [
 *       {
 *         "matcher": "exec",
 *         "hooks": [
 *           { "type": "command", "command": "/abs/path/block-rm.sh", "timeoutMs": 5000 }
 *         ]
 *       }
 *     ],
 *     "PostToolUse": [
 *       {
 *         "matcher": "write_file|edit_file",
 *         "hooks": [
 *           { "type": "command", "command": "/abs/path/format.sh" }
 *         ]
 *       }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <h3>加载语义</h3>
 * <ul>
 *   <li>未知事件名、非法 matcher 正则、非法 handler type 等错误均 <b>跳过该条记 warn 日志</b>，
 *       不抛出异常；保证 hooks.json 中的局部错误不拖垮整个加载过程。</li>
 *   <li>顶层 JSON 解析失败（文件损坏）会记 error 日志并返回空注册表。</li>
 * </ul>
 */
public final class HookConfigLoader {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("hooks");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_FILE_NAME = "hooks.json";
    private static final String CONFIG_DIR = ".tinyclaw";

    private HookConfigLoader() {
    }

    /** 从默认路径加载：{@code ~/.tinyclaw/hooks.json}。 */
    public static HookRegistry loadDefault() {
        return load(defaultPath());
    }

    /** 默认 hooks.json 路径。 */
    public static String defaultPath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, DEFAULT_FILE_NAME).toString();
    }

    /**
     * 从指定路径加载。
     *
     * @param path hooks.json 路径；null 或文件不存在时返回空注册表
     * @return 构建好的 {@link HookRegistry}，不会返回 null
     */
    public static HookRegistry load(String path) {
        if (path == null || path.isBlank()) {
            return HookRegistry.EMPTY;
        }
        Path p = Paths.get(path);
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            return HookRegistry.EMPTY;
        }

        JsonNode root;
        try {
            String content = Files.readString(p);
            if (content.isBlank()) {
                return HookRegistry.EMPTY;
            }
            root = MAPPER.readTree(content);
        } catch (IOException e) {
            logger.error("Failed to read hooks config, returning empty registry", Map.of(
                    "path", path, "error", e.getMessage()));
            return HookRegistry.EMPTY;
        } catch (Exception e) {
            logger.error("Failed to parse hooks config, returning empty registry", Map.of(
                    "path", path, "error", e.getMessage()));
            return HookRegistry.EMPTY;
        }

        return fromJson(root);
    }

    /**
     * 从已解析的 JSON 节点构建注册表（测试友好）。
     *
     * @param root 顶层节点，期望包含 {@code hooks} 对象字段；为 null 或结构错误返回空注册表
     */
    public static HookRegistry fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            return HookRegistry.EMPTY;
        }

        JsonNode hooksNode = root.path("hooks");
        if (hooksNode.isMissingNode() || !hooksNode.isObject()) {
            return HookRegistry.EMPTY;
        }

        Map<HookEvent, List<HookEntry>> byEvent = new EnumMap<>(HookEvent.class);
        Iterator<Map.Entry<String, JsonNode>> fields = hooksNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String eventName = field.getKey();
            HookEvent event = HookEvent.fromWireName(eventName);
            if (event == null) {
                logger.warn("Unknown hook event name, skipping", Map.of("event", eventName));
                continue;
            }
            List<HookEntry> entries = parseEventEntries(event, field.getValue());
            if (!entries.isEmpty()) {
                byEvent.put(event, entries);
            }
        }

        if (byEvent.isEmpty()) {
            return HookRegistry.EMPTY;
        }
        logger.info("Hook registry loaded", Map.of(
                "events", byEvent.size(),
                "total_entries", byEvent.values().stream().mapToInt(List::size).sum()));
        return new HookRegistry(byEvent);
    }

    private static List<HookEntry> parseEventEntries(HookEvent event, JsonNode arrayNode) {
        List<HookEntry> result = new ArrayList<>();
        if (!arrayNode.isArray()) {
            logger.warn("Hook event value is not an array, skipping", Map.of(
                    "event", event.wireName()));
            return result;
        }

        for (JsonNode entryNode : arrayNode) {
            HookEntry entry = parseEntry(event, entryNode);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private static HookEntry parseEntry(HookEvent event, JsonNode entryNode) {
        if (!entryNode.isObject()) {
            logger.warn("Hook entry is not an object, skipping", Map.of(
                    "event", event.wireName()));
            return null;
        }

        String matcherText = entryNode.path("matcher").asText("");
        HookMatcher matcher;
        try {
            matcher = HookMatcher.of(matcherText);
        } catch (Exception e) {
            logger.warn("Invalid matcher regex, skipping entry", Map.of(
                    "event", event.wireName(), "matcher", matcherText, "error", e.getMessage()));
            return null;
        }

        JsonNode handlersNode = entryNode.path("hooks");
        if (!handlersNode.isArray() || handlersNode.isEmpty()) {
            logger.warn("Hook entry has no handlers, skipping", Map.of(
                    "event", event.wireName(), "matcher", matcherText));
            return null;
        }

        List<HookHandler> handlers = new ArrayList<>();
        for (JsonNode handlerNode : handlersNode) {
            HookHandler handler = parseHandler(event, handlerNode);
            if (handler != null) {
                handlers.add(handler);
            }
        }
        if (handlers.isEmpty()) {
            return null;
        }
        return new HookEntry(matcher, handlers);
    }

    private static HookHandler parseHandler(HookEvent event, JsonNode handlerNode) {
        if (!handlerNode.isObject()) {
            logger.warn("Handler node is not an object, skipping", Map.of(
                    "event", event.wireName()));
            return null;
        }
        String type = handlerNode.path("type").asText("command");
        if (!"command".equalsIgnoreCase(type)) {
            logger.warn("Unsupported hook handler type, skipping", Map.of(
                    "event", event.wireName(), "type", type));
            return null;
        }
        String command = handlerNode.path("command").asText(null);
        if (command == null || command.isBlank()) {
            logger.warn("Command handler missing 'command' field, skipping", Map.of(
                    "event", event.wireName()));
            return null;
        }
        long timeoutMs = handlerNode.path("timeoutMs").asLong(0L);
        String workingDir = handlerNode.path("workingDir").asText(null);

        Map<String, String> env = null;
        JsonNode envNode = handlerNode.path("env");
        if (envNode.isObject()) {
            env = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = envNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual()) {
                    env.put(e.getKey(), e.getValue().asText());
                }
            }
        }
        try {
            return new CommandHookHandler(command, timeoutMs, workingDir, env);
        } catch (Exception e) {
            logger.warn("Failed to construct command handler, skipping", Map.of(
                    "event", event.wireName(), "command", command, "error", e.getMessage()));
            return null;
        }
    }
}
