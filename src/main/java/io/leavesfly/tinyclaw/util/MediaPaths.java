package io.leavesfly.tinyclaw.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 媒体文件路径的统一解析与边界校验。
 *
 * <p>图片等媒体文件只有两个合法来源：用户经 {@code /api/upload} 上传后落在
 * {@code workspace/uploads} 下的文件，以及各通道（Telegram/Discord 等）下载附件时
 * 写入的 {@link #channelMediaDir() 通道媒体目录}。</p>
 *
 * <p>媒体路径最终会被 {@code LLMRequestBuilder} 读成 Base64 送进 LLM 请求，
 * 因此路径来自不可信输入（HTTP 请求体）时必须做归一化与根目录归属校验：
 * 否则 {@code ../} 或绝对路径可以让调用方读取工作区外的任意文件并外发到模型服务商。</p>
 */
public final class MediaPaths {

    /** 通道下载附件的落地目录名，位于系统临时目录下 */
    private static final String CHANNEL_MEDIA_DIR_NAME = "tinyclaw_media";

    private MediaPaths() {
    }

    /**
     * 通道媒体目录：各通道下载的附件统一落在此处。
     *
     * @return 归一化后的绝对路径
     */
    public static Path channelMediaDir() {
        return Paths.get(System.getProperty("java.io.tmpdir"), CHANNEL_MEDIA_DIR_NAME)
                .toAbsolutePath().normalize();
    }

    /**
     * 把媒体路径解析为绝对路径，并校验它落在允许的根目录内。
     *
     * <p>相对路径按 workspace 解析；绝对路径直接归一化。两种情况都要求结果位于
     * workspace 或通道媒体目录之内，越界返回 null 由调用方跳过该文件。</p>
     *
     * @param workspace 工作区根目录
     * @param candidate 待校验的媒体路径（相对或绝对）
     * @return 归一化后的绝对路径；入参为空或越界时返回 null
     */
    public static Path resolveMediaPath(String workspace, String candidate) {
        if (candidate == null || candidate.isEmpty() || workspace == null || workspace.isEmpty()) {
            return null;
        }

        Path workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
        Path candidatePath = Paths.get(candidate);
        Path resolved = (candidatePath.isAbsolute()
                ? candidatePath
                : workspaceRoot.resolve(candidatePath)).toAbsolutePath().normalize();

        if (resolved.startsWith(workspaceRoot) || resolved.startsWith(channelMediaDir())) {
            return resolved;
        }
        return null;
    }
}
