package io.leavesfly.tinyclaw.web.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 通用附件元信息（P2）。
 *
 * <p>原始文件与解析缓存分离存储于 workspace/attachments/ 下：
 * 元信息 {@code <id>.json}，原始字节 {@code <id>.bin}，解析文本 {@code <id>.txt}。
 * 聊天消息只保存附件 ID 引用，正文注入由 ChatHandler 在提交时完成。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Attachment {

    /** 附件状态。 */
    public enum Status { UPLOADING, PARSING, READY, FAILED }

    /** 附件 ID（UUID 短串，文件名仅用于展示）。 */
    public String id;
    /** 原始文件名（仅展示，不参与寻址）。 */
    public String name;
    /** MIME 类型（按文件头探测修正后）。 */
    public String mediaType;
    /** 原始大小（字节）。 */
    public long size;
    /** 内容 SHA-256 前 16 位（去重与外部改动检测用）。 */
    public String hash;
    /** 当前状态。 */
    public String status;
    /** 归属会话（上传时指定）。 */
    public String sessionKey;
    /** 解析错误信息（仅 FAILED）。 */
    public String parseError;
    /** 解析后文本字符数（仅 READY）。 */
    public long parsedChars;
    /** 是否因超限被截断。 */
    public boolean truncated;
    /** 创建时间（epoch millis）。 */
    public long createdAt;
    /** 最后更新时间。 */
    public long updatedAt;

    public Attachment() {
        // Jackson 反序列化需要
    }

    public static Attachment create(String id, String name, String mediaType, long size,
                                     String hash, String sessionKey) {
        Attachment a = new Attachment();
        a.id = id;
        a.name = name != null ? name : "attachment";
        a.mediaType = mediaType;
        a.size = size;
        a.hash = hash;
        a.status = Status.UPLOADING.name();
        a.sessionKey = sessionKey;
        a.createdAt = System.currentTimeMillis();
        a.updatedAt = a.createdAt;
        return a;
    }

    public void mark(Status next, String error) {
        this.status = next.name();
        this.updatedAt = System.currentTimeMillis();
        if (error != null) {
            this.parseError = error.length() > 200 ? error.substring(0, 200) + "…" : error;
        }
    }

    /** 状态枚举（未知值按 FAILED 处理，防误判）。 */
    public Status statusEnum() {
        try {
            return Status.valueOf(status);
        } catch (Exception e) {
            return Status.FAILED;
        }
    }
}
