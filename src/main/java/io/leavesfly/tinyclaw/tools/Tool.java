package io.leavesfly.tinyclaw.tools;

import java.util.Map;

/**
 * 工具接口 for Agent操作
 */
public interface Tool {
    
    /**
     * 获取 工具 name
     */
    String name();
    
    /**
     * 获取 工具 description
     */
    String description();
    
    /**
     * 获取 工具 parameter schema (JSON Schema format)
     */
    Map<String, Object> parameters();
    
    /**
     * 执行 工具 使用给定的 参数
     * 
     * @param args Tool 参数
     * @return 执行结果 作为字符串
     */
    String execute(Map<String, Object> args) throws Exception;
}
