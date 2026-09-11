package io.leavesfly.tinyclaw.web.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.tools.ArtifactRecorder;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 成果存储（P3）：write_file / edit_file 实际写盘成功后的服务端登记与版本快照。
 *
 * <p>定位：替换前端「TOOL_START 意图登记」——只在实际文件操作成功处登记，
 * 记录规范化路径、hash 与递增 revision；每次成功写入保存版本快照到
 * workspace/artifacts/versions/&lt;id&gt;/&lt;rev&gt;.snap，切会话与刷新后仍可找回。</p>
 *
 * <p>实现 {@link ArtifactRecorder}：工具层上报失败静默吞掉（登记不影响工具结果）。
 * 登记与查询线程安全（map + 原子写）。</p>
 */
public class ArtifactStore implements ArtifactRecorder {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /** 版本快照大小上限：超过则不保存快照（大文件仍登记与下载，仅无版本回放）。 */
    private static final int SNAPSHOT_MAX_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storeDir;
    private final Path versionsDir;
    /** id → 记录。 */
    private final ConcurrentHashMap<String, ArtifactRecord> byId = new ConcurrentHashMap<>();
    /** 「会话 + 规范化路径」 → id（同文件多次写复用同一成果条目）。 */
    private final ConcurrentHashMap<String, String> bySessionPath = new ConcurrentHashMap<>();

    /**
     * @param storeDir 存储目录（workspace/artifacts）；null 表示禁用（测试/未启用）
     */
    public ArtifactStore(String storeDir) {
        this.storeDir = storeDir != null ? Paths.get(storeDir) : null;
        this.versionsDir = this.storeDir != null ? this.storeDir.resolve("versions") : null;
        if (this.storeDir != null) {
            try {
                Files.createDirectories(this.storeDir);
                Files.createDirectories(versionsDir);
                restoreFromDisk();
            } catch (IOException e) {
                logger.error("Failed to create artifacts dir", Map.of(
                        "path", storeDir, "error", String.valueOf(e.getMessage())));
            }
        }
    }

    // ==================== ArtifactRecorder ====================

    /**
     * 工具写盘成功回调：新建或递增版本，并保存快照。
     * 任何失败只记日志，不影响工具执行。
     */
    @Override
    public void record(String sessionKey, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String session = sessionKey != null ? sessionKey : "global";
        String normalized;
        try {
            normalized = Paths.get(path).normalize().toAbsolutePath().toString();
        } catch (Exception e) {
            return;
        }
        try {
            byte[] content = Files.readAllBytes(Paths.get(normalized));
            String hash = sha256Prefix(content);
            String mapKey = session + "|" + normalized;
            String id = bySessionPath.computeIfAbsent(mapKey, k ->
                    UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            ArtifactRecord record = byId.computeIfAbsent(id, k ->
                    ArtifactRecord.create(k, session, normalized,
                            fileNameOf(normalized), guessMime(normalized), hash));
            synchronized (record) {
                record.revision++;
                record.hash = hash;
                record.updatedAt = System.currentTimeMillis();
                record.mediaType = guessMime(normalized);
                if (storeDir != null) {
                    // 版本快照（超限跳过：登记仍在，仅无该版本回放）
                    if (content.length <= SNAPSHOT_MAX_BYTES) {
                        Path snapDir = versionsDir.resolve(record.id);
                        Files.createDirectories(snapDir);
                        Files.write(snapDir.resolve(record.revision + ".snap"), content);
                    }
                    JsonFileStore.writeJson(MAPPER, storeDir.resolve(record.id + ".json"), record);
                }
            }
            logger.info("Artifact recorded", Map.of(
                    "id", record.id, "revision", record.revision,
                    "path", normalized, "session", session, "bytes", content.length));
        } catch (Exception e) {
            logger.warn("Artifact record failed (ignored)", Map.of(
                    "path", normalized, "error", String.valueOf(e.getMessage())));
        }
    }

    // ==================== 查询 ====================

    /** 按 id 查询。 */
    public ArtifactRecord get(String id) {
        if (id == null) {
            return null;
        }
        ArtifactRecord record = byId.get(id);
        if (record == null && storeDir != null) {
            record = JsonFileStore.readJson(MAPPER, storeDir.resolve(id + ".json"),
                    ArtifactRecord.class, () -> null);
            if (record != null) {
                byId.put(id, record);
            }
        }
        return record;
    }

    /** 会话的成果列表（按最后写入倒序）；文件已不存在标记 exists=false。 */
    public List<ArtifactRecord> listBySession(String sessionKey) {
        List<ArtifactRecord> result = new ArrayList<>();
        for (ArtifactRecord r : byId.values()) {
            if (sessionKey.equals(r.sessionKey)) {
                result.add(r);
            }
        }
        result.sort(Comparator.comparingLong((ArtifactRecord r) -> r.updatedAt).reversed());
        return result;
    }

    /** 读取指定版本内容字节：revision=0 表示当前文件本身。 */
    public byte[] readContent(String id, int revision) throws IOException {
        ArtifactRecord record = requireRecord(id);
        if (revision <= 0 || revision > record.revision) {
            // 当前文件（实时）
            Path file = Paths.get(record.path);
            if (!Files.exists(file)) {
                throw new IOException("原文件已移除");
            }
            return Files.readAllBytes(file);
        }
        if (versionsDir == null) {
            throw new IOException("版本快照未启用");
        }
        Path snap = versionsDir.resolve(record.id).resolve(revision + ".snap").normalize();
        if (!snap.startsWith(versionsDir) || !Files.exists(snap)) {
            throw new IOException("版本快照不存在（可能超限未保存）");
        }
        return Files.readAllBytes(snap);
    }

    /** 版本列表：[{revision, hash, updatedAt}]（含当前文件的实时 hash 与存在性）。 */
    public List<Map<String, Object>> versions(String id) throws IOException {
        ArtifactRecord record = requireRecord(id);
        List<Map<String, Object>> list = new ArrayList<>();
        Path file = Paths.get(record.path);
        boolean currentExists = Files.exists(file);
        String currentHash = currentExists ? sha256Prefix(Files.readAllBytes(file)) : null;
        for (int rev = 1; rev <= record.revision; rev++) {
            Map<String, Object> v = new java.util.HashMap<>();
            v.put("revision", rev);
            v.put("hash", rev == record.revision ? record.hash : snapshotHash(record.id, rev));
            v.put("updatedAt", record.updatedAt);
            v.put("isCurrent", rev == record.revision);
            v.put("currentExists", currentExists);
            v.put("currentHashChanged", rev == record.revision && currentExists
                    && currentHash != null && !currentHash.equals(record.hash));
            list.add(v);
        }
        return list;
    }

    /** 读取记录，不存在抛 IOException。 */
    private ArtifactRecord requireRecord(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{12}")) {
            throw new IOException("非法成果 ID");
        }
        ArtifactRecord record = get(id);
        if (record == null) {
            throw new IOException("成果不存在");
        }
        return record;
    }

    /** 快照 hash（读取失败返回空）。 */
    private String snapshotHash(String id, int revision) {
        if (versionsDir == null) {
            return "";
        }
        try {
            return sha256Prefix(Files.readAllBytes(
                    versionsDir.resolve(id).resolve(revision + ".snap")));
        } catch (IOException e) {
            return "";
        }
    }

    // ==================== 持久化与恢复 ====================

    private void restoreFromDisk() {
        if (storeDir == null || !Files.isDirectory(storeDir)) {
            return;
        }
        int restored = 0;
        try (Stream<Path> files = Files.list(storeDir)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) {
                    continue;
                }
                try {
                    ArtifactRecord record = JsonFileStore.readJson(MAPPER, file, ArtifactRecord.class, () -> null);
                    if (record == null || record.id == null || record.path == null) {
                        continue;
                    }
                    byId.put(record.id, record);
                    bySessionPath.put(record.sessionKey + "|" + record.path, record.id);
                    restored++;
                } catch (Exception e) {
                    logger.warn("Skip unreadable artifact record", Map.of(
                            "file", fileName, "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException e) {
            logger.error("Failed to scan artifacts dir", Map.of(
                    "path", storeDir.toString(), "error", String.valueOf(e.getMessage())));
            return;
        }
        if (restored > 0) {
            logger.info("Artifact records restored", Map.of("restored", restored));
        }
    }

    // ==================== 工具 ====================

    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    static String guessMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".go") || lower.endsWith(".rs")
                || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".sh")) {
            return "text/x-code";
        }
        return "text/plain";
    }

    static String sha256Prefix(byte[] raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读文本内容（预览用；二进制抛 IOException 提示下载）。 */
    public String readTextContent(String id, int revision) throws IOException {
        ArtifactRecord record = requireRecord(id);
        byte[] raw = readContent(id, revision);
        String mime = record.mediaType != null ? record.mediaType : "text/plain";
        if (mime.startsWith("image/") || "application/pdf".equals(mime)) {
            throw new IOException("二进制内容，请使用下载");
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
