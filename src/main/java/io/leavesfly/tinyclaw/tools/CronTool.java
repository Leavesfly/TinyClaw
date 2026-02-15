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
 * 定时任务工具，调度提醒和任务。
 * 
 * 允许 Agent 创建、管理和执行定时任务，这是系统自动化能力的核心工具。
 * 
 * 核心功能：
 * - 任务调度：使用标准 cron 表达式创建定时任务
 * - 任务管理：支持列出、启用、禁用、删除任务
 * - 消息发送：定时任务触发时自动发送消息到指定通道
 * - 灵活执行：支持立即执行或延迟执行任务
 * 
 * 支持的操作：
 * - add：添加新任务（指定 cron 表达式、消息内容、目标通道）
 * - list：列出所有已调度的任务
 * - remove：删除指定任务
 * - enable/disable：启用或禁用任务
 * 
 * 设计特点：
 * - 与 CronService 紧密集成
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
    
    private static final int MESSAGE_PREVIEW_LENGTH = 30;  // 任务名称预览长度
    private static final String DEFAULT_CHANNEL = "cli";   // 默认通道
    private static final String DEFAULT_CHAT_ID = "direct";// 默认聊天 ID
    
    private final CronService cronService;   // 定时任务服务
    private final JobExecutor executor;      // 任务执行器
    private final MessageBus msgBus;         // 消息总线
    
    private String channel = "";             // 当前通道
    private String chatId = "";              // 当前聊天 ID
    
    /**
     * 通过 Agent 执行任务的接口。
     * 
     * 定义了定时任务执行器需要实现的方法，
     * 用于将定时任务的结果通过 Agent 处理并发送。
     */
    public interface JobExecutor {
        String processDirectWithChannel(String content, String sessionKey, String channel, String chatId) throws Exception;
    }
    
    /**
     * 构造定时任务工具。
     * 
     * @param cronService 定时任务服务
     * @param executor 任务执行器
     * @param msgBus 消息总线
     */
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
     * 设置任务创建的上下文。
     * 
     * @param channel 通道名称
     * @param chatId 聊天 ID
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
        
        return switch (action) {
            case "add" -> addJob(args);
            case "list" -> listJobs();
            case "remove" -> removeJob(args);
            case "enable" -> enableJob(args, true);
            case "disable" -> enableJob(args, false);
            default -> "错误: 未知操作: " + action;
        };
    }
    
    /**
     * 添加定时任务。
     * 
     * @param args 参数映射，必须包含 message 和调度参数
     * @return 添加结果信息
     */
    private String addJob(Map<String, Object> args) {
        // 验证上下文
        if (channel.isEmpty() || chatId.isEmpty()) {
            return "错误: 无会话上下文（通道/聊天ID未设置）。请在活跃对话中使用此工具。";
        }
        
        // 验证消息参数
        String message = (String) args.get("message");
        if (message == null || message.isEmpty()) {
            return "错误: add 操作需要 message 参数";
        }
        
        // 解析调度类型
        CronSchedule schedule = parseSchedule(args);
        if (schedule == null) {
            return "错误: 必须提供 at_seconds、every_seconds 或 cron_expr 之一";
        }
        
        // 读取 deliver 参数，默认为 true
        boolean deliver = args.containsKey("deliver") && args.get("deliver") instanceof Boolean
                ? (Boolean) args.get("deliver")
                : true;
        
        // 创建任务
        String messagePreview = StringUtils.truncate(message, MESSAGE_PREVIEW_LENGTH);
        CronJob job = cronService.addJob(messagePreview, schedule, message, deliver, channel, chatId);
        
        logger.info("Added cron job", Map.of(
                "job_id", job.getId(),
                "name", messagePreview,
                "kind", schedule.getKind()
        ));
        
        return "Created job '" + job.getName() + "' (id: " + job.getId() + ")";
    }
    
    /**
     * 解析调度参数。
     * 
     * @param args 参数映射
     * @return 调度对象，如果参数不合法返回 null
     */
    private CronSchedule parseSchedule(Map<String, Object> args) {
        Number atSeconds = (Number) args.get("at_seconds");
        Number everySeconds = (Number) args.get("every_seconds");
        String cronExpr = (String) args.get("cron_expr");
        
        if (atSeconds != null) {
            long atMs = System.currentTimeMillis() + atSeconds.longValue() * 1000;
            return CronSchedule.at(atMs);
        } else if (everySeconds != null) {
            long everyMs = everySeconds.longValue() * 1000;
            return CronSchedule.every(everyMs);
        } else if (cronExpr != null && !cronExpr.isEmpty()) {
            return CronSchedule.cron(cronExpr);
        }
        
        return null;
    }
    
    /**
     * 列出所有定时任务。
     * 
     * @return 任务列表的格式化字符串
     */
    private String listJobs() {
        List<CronJob> jobs = cronService.listJobs(false);
        
        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }
        
        StringBuilder result = new StringBuilder("Scheduled jobs:\n");
        for (CronJob j : jobs) {
            String scheduleInfo = formatScheduleInfo(j.getSchedule());
            result.append("- ").append(j.getName())
                  .append(" (id: ").append(j.getId())
                  .append(", ").append(scheduleInfo).append(")\n");
        }
        
        return result.toString();
    }
    
    /**
     * 格式化调度信息。
     * 
     * @param schedule 调度对象
     * @return 格式化的调度描述
     */
    private String formatScheduleInfo(CronSchedule schedule) {
        return switch (schedule.getKind()) {
            case EVERY -> schedule.getEveryMs() != null 
                    ? "every " + (schedule.getEveryMs() / 1000) + "s"
                    : "unknown";
            case CRON -> schedule.getExpr();
            case AT -> "one-time";
            default -> "unknown";
        };
    }
    
    /**
     * 删除定时任务。
     * 
     * @param args 参数映射，必须包含 job_id 字段
     * @return 删除结果信息
     */
    private String removeJob(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for remove";
        }
        
        return cronService.removeJob(jobId) 
                ? "Removed job " + jobId
                : "Job " + jobId + " not found";
    }
    
    /**
     * 启用或禁用定时任务。
     * 
     * @param args 参数映射，必须包含 job_id 字段
     * @param enable true 表示启用，false 表示禁用
     * @return 操作结果信息
     */
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
     * 执行定时任务。
     * 
     * 根据任务配置决定是直接发送消息还是通过 Agent 处理。
     * 
     * @param job 要执行的定时任务
     * @return 执行结果
     */
    public String executeJob(CronJob job) {
        String jobChannel = getJobChannel(job);
        String jobChatId = getJobChatId(job);
        
        // 如果 deliver=true，直接发送消息不经过 agent 处理
        if (job.getPayload().isDeliver()) {
            msgBus.publishOutbound(new OutboundMessage(jobChannel, jobChatId, job.getPayload().getMessage()));
            return "ok";
        }
        
        // 对于 deliver=false，通过 agent 处理（用于复杂任务）
        return executeJobThroughAgent(job, jobChannel, jobChatId);
    }
    
    /**
     * 获取任务的通道名称。
     * 
     * @param job 定时任务
     * @return 通道名称，未设置时返回默认值
     */
    private String getJobChannel(CronJob job) {
        String jobChannel = job.getPayload().getChannel();
        return (jobChannel != null && !jobChannel.isEmpty()) ? jobChannel : DEFAULT_CHANNEL;
    }
    
    /**
     * 获取任务的聊天 ID。
     * 
     * @param job 定时任务
     * @return 聊天 ID，未设置时返回默认值
     */
    private String getJobChatId(CronJob job) {
        String jobChatId = job.getPayload().getTo();
        return (jobChatId != null && !jobChatId.isEmpty()) ? jobChatId : DEFAULT_CHAT_ID;
    }
    
    /**
     * 通过 Agent 执行任务。
     * 
     * @param job 定时任务
     * @param jobChannel 通道名称
     * @param jobChatId 聊天 ID
     * @return 执行结果
     */
    private String executeJobThroughAgent(CronJob job, String jobChannel, String jobChatId) {
        String sessionKey = "cron-" + job.getId();
        
        try {
            executor.processDirectWithChannel(
                    job.getPayload().getMessage(),
                    sessionKey,
                    jobChannel,
                    jobChatId
            );
            // 响应会通过 MessageBus 由 AgentLoop 自动发送
            return "ok";
        } catch (Exception e) {
            logger.error("Failed to execute cron job", Map.of(
                    "job_id", job.getId(),
                    "error", e.getMessage()
            ));
            return "Error: " + e.getMessage();
        }
    }
}