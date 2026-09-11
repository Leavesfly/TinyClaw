package io.leavesfly.tinyclaw.web.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 成果登记记录（P3）。
 *
 * <p>一次成果对应一个「会话内被写过的文件」；同一文件多次写入递增 revision，
 * 每个成功写入的版本保存快照（快照文件存 workspace/artifacts/versions/）。
 * 当前是否仍存在由读时探测（exists 字段仅作缓存提示）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactRecord {

    /** 成果 ID（UUID 短串）。 */
    public String id;
    /** 归属会话。 */
    public String sessionKey;
    /** 目标文件绝对路径（规范化）。 */
    public String path;
    /** 展示文件名。 */
    public String name;
    /** 推断 MIME。 */
    public String mediaType;
    /** 当前版本号（从 1 递增）。 */
    public int revision;
    /** 最新内容 hash（SHA-256 前 16 位）。 */
    public String hash;
    /** 首次登记时间。 */
    public long createdAt;
    /** 最后写入时间。 */
    public long updatedAt;

    public ArtifactRecord() {
        // Jackson
    }

    public static ArtifactRecord create(String id, String sessionKey, String path,
                                        String name, String mediaType, String hash) {
        ArtifactRecord r = new ArtifactRecord();
        r.id = id;
        r.sessionKey = sessionKey;
        r.path = path;
        r.name = name;
        r.mediaType = mediaType;
        r.revision = 0;
        r.hash = hash;
        r.createdAt = System.currentTimeMillis();
        r.updatedAt = r.createdAt;
        return r;
    }
}
