package io.leavesfly.tinyclaw.tools;

import java.util.Map;

/**
 * 工具接口，用于 Agent 操作
 *
 * <p>学习提示：如果你想为 TinyClaw 增加一个新能力（例如文件搜索、调用某个业务 HTTP API 等），
 * 可以先在这里实现一个新的 Tool，再在 AgentLoop 或 AgentCommand 中通过 ToolRegistry 进行注册。</p>
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
