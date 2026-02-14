package io.leavesfly.tinyclaw.agent;

/**
 * Agent 相关常量配置
 */
public final class AgentConstants {
    
    private AgentConstants() {
        // Utility class
    }
    
    // LLM 调用参数
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final double DEFAULT_TEMPERATURE = 0.7;
    
    // 摘要触发阈值
    public static final int SUMMARIZE_MESSAGE_THRESHOLD = 20;
    public static final int SUMMARIZE_TOKEN_PERCENTAGE = 75;
    public static final int RECENT_MESSAGES_TO_KEEP = 4;
    public static final int BATCH_SUMMARIZE_THRESHOLD = 10;
    
    // 摘要生成参数
    public static final int SUMMARY_MAX_TOKENS = 1024;
    public static final double SUMMARY_TEMPERATURE = 0.3;
}
