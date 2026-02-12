package io.leavesfly.tinyclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload for a cron job
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronPayload {
    
    private String kind;
    private String message;
    private boolean deliver = true;
    private String channel;
    private String to;
    
    public CronPayload() {}
    
    public CronPayload(String message, boolean deliver, String channel, String to) {
        this.kind = "agent_turn";
        this.message = message;
        this.deliver = deliver;
        this.channel = channel;
        this.to = to;
    }
    
    // Getters and setters
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public boolean isDeliver() { return deliver; }
    public void setDeliver(boolean deliver) { this.deliver = deliver; }
    
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
}
