package io.leavesfly.tinyclaw.skills;

/**
 * Skill information
 */
public class SkillInfo {
    
    private String name;
    private String path;
    private String source;
    private String description;
    
    public SkillInfo() {}
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
