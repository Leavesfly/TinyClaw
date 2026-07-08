package io.leavesfly.tinyclaw.plugins;

import io.leavesfly.tinyclaw.collaboration.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件 agent 组件适配器测试。
 */
class AgentComponentAdapterTest {

    private final AgentComponentAdapter adapter = new AgentComponentAdapter();

    private PluginManifest.AgentDefinition def(String name, String prompt) {
        PluginManifest.AgentDefinition d = new PluginManifest.AgentDefinition();
        d.setName(name);
        d.setSystemPrompt(prompt);
        return d;
    }

    @Test
    void testConvertWithVariableSubstitution() {
        PluginManifest m = new PluginManifest();
        m.setId("myplugin");
        PluginManifest.AgentDefinition d = def("code-reviewer", "Review code in ${CLAUDE_PLUGIN_ROOT}");
        d.setDescription("desc");
        d.setModel("qwen-max");
        d.addTool("Read");
        d.addTool("Grep");
        m.addAgent(d);
        VariableResolver resolver = new VariableResolver("/root", "/data", "/w", null);

        List<AgentRole> roles = adapter.adapt(m, resolver);

        assertEquals(1, roles.size());
        AgentRole role = roles.get(0);
        assertEquals("code-reviewer", role.getRoleName());
        assertEquals("plugin:myplugin:code-reviewer", role.getRoleId());
        assertEquals("Review code in /root", role.getSystemPrompt());
        assertEquals("desc", role.getDescription());
        assertEquals("qwen-max", role.getModel());
        assertTrue(role.hasToolRestrictions());
        assertEquals(2, role.getAllowedTools().size());
    }

    @Test
    void testConvertWithoutOptionalFields() {
        PluginManifest m = new PluginManifest();
        m.setId("p");
        m.addAgent(def("planner", "You plan tasks."));
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        List<AgentRole> roles = adapter.adapt(m, resolver);

        assertEquals(1, roles.size());
        AgentRole role = roles.get(0);
        assertEquals("planner", role.getRoleName());
        assertNull(role.getModel());
        assertFalse(role.hasToolRestrictions());
    }

    @Test
    void testSkipAgentWithoutSystemPrompt() {
        PluginManifest m = new PluginManifest();
        m.setId("p");
        m.addAgent(def("empty", ""));
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        assertTrue(adapter.adapt(m, resolver).isEmpty());
    }

    @Test
    void testNoAgentsReturnsEmpty() {
        PluginManifest m = new PluginManifest();
        m.setId("p");
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w", null);

        assertTrue(adapter.adapt(m, resolver).isEmpty());
    }

    @Test
    void testUserConfigVariableInPrompt() {
        PluginManifest m = new PluginManifest();
        m.setId("p");
        m.addAgent(def("greeter", "Hello ${user_config.persona}"));
        VariableResolver resolver = new VariableResolver("/r", "/d", "/w",
                Map.of("persona", "pirate"));

        List<AgentRole> roles = adapter.adapt(m, resolver);

        assertEquals(1, roles.size());
        assertEquals("Hello pirate", roles.get(0).getSystemPrompt());
    }
}
