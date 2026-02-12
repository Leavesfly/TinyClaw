package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents a cron schedule configuration
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronSchedule {
    
    public enum ScheduleKind {
        AT("at"),
        EVERY("every"),
        CRON("cron");

        private final String value;

        ScheduleKind(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static ScheduleKind fromValue(String value) {
            for (ScheduleKind kind : values()) {
                if (kind.value.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown schedule kind: " + value);
        }
    }
    
    private ScheduleKind kind;
    private Long atMs;
    private Long everyMs;
    private String expr;
    private String tz;
    
    public CronSchedule() {}
    
    public CronSchedule(ScheduleKind kind) {
        this.kind = kind;
    }
    
    // Factory methods
    public static CronSchedule at(long atMs) {
        CronSchedule s = new CronSchedule(ScheduleKind.AT);
        s.setAtMs(atMs);
        return s;
    }
    
    public static CronSchedule every(long everyMs) {
        CronSchedule s = new CronSchedule(ScheduleKind.EVERY);
        s.setEveryMs(everyMs);
        return s;
    }
    
    public static CronSchedule cron(String expr) {
        CronSchedule s = new CronSchedule(ScheduleKind.CRON);
        s.setExpr(expr);
        return s;
    }
    
    // Getters and setters
    public ScheduleKind getKind() { return kind; }
    public void setKind(ScheduleKind kind) { this.kind = kind; }
    
    public Long getAtMs() { return atMs; }
    public void setAtMs(Long atMs) { this.atMs = atMs; }
    
    public Long getEveryMs() { return everyMs; }
    public void setEveryMs(Long everyMs) { this.everyMs = everyMs; }
    
    public String getExpr() { return expr; }
    public void setExpr(String expr) { this.expr = expr; }
    
    public String getTz() { return tz; }
    public void setTz(String tz) { this.tz = tz; }
}