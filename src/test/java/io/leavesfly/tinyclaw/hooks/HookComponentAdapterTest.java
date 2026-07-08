package io.leavesfly.tinyclaw.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.plugins.HookComponentAdapter;
import io.leavesfly.tinyclaw.plugins.PluginManifest;
import io.leavesfly.tinyclaw.plugins.VariableResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件 hooks 组件适配器测试。
 *
 * <p>置于 {@code hooks} 包内以便访问 {@link CommandHookHandler} 的包级访问器，
 * 从而校验变量替换、超时与工作目录注入结果。</p>
 */
class HookComponentAdapterTest {

    private final HookComponentAdapter adapter = new HookComponentAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    private PluginManifest manifest(String hooksJson, Path root) throws Exception {
        PluginManifest m = new PluginManifest();
        m.setId("qr");
        m.setRootDir(root);
        JsonNode node = mapper.readTree(hooksJson);
        m.setHooks(node);
        return m;
    }

    @Test
    void testAdaptSubstitutesVariablesAndInjectsWorkingDir() throws Exception {
        Path root = Paths.get("/plugins/qr");
        PluginManifest m = manifest(
                "{ \"PreToolUse\": [ { \"matcher\": \"exec\", " +
                "\"hooks\": [ {\"type\":\"command\",\"command\":\"${CLAUDE_PLUGIN_ROOT}/hooks/guard.sh\",\"timeoutMs\":5000} ] } ] }",
                root);
        VariableResolver resolver = new VariableResolver(
                root.toString(), "/data/qr", "/workspace", null);

        HookRegistry registry = adapter.adapt(m, resolver);

        assertFalse(registry.isEmpty());
        assertTrue(registry.hasEntries(HookEvent.PRE_TOOL_USE));
        List<HookEntry> entries = registry.getEntries(HookEvent.PRE_TOOL_USE);
        assertEquals(1, entries.size());
        assertEquals("exec", entries.get(0).getMatcher().raw());

        HookHandler handler = entries.get(0).getHandlers().get(0);
        assertTrue(handler instanceof CommandHookHandler);
        CommandHookHandler cmd = (CommandHookHandler) handler;
        // ${CLAUDE_PLUGIN_ROOT} 已替换为插件根
        assertEquals("/plugins/qr/hooks/guard.sh", cmd.getCommand());
        assertEquals(5000L, cmd.getTimeoutMs());
        // 未显式声明 workingDir → 注入插件根目录
        assertNotNull(cmd.getWorkingDir());
        assertEquals("/plugins/qr", cmd.getWorkingDir().getPath());
    }

    @Test
    void testExplicitWorkingDirNotOverwritten() throws Exception {
        Path root = Paths.get("/plugins/qr");
        PluginManifest m = manifest(
                "{ \"PostToolUse\": [ { \"hooks\": [ {\"type\":\"command\",\"command\":\"fmt\",\"workingDir\":\"/custom/wd\"} ] } ] }",
                root);
        VariableResolver resolver = new VariableResolver(root.toString(), "/d", "/w", null);

        HookRegistry registry = adapter.adapt(m, resolver);

        CommandHookHandler cmd = (CommandHookHandler)
                registry.getEntries(HookEvent.POST_TOOL_USE).get(0).getHandlers().get(0);
        assertEquals("/custom/wd", cmd.getWorkingDir().getPath());
    }

    @Test
    void testNoHooksReturnsEmptyRegistry() {
        PluginManifest m = new PluginManifest();
        m.setId("p");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        HookRegistry registry = adapter.adapt(m, resolver);

        assertTrue(registry.isEmpty());
    }

    @Test
    void testMultipleEventsPreserved() throws Exception {
        Path root = Paths.get("/plugins/qr");
        PluginManifest m = manifest(
                "{ \"PreToolUse\": [ { \"matcher\": \"exec\", \"hooks\": [ {\"type\":\"command\",\"command\":\"a\"} ] } ], " +
                "\"SessionStart\": [ { \"hooks\": [ {\"type\":\"command\",\"command\":\"b\"} ] } ] }",
                root);
        VariableResolver resolver = new VariableResolver(root.toString(), "/d", "/w", null);

        HookRegistry registry = adapter.adapt(m, resolver);

        assertTrue(registry.hasEntries(HookEvent.PRE_TOOL_USE));
        assertTrue(registry.hasEntries(HookEvent.SESSION_START));
    }
}
