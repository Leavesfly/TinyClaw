package io.leavesfly.tinyclaw.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StringUtils 工具类单元测试
 *
 * <h2>学习目标</h2>
 * <ul>
 *   <li>掌握 JUnit 5 参数化测试（@ParameterizedTest、@CsvSource）</li>
 *   <li>理解边界条件测试：null、空字符串、正常值</li>
 *   <li>学习断言方法：assertEquals、assertTrue、assertFalse</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=StringUtilsTest
 * </pre>
 */
@DisplayName("StringUtils 工具类测试")
class StringUtilsTest {

    // ==================== truncate 方法测试 ====================

    @Test
    @DisplayName("truncate: null 输入返回空字符串")
    void truncate_NullInput_ReturnsEmpty() {
        assertEquals("", StringUtils.truncate(null, 10));
    }

    @Test
    @DisplayName("truncate: 短于限制时返回原字符串")
    void truncate_ShortString_ReturnsOriginal() {
        assertEquals("hello", StringUtils.truncate("hello", 10));
    }

    @Test
    @DisplayName("truncate: 等于限制时返回原字符串")
    void truncate_ExactLength_ReturnsOriginal() {
        assertEquals("hello", StringUtils.truncate("hello", 5));
    }

    @Test
    @DisplayName("truncate: 超过限制时截断并添加省略号")
    void truncate_LongString_TruncatesWithEllipsis() {
        assertEquals("hel...", StringUtils.truncate("hello world", 3));
    }

    // ==================== isEmpty / isNotEmpty 方法测试 ====================

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {""})
    @DisplayName("isEmpty: null 或空字符串返回 true")
    void isEmpty_NullOrEmpty_ReturnsTrue(String input) {
        assertTrue(StringUtils.isEmpty(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", " ", "hello"})
    @DisplayName("isEmpty: 非空字符串返回 false")
    void isEmpty_NonEmpty_ReturnsFalse(String input) {
        assertFalse(StringUtils.isEmpty(input));
    }

    @Test
    @DisplayName("isNotEmpty: 与 isEmpty 互补")
    void isNotEmpty_ComplementsIsEmpty() {
        assertTrue(StringUtils.isNotEmpty("hello"));
        assertFalse(StringUtils.isNotEmpty(null));
        assertFalse(StringUtils.isNotEmpty(""));
    }

    // ==================== isBlank / isNotBlank 方法测试 ====================

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "  ", "\t", "\n"})
    @DisplayName("isBlank: null、空或纯空白字符串返回 true")
    void isBlank_NullOrBlank_ReturnsTrue(String input) {
        assertTrue(StringUtils.isBlank(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", " a", "a ", " a "})
    @DisplayName("isBlank: 包含非空白字符返回 false")
    void isBlank_NonBlank_ReturnsFalse(String input) {
        assertFalse(StringUtils.isBlank(input));
    }

    @Test
    @DisplayName("isNotBlank: 与 isBlank 互补")
    void isNotBlank_ComplementsIsBlank() {
        assertTrue(StringUtils.isNotBlank("hello"));
        assertFalse(StringUtils.isNotBlank(null));
        assertFalse(StringUtils.isNotBlank("   "));
    }

    // ==================== trim 方法测试 ====================

    @Test
    @DisplayName("trim: null 返回空字符串")
    void trim_NullInput_ReturnsEmpty() {
        assertEquals("", StringUtils.trim(null));
    }

    @Test
    @DisplayName("trim: 去除首尾空白")
    void trim_WhitespaceString_Trims() {
        assertEquals("hello", StringUtils.trim("  hello  "));
    }

    // ==================== escapeXml 方法测试 ====================

    @Test
    @DisplayName("escapeXml: null 返回空字符串")
    void escapeXml_NullInput_ReturnsEmpty() {
        assertEquals("", StringUtils.escapeXml(null));
    }

    @ParameterizedTest
    @CsvSource({
        "'<tag>', '&lt;tag&gt;'",
        "'a & b', 'a &amp; b'",
        "'\"quoted\"', '&quot;quoted&quot;'",
        "'''single''', '&apos;single&apos;'"
    })
    @DisplayName("escapeXml: 正确转义 XML 特殊字符")
    void escapeXml_SpecialChars_Escapes(String input, String expected) {
        assertEquals(expected, StringUtils.escapeXml(input));
    }

    // ==================== escapeHtml 方法测试 ====================

    @Test
    @DisplayName("escapeHtml: null 返回空字符串")
    void escapeHtml_NullInput_ReturnsEmpty() {
        assertEquals("", StringUtils.escapeHtml(null));
    }

    @Test
    @DisplayName("escapeHtml: 转义 HTML 特殊字符")
    void escapeHtml_SpecialChars_Escapes() {
        assertEquals("&lt;div&gt;", StringUtils.escapeHtml("<div>"));
        assertEquals("a &amp; b", StringUtils.escapeHtml("a & b"));
    }

    // ==================== repeat 方法测试 ====================

    @Test
    @DisplayName("repeat: null 或次数<=0 返回空字符串")
    void repeat_InvalidInput_ReturnsEmpty() {
        assertEquals("", StringUtils.repeat(null, 3));
        assertEquals("", StringUtils.repeat("a", 0));
        assertEquals("", StringUtils.repeat("a", -1));
    }

    @Test
    @DisplayName("repeat: 正确重复字符串")
    void repeat_ValidInput_Repeats() {
        assertEquals("aaa", StringUtils.repeat("a", 3));
        assertEquals("abab", StringUtils.repeat("ab", 2));
    }

    // ==================== join 方法测试 ====================

    @Test
    @DisplayName("join(String[]): null 或空数组返回空字符串")
    void joinArray_NullOrEmpty_ReturnsEmpty() {
        assertEquals("", StringUtils.join((String[]) null, ","));
        assertEquals("", StringUtils.join(new String[]{}, ","));
    }

    @Test
    @DisplayName("join(String[]): 正确连接数组元素")
    void joinArray_ValidInput_Joins() {
        assertEquals("a,b,c", StringUtils.join(new String[]{"a", "b", "c"}, ","));
        assertEquals("a-b", StringUtils.join(new String[]{"a", "b"}, "-"));
    }

    @Test
    @DisplayName("join(Iterable): null 返回空字符串")
    void joinIterable_Null_ReturnsEmpty() {
        assertEquals("", StringUtils.join((Iterable<String>) null, ","));
    }

    @Test
    @DisplayName("join(Iterable): 正确连接集合元素")
    void joinIterable_ValidInput_Joins() {
        assertEquals("a,b,c", StringUtils.join(Arrays.asList("a", "b", "c"), ","));
        assertEquals("", StringUtils.join(Collections.emptyList(), ","));
    }

    // ==================== estimateTokens 方法测试 ====================

    @Test
    @DisplayName("estimateTokens: null 或空字符串返回 0")
    void estimateTokens_NullOrEmpty_ReturnsZero() {
        assertEquals(0, StringUtils.estimateTokens(null));
        assertEquals(0, StringUtils.estimateTokens(""));
    }

    @Test
    @DisplayName("estimateTokens: 按约4字符/token估算")
    void estimateTokens_ValidInput_EstimatesCorrectly() {
        // 8 字符 / 4 = 2 tokens
        assertEquals(2, StringUtils.estimateTokens("12345678"));
        // 16 字符 / 4 = 4 tokens
        assertEquals(4, StringUtils.estimateTokens("1234567890123456"));
    }
}
