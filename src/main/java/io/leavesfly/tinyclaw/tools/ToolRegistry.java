package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.ToolDefinition;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表 - 用于管理和执行各种工具
 * 
 * 这是TinyClaw工具系统的核心管理组件，负责：
 * 
 * 核心功能：
 * - 工具注册与发现：维护系统中所有可用工具的注册信息
 * - 工具执行：提供统一的工具调用接口
 * - 生命周期管理：支持工具的动态注册和注销
 * - 元数据管理：生成工具定义和摘要信息供LLM使用
 * 
 * 设计特点：
 * - 线程安全：使用ConcurrentHashMap确保并发访问安全
 * - 性能监控：记录工具执行时间和结果统计
 * - 错误处理：完善的异常处理和日志记录
 * - 标准化接口：遵循OpenAI工具定义格式
 * 
 * 使用场景：
 * 1. Agent初始化时注册所有可用工具
 * 2. LLM请求工具调用时执行相应工具
 * 3. 系统运行时动态扩展工具功能
 * 4. 生成系统提示词中的工具说明部分
 *
 */
public class ToolRegistry {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("tools");
    
    private final Map<String, Tool> tools;
    
    public ToolRegistry() {
        this.tools = new ConcurrentHashMap<>();
    }
    
    /**
     * 注册一个工具
     * 
     * 将工具实例添加到注册表中，使其可供Agent调用。
     * 工具名称作为唯一标识符，重复注册会覆盖之前的工具。
     * 
     * @param tool 要注册的工具实例
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        logger.debug("Registered tool: " + tool.name());
    }
    
    /**
     * 取消注册一个工具
     * 
     * 从注册表中移除指定名称的工具。
     * 
     * @param name 要取消注册的工具名称
     */
    public void unregister(String name) {
        tools.remove(name);
        logger.debug("Unregistered tool: " + name);
    }
    
    /**
     * 根据名称获取工具
     * 
     * 查找并返回指定名称的工具实例。
     * 
     * @param name 工具名称
     * @return 对应的工具实例，如果未找到则返回空Optional
     */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }
    
    /**
     * 检查工具是否存在
     * 
     * 判断指定名称的工具是否已在注册表中注册。
     * 
     * @param name 工具名称
     * @return 如果工具存在返回true，否则返回false
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
    
    /**
     * 执行工具使用给定的参数
     * 
     * 执行指定名称的工具，传入相应的参数。
     * 会记录执行时间、结果长度等性能指标。
     * 
     * @param name 工具名称
     * @param args 工具参数映射
     * @return 工具执行结果
     * @throws Exception 如果工具未找到或执行失败
     */
    public String execute(String name, Map<String, Object> args) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        
        long start = System.currentTimeMillis();
        try {
            String result = tool.execute(args);
            long duration = System.currentTimeMillis() - start;
            logger.info("Tool executed", Map.of(
                    "tool", name,
                    "duration_ms", duration,
                    "result_length", result != null ? result.length() : 0
            ));
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            logger.error("Tool execution failed", Map.of(
                    "tool", name,
                    "duration_ms", duration,
                    "error", e.getMessage()
            ));
            throw e;
        }
    }
    
    /**
     * 获取所有已注册工具的名称
     * 
     * 返回当前注册表中所有工具的名称列表。
     * 
     * @return 工具名称列表
     */
    public List<String> list() {
        return new ArrayList<>(tools.keySet());
    }
    
    /**
     * 获取已注册工具的数量
     * 
     * 返回当前注册表中的工具总数。
     * 
     * @return 工具数量
     */
    public int count() {
        return tools.size();
    }
    
    /**
     * 获取OpenAI格式的工具定义
     * 
     * 将所有已注册工具转换为OpenAI兼容的工具定义格式，
     * 供LLM在工具调用时使用。
     * 
     * @return 工具定义列表
     */
    public List<ToolDefinition> getDefinitions() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            definitions.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
        }
        return definitions;
    }
    
    /**
     * 获取人类可读的工具摘要
     * 
     * 生成所有工具的简要说明，用于构建系统提示词。
     * 格式为："- `tool_name` - 工具描述"
     * 
     * @return 工具摘要列表
     */
    public List<String> getSummaries() {
        List<String> summaries = new ArrayList<>();
        for (Tool tool : tools.values()) {
            summaries.add("- `" + tool.name() + "` - " + tool.description());
        }
        return summaries;
    }
    
    /**
     * 清除所有已注册工具
     * 
     * 移除注册表中的所有工具，重置到初始状态。
     * 此操作不可逆，请谨慎使用。
     */
    public void clear() {
        tools.clear();
        logger.debug("All tools cleared");
    }
}
