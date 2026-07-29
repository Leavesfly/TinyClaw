package io.leavesfly.tinyclaw.subagent;

import java.util.List;

/**
 * 子代理定义 - 描述一个通过 AGENT.md 动态定义的专职子代理。
 * <p>
 * 与技能（SKILL.md）对称：YAML 前置元数据声明名称、描述、模型、工具白名单等，
 * Markdown 正文即该子代理的 system prompt。
 */
public class SubagentDefinition {

    /**
     * 子代理名称（取自目录名）
     */
    private String name;

    /**
     * 描述信息，供主 Agent 的 LLM 判断何时派发任务给该子代理
     */
    private String description;

    /**
     * 覆盖模型，null 表示继承主 Agent 配置
     */
    private String model;

    /**
     * 工具白名单，null 或空表示继承主 Agent 的完整工具集
     */
    private List<String> tools;

    /**
     * 覆盖最大迭代次数，0 表示继承主 Agent 配置
     */
    private int maxIterations;

    /**
     * 子代理的 system prompt（AGENT.md 去除前置元数据后的正文）
     */
    private String systemPrompt;

    /**
     * 定义文件路径
     */
    private String path;

    /**
     * 来源（workspace / global）
     */
    private String source;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
