package io.leavesfly.tinyclaw.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 文件持久化基础设施 —— 原子写、容错读、损坏隔离。
 *
 * <p>此前 9 个持久化类各自实现文件读写，其中 6 个直接覆盖写（{@code Files.writeString} /
 * {@code writeValue}），进程在写入中途退出就会留下半截 JSON，导致整份数据不可读。
 * 本类把 {@code JsonlSessionStore} 里已验证的正确做法提取为公共实现。</p>
 *
 * <h2>写入保证</h2>
 * <ul>
 *   <li><b>唯一临时文件名</b>：避免并发写入互相踩同一个 {@code .tmp}</li>
 *   <li><b>fsync 后再 rename</b>：数据真正落盘才替换目标，崩溃时目标文件仍是上一个完整版本</li>
 *   <li><b>ATOMIC_MOVE</b>：不支持的文件系统自动降级为普通 move</li>
 *   <li><b>目录 fsync</b>：让 rename 本身也具备持久性</li>
 *   <li><b>600 权限</b>：先建后写，避免存在 644 可读窗口（会话/记忆含敏感内容）</li>
 *   <li><b>循环写至耗尽</b>：{@code FileChannel.write} 可能短写，不循环会截断内容</li>
 * </ul>
 *
 * <h2>读取语义</h2>
 * <p>容错读：文件不存在或为空返回 fallback；<b>解析失败时把原文件隔离到
 * {@code corrupt/} 而不是静默丢弃</b>，避免上层拿到空值后覆盖式销毁原始数据。</p>
 */
public final class JsonFileStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("util.filestore");

    /** 损坏文件的隔离目录名。 */
    private static final String CORRUPT_DIR = "corrupt";

    /** 临时文件名后缀。 */
    private static final String TEMP_SUFFIX = ".tmp";

    /** 临时文件名中随机段的长度。 */
    private static final int TEMP_TOKEN_LENGTH = 8;

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private JsonFileStore() {
    }

    // ==================== 写入 ====================

    /**
     * 原子全量写入文本（JSON / JSONL / Markdown 皆可）。
     *
     * <p>写入路径：唯一临时文件 → fsync → 原子 rename → fsync 父目录。
     * 目标文件在任意时刻要么是旧的完整内容、要么是新的完整内容，不会出现中间态。</p>
     *
     * @param target  目标文件路径
     * @param content 完整内容
     * @throws IOException 写入或重命名失败
     */
    public static void writeAtomic(Path target, String content) throws IOException {
        ensureParentDir(target);
        Path temp = target.resolveSibling(target.getFileName() + "."
                + UUID.randomUUID().toString().substring(0, TEMP_TOKEN_LENGTH) + TEMP_SUFFIX);
        try {
            createWithOwnerOnlyPermissions(temp);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(channel, ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 序列化为 JSON 后原子写入。
     *
     * <p>使用调用方的 {@link ObjectMapper}，保留各自的序列化配置（缩进、时间格式、
     * NON_NULL 等），本类不强加统一格式。</p>
     *
     * @param mapper 调用方的 ObjectMapper
     * @param target 目标文件路径
     * @param value  待序列化对象
     * @throws IOException 序列化或写入失败
     */
    public static void writeJson(ObjectMapper mapper, Path target, Object value) throws IOException {
        writeAtomic(target, mapper.writeValueAsString(value));
    }

    /**
     * 追加写入并 fsync，用于 append-only 日志（JSONL）。
     *
     * <p>追加本身对已有内容无破坏性，但仍需 fsync 保证新增记录真正落盘。</p>
     *
     * @param target  目标文件路径
     * @param content 追加内容（调用方负责行尾换行）
     * @throws IOException 写入失败
     */
    public static void appendAndSync(Path target, String content) throws IOException {
        if (content == null || content.isEmpty()) {
            return;
        }
        ensureParentDir(target);
        createWithOwnerOnlyPermissions(target);
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writeFully(channel, ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
    }

    // ==================== 读取 ====================

    /**
     * 容错读取 JSON（泛型集合用）。
     *
     * @param mapper   调用方的 ObjectMapper
     * @param source   源文件路径
     * @param type     目标类型引用
     * @param fallback 文件缺失/ 为空 / 解析失败时的兜底值提供者
     * @param <T>      目标类型
     * @return 反序列化结果，或 fallback
     */
    public static <T> T readJson(ObjectMapper mapper, Path source,
                                 TypeReference<T> type, Supplier<T> fallback) {
        return read(source, fallback, json -> mapper.readValue(json, type));
    }

    /**
     * 容错读取 JSON（具体类型用）。
     *
     * @param mapper   调用方的 ObjectMapper
     * @param source   源文件路径
     * @param type     目标类型
     * @param fallback 文件缺失/ 为空 / 解析失败时的兜底值提供者
     * @param <T>      目标类型
     * @return 反序列化结果，或 fallback
     */
    public static <T> T readJson(ObjectMapper mapper, Path source,
                                 Class<T> type, Supplier<T> fallback) {
        return read(source, fallback, json -> mapper.readValue(json, type));
    }

    /**
     * 容错读取纯文本。
     *
     * @param source   源文件路径
     * @param fallback 文件缺失或读取失败时的兜底值
     * @return 文件内容，或 fallback
     */
    public static String readString(Path source, String fallback) {
        try {
            if (source == null || !Files.exists(source)) {
                return fallback;
            }
            return Files.readString(source);
        } catch (IOException e) {
            logger.warn("Failed to read file, using fallback: " + source + " - " + e.getMessage());
            return fallback;
        }
    }

    /**
     * 读取骨架：统一「不存在 / 空白 / 解析失败」三种退化路径的处理。
     *
     * <p>解析失败与 IO 失败区别对待：解析失败说明文件内容已损坏，隔离保留；
     * IO 失败（如权限、临时不可读）不隔离，避免误伤可恢复的文件。</p>
     */
    private static <T> T read(Path source, Supplier<T> fallback, JsonParser<T> parser) {
        if (source == null || !Files.exists(source)) {
            return fallback.get();
        }
        String json;
        try {
            json = Files.readString(source);
        } catch (IOException e) {
            logger.warn("Failed to read file, using fallback: " + source + " - " + e.getMessage());
            return fallback.get();
        }
        if (json == null || json.isBlank()) {
            return fallback.get();
        }
        try {
            T parsed = parser.parse(json);
            return parsed != null ? parsed : fallback.get();
        } catch (Exception e) {
            // 内容已损坏：隔离原文件保留证据，绝不让上层覆盖式销毁
            quarantine(source, "unparseable: " + e.getMessage());
            return fallback.get();
        }
    }

    /** 反序列化动作，允许抛出受检异常。 */
    @FunctionalInterface
    private interface JsonParser<T> {
        T parse(String json) throws Exception;
    }

    // ==================== 损坏隔离 ====================

    /**
     * 把无法解析的文件移入同级 {@code corrupt/} 目录保留。
     *
     * @param path   损坏的文件
     * @param reason 隔离原因（写入日志便于排查）
     * @return 隔离后的路径；隔离失败返回 null
     */
    public static Path quarantine(Path path, String reason) {
        try {
            Path parent = path.getParent();
            Path corruptDir = parent != null ? parent.resolve(CORRUPT_DIR) : Path.of(CORRUPT_DIR);
            Files.createDirectories(corruptDir);
            Path target = corruptDir.resolve(
                    path.getFileName() + ".corrupt." + System.currentTimeMillis());
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
            logger.error("File quarantined, original content preserved", Map.of(
                    "file", path.toString(),
                    "reason", String.valueOf(reason),
                    "quarantined_to", target.toString()));
            return target;
        } catch (Exception e) {
            logger.error("Failed to quarantine corrupt file: " + path,
                    Map.of("error", String.valueOf(e.getMessage())));
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 确保父目录存在。
     */
    private static void ensureParentDir(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * 以 600 权限创建文件（若不存在）。先建后写，避免存在 644 可读窗口。
     */
    private static void createWithOwnerOnlyPermissions(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (FileAlreadyExistsException e) {
            // 并发创建，无需处理
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（Windows）
            Files.createFile(path);
        }
    }

    /**
     * 将 buffer 完整写入 channel：{@code FileChannel.write} 可能发生短写，
     * 需循环写至耗尽，否则内容被截断。
     */
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * 尽力 fsync 目录项，让 rename 本身也具备持久性；不支持的平台静默跳过。
     */
    private static void syncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception e) {
            // Windows 等平台不允许对目录 open/force
        }
    }
}
