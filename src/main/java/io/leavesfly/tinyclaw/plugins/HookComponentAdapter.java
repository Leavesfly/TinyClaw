package io.leavesfly.tinyclaw.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.leavesfly.tinyclaw.hooks.HookConfigLoader;
import io.leavesfly.tinyclaw.hooks.HookRegistry;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.Map;

/**
 * 插件 hooks 组件适配器。
 *
 * <p>把插件清单中归一化的 hooks 节点（事件名 -&gt; 条目数组）转换为 TinyClaw 的
 * {@link HookRegistry}，从而复用现有 {@link HookConfigLoader}/{@code HookDispatcher}
 * 的解析、匹配与执行逻辑，无需新增协议。</p>
 *
 * <h3>处理步骤</h3>
 * <ol>
 *   <li>递归替换所有文本值中的 {@code ${CLAUDE_PLUGIN_ROOT}}、{@code ${CLAUDE_PLUGIN_DATA}}、
 *       {@code ${user_config.*}} 等变量（由 {@link VariableResolver} 完成）；</li>
 *   <li>为未显式声明 {@code workingDir} 的 command handler 注入默认工作目录 = 插件根目录，
 *       使插件内脚本的相对路径可用；</li>
 *   <li>包裹为 {@code { "hooks": ... }} 后交由 {@link HookConfigLoader#fromJson(JsonNode)} 构建注册表。</li>
 * </ol>
 *
 * <p>解析阶段不执行任何脚本；命令仅在对应事件触发时由 CommandHookHandler 以 fail-open 语义执行。</p>
 */
public class HookComponentAdapter {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将插件 hooks 组件适配为 {@link HookRegistry}。
     *
     * @param manifest 插件清单
     * @param resolver 变量替换器
     * @return 构建好的注册表；无 hooks 或解析失败时返回 {@link HookRegistry#EMPTY}
     */
    public HookRegistry adapt(PluginManifest manifest, VariableResolver resolver) {
        if (manifest == null || !manifest.hasHooks()) {
            return HookRegistry.EMPTY;
        }
        String defaultWorkingDir = manifest.getRootDir() != null
                ? manifest.getRootDir().toAbsolutePath().toString() : null;

        JsonNode resolved = substitute(manifest.getHooks(), resolver, defaultWorkingDir);

        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("hooks", resolved);

        HookRegistry registry = HookConfigLoader.fromJson(wrapper);
        if (!registry.isEmpty()) {
            logger.info("已装配插件 hooks", Map.of("plugin", String.valueOf(manifest.getId())));
        }
        return registry;
    }

    /**
     * 递归深拷贝并替换文本值中的变量；对 command handler 补默认 workingDir。
     */
    private JsonNode substitute(JsonNode node, VariableResolver resolver, String defaultWorkingDir) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e ->
                    out.set(e.getKey(), substitute(e.getValue(), resolver, defaultWorkingDir)));
            // command handler：补默认工作目录，便于插件内相对脚本定位
            if (out.has("command") && out.get("command").isTextual()
                    && !out.has("workingDir")
                    && defaultWorkingDir != null && !defaultWorkingDir.isEmpty()) {
                out.put("workingDir", defaultWorkingDir);
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                out.add(substitute(item, resolver, defaultWorkingDir));
            }
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(resolver.resolve(node.asText()));
        }
        return node;
    }
}
