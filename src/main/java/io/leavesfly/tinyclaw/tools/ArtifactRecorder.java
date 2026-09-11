package io.leavesfly.tinyclaw.tools;

/**
 * 成果登记回调（P3）：文件工具在实际写盘成功后上报。
 *
 * <p>工具层只依赖本接口，登记实现位于 web 包（ArtifactStore），避免
 * tools → web 的反向依赖。上报失败绝不能影响工具执行结果——实现方自行吞异常。</p>
 */
public interface ArtifactRecorder {

    /**
     * 登记一次成功的文件写入。
     *
     * @param sessionKey 归属会话（可能为 null：非会话上下文，如心跳；此时按全局登记或不登记）
     * @param path       写入的文件绝对路径（已过安全检查）
     */
    void record(String sessionKey, String path);
}
