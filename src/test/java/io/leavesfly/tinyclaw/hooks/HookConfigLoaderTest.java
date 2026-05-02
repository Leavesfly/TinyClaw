package io.leavesfly.tinyclaw.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookConfigLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nullOrEmptyPath_returnsEmpty() {
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load(null));
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load(""));
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load("   "));
    }

    @Test
    void missingFile_returnsEmpty(@TempDir Path dir) {
        Path p = dir.resolve("nope.json");
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load(p.toString()));
    }

    @Test
    void blankFile_returnsEmpty(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, "   \n");
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load(p.toString()));
    }

    @Test
    void corruptedJson_returnsEmptyAndDoesNotThrow(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, "{ this is not valid json");
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load(p.toString()));
    }

    @Test
    void missingHooksField_returnsEmpty(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, "{\"unrelated\": 1}");
        assertSame(HookRegistry.EMPTY, HookConfigLoader.load(p.toString()));
    }

    @Test
    void validConfig_buildsRegistryAndEntries(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "exec",
                        "hooks": [
                          {"type": "command", "command": "echo pre", "timeoutMs": 1234}
                        ]
                      }
                    ],
                    "PostToolUse": [
                      {
                        "matcher": "write_file|edit_file",
                        "hooks": [
                          {"type": "command", "command": "echo post"}
                        ]
                      }
                    ]
                  }
                }
                """;
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, json);

        HookRegistry reg = HookConfigLoader.load(p.toString());
        assertFalse(reg.isEmpty());
        assertTrue(reg.hasEntries(HookEvent.PRE_TOOL_USE));
        assertTrue(reg.hasEntries(HookEvent.POST_TOOL_USE));
        assertFalse(reg.hasEntries(HookEvent.SESSION_START));

        List<HookEntry> pre = reg.getEntries(HookEvent.PRE_TOOL_USE);
        assertEquals(1, pre.size());
        assertEquals("exec", pre.get(0).getMatcher().raw());
        assertEquals(1, pre.get(0).getHandlers().size());
        assertTrue(pre.get(0).getHandlers().get(0) instanceof CommandHookHandler);
        CommandHookHandler h = (CommandHookHandler) pre.get(0).getHandlers().get(0);
        assertEquals("echo pre", h.getCommand());
        assertEquals(1234L, h.getTimeoutMs());

        List<HookEntry> post = reg.getEntries(HookEvent.POST_TOOL_USE);
        assertEquals(1, post.size());
        assertEquals("write_file|edit_file", post.get(0).getMatcher().raw());
    }

    @Test
    void unknownEventName_isSkipped(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "hooks": {
                    "BogusEvent": [
                      {"matcher": "*", "hooks": [{"type":"command","command":"echo"}]}
                    ],
                    "PreToolUse": [
                      {"matcher": "*", "hooks": [{"type":"command","command":"echo"}]}
                    ]
                  }
                }
                """;
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, json);

        HookRegistry reg = HookConfigLoader.load(p.toString());
        assertTrue(reg.hasEntries(HookEvent.PRE_TOOL_USE));
        // BogusEvent 不会被识别，注册表里不应出现它对应的条目
        // 只能间接验证：全部 6 个已知事件里只有 PreToolUse 有条目
        int counted = 0;
        for (HookEvent e : HookEvent.values()) {
            if (reg.hasEntries(e)) {
                counted++;
            }
        }
        assertEquals(1, counted);
    }

    @Test
    void invalidMatcherRegex_isSkipped(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {"matcher": "[unclosed", "hooks": [{"type":"command","command":"echo"}]},
                      {"matcher": "exec", "hooks": [{"type":"command","command":"echo"}]}
                    ]
                  }
                }
                """;
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, json);

        HookRegistry reg = HookConfigLoader.load(p.toString());
        List<HookEntry> entries = reg.getEntries(HookEvent.PRE_TOOL_USE);
        // 非法 matcher 的条目被跳过，只保留合法的那一条
        assertEquals(1, entries.size());
        assertEquals("exec", entries.get(0).getMatcher().raw());
    }

    @Test
    void unsupportedHandlerType_isSkipped(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "*",
                        "hooks": [
                          {"type": "http", "url": "http://example.com"},
                          {"type": "command", "command": "echo ok"}
                        ]
                      }
                    ]
                  }
                }
                """;
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, json);

        HookRegistry reg = HookConfigLoader.load(p.toString());
        List<HookEntry> entries = reg.getEntries(HookEvent.PRE_TOOL_USE);
        assertEquals(1, entries.size());
        // http 被跳过，只剩 command 一个 handler
        assertEquals(1, entries.get(0).getHandlers().size());
    }

    @Test
    void commandHandlerWithoutCommand_isSkipped(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "*",
                        "hooks": [
                          {"type": "command"}
                        ]
                      }
                    ]
                  }
                }
                """;
        Path p = dir.resolve("hooks.json");
        Files.writeString(p, json);

        HookRegistry reg = HookConfigLoader.load(p.toString());
        // 没有合法 handler，整个 entry 被过滤
        assertFalse(reg.hasEntries(HookEvent.PRE_TOOL_USE));
    }

    @Test
    void fromJson_nullOrNonObject_returnsEmpty() throws Exception {
        assertSame(HookRegistry.EMPTY, HookConfigLoader.fromJson(null));
        assertSame(HookRegistry.EMPTY, HookConfigLoader.fromJson(MAPPER.readTree("[1,2,3]")));
        assertSame(HookRegistry.EMPTY, HookConfigLoader.fromJson(MAPPER.readTree("123")));
    }

    @Test
    void defaultPath_isInHomeDotTinyclaw() {
        String path = HookConfigLoader.defaultPath();
        assertNotNull(path);
        assertTrue(path.endsWith("hooks.json"),
                "default path should end with hooks.json, got: " + path);
        assertTrue(path.contains(".tinyclaw"),
                "default path should contain .tinyclaw, got: " + path);
    }
}
