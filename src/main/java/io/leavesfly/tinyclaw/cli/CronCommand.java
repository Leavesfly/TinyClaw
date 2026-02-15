package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.cron.CronJob;
import io.leavesfly.tinyclaw.cron.CronSchedule;
import io.leavesfly.tinyclaw.cron.CronService;

import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 定时任务命令，管理定时任务。
 * 
 * 核心功能：
 * - 列出所有定时任务（包括已禁用的）
 * - 添加新的定时任务（支持周期性和 cron 表达式）
 * - 删除指定任务
 * - 启用/禁用任务
 * 
 * 支持的调度类型：
 * - 周期性任务：使用 --every 指定间隔秒数
 * - Cron 表达式：使用 --cron 指定标准 cron 表达式
 * - 一次性任务：通过 CronService API 支持
 * 
 * 任务存储：
 * - 存储路径：workspace/cron/jobs.json
 * - 持久化：JSON 格式存储所有任务配置
 * 
 * 使用场景：
 * - 设置定期提醒（如每天早上 9 点）
 * - 定时执行重复任务
 * - 管理和查看所有定时任务
 */
public class CronCommand extends CliCommand {
    
    private static final String SUBCOMMAND_LIST = "list";         // 列出任务子命令
    private static final String SUBCOMMAND_ADD = "add";           // 添加任务子命令
    private static final String SUBCOMMAND_REMOVE = "remove";     // 删除任务子命令
    private static final String SUBCOMMAND_ENABLE = "enable";     // 启用任务子命令
    private static final String SUBCOMMAND_DISABLE = "disable";   // 禁用任务子命令
    
    private static final String CRON_DIR = "cron";                // 定时任务目录
    private static final String JOBS_FILE = "jobs.json";          // 任务配置文件
    
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm"; // 日期格式
    
    @Override
    public String name() {
        return "cron";
    }
    
    @Override
    public String description() {
        return "管理定时任务";
    }
    
    @Override
    public int execute(String[] args) throws Exception {
        if (args.length < 1) {
            printHelp();
            return 1;
        }
        
        String subcommand = args[0];
        
        // 加载配置并获取任务存储路径
        String cronStorePath = getCronStorePath();
        if (cronStorePath == null) {
            return 1;
        }
        
        // 执行子命令
        return executeSubcommand(subcommand, cronStorePath, args);
    }
    
    /**
     * 获取定时任务存储路径。
     * 
     * @return 存储路径，失败时返回 null
     */
    private String getCronStorePath() {
        try {
            Config config = ConfigLoader.load(getConfigPath());
            return Paths.get(config.getWorkspacePath(), CRON_DIR, JOBS_FILE).toString();
        } catch (Exception e) {
            System.err.println("加载配置错误: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 执行子命令。
     * 
     * @param subcommand 子命令名称
     * @param cronStorePath 任务存储路径
     * @param args 所有参数
     * @return 执行结果状态码
     */
    private int executeSubcommand(String subcommand, String cronStorePath, String[] args) {
        return switch (subcommand) {
            case SUBCOMMAND_LIST -> listJobs(cronStorePath);
            case SUBCOMMAND_ADD -> addJob(cronStorePath, args);
            case SUBCOMMAND_REMOVE -> {
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw cron remove <job_id>");
                    yield 1;
                }
                yield removeJob(cronStorePath, args[1]);
            }
            case SUBCOMMAND_ENABLE -> {
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw cron enable <job_id>");
                    yield 1;
                }
                yield enableJob(cronStorePath, args[1], true);
            }
            case SUBCOMMAND_DISABLE -> {
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw cron disable <job_id>");
                    yield 1;
                }
                yield enableJob(cronStorePath, args[1], false);
            }
            default -> {
                System.out.println("未知的定时任务命令: " + subcommand);
                printHelp();
                yield 1;
            }
        };
    }
    
    /**
     * 列出所有定时任务。
     * 
     * @param storePath 任务存储路径
     * @return 执行结果状态码
     */
    private int listJobs(String storePath) {
        CronService service = new CronService(storePath);
        List<CronJob> jobs = service.listJobs(true);
        
        if (jobs.isEmpty()) {
            System.out.println("没有定时任务。");
            return 0;
        }
        
        System.out.println();
        System.out.println("定时任务：");
        System.out.println("----------------");
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_FORMAT)
                .withZone(ZoneId.systemDefault());
        
        for (CronJob job : jobs) {
            printJobInfo(job, formatter);
        }
        
        return 0;
    }
    
    /**
     * 打印单个任务信息。
     * 
     * @param job 任务对象
     * @param formatter 时间格式化器
     */
    private void printJobInfo(CronJob job, DateTimeFormatter formatter) {
        String schedule = formatSchedule(job.getSchedule());
        String nextRun = formatNextRun(job, formatter);
        String status = job.isEnabled() ? "已启用" : "已禁用";
        
        System.out.println("  " + job.getName() + " (" + job.getId() + ")");
        System.out.println("    计划: " + schedule);
        System.out.println("    状态: " + status);
        System.out.println("    下次运行: " + nextRun);
    }
    
    /**
     * 格式化调度信息。
     * 
     * @param schedule 调度对象
     * @return 格式化的调度描述
     */
    private String formatSchedule(CronSchedule schedule) {
        return switch (schedule.getKind()) {
            case EVERY -> schedule.getEveryMs() != null 
                    ? "每 " + (schedule.getEveryMs() / 1000) + " 秒"
                    : "未知";
            case CRON -> schedule.getExpr();
            case AT -> "一次性";
            default -> "未知";
        };
    }
    
    /**
     * 格式化下次运行时间。
     * 
     * @param job 任务对象
     * @param formatter 时间格式化器
     * @return 格式化的运行时间
     */
    private String formatNextRun(CronJob job, DateTimeFormatter formatter) {
        if (job.getState().getNextRunAtMs() == null) {
            return "已计划";
        }
        return formatter.format(Instant.ofEpochMilli(job.getState().getNextRunAtMs()));
    }
    
    /**
     * 添加定时任务。
     * 
     * @param storePath 任务存储路径
     * @param args 所有参数
     * @return 执行结果状态码
     */
    private int addJob(String storePath, String[] args) {
        Map<String, String> params = parseArgs(args, 1);
        
        // 解析参数
        JobParams jobParams = parseJobParams(params);
        
        // 验证必需参数
        String validationError = validateJobParams(jobParams);
        if (validationError != null) {
            System.out.println(validationError);
            return 1;
        }
        
        // 解析调度
        CronSchedule schedule = parseSchedule(jobParams);
        if (schedule == null) {
            return 1;
        }
        
        // 创建任务
        CronService service = new CronService(storePath);
        CronJob job = service.addJob(
                jobParams.name, 
                schedule, 
                jobParams.message, 
                jobParams.deliver,
                jobParams.channel != null ? jobParams.channel : "",
                jobParams.to != null ? jobParams.to : ""
        );
        
        System.out.println("✓ 已添加任务 '" + job.getName() + "' (" + job.getId() + ")");
        return 0;
    }
    
    /**
     * 解析任务参数。
     * 
     * @param params 参数映射
     * @return 任务参数对象
     */
    private JobParams parseJobParams(Map<String, String> params) {
        String name = getParam(params, "n", "name");
        String message = getParam(params, "m", "message");
        String everyStr = getParam(params, "e", "every");
        String cronExpr = getParam(params, "c", "cron");
        boolean deliver = params.containsKey("d") || params.containsKey("deliver");
        String channel = params.get("channel");
        String to = params.get("to");
        
        return new JobParams(name, message, everyStr, cronExpr, deliver, channel, to);
    }
    
    /**
     * 获取参数值（支持短名和长名）。
     * 
     * @param params 参数映射
     * @param shortName 短名
     * @param longName 长名
     * @return 参数值
     */
    private String getParam(Map<String, String> params, String shortName, String longName) {
        String value = params.get(shortName);
        return value != null ? value : params.get(longName);
    }
    
    /**
     * 验证任务参数。
     * 
     * @param jobParams 任务参数
     * @return 验证错误信息，验证通过返回 null
     */
    private String validateJobParams(JobParams jobParams) {
        if (jobParams.name == null || jobParams.name.isEmpty()) {
            return "错误: --name 是必需的";
        }
        
        if (jobParams.message == null || jobParams.message.isEmpty()) {
            return "错误: --message 是必需的";
        }
        
        if (jobParams.everyStr == null && jobParams.cronExpr == null) {
            return "错误: 必须指定 --every 或 --cron";
        }
        
        return null;
    }
    
    /**
     * 解析调度配置。
     * 
     * @param jobParams 任务参数
     * @return 调度对象，解析失败返回 null
     */
    private CronSchedule parseSchedule(JobParams jobParams) {
        if (jobParams.everyStr != null) {
            try {
                long everySec = Long.parseLong(jobParams.everyStr);
                return CronSchedule.every(everySec * 1000);
            } catch (NumberFormatException e) {
                System.out.println("错误: 无效的 --every 值");
                return null;
            }
        } else {
            return CronSchedule.cron(jobParams.cronExpr);
        }
    }
    
    /**
     * 删除定时任务。
     * 
     * @param storePath 任务存储路径
     * @param jobId 任务 ID
     * @return 执行结果状态码
     */
    private int removeJob(String storePath, String jobId) {
        CronService service = new CronService(storePath);
        if (service.removeJob(jobId)) {
            System.out.println("✓ 已移除任务 " + jobId);
            return 0;
        }
        System.out.println("✗ 未找到任务 " + jobId);
        return 1;
    }
    
    /**
     * 启用或禁用定时任务。
     * 
     * @param storePath 任务存储路径
     * @param jobId 任务 ID
     * @param enable true 表示启用，false 表示禁用
     * @return 执行结果状态码
     */
    private int enableJob(String storePath, String jobId, boolean enable) {
        CronService service = new CronService(storePath);
        CronJob job = service.enableJob(jobId, enable);
        if (job != null) {
            String status = enable ? "已启用" : "已禁用";
            System.out.println("✓ 任务 '" + job.getName() + "' " + status);
            return 0;
        }
        System.out.println("✗ 未找到任务 " + jobId);
        return 1;
    }
    
    @Override
    public void printHelp() {
        System.out.println();
        System.out.println("定时任务命令：");
        System.out.println("  list              列出所有定时任务");
        System.out.println("  add              添加新的定时任务");
        System.out.println("  remove <id>       根据 ID 移除任务");
        System.out.println("  enable <id>      启用任务");
        System.out.println("  disable <id>     禁用任务");
        System.out.println();
        System.out.println("添加选项：");
        System.out.println("  -n, --name       任务名称");
        System.out.println("  -m, --message    Agent 的消息");
        System.out.println("  -e, --every      每隔 N 秒运行");
        System.out.println("  -c, --cron       Cron 表达式（例如 '0 9 * * *'）");
        System.out.println("  -d, --deliver    将响应发送到通道");
        System.out.println("  --to             发送接收者");
        System.out.println("  --channel        发送通道");
    }
    
    /**
     * 任务参数封装类。
     */
    private record JobParams(
            String name,
            String message,
            String everyStr,
            String cronExpr,
            boolean deliver,
            String channel,
            String to
    ) {
    }
}