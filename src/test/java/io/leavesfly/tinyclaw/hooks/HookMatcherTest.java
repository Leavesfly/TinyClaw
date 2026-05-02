package io.leavesfly.tinyclaw.hooks;

import org.junit.jupiter.api.Test;

import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;

class HookMatcherTest {

    @Test
    void nullOrEmpty_matchesAll() {
        assertTrue(HookMatcher.of(null).matchAll());
        assertTrue(HookMatcher.of("").matchAll());
        assertTrue(HookMatcher.of("*").matchAll());
    }

    @Test
    void matchAll_matchesAnyToolName() {
        HookMatcher matcher = HookMatcher.of("*");
        assertTrue(matcher.matches("exec"));
        assertTrue(matcher.matches("write_file"));
        assertTrue(matcher.matches(null));
    }

    @Test
    void literalRegex_matchesExactToolName() {
        HookMatcher matcher = HookMatcher.of("exec");
        assertTrue(matcher.matches("exec"));
        assertFalse(matcher.matches("execute"));
        assertFalse(matcher.matches("ex"));
        assertFalse(matcher.matches(null));
    }

    @Test
    void alternationRegex_matchesAnyBranch() {
        HookMatcher matcher = HookMatcher.of("write_file|edit_file");
        assertTrue(matcher.matches("write_file"));
        assertTrue(matcher.matches("edit_file"));
        assertFalse(matcher.matches("read_file"));
    }

    @Test
    void wildcardRegex_matchesByPrefix() {
        HookMatcher matcher = HookMatcher.of("web_.*");
        assertTrue(matcher.matches("web_search"));
        assertTrue(matcher.matches("web_fetch"));
        assertFalse(matcher.matches("exec"));
    }

    @Test
    void nonMatchAll_returnsFalseForNullToolName() {
        HookMatcher matcher = HookMatcher.of("exec");
        assertFalse(matcher.matches(null));
    }

    @Test
    void invalidRegex_throwsPatternSyntaxException() {
        assertThrows(PatternSyntaxException.class, () -> HookMatcher.of("[unclosed"));
    }

    @Test
    void raw_returnsOriginalPattern() {
        assertEquals("*", HookMatcher.of(null).raw());
        assertEquals("*", HookMatcher.of("*").raw());
        assertEquals("exec", HookMatcher.of("exec").raw());
    }
}
