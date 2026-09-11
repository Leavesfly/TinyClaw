package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 定时任务负载类
 * 定义任务执行时的具体内容和目标信息
 *
 * <p>P6 扩展字段（旧 JSON 缺失时均为 null，保持旧语义）：</p>
 * <ul>
 *   <li>{@code runMode}：NEW_SESSION（默认，每次运行新建会话）或 CONTINUE_SESSION
 *       （用户明确选择后继续同一专用会话；忙碌时跳过本轮并记录原因）；</li>
 *   <li>{@code projectId}：任务归属项目，运行时把新会话的项目标记设为该值，
 *       项目指令与项目记忆域随会话注入；</li>
 *   <li>{@code sourceSessionKey}：来源会话（从会话创建任务时记录，仅作回溯导航）；</li>
 *   <li>{@code attachmentIds}：用户确认复用的资料引用（运行时注入任务消息，
 *       新会话模式不复制源会话历史）；</li>
 *   <li>{@code outputTarget}：输出要求/目标描述（随任务指令注入）。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronPayload {

    /** 执行模式：每次运行新建会话（默认）。 */
    public static final String RUN_MODE_NEW_SESSION = "NEW_SESSION";

    /** 执行模式：继续同一专用会话（用户明确选择；忙碌时跳过本轮）。 */
    public static final String RUN_MODE_CONTINUE_SESSION = "CONTINUE_SESSION";

    private String kind;
    private String message;
    private String channel;
    private String to;

    private String runMode;
    private String projectId;
    private String sourceSessionKey;
    private List<String> attachmentIds;
    private String outputTarget;

    public CronPayload() {}

    public CronPayload(String message, String channel, String to) {
        this.kind = "agent_turn";
        this.message = message;
        this.channel = channel;
        this.to = to;
    }

    // Getter 和 Setter 方法
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    /** 执行模式；null/非法值按 NEW_SESSION 处理（旧行为兼容）。 */
    public String getRunMode() { return runMode; }
    public void setRunMode(String runMode) { this.runMode = runMode; }

    /** 规范化执行模式：仅认识 NEW_SESSION / CONTINUE_SESSION，其余按 NEW_SESSION。 */
    public String effectiveRunMode() {
        return RUN_MODE_CONTINUE_SESSION.equals(runMode)
                ? RUN_MODE_CONTINUE_SESSION : RUN_MODE_NEW_SESSION;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSourceSessionKey() { return sourceSessionKey; }
    public void setSourceSessionKey(String sourceSessionKey) { this.sourceSessionKey = sourceSessionKey; }

    public List<String> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<String> attachmentIds) { this.attachmentIds = attachmentIds; }

    public String getOutputTarget() { return outputTarget; }
    public void setOutputTarget(String outputTarget) { this.outputTarget = outputTarget; }
}
