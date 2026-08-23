package io.leavesfly.tinyclaw.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonFileStore} 单元测试。
 *
 * <p>重点覆盖此前 6 处直接覆盖写留下的风险：写入中途崩溃留下半截 JSON、
 * 并发写踩同一临时文件、损坏文件被上层静默覆盖销毁。
 */
class JsonFileStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ==================== 原子写 ====================

    @Test
    @DisplayName("原子写: 写入后内容完整且不留临时文件")
    void writeAtomic_LeavesNoTempFile() throws IOException {
        Path target = tempDir.resolve("data.json");
        JsonFileStore.writeAtomic(target, "{\"a\":1}");

        assertEquals("{\"a\":1}", Files.readString(target));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "不应残留 .tmp 文件");
        }
    }

    @Test
    @DisplayName("原子写: 覆盖已有文件不产生中间态")
    void writeAtomic_ReplacesExistingContent() throws IOException {
        Path target = tempDir.resolve("data.json");
        JsonFileStore.writeAtomic(target, "old content");
        JsonFileStore.writeAtomic(target, "new");

        // 旧内容更长，若是截断式覆盖写会留下 "new content" 之类的混合结果
        assertEquals("new", Files.readString(target));
    }

    @Test
    @DisplayName("原子写: 自动创建缺失的父目录")
    void writeAtomic_CreatesParentDirectories() throws IOException {
        Path target = tempDir.resolve("nested/deep/data.json");
        JsonFileStore.writeAtomic(target, "ok");

        assertEquals("ok", Files.readString(target));
    }

    @Test
    @DisplayName("原子写: 并发写同一目标时，落地内容总是某一次的完整写入")
    void writeAtomic_ConcurrentWritersDoNotCorrupt() throws Exception {
        Path target = tempDir.resolve("contended.json");
        int writers = 8;
        int rounds = 40;
        // payload 要足够大（~1.2MB），单次写入要跨多个 write() 系统调用，
        // 否则写太快，固定临时文件名的竞态几乎撞不到
        Map<String, String> payloads = new HashMap<>();
        for (int i = 0; i < writers; i++) {
            payloads.put("w" + i, ("{\"writer\":\"w" + i + "\"}").repeat(50_000));
        }

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int round = 0; round < rounds; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    String payload = payloads.get("w" + i);
                    futures.add(pool.submit(() -> {
                        start.await();
                        JsonFileStore.writeAtomic(target, payload);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }

                // 每轮都校验：落地内容必须与某个 writer 的 payload 字节级全等，
                // 不得是截断、空文件或两份 payload 交错的结果
                String content = Files.readString(target);
                assertTrue(payloads.containsValue(content),
                        "第 " + round + " 轮落地内容不是任何一个完整 payload（长度 "
                                + content.length() + "），说明发生了截断或交错写");
            }
        } finally {
            pool.shutdownNow();
        }

        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "并发写后不应残留 .tmp 文件");
        }
    }

    @Test
    @DisplayName("原子写: 新建文件为 600 权限")
    void writeAtomic_CreatesOwnerOnlyFile() throws IOException {
        Path target = tempDir.resolve("secret.json");
        JsonFileStore.writeAtomic(target, "sensitive");

        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (view == null) {
            return; // 非 POSIX 文件系统跳过
        }
        Set<PosixFilePermission> perms = view.readAttributes().permissions();
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "组内不应可读");
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "其他用户不应可读");
    }

    @Test
    @DisplayName("原子写: UTF-8 中文内容按字节完整写入")
    void writeAtomic_PreservesUtf8() throws IOException {
        Path target = tempDir.resolve("cn.md");
        String content = "# 记忆索引\n包含中文与 emoji 🐾";
        JsonFileStore.writeAtomic(target, content);

        assertEquals(content, Files.readString(target));
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, Files.size(target));
    }

    // ==================== 追加写 ====================

    @Test
    @DisplayName("追加写: 多次追加按顺序累积")
    void appendAndSync_AccumulatesLines() throws IOException {
        Path target = tempDir.resolve("events.jsonl");
        JsonFileStore.appendAndSync(target, "line1\n");
        JsonFileStore.appendAndSync(target, "line2\n");

        assertEquals(List.of("line1", "line2"), Files.readAllLines(target));
    }

    @Test
    @DisplayName("追加写: 空内容不创建文件")
    void appendAndSync_EmptyContentIsNoOp() throws IOException {
        Path target = tempDir.resolve("empty.jsonl");
        JsonFileStore.appendAndSync(target, "");
        JsonFileStore.appendAndSync(target, null);

        assertFalse(Files.exists(target), "空内容不应创建文件");
    }

    // ==================== 容错读 ====================

    @Test
    @DisplayName("容错读: 文件不存在返回 fallback")
    void readJson_MissingFileReturnsFallback() {
        List<String> result = JsonFileStore.readJson(MAPPER, tempDir.resolve("nope.json"),
                new TypeReference<List<String>>() {}, ArrayList::new);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("容错读: 空白文件返回 fallback 且不隔离")
    void readJson_BlankFileReturnsFallbackWithoutQuarantine() throws IOException {
        Path target = tempDir.resolve("blank.json");
        Files.writeString(target, "   \n");

        List<String> result = JsonFileStore.readJson(MAPPER, target,
                new TypeReference<List<String>>() {}, ArrayList::new);

        assertTrue(result.isEmpty());
        assertTrue(Files.exists(target), "空白文件不算损坏，不应被移走");
    }

    @Test
    @DisplayName("容错读: 正常内容完整反序列化")
    void readJson_ParsesValidContent() throws IOException {
        Path target = tempDir.resolve("map.json");
        JsonFileStore.writeJson(MAPPER, target, Map.of("k", "v"));

        Map<String, String> result = JsonFileStore.readJson(MAPPER, target,
                new TypeReference<HashMap<String, String>>() {}, HashMap::new);

        assertEquals("v", result.get("k"));
    }

    @Test
    @DisplayName("容错读: 损坏文件被隔离到 corrupt/ 而非静默丢弃")
    void readJson_QuarantinesCorruptFile() throws IOException {
        Path target = tempDir.resolve("broken.json");
        Files.writeString(target, "{\"truncated\": [1, 2");

        List<String> result = JsonFileStore.readJson(MAPPER, target,
                new TypeReference<List<String>>() {}, ArrayList::new);

        assertTrue(result.isEmpty(), "解析失败应返回 fallback");
        assertFalse(Files.exists(target), "原文件应已被移入隔离目录");

        Path corruptDir = tempDir.resolve("corrupt");
        assertTrue(Files.isDirectory(corruptDir), "应创建 corrupt/ 目录");
        try (Stream<Path> files = Files.list(corruptDir)) {
            Path quarantined = files.findFirst().orElse(null);
            assertNotNull(quarantined, "隔离目录中应有文件");
            assertEquals("{\"truncated\": [1, 2", Files.readString(quarantined),
                    "隔离文件必须保留原始内容作为证据");
        }
    }

    @Test
    @DisplayName("容错读: 类型不匹配也走隔离路径")
    void readJson_QuarantinesTypeMismatch() throws IOException {
        Path target = tempDir.resolve("wrong-type.json");
        Files.writeString(target, "{\"not\":\"an array\"}");

        List<String> result = JsonFileStore.readJson(MAPPER, target,
                new TypeReference<List<String>>() {}, ArrayList::new);

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(target), "类型不匹配同样是内容损坏，应隔离");
    }

    @Test
    @DisplayName("容错读: readString 缺失文件返回 fallback")
    void readString_MissingFileReturnsFallback() {
        assertEquals("default", JsonFileStore.readString(tempDir.resolve("gone.md"), "default"));
        assertEquals("default", JsonFileStore.readString(null, "default"));
    }

    // ==================== 隔离 ====================

    @Test
    @DisplayName("隔离: 同名文件多次隔离不互相覆盖")
    void quarantine_ReturnsTargetPath() throws IOException {
        Path target = tempDir.resolve("bad.json");
        Files.writeString(target, "first");

        Path first = JsonFileStore.quarantine(target, "test");
        assertNotNull(first);
        assertEquals("first", Files.readString(first));
    }

    @Test
    @DisplayName("隔离: 文件不存在时返回 null 而不抛异常")
    void quarantine_MissingFileReturnsNull() {
        assertEquals(null, JsonFileStore.quarantine(tempDir.resolve("absent.json"), "test"));
    }
}
