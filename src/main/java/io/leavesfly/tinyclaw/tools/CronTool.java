package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.bus.MessageBus;
import io.leavesfly.tinyclaw.bus.OutboundMessage;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务工具 - 调度提醒和任务
 * 
 * 允许Agent创建、管理和执行定时任务。
 * 这是系统自动化能力的核心工具。
 * 
 * 核心功能：
 * - 任务调度：使用标准cron表达式创建定时任务
 * - 任务管理：支持列出、启用、禁用、删除任务
 * - 消息发送：定时任务触发时自动发送消息到指定通道
 * - 灵活执行：支持立即执行或延迟执行任务
 * 
 * 支持的操作：
 * - add：添加新任务（指定cron表达式、消息内容、目标通道）
 * - list：列出所有已调度的任务
 * - remove：删除指定任务
 * - enable/disable：启用或禁用任务
 * 
 * 设计特点：
 * - 与CronService紧密集成
 * - 支持多通道消息发送
 * - 提供友好的错误处理和用户反馈
 * - 任务状态持久化存储
 * 
 * 使用场景：
 * - 设置每日提醒（如"每天早上9点提醒喝水"）
 * - 定期执行系统维护任务
 * - 创建周期性通知
 * - 自动化日常工作任务
 */
public class CronTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("tools.cron");
    
    private final CronService cronService;
    private final JobExecutor executor;
    private final MessageBus msgBus;
    
    private String channel = "";
    private String chatId = "";
    
    /**
     * 通过Agent执行任务的接口
     * 
     * 定义了定时任务执行器需要实现的方法，
     * 用于将定时任务的结果通过Agent处理并发送。
     */
    public interface JobExecutor {
        String processDirectWithChannel(String content, String sessionKey, String channel, String chatId) throws Exception;
    }
    
    public CronTool(CronService cronService, JobExecutor executor, MessageBus msgBus) {
        this.cronService = cronService;
        this.executor = executor;
        this.msgBus = msgBus;
    }
    
    @Override
    public String name() {
        return "cron";
    }
    
    @Override
    public String description() {
       return "调度提醒和任务。重要：当用户要求被提醒或调度时，" +
               "您必须调用此工具。使用 'at_seconds' 进行一次性提醒（例如，'10分钟后提醒我' → at_seconds=600）。" +
               "仅在重复任务时使用 'every_seconds'（例如，'每2小时' → every_seconds=7200）。" +
               "使用 'cron_expr' 进行复杂的重复调度（例如，'0 9 * * *' 表示每天上午9点）。";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("add", "list", "remove", "enable", "disable"));
        action.put("description", "要执行的操作。当用户想要调度提醒或任务时使用 'add'。");
        properties.put("action", action);
        
        Map<String, Object> message = new HashMap<>();
        message.put("type", "string");
        message.put("description", "触发时显示的提醒/任务消息（add 操作必需）");
        properties.put("message", message);
        
        Map<String, Object> atSeconds = new HashMap<>();
        atSeconds.put("type", "integer");
        atSeconds.put("description", "一次性提醒：从现在起多少秒后触发（例如，600表示10分钟后）。" +
                                     "用于一次性提醒，如 '10分钟后提醒我'。");
        properties.put("at_seconds", atSeconds);
        
        Map<String, Object> everySeconds = new HashMap<>();
        everySeconds.put("type", "integer");
        everySeconds.put("description", "重复间隔（秒）（例如，3600表示每小时）。" +
                                        "仅用于重复任务，如 '每2小时' 或 '每天提醒'。");
        properties.put("every_seconds", everySeconds);
        
        Map<String, Object> cronExpr = new HashMap<>();
        cronExpr.put("type", "string");
        cronExpr.put("description", "用于复杂重复调度的 Cron 表达式（例如，'0 9 * * *' 表示每天上午9点）。" +
                                    "用于复杂的重复调度。");
        properties.put("cron_expr", cronExpr);
        
        Map<String, Object> jobId = new HashMap<>();
        jobId.put("type", "string");
        jobId.put("description", "任务 ID（用于 remove/enable/disable 操作）");
        properties.put("job_id", jobId);
        
        Map<String, Object> deliver = new HashMap<>();
        deliver.put("type", "boolean");
        deliver.put("description", "如果为 true，直接将消息发送到通道。如果为 false，让 Agent 处理消息（用于复杂任务）。默认：true");
        properties.put("deliver", deliver);
        
        params.put("properties", properties);
        params.put("required", List.of("action"));
        
        return params;
    }
    
    /**
     * 设置任务创建的上下文
     */
    public void setContext(String channel, String chatId) {
        this.channel = channel;
        this.chatId = chatId;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        if (action == null) {
            return "错误: 操作参数是必需的";
        }
        
        switch (action) {
            case "add":
                return addJob(args);
            case "list":
                return listJobs();
            case "remove":
                return removeJob(args);
            case "enable":
                return enableJob(args, true);
            case "disable":
                return enableJob(args, false);
            default:
                return "错误: 未知操作: " + action;
        }
    }
    
    private String addJob(Map<String, Object> args) {
        if (channel.isEmpty() || chatId.isEmpty()) {
            return "错误: 无会话上下文（通道/聊天ID未设置）。请在活跃对话中使用此工具。";
        }
        
        String message = (String) args.get("message");
        if (message == null || message.isEmpty()) {
            return "错误: add 操作需要消息参数";
        }
        
        CronSchedule schedule;
        
        // 检查 at_seconds、every_seconds 或 cron_expr
        Number atSeconds = (Number) args.get("at_seconds");
        Number everySeconds = (Number) args.get("every_seconds");
        String cronExpr = (String) args.get("cron_expr");
        
        if (atSeconds != null) {
            long atMs = System.currentTimeMillis() + atSeconds.longValue() * 1000;
            schedule = CronSchedule.at(atMs);
        } else if (everySeconds != null) {
            long everyMs = everySeconds.longValue() * 1000;
            schedule = CronSchedule.every(everyMs);
        } else if (cronExpr != null && !cronExpr.isEmpty()) {
            schedule = CronSchedule.cron(cronExpr);
        } else {
            return "错误: 必须提供 at_seconds、every_seconds 或 cron_expr 之一";
        }
        
        // 读取 deliver 参数，默认为 true
        boolean deliver = true;
        if (args.containsKey("deliver") && args.get("deliver") instanceof Boolean) {
            deliver = (Boolean) args.get("deliver");
        }
        
        // Truncate message for job name
        String messagePreview = StringUtils.truncate(message, 30);
        
        CronJob job = cronService.addJob(messagePreview, schedule, message, deliver, channel, chatId);
        
        logger.info("Added cron job", java.util.Map.of(
                "job_id", job.getId(),
                "name", messagePreview,
                "kind", schedule.getKind()
        ));
        
        return "Created job '" + job.getName() + "' (id: " + job.getId() + ")";
    }
    
    private String listJobs() {
        List<CronJob> jobs = cronService.listJobs(false);
        
        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }
        
        StringBuilder result = new StringBuilder("Scheduled jobs:\n");
        for (CronJob j : jobs) {
            String scheduleInfo;
            if (CronSchedule.ScheduleKind.EVERY == j.getSchedule().getKind() && j.getSchedule().getEveryMs() != null) {
                scheduleInfo = "every " + (j.getSchedule().getEveryMs() / 1000) + "s";
            } else if (CronSchedule.ScheduleKind.CRON == j.getSchedule().getKind()) {
                scheduleInfo = j.getSchedule().getExpr();
            } else if (CronSchedule.ScheduleKind.AT == j.getSchedule().getKind()) {
                scheduleInfo = "one-time";
            } else {
                scheduleInfo = "unknown";
            }
            result.append("- ").append(j.getName())
                  .append(" (id: ").append(j.getId())
                  .append(", ").append(scheduleInfo).append(")\n");
        }
        
        return result.toString();
    }
    
    private String removeJob(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for remove";
        }
        
        if (cronService.removeJob(jobId)) {
            return "Removed job " + jobId;
        }
        return "Job " + jobId + " not found";
    }
    
    private String enableJob(Map<String, Object> args, boolean enable) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for enable/disable";
        }
        
        CronJob job = cronService.enableJob(jobId, enable);
        if (job == null) {
            return "Job " + jobId + " not found";
        }
        
        String status = enable ? "enabled" : "disabled";
        return "Job '" + job.getName() + "' " + status;
    }
    
    /**
     * 执行 a cron job through Agent
     */
    public String executeJob(CronJob job) {
        String jobChannel = job.getPayload().getChannel();
        String jobChatId = job.getPayload().getTo();
        
        // Default values if not set
        if (jobChannel == null || jobChannel.isEmpty()) {
            jobChannel = "cli";
        }
        if (jobChatId == null || jobChatId.isEmpty()) {
            jobChatId = "direct";
        }
        
        // If deliver=true, send message directly without agent processing
        if (job.getPayload().isDeliver()) {
            msgBus.publishOutbound(new OutboundMessage(jobChannel, jobChatId, job.getPayload().getMessage()));
            return "ok";
        }
        
        // For deliver=false, process through agent (for complex tasks)
        String sessionKey = "cron-" + job.getId();
        
        try {
            executor.processDirectWithChannel(
                    job.getPayload().getMessage(),
                    sessionKey,
                    jobChannel,
                    jobChatId
            );
            // Response is automatically sent via MessageBus by AgentLoop
        } catch (Exception e) {
            logger.error("Failed to execute cron job", java.util.Map.of(
                    "job_id", job.getId(),
                    "error", e.getMessage()
            ));
            return "Error: " + e.getMessage();
        }
        
        return "ok";
    }
}