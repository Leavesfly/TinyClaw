package io.leavesfly.tinyclaw;

import io.leavesfly.tinyclaw.cli.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * TinyClaw - 超轻量个人AI助手
 * 
 * <p>这是一个基于 Java 实现的个人 AI 助手命令行工具，灵感来源于 PicoClaw。
 * TinyClaw 提供了一套简洁的命令行接口，帮助用户管理 AI Agent、网关、定时任务等。</p>
 * 
 * <h2>主要功能：</h2>
 * <ul>
 *   <li>onboard - 新用户引导和初始化配置</li>
 *   <li>agent - AI Agent 管理</li>
 *   <li>gateway - 网关配置和管理</li>
 *   <li>status - 系统状态查看</li>
 *   <li>cron - 定时任务管理</li>
 *   <li>skills - 技能插件管理</li>
 * </ul>
 * 
 * <h2>使用示例：</h2>
 * <pre>
 * # 查看版本信息
 * java -jar tinyclaw.jar version
 * 
 * # 查看帮助信息
 * java -jar tinyclaw.jar
 * 
 * # 执行特定命令
 * java -jar tinyclaw.jar agent list
 * </pre>
 *
 */
public class TinyClaw {
    
    /** 当前软件版本号 */
    public static final String VERSION = "0.1.0";
    
    /** 应用程序 Logo 符号 */
    public static final String LOGO = "🦞";
    
    /** 命令注册表，存储所有可用命令及其创建工厂 */
    private static final Map<String, Supplier<CliCommand>> COMMAND_REGISTRY;
    
    // 初始化命令注册表，注册所有支持的命令
    static {
        COMMAND_REGISTRY = new LinkedHashMap<>();
        COMMAND_REGISTRY.put("onboard", OnboardCommand::new);
        COMMAND_REGISTRY.put("agent", AgentCommand::new);
        COMMAND_REGISTRY.put("gateway", GatewayCommand::new);
        COMMAND_REGISTRY.put("status", StatusCommand::new);
        COMMAND_REGISTRY.put("cron", CronCommand::new);
        COMMAND_REGISTRY.put("skills", SkillsCommand::new);
        COMMAND_REGISTRY.put("demo", DemoCommand::new);
    }
    
    /**
     * 注册命令（主要用于测试）
     * @param name 命令名称
     * @param supplier 命令工厂
     */
    public static void registerCommand(String name, Supplier<CliCommand> supplier) {
        COMMAND_REGISTRY.put(name, supplier);
    }

    /**
     * 应用程序主入口
     * 
     * <p>解析命令行参数并根据第一个参数执行相应的命令。如果没有提供参数或命令不存在，
     * 则显示帮助信息并退出。</p>
     * 
     * <p>执行流程：</p>
     * <ol>
     *   <li>检查命令行参数是否存在</li>
     *   <li>判断是否为版本查询命令</li>
     *   <li>从注册表中查找对应的命令处理器</li>
     *   <li>执行命令并处理返回结果</li>
     *   <li>捕获并处理异常</li>
     * </ol>
     * 
     * @param args 命令行参数，第一个参数为命令名称，后续参数为命令的子参数
     */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * 执行应用程序逻辑
     * 
     * @param args 命令行参数
     * @return 退出码
     */
    public static int run(String[] args) {
        if (args.length < 1) {
            printHelp();
            return 1;
        }
        
        String command = args[0];
        
        try {
            // 优先检查是否为版本查询命令
            if (isVersionCommand(command)) {
                System.out.println(LOGO + " tinyclaw v" + VERSION);
                return 0;
            }
            
            // 提取子命令参数（去掉第一个命令名称）
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            
            // 从注册表中获取对应的命令处理器
            Supplier<CliCommand> commandSupplier = COMMAND_REGISTRY.get(command);
            if (commandSupplier != null) {
                return commandSupplier.get().execute(subArgs);
            } else {
                System.out.println("Unknown command: " + command);
                printHelp();
                return 1;
            }
        } catch (Exception e) {
            TinyClawLogger logger = TinyClawLogger.getLogger("main");
            logger.error("Application error", Map.of("error", e.getMessage()));
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
    
    /**
     * 判断给定的命令字符串是否为版本查询命令
     * 
     * <p>支持以下版本查询命令格式：</p>
     * <ul>
     *   <li>version</li>
     *   <li>--version</li>
     *   <li>-v</li>
     * </ul>
     * 
     * @param command 待检查的命令字符串
     * @return 如果是版本查询命令返回 true，否则返回 false
     */
    private static boolean isVersionCommand(String command) {
        return "version".equals(command) || "--version".equals(command) || "-v".equals(command);
    }
    
    /**
     * 打印帮助信息
     * 
     * <p>显示应用程序的版本信息、使用说明以及所有可用命令的列表。
     * 每个命令都会显示其名称和描述信息。</p>
     */
    private static void printHelp() {
        System.out.println(LOGO + " tinyclaw - Personal AI Assistant v" + VERSION);
        System.out.println();
        System.out.println("Usage: tinyclaw <command>");
        System.out.println();
        System.out.println("Commands:");
        
        // 遍历命令注册表，打印所有可用命令及其描述
        for (Map.Entry<String, Supplier<CliCommand>> entry : COMMAND_REGISTRY.entrySet()) {
            CliCommand cmd = entry.getValue().get();
            System.out.println("  " + String.format("%-11s", entry.getKey()) + cmd.description());
        }
        
        // 添加版本命令的帮助信息
        System.out.println("  version     显示版本信息");
    }
}