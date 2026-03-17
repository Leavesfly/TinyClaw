package io.leavesfly.tinyclaw.agent.collaboration;

/**
 * Agent角色定义
 * 定义参与协同的Agent的角色信息和行为配置
 */
public class AgentRole {
    
    /** 角色唯一标识 */
    private String roleId;
    
    /** 角色名称（如"正方辩手"、"测试工程师"） */
    private String roleName;
    
    /** 角色专属的系统提示词 */
    private String systemPrompt;
    
    /** 可选：指定该角色使用的模型（为空则使用默认模型） */
    private String model;
    
    /** 可选：角色描述 */
    private String description;
    
    public AgentRole() {}
    
    public AgentRole(String roleId, String roleName, String systemPrompt) {
        this.roleId = roleId;
        this.roleName = roleName;
        this.systemPrompt = systemPrompt;
    }
    
    /**
     * 便捷构造：使用roleName作为roleId
     */
    public static AgentRole of(String roleName, String systemPrompt) {
        return new AgentRole(roleName, roleName, systemPrompt);
    }
    
    // Getters and Setters
    
    public String getRoleId() {
        return roleId;
    }
    
    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }
    
    public String getRoleName() {
        return roleName;
    }
    
    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
    
    public String getSystemPrompt() {
        return systemPrompt;
    }
    
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
    
    public String getModel() {
        return model;
    }
    
    public void setModel(String model) {
        this.model = model;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    @Override
    public String toString() {
        return "AgentRole{" +
                "roleId='" + roleId + '\'' +
                ", roleName='" + roleName + '\'' +
                '}';
    }
}
