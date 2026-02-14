package io.leavesfly.tinyclaw.tools;

import java.util.Map;

/**
 * 工具接口，用于 Agent 操作
 *
 */
public interface Tool {
    
    /**
     * 获取工具名称
     */
    String name();
    
    /**
     * 获取工具描述
     */
    String description();
    
    /**
     * 获取工具参数模式（JSON Schema 格式）
     */
    Map<String, Object> parameters();
    
    /**
     * 执行工具，使用给定的参数
     * 
     * @param args 工具参数
     * @return 执行结果（字符串格式）
     */
    String execute(Map<String, Object> args) throws Exception;
}
