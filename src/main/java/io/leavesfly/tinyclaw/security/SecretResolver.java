package io.leavesfly.tinyclaw.security;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 凭据引用解析器 - 在工具执行边界把 {@code ${secret:NAME}} 换成真实值。
 *
 * <h2>替换发生在哪里，为什么重要</h2>
 * <p>只在 {@code ToolRegistry.execute} 调用 {@code tool.execute} 之前，且作用于参数的
 * <b>副本</b>。调用方持有的原始参数不被修改，因此：会话转录、工具调用记录、结构化日志、
 * 以及回传给模型的 tool_result 上下文里留下的始终是占位符。明文的生命周期被压缩到
 * 一次工具调用的栈帧内。</p>
 *
 * <h2>出口绑定的判定规则</h2>
 * <p>凭据声明了 {@code allowedHosts} 时，参数中出现的每一个 URL 主机都必须在允许列表里，
 * 且至少要出现一个 URL。"一个都没有"被判为拒绝而不是放行——无法确认目的地时放行，
 * 等于这项声明形同虚设。</p>
 */
public class SecretResolver {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("security");

    /** 引用语法：${secret:NAME}，NAME 允许字母数字与 _ - . */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]+)}");

    /** 从任意字符串里提取 URL，用于判定调用的目的地 */
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\"'<>]+");

    private final SecretStore store;

    public SecretResolver(SecretStore store) {
        this.store = store;
    }

    /**
     * 参数中是否含凭据引用。用于跳过绝大多数无引用的调用，避免无谓的深拷贝。
     */
    public boolean containsReference(Map<String, Object> args) {
        return args != null && scanForReferences(args, new LinkedHashSet<>());
    }

    /**
     * 解析参数中的凭据引用，返回替换后的<b>新</b>参数映射。
     *
     * <p>无引用时直接返回原对象：不做无意义的拷贝，也让"未使用凭据的调用完全没有行为变化"
     * 这一点显而易见。</p>
     *
     * @param toolName 工具名，仅用于告警日志
     * @param args     原始参数，不会被修改
     * @return 可直接交给工具执行的参数
     * @throws SecurityException 引用了不存在的凭据，或违反出口绑定
     */
    public Map<String, Object> resolve(String toolName, Map<String, Object> args) {
        if (args == null || !containsReference(args)) {
            return args;
        }
    
        Set<String> referenced = new LinkedHashSet<>();
        scanForReferences(args, referenced);
    
        Set<String> targetHosts = collectHosts(args);
        for (String name : referenced) {
            if (!store.has(name)) {
                throw new SecurityException("引用了不存在的凭据: " + name
                        + "（可用凭据请通过 secrets 工具查询）");
            }
            enforceHostBinding(name, targetHosts);
        }
    
        logger.info("Resolved secret references at tool boundary", Map.of(
                "tool", toolName != null ? toolName : "",
                // 只记名字与个数，值绝不进日志
                "secrets", String.join(",", referenced)));
    
        return asStringKeyedMap(substitute(args));
    }
    
    /**
     * {@link #substitute} 对 Map 输入恒返回新建的 {@code Map<String, Object>}，
     * 这里把转型收在一处，避免调用点散落未检查转型。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringKeyedMap(Object node) {
        return (Map<String, Object>) node;
    }

    /**
     * 校验出口绑定。
     */
    private void enforceHostBinding(String name, Set<String> targetHosts) {
        Set<String> allowed = store.allowedHosts(name);
        if (allowed.isEmpty()) {
            return;
        }
        if (targetHosts.isEmpty()) {
            throw new SecurityException("凭据 " + name + " 限定了目标主机 " + allowed
                    + "，但本次调用参数中没有可识别的目标地址，已拒绝");
        }
        for (String host : targetHosts) {
            if (!allowed.contains(host)) {
                throw new SecurityException("凭据 " + name + " 不允许发送到 " + host
                        + "（允许: " + allowed + "）");
            }
        }
    }

    // ==================== 递归遍历 ====================

    /**
     * 递归扫描引用。
     *
     * @param collected 收集到的凭据名；传入非空集合即为"收集模式"
     * @return 是否发现了至少一个引用
     */
    private boolean scanForReferences(Object node, Set<String> collected) {
        if (node instanceof String text) {
            Matcher matcher = REFERENCE.matcher(text);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                collected.add(matcher.group(1));
            }
            return found;
        }
        if (node instanceof Map<?, ?> map) {
            boolean found = false;
            for (Object value : map.values()) {
                // 不短路：需要把所有引用都收集齐，用于逐条校验出口绑定
                found |= scanForReferences(value, collected);
            }
            return found;
        }
        if (node instanceof Iterable<?> iterable) {
            boolean found = false;
            for (Object value : iterable) {
                found |= scanForReferences(value, collected);
            }
            return found;
        }
        return false;
    }

    /**
     * 递归深拷贝并替换。
     *
     * <p>Map 与 List 都新建容器：原地替换会改到调用方持有的那份参数，
     * 明文随即被写进工具调用记录与会话转录。</p>
     */
    private Object substitute(Object node) {
        if (node instanceof String text) {
            Matcher matcher = REFERENCE.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String value = store.reveal(matcher.group(1));
                matcher.appendReplacement(sb,
                        Matcher.quoteReplacement(value != null ? value : ""));
            }
            matcher.appendTail(sb);
            return sb.toString();
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), substitute(value)));
            return copy;
        }
        if (node instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object value : iterable) {
                copy.add(substitute(value));
            }
            return copy;
        }
        return node;
    }

    /**
     * 收集参数中出现的所有 URL 主机（小写）。
     *
     * <p>从任意字符串里提取而不只看 {@code url} 字段：MCP 工具与插件工具的参数名各不相同，
     * 按字段名白名单会在新工具上默默失效。</p>
     */
    private Set<String> collectHosts(Object node) {
        Set<String> hosts = new LinkedHashSet<>();
        collectHosts(node, hosts);
        return hosts;
    }

    private void collectHosts(Object node, Set<String> hosts) {
        if (node instanceof String text) {
            Matcher matcher = URL_IN_TEXT.matcher(text);
            while (matcher.find()) {
                String host = hostOf(matcher.group());
                if (host != null) {
                    hosts.add(host);
                }
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> collectHosts(value, hosts));
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            iterable.forEach(value -> collectHosts(value, hosts));
        }
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
