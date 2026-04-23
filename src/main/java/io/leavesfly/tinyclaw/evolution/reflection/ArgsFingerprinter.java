package io.leavesfly.tinyclaw.evolution.reflection;

import java.util.Map;
import java.util.TreeMap;

/**
 * 参数指纹生成器。
 *
 * <p>把原始 tool 参数 Map 归一化为一个短字符串作为聚类 key，规则：
 * <ul>
 *   <li>按 key 字典序排序，保证相同参数生成相同指纹；</li>
 *   <li>字符串值：
 *       <ul>
 *           <li>绝对/相对路径 → 保留 depth 标记 + 扩展名，例如 /a/b/c.log → <code>path:*//*.log</code></li>
 *           <li>URL → 保留 scheme+host，例如 https://api.x.com/v1/... → <code>url:https://api.x.com/*</code></li>
 *           <li>长字符串（>64 字符）→ 截断并加 "..."</li>
 *           <li>其它短字符串 → 原样保留</li>
 *       </ul>
 *   </li>
 *   <li>数字/布尔 → 原样保留；</li>
 *   <li>Map/List → 用 "<obj>" 占位，避免指纹过长。</li>
 * </ul>
 *
 * <p>目的是让"参数不同但结构一致"的失败被合并为同一类，便于后续 LLM 反思。
 */
public final class ArgsFingerprinter {

    private static final int MAX_VALUE_LEN = 64;

    private ArgsFingerprinter() {}

    /**
     * 生成指纹。
     *
     * @param args 参数 Map
     * @return 指纹字符串
     */
    public static String fingerprint(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "<empty>";
        }
        // 使用 TreeMap 保证 key 字典序
        Map<String, Object> sorted = new TreeMap<>(args);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(normalizeValue(e.getValue()));
        }
        return sb.toString();
    }

    private static String normalizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map || value instanceof Iterable) {
            return "<obj>";
        }
        String s = value.toString();
        if (s.isEmpty()) {
            return "''";
        }

        // URL 归一化
        if (s.startsWith("http://") || s.startsWith("https://")) {
            int schemeEnd = s.indexOf("://");
            int hostEnd = s.indexOf('/', schemeEnd + 3);
            if (hostEnd > 0) {
                return "url:" + s.substring(0, hostEnd) + "/*";
            }
            return "url:" + s;
        }

        // 路径归一化（绝对路径或含多个斜杠）
        if (s.startsWith("/") || s.contains("/") && !s.contains(" ")) {
            return pathFingerprint(s);
        }

        // 长字符串截断
        if (s.length() > MAX_VALUE_LEN) {
            return s.substring(0, MAX_VALUE_LEN) + "...";
        }
        return s;
    }

    private static String pathFingerprint(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder("path:");
        if (path.startsWith("/")) sb.append("/");
        int depth = 0;
        String last = "";
        for (String p : parts) {
            if (p.isEmpty()) continue;
            depth++;
            last = p;
        }
        for (int i = 1; i < depth; i++) {
            sb.append("*/");
        }
        // 保留最后一段的扩展名（如 .log / .json）
        int dot = last.lastIndexOf('.');
        if (dot > 0 && dot < last.length() - 1) {
            sb.append("*").append(last.substring(dot));
        } else {
            sb.append("*");
        }
        return sb.toString();
    }
}
