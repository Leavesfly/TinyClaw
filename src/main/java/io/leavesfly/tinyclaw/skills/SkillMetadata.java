package io.leavesfly.tinyclaw.skills;

/**
 * Skill metadata
 */
public class SkillMetadata {
    
    private String name;
    private String description;
    
    public SkillMetadata() {}
    
    public SkillMetadata(String name) {
        this.name = name;
    }
    
    public SkillMetadata(String name, String description) {
        this.name = name;
        this.description = description;
    }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
