package io.leavesfly.tinyclaw.util;

/**
 * 字符串工具类
 */
public class StringUtils {
    
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    
    /**
     * 将字符串截断到最大长度
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
    
    /**
     * 检查字符串是否为 null 或空
     */
    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
    
    /**
     * 检查字符串是否不为 null 且不为空
     */
    public static boolean isNotEmpty(String s) {
        return s != null && !s.isEmpty();
    }
    
    /**
     * 检查字符串是否为 null 或空白（仅包含空格）
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
    
    /**
     * 检查字符串是否不为 null 且不为空白
     */
    public static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
    
    /**
     * 去除字符串首尾空格，如果为 null 则返回空字符串
     */
    public static String trim(String s) {
        return s == null ? "" : s.trim();
    }
    
    /**
     * 转义 XML 特殊字符
     */
    public static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
    
    /**
     * 转义 HTML 特殊字符
     */
    public static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
    
    /**
     * 重复字符串 n 次
     */
    public static String repeat(String s, int times) {
        if (s == null || times <= 0) {
            return "";
        }
        return s.repeat(times);
    }
    
    /**
     * 使用分隔符连接字符串数组
     */
    public static String join(String[] parts, String separator) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        return String.join(separator, parts);
    }
    
    /**
     * 使用分隔符连接字符串集合
     */
    public static String join(Iterable<String> parts, String separator) {
        if (parts == null) {
            return "";
        }
        return String.join(separator, parts);
    }
    
    /**
     * 估算字符串的 token 数量（CJK 感知）。
     *
     * <p>中文/日文/韩文字符实际约 0.7 token/字符，而 ASCII 约 4 字符/token。
     * 此方法按字符类型分别累加，对混合文本提供更精确的估算。</p>
     */
    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjkCharacter(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        // CJK: ~0.7 token/字符；其他: ~4 字符/token
        return (int) (cjkChars * 0.7) + otherChars / ESTIMATED_CHARS_PER_TOKEN;
    }

    /**
     * 判断字符是否为 CJK 统一表意文字、CJK 标点或全角字符。
     */
    private static boolean isCjkCharacter(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION;  // 中文标点（“”‘’—…）
    }
}