package io.leavesfly.tinyclaw.config;

/**
 * 心跳活跃时段配置
 * <p>
 * 仅当系统时间落在 [start, end) 窗口内才执行心跳；
 * start == end 视为零宽窗口（全部跳过）。时区缺省用系统时区。
 * 时间格式为 HH:mm（24 小时制）。
 * </p>
 */
public class ActiveHours {

    /**
     * 窗口开始时间（HH:mm，含）
     */
    private String start;

    /**
     * 窗口结束时间（HH:mm，不含）
     */
    private String end;

    /**
     * 时区 ID（如 Asia/Shanghai）
     * <p>null 或空时使用系统默认时区</p>
     */
    private String timezone;

    public ActiveHours() {}

    public ActiveHours(String start, String end, String timezone) {
        this.start = start;
        this.end = end;
        this.timezone = timezone;
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start;
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
