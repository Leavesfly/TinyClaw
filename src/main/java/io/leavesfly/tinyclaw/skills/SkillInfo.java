package io.leavesfly.tinyclaw.skills;

import java.util.List;

/**
 * Skill information
 */
public class SkillInfo {
    
    private String name;
    private String path;
    private String source;
    private String description;

    /** 标签（Claude/OpenClaw frontmatter tags）。 */
    private List<String> tags;
    /** 触发关键词（frontmatter triggers）。 */
    private List<String> triggers;
    /** 该技能触发时强制使用的模型（frontmatter model，可选）。 */
    private String model;
    /** 来源插件 id（若技能由插件提供，否则为 null）。 */
    private String pluginId;
    
    public SkillInfo() {}
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPluginId() { return pluginId; }
    public void setPluginId(String pluginId) { this.pluginId = pluginId; }
}
