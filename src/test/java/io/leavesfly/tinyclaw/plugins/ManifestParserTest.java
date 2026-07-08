package io.leavesfly.tinyclaw.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件清单解析器测试。
 */
class ManifestParserTest {

    private final ManifestParser parser = new ManifestParser();

    @Test
    void testParseClaudeLayoutWithSkillsAndMcp(@TempDir Path dir) throws IOException {
        // .claude-plugin/plugin.json + skills/ + 内联 mcpServers
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"), "{\n" +
                "  \"name\": \"demo-plugin\",\n" +
                "  \"displayName\": \"Demo\",\n" +
                "  \"version\": \"1.2.0\",\n" +
                "  \"description\": \"a demo\",\n" +
                "  \"mcpServers\": {\n" +
                "    \"echo\": { \"command\": \"node\", \"args\": [\"server.js\"] }\n" +
                "  }\n" +
                "}");
        Path skill = Files.createDirectories(dir.resolve("skills").resolve("hello"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: hello\ndescription: hi\n---\n# Hello");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals("demo-plugin", m.getId());
        assertEquals("Demo", m.getDisplayName());
        assertEquals("1.2.0", m.getVersion());
        assertEquals("claude", m.getLayout());
        assertTrue(m.hasSkills());
        assertTrue(m.hasMcpServers());
        assertTrue(m.getDeclaredComponents().contains("skills"));
        assertTrue(m.getDeclaredComponents().contains("mcpServers"));
    }

    @Test
    void testParseOpenClawLayout(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("openclaw.plugin.json"),
                "{ \"id\": \"oc\", \"name\": \"oc-plugin\" }");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals("openclaw", m.getLayout());
        assertEquals("oc-plugin", m.getId());
    }

    @Test
    void testMissingNameFallsBackToDirName(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"), "{ \"version\": \"1.0.0\" }");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals(dir.getFileName().toString(), m.getId());
    }

    @Test
    void testMcpFromDotMcpJsonFile(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"), "{ \"name\": \"p\" }");
        Files.writeString(dir.resolve(".mcp.json"), "{\n" +
                "  \"mcpServers\": { \"db\": { \"command\": \"db-server\" } }\n" +
                "}");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertTrue(m.hasMcpServers());
        assertTrue(m.getMcpServers().has("db"));
    }

    @Test
    void testRejectsPathTraversalInSkills(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"),
                "{ \"name\": \"p\", \"skills\": \"../evil\" }");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        // 越界路径应被拒绝，不产生技能根
        assertFalse(m.hasSkills());
    }

    @Test
    void testSingleSkillLayout(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: solo\n---\n# Solo");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertEquals("single-skill", m.getLayout());
    }

    @Test
    void testNonPluginDirReturnsNull(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("readme.txt"), "not a plugin");

        assertNull(parser.parse(dir));
    }

    @Test
    void testParseHooksFromDefaultFile(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"), "{ \"name\": \"p\" }");
        // 默认 hooks/hooks.json，带顶层 hooks 包裹
        Path hooksDir = Files.createDirectories(dir.resolve("hooks"));
        Files.writeString(hooksDir.resolve("hooks.json"), "{\n" +
                "  \"hooks\": {\n" +
                "    \"PreToolUse\": [\n" +
                "      { \"matcher\": \"exec\", \"hooks\": [ {\"type\":\"command\",\"command\":\"echo hi\"} ] }\n" +
                "    ]\n" +
                "  }\n" +
                "}");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertTrue(m.hasHooks());
        // 拆包后应为事件名->条目数组的映射
        assertTrue(m.getHooks().has("PreToolUse"));
        assertTrue(m.getDeclaredComponents().contains("hooks"));
    }

    @Test
    void testParseInlineHooksObject(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        // 清单内联 hooks 对象（事件名->条目）
        Files.writeString(meta.resolve("plugin.json"), "{\n" +
                "  \"name\": \"p\",\n" +
                "  \"hooks\": { \"PostToolUse\": [ { \"matcher\": \"write_file\", \"hooks\": [ {\"type\":\"command\",\"command\":\"echo post\"} ] } ] }\n" +
                "}");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertTrue(m.hasHooks());
        assertTrue(m.getHooks().has("PostToolUse"));
    }

    @Test
    void testParseHooksFieldAsFilePath(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"),
                "{ \"name\": \"p\", \"hooks\": \"hooks/custom.json\" }");
        Path hooksDir = Files.createDirectories(dir.resolve("hooks"));
        Files.writeString(hooksDir.resolve("custom.json"), "{\n" +
                "  \"hooks\": { \"SessionStart\": [ { \"hooks\": [ {\"type\":\"command\",\"command\":\"echo start\"} ] } ] }\n" +
                "}");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertTrue(m.hasHooks());
        assertTrue(m.getHooks().has("SessionStart"));
    }

    @Test
    void testNoHooksMeansHasHooksFalse(@TempDir Path dir) throws IOException {
        Path meta = Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(meta.resolve("plugin.json"), "{ \"name\": \"p\" }");

        PluginManifest m = parser.parse(dir);

        assertNotNull(m);
        assertFalse(m.hasHooks());
    }
}
