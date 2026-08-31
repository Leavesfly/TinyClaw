package io.leavesfly.tinyclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigMigrator 与 ConfigLoader 迁移链路测试。
 *
 * <p>重点验证三件事：迁移幂等、未知键无损保留、稳态不写盘。这三条是迁移器敢挂在
 * 启动路径上无条件执行的前提。</p>
 */
class ConfigMigratorTest {

    @Test
    void readVersionTreatsMissingAndBrokenValuesAsZero() {
        assertEquals(0, ConfigMigrator.readVersion(new LinkedHashMap<>()));
        assertEquals(0, ConfigMigrator.readVersion(mapOf("schemaVersion", "not-a-number")));
        assertEquals(0, ConfigMigrator.readVersion(mapOf("schemaVersion", -5)));
        assertEquals(3, ConfigMigrator.readVersion(mapOf("schemaVersion", 3)));
        assertEquals(2, ConfigMigrator.readVersion(mapOf("schemaVersion", " 2 ")));
    }

    @Test
    void pendingListsOnlyFutureMigrations() {
        assertFalse(ConfigMigrator.pending(0).isEmpty());
        assertTrue(ConfigMigrator.pending(ConfigMigrator.CURRENT_VERSION).isEmpty());
    }

    @Test
    void migrateStampsVersionAndNormalizesWorkspace() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("agent", mapOf("workspace", "  ~/.tinyclaw/workspace  "));

        ConfigMigrator.Result result = ConfigMigrator.migrate(raw);

        assertTrue(result.rewritten());
        assertEquals(0, result.fromVersion());
        assertEquals(ConfigMigrator.CURRENT_VERSION, result.toVersion());
        assertEquals(ConfigMigrator.CURRENT_VERSION, raw.get("schemaVersion"));
        assertEquals("~/.tinyclaw/workspace", asMap(raw.get("agent")).get("workspace"));
        assertFalse(result.applied().isEmpty(), "实际改动了内容应记入 applied");
    }

    @Test
    void migrateStripsTrailingSlashesFromEveryProviderApiBase() {
        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("openai", mapOf("apiBase", "https://api.openai.com/v1//"));
        providers.put("ollama", mapOf("apiBase", "  http://localhost:11434/v1/  "));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("providers", providers);

        ConfigMigrator.migrate(raw);

        assertEquals("https://api.openai.com/v1", asMap(providers.get("openai")).get("apiBase"));
        assertEquals("http://localhost:11434/v1", asMap(providers.get("ollama")).get("apiBase"));
    }

    @Test
    void migrateIsIdempotent() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("agent", mapOf("workspace", " ~/ws "));

        ConfigMigrator.migrate(raw);
        Map<String, Object> afterFirst = new LinkedHashMap<>(raw);

        ConfigMigrator.Result second = ConfigMigrator.migrate(raw);

        assertFalse(second.rewritten(), "版本已最新时不应再改写");
        assertEquals(afterFirst, raw);
    }

    @Test
    void migrateToleratesWrongTypesInsteadOfThrowing() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("agent", "should-be-an-object");
        raw.put("providers", 42);

        assertDoesNotThrow(() -> ConfigMigrator.migrate(raw));
        assertEquals(ConfigMigrator.CURRENT_VERSION, raw.get("schemaVersion"));
    }

    @Test
    void loadAndMigratePersistsOnceAndKeepsUnknownKeys(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "agent": { "workspace": " ~/ws ", "model": "demo" },
                  "providers": { "openai": { "apiKey": "k", "apiBase": "https://x/v1/" } },
                  "somethingTinyClawDoesNotKnowYet": { "keep": true }
                }
                """);

        ConfigLoader.LoadResult first = ConfigLoader.loadAndMigrate(configFile.toString());

        assertTrue(first.persisted(), "版本落后应写回磁盘");
        assertEquals("~/ws", first.config().getAgent().getWorkspace());
        assertEquals(ConfigMigrator.CURRENT_VERSION, first.config().getSchemaVersion());

        Map<String, Object> rewritten = ConfigLoader.readRaw(configFile.toString());
        assertTrue(rewritten.containsKey("somethingTinyClawDoesNotKnowYet"),
                "写回原始 JSON 才能保住当前模型不认识的键");
        assertEquals("https://x/v1", asMap(asMap(rewritten.get("providers")).get("openai")).get("apiBase"));

        ConfigLoader.LoadResult second = ConfigLoader.loadAndMigrate(configFile.toString());
        assertFalse(second.persisted(), "稳态下不应产生写盘");
    }

    @Test
    void loadAndMigrateBacksUpBeforeRewriting(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        String original = "{\"agent\":{\"workspace\":\" ~/ws \"}}";
        Files.writeString(configFile, original);

        ConfigLoader.loadAndMigrate(configFile.toString());

        try (var stream = Files.list(dir)) {
            Path backup = stream
                    .filter(path -> path.getFileName().toString().startsWith("config.json.bak-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("迁移应留下备份文件"));
            assertEquals(original, Files.readString(backup));
        }
    }

    @Test
    void plainLoadMigratesInMemoryWithoutTouchingDisk(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        String original = "{\"agent\":{\"workspace\":\" ~/ws \"}}";
        Files.writeString(configFile, original);

        Config config = ConfigLoader.load(configFile.toString());

        assertEquals("~/ws", config.getAgent().getWorkspace());
        assertEquals(original, Files.readString(configFile), "只读加载不应改写配置文件");
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }
}
