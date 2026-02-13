package io.leavesfly.tinyclaw.cli;

import java.util.HashMap;
import java.util.Map;

/**
 * CLI 命令的基类
 */
public abstract class CliCommand {
    
    protected static final String LOGO = "🦞";
    protected static final String VERSION = "0.1.0";
    
    /**
     * 获取命令名称
     */
    public abstract String name();
    
    /**
     * 获取命令描述
     */
    public abstract String description();
    
    /**
     * 执行命令
     * @return 退出码（0 表示成功）
     */
    public abstract int execute(String[] args) throws Exception;
    
    /**
     * 打印此命令的帮助信息
     */
    public void printHelp() {
        System.out.println(name() + " - " + description());
    }
    
    /**
     * 将命令行参数解析为键值对
     */
    protected Map<String, String> parseArgs(String[] args, int startIndex) {
        Map<String, String> result = new HashMap<>();
        
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    result.put(key, args[++i]);
                } else {
                    result.put(key, "true");
                }
            } else if (arg.startsWith("-")) {
                String key = arg.substring(1);
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    result.put(key, args[++i]);
                } else {
                    result.put(key, "true");
                }
            }
        }
        
        return result;
    }
    
    /**
     * 获取配置文件路径
     */
    protected String getConfigPath() {
        String home = System.getProperty("user.home");
        return home + "/.tinyclaw/config.json";
    }
    
    /**
     * 从配置获取工作空间路径
     */
    protected String getWorkspacePath() {
        String home = System.getProperty("user.home");
        return home + "/.tinyclaw/workspace";
    }
}