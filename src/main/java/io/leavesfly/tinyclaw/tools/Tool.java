package io.leavesfly.tinyclaw.tools;

import java.util.Map;

/**
 * 工具接口 for Agent操作
 *
 * <p>学习提示：如果你想为 TinyClaw 增加一个新能力（例如文件搜索、调用某个业务 HTTP API 等），
 * 可以先在这里实现一个新的 Tool，再在 AgentLoop 或 AgentCommand 中通过 ToolRegistry 进行注册。</p>
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
