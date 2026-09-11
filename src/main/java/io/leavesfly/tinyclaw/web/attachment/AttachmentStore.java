package io.leavesfly.tinyclaw.web.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 附件存储（P2）：workspace/attachments/ 下的原始字节、元信息与解析缓存。
 *
 * <p>上传与解析分离：保存原始字节后立即返回（UPLOADING→PARSING），解析在独立
 * 有界线程池执行（并发 2、等待队列 16，与 HTTP 工作线程隔离）；队列满时明确
 * 返回忙碌，不静默排队。解析完成写 {@code <id>.txt} 并把状态置为 READY/FAILED。</p>
 *
 * <p>安全边界：新资源使用 UUID，文件名仅用于展示；读取请求体时即限制大小
 * （由 Handler 层执行）；符号链接在保存/读取两侧都做 realpath 校验。</p>
 */
public class AttachmentStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /** 单附件大小上限（10 MiB）。 */
    public static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    /** 解析线程池参数：并发 2、等待队列 16。 */
    private static final int PARSE_THREADS = 2;
    private static final int PARSE_QUEUE = 16;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storeDir;
    private final ThreadPoolExecutor parseExecutor;
    /** id → 元信息（内存索引，可从磁盘重建）。 */
    private final ConcurrentHashMap<String, Attachment> index = new ConcurrentHashMap<>();

    /**
     * @param storeDir 存储目录（workspace/attachments）；null 表示禁用（测试）
     */
    public AttachmentStore(String storeDir) {
        this.storeDir = storeDir != null ? Paths.get(storeDir) : null;
        this.parseExecutor = new ThreadPoolExecutor(
                PARSE_THREADS, PARSE_THREADS, 60, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(PARSE_QUEUE),
                r -> {
                    Thread t = new Thread(r, "attachment-parser");
                    t.setDaemon(true);
                    return t;
                });
        this.parseExecutor.allowCoreThreadTimeOut(true);
        if (this.storeDir != null) {
            try {
                Files.createDirectories(this.storeDir);
            } catch (IOException e) {
                logger.error("Failed to create attachments dir", Map.of(
                        "path", storeDir, "error", String.valueOf(e.getMessage())));
            }
        }
    }

    /**
     * 保存原始字节并异步解析。
     *
     * @return 已登记的附件（状态 PARSING 或 FAILED——队列满时同步失败）
     */
    public Attachment save(byte[] raw, String name, String mediaType, String sessionKey) throws IOException {
        if (raw == null || raw.length == 0) {
            throw new IOException("空文件");
        }
        if (raw.length > MAX_FILE_SIZE) {
            throw new IOException("文件超过 " + (MAX_FILE_SIZE / 1024 / 1024) + " MiB 上限");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Attachment meta = Attachment.create(id, name, mediaType, raw.length,
                sha256Prefix(raw), sessionKey);

        if (storeDir != null) {
            // 原始字节落盘
            Path bin = storeDir.resolve(id + ".bin");
            Files.write(bin, raw);
            persistMeta(meta);
        }
        index.put(id, meta);

        // 异步解析：立即返回 PARSING，解析完成后置 READY/FAILED
        meta.mark(Attachment.Status.PARSING, null);
        persistMeta(meta);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    AttachmentParser.Result result = AttachmentParser.parse(raw, name, mediaType);
                    if (storeDir != null) {
                        Files.writeString(storeDir.resolve(id + ".txt"), result.text(), StandardCharsets.UTF_8);
                    }
                    meta.parsedChars = result.text().length();
                    meta.truncated = result.truncated();
                    meta.mark(Attachment.Status.READY, null);
                    logger.info("Attachment ready", Map.of(
                            "id", id, "chars", meta.parsedChars, "truncated", meta.truncated));
                } catch (Exception e) {
                    meta.mark(Attachment.Status.FAILED, e.getMessage());
                    logger.warn("Attachment parse failed", Map.of(
                            "id", id, "error", String.valueOf(e.getMessage())));
                } finally {
                    persistMeta(meta);
                }
            }, parseExecutor);
        } catch (RejectedExecutionException e) {
            meta.mark(Attachment.Status.FAILED, "解析队列已满，请稍后重试");
            persistMeta(meta);
        }
        return meta;
    }

    /** 按 id 查询元信息。 */
    public Attachment get(String id) {
        if (id == null) {
            return null;
        }
        Attachment meta = index.get(id);
        if (meta == null && storeDir != null) {
            // 内存索引未命中（重启后）：从磁盘恢复
            meta = JsonFileStore.readJson(MAPPER, storeDir.resolve(id + ".json"),
                    Attachment.class, () -> null);
            if (meta != null) {
                index.put(id, meta);
            }
        }
        return meta;
    }

    /** 读取解析文本（仅 READY 状态；id 校验防路径拼接注入）。 */
    public String readParsedText(String id) throws IOException {
        Attachment meta = requireValidId(id);
        if (meta.statusEnum() != Attachment.Status.READY) {
            throw new IOException("附件未就绪（当前状态 " + meta.status + "）");
        }
        if (storeDir == null) {
            throw new IOException("附件存储未启用");
        }
        Path txt = storeDir.resolve(id + ".txt").normalize();
        if (!txt.startsWith(storeDir)) {
            throw new IOException("非法附件路径");
        }
        return Files.readString(txt, StandardCharsets.UTF_8);
    }

    /** 读取原始字节（下载用；id 校验防路径拼接注入）。 */
    public byte[] readRaw(String id) throws IOException {
        Attachment meta = requireValidId(id);
        if (storeDir == null) {
            throw new IOException("附件存储未启用");
        }
        Path bin = storeDir.resolve(id + ".bin").normalize();
        if (!bin.startsWith(storeDir)) {
            throw new IOException("非法附件路径");
        }
        return Files.readAllBytes(bin);
    }

    /** 附件是否就绪（前端轮询用）。 */
    public boolean isReady(String id) {
        Attachment meta = get(id);
        return meta != null && meta.statusEnum() == Attachment.Status.READY;
    }

    /**
     * 把附件解析文本构建为注入用户消息的上下文块。
     *
     * <p>显式选中的附件全文注入（受解析输出上限约束），带稳定来源 ID 标记，
     * 模型引用时前端可据此渲染来源。缺失/未就绪的附件明确标注状态。</p>
     */
    public String buildContextBlock(java.util.List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String id : attachmentIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Attachment meta = get(id);
            if (meta == null) {
                sb.append("\n\n---\n[附件 ").append(id).append("：不存在或已删除]\n");
                continue;
            }
            if (meta.statusEnum() != Attachment.Status.READY) {
                sb.append("\n\n---\n[附件「").append(meta.name).append("」(").append(id)
                        .append(")：").append("解析").append(meta.statusEnum() == Attachment.Status.FAILED
                                ? "失败——" + meta.parseError : "尚未完成").append("]\n");
                continue;
            }
            try {
                String text = readParsedText(id);
                sb.append("\n\n---\n[附件「").append(meta.name).append("」，来源ID ").append(id).append("]\n")
                        .append(text).append("\n[附件 ").append(id).append(" 结束]\n");
            } catch (IOException e) {
                sb.append("\n\n---\n[附件「").append(meta.name).append("」(").append(id)
                        .append(")：读取失败——").append(e.getMessage()).append("]\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 停机：关闭解析线程池。 */
    public void shutdown() {
        parseExecutor.shutdown();
    }

    // ==================== 内部 ====================

    /** id 合法性校验（UUID 短串，防路径拼接与遍历）。 */
    private Attachment requireValidId(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{12}")) {
            throw new IOException("非法附件 ID");
        }
        Attachment meta = get(id);
        if (meta == null) {
            throw new IOException("附件不存在");
        }
        return meta;
    }

    private void persistMeta(Attachment meta) {
        if (storeDir == null || meta == null || meta.id == null) {
            return;
        }
        try {
            JsonFileStore.writeJson(MAPPER, storeDir.resolve(meta.id + ".json"), meta);
        } catch (IOException e) {
            logger.error("Failed to persist attachment meta", Map.of(
                    "id", meta.id, "error", String.valueOf(e.getMessage())));
        }
    }

    private static String sha256Prefix(byte[] raw) {
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
}
