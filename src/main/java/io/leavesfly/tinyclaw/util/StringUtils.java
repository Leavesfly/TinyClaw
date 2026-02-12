package io.leavesfly.tinyclaw.util;

/**
 * String utility functions
 */
public class StringUtils {
    
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    
    /**
     * Truncate a string to a maximum length
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
     * 检查 if a string is null or empty
     */
    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
    
    /**
     * 检查 if a string is not null and not empty
     */
    public static boolean isNotEmpty(String s) {
        return s != null && !s.isEmpty();
    }
    
    /**
     * 检查 if a string is null or blank (whitespace only)
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
    
    /**
     * 检查 if a string is not null and not blank
     */
    public static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
    
    /**
     * Trim a string, returning empty string if null
     */
    public static String trim(String s) {
        return s == null ? "" : s.trim();
    }
    
    /**
     * Escape XML special characters
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
     * Escape HTML special characters
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
     * Repeat a string n times
     */
    public static String repeat(String s, int times) {
        if (s == null || times <= 0) {
            return "";
        }
        return s.repeat(times);
    }
    
    /**
     * Join strings with a separator
     */
    public static String join(String[] parts, String separator) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        return String.join(separator, parts);
    }
    
    /**
     * Join strings with a separator
     */
    public static String join(Iterable<String> parts, String separator) {
        if (parts == null) {
            return "";
        }
        return String.join(separator, parts);
    }
    
    /**
     * Estimate token count for a string (simple heuristic: ~4 chars per token)
     */
    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.length() / ESTIMATED_CHARS_PER_TOKEN;
    }
}