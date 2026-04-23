package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.evolution.reflection.*;
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

    /** Reflection 2.0 事件记录器（可选，注入后每次 execute 自动记录事件）。 */
    private volatile ToolCallRecorder recorder;

    /** Reflection 2.0 修复应用器（可选，注入后 execute 前自动校验参数）。 */
    private volatile RepairApplier repairApplier;
    
    public ToolRegistry() {
        this.tools = new ConcurrentHashMap<>();
    }

    /**
     * 注入 Reflection 2.0 事件记录器。
     */
    public void setRecorder(ToolCallRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 注入 Reflection 2.0 修复应用器（用于参数预校验和描述覆写）。
     */
    public void setRepairApplier(RepairApplier repairApplier) {
        this.repairApplier = repairApplier;
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

        // Reflection 2.0：构建事件（如果 recorder 已注入）
        ToolCallRecorder currentRecorder = this.recorder;
        ToolCallEvent event = null;
        if (currentRecorder != null) {
            event = ToolCallEvent.begin(name, args, null);
            event.setArgsFingerprint(ArgsFingerprinter.fingerprint(args));
        }
        
        // Reflection 2.0：参数预校验（如果有校验规则）
        RepairApplier currentApplier = this.repairApplier;
        if (currentApplier != null) {
            Map<String, String> rules = currentApplier.getValidationRules(name);
            if (rules != null && !rules.isEmpty()) {
                String validationError = validateArgs(name, args, rules);
                if (validationError != null) {
                    logger.warn("Tool args validation failed", Map.of(
                            "tool", name, "violation", validationError));
                    // 记录为校验失败事件
                    if (event != null) {
                        event.markFailure(ToolCallEvent.ErrorType.VALIDATION_ERROR,
                                new IllegalArgumentException(validationError), 0);
                        currentRecorder.record(event);
                    }
                    throw new IllegalArgumentException("Parameter validation failed: " + validationError);
                }
            }
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

            // Reflection 2.0：记录成功事件
            if (event != null) {
                event.markSuccess(duration);
                currentRecorder.record(event);
            }

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            logger.error("Tool execution failed", Map.of(
                    "tool", name,
                    "duration_ms", duration,
                    "error", e.getMessage()
            ));

            // Reflection 2.0：记录失败事件
            if (event != null) {
                ToolCallEvent.ErrorType errorType = ErrorClassifier.classify(e);
                event.markFailure(errorType, e, duration);
                event.setStackHash(ErrorClassifier.stackHash(e, 4));
                currentRecorder.record(event);
            }

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
    /**
     * 获取所有工具的 few-shot 示范（Reflection 2.0 进化产物）。
     *
     * @return toolName → few-shot 文本的不可变映射，无 few-shot 时返回空 map
     */
    public Map<String, String> getFewShotExamples() {
        RepairApplier currentApplier = this.repairApplier;
        if (currentApplier == null) {
            return Collections.emptyMap();
        }
        return currentApplier.getAllFewShotExamples();
    }

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
        RepairApplier currentApplier = this.repairApplier;
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            String description = tool.description();
            // Reflection 2.0：若有描述覆写，优先使用进化后的描述
            if (currentApplier != null) {
                String override = currentApplier.getDescriptionOverride(tool.name());
                if (override != null) {
                    description = override;
                }
            }
            definitions.add(new ToolDefinition(tool.name(), description, tool.parameters()));
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
        RepairApplier currentApplier = this.repairApplier;
        List<String> summaries = new ArrayList<>();
        for (Tool tool : tools.values()) {
            String description = tool.description();
            // Reflection 2.0：若有描述覆写，优先使用进化后的描述
            if (currentApplier != null) {
                String override = currentApplier.getDescriptionOverride(tool.name());
                if (override != null) {
                    description = override;
                }
            }
            summaries.add("- `" + tool.name() + "` - " + description);
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

    // ==================== Reflection 2.0：参数校验 ====================

    /**
     * 根据校验规则验证工具参数。
     *
     * <p>支持的规则表达式：
     * <ul>
     *   <li>{@code required} — 参数必须存在且非空</li>
     *   <li>{@code must_start_with: prefix} — 字符串参数必须以指定前缀开头</li>
     *   <li>{@code must_end_with: suffix} — 字符串参数必须以指定后缀结尾</li>
     *   <li>{@code range: min-max} — 数值参数必须在指定范围内</li>
     *   <li>{@code max_length: N} — 字符串参数长度不超过 N</li>
     * </ul>
     *
     * @return 首个违规信息，全部通过时返回 null
     */
    private String validateArgs(String toolName, Map<String, Object> args, Map<String, String> rules) {
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String paramName = rule.getKey();
            String ruleExpr = rule.getValue();

            // 跳过非标准规则（如 _raw fallback）
            if (paramName.startsWith("_")) continue;

            Object value = args != null ? args.get(paramName) : null;

            if (ruleExpr.equals("required")) {
                if (value == null || (value instanceof String && ((String) value).isBlank())) {
                    return String.format("Parameter '%s' is required but missing or empty", paramName);
                }
                continue;
            }

            // 以下规则仅在参数存在时才校验
            if (value == null) continue;

            if (ruleExpr.startsWith("must_start_with:")) {
                String prefix = ruleExpr.substring("must_start_with:".length()).trim();
                if (value instanceof String && !((String) value).startsWith(prefix)) {
                    return String.format("Parameter '%s' must start with '%s', got '%s'",
                            paramName, prefix, truncateValue(value));
                }
            } else if (ruleExpr.startsWith("must_end_with:")) {
                String suffix = ruleExpr.substring("must_end_with:".length()).trim();
                if (value instanceof String && !((String) value).endsWith(suffix)) {
                    return String.format("Parameter '%s' must end with '%s', got '%s'",
                            paramName, suffix, truncateValue(value));
                }
            } else if (ruleExpr.startsWith("range:")) {
                String rangeStr = ruleExpr.substring("range:".length()).trim();
                String[] parts = rangeStr.split("-", 2);
                if (parts.length == 2 && value instanceof Number) {
                    double numValue = ((Number) value).doubleValue();
                    double min = Double.parseDouble(parts[0].trim());
                    double max = Double.parseDouble(parts[1].trim());
                    if (numValue < min || numValue > max) {
                        return String.format("Parameter '%s' must be in range [%s, %s], got %s",
                                paramName, parts[0].trim(), parts[1].trim(), value);
                    }
                }
            } else if (ruleExpr.startsWith("max_length:")) {
                int maxLen = Integer.parseInt(ruleExpr.substring("max_length:".length()).trim());
                if (value instanceof String && ((String) value).length() > maxLen) {
                    return String.format("Parameter '%s' exceeds max length %d (actual: %d)",
                            paramName, maxLen, ((String) value).length());
                }
            }
        }
        return null; // 全部通过
    }

    private static String truncateValue(Object value) {
        String str = String.valueOf(value);
        return str.length() > 60 ? str.substring(0, 60) + "..." : str;
    }

    /**
     * 创建一个只包含指定工具名称的受限工具注册表。
     *
     * <p>用于为不同 Agent 角色配置差异化的工具权限：
     * 只有在 {@code allowedToolNames} 中且已注册的工具才会出现在返回的注册表中。
     * 若 {@code allowedToolNames} 为空，则返回当前注册表的完整副本。
     *
     * @param allowedToolNames 允许使用的工具名称白名单
     * @return 受限工具注册表
     */
    public ToolRegistry filter(List<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            ToolRegistry copy = new ToolRegistry();
            tools.values().forEach(copy::register);
            return copy;
        }

        ToolRegistry restricted = new ToolRegistry();
        for (String name : allowedToolNames) {
            Tool tool = tools.get(name);
            if (tool != null) {
                restricted.register(tool);
            } else {
                logger.warn("allowedTools 中指定的工具未注册，已忽略: " + name);
            }
        }
        return restricted;
    }
}
