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
 * 定时任务命令 - 管理定时任务
 */
public class CronCommand extends CliCommand {
    
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
        
        // 加载配置以获取工作空间路径
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
        } catch (Exception e) {
            System.err.println("加载配置错误: " + e.getMessage());
            return 1;
        }
        
        String cronStorePath = Paths.get(config.getWorkspacePath(), "cron", "jobs.json").toString();
        
        switch (subcommand) {
            case "list":
                return listJobs(cronStorePath);
            case "add":
                return addJob(cronStorePath, args);
            case "remove":
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw cron remove <job_id>");
                    return 1;
                }
                return removeJob(cronStorePath, args[1]);
            case "enable":
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw cron enable <job_id>");
                    return 1;
                }
                return enableJob(cronStorePath, args[1], true);
            case "disable":
                if (args.length < 2) {
                    System.out.println("Usage: tinyclaw cron disable <job_id>");
                    return 1;
                }
                return enableJob(cronStorePath, args[1], false);
            default:
                System.out.println("未知的定时任务命令: " + subcommand);
                printHelp();
                return 1;
        }
    }
    
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
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());
        
        for (CronJob job : jobs) {
            String schedule;
            if (CronSchedule.ScheduleKind.EVERY == job.getSchedule().getKind() && job.getSchedule().getEveryMs() != null) {
                schedule = "每 " + (job.getSchedule().getEveryMs() / 1000) + " 秒";
            } else if (CronSchedule.ScheduleKind.CRON == job.getSchedule().getKind()) {
                schedule = job.getSchedule().getExpr();
            } else {
                schedule = "一次性";
            }
            
            String nextRun = "已计划";
            if (job.getState().getNextRunAtMs() != null) {
                nextRun = formatter.format(Instant.ofEpochMilli(job.getState().getNextRunAtMs()));
            }
            
            String status = job.isEnabled() ? "已启用" : "已禁用";
            
            System.out.println("  " + job.getName() + " (" + job.getId() + ")");
            System.out.println("    计划: " + schedule);
            System.out.println("    状态: " + status);
            System.out.println("    下次运行: " + nextRun);
        }
        
        return 0;
    }
    
    private int addJob(String storePath, String[] args) {
        Map<String, String> params = parseArgs(args, 1);
        
        String name = params.get("n");
        if (name == null) name = params.get("name");
        
        String message = params.get("m");
        if (message == null) message = params.get("message");
        
        String everyStr = params.get("e");
        if (everyStr == null) everyStr = params.get("every");
        
        String cronExpr = params.get("c");
        if (cronExpr == null) cronExpr = params.get("cron");
        
        boolean deliver = params.containsKey("d") || params.containsKey("deliver");
        String channel = params.get("channel");
        String to = params.get("to");
        
        if (name == null || name.isEmpty()) {
            System.out.println("错误: --name 是必需的");
            return 1;
        }
        
        if (message == null || message.isEmpty()) {
            System.out.println("错误: --message 是必需的");
            return 1;
        }
        
        if (everyStr == null && cronExpr == null) {
            System.out.println("错误: 必须指定 --every 或 --cron");
            return 1;
        }
        
        CronSchedule schedule;
        if (everyStr != null) {
            try {
                long everySec = Long.parseLong(everyStr);
                schedule = CronSchedule.every(everySec * 1000);
            } catch (NumberFormatException e) {
                System.out.println("错误: 无效的 --every 值");
                return 1;
            }
        } else {
            schedule = CronSchedule.cron(cronExpr);
        }
        
        CronService service = new CronService(storePath);
        CronJob job = service.addJob(name, schedule, message, deliver, 
                channel != null ? channel : "", 
                to != null ? to : "");
        
        System.out.println("✓ 已添加任务 '" + job.getName() + "' (" + job.getId() + ")");
        return 0;
    }
    
    private int removeJob(String storePath, String jobId) {
        CronService service = new CronService(storePath);
        if (service.removeJob(jobId)) {
            System.out.println("✓ 已移除任务 " + jobId);
            return 0;
        }
        System.out.println("✗ 未找到任务 " + jobId);
        return 1;
    }
    
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
}