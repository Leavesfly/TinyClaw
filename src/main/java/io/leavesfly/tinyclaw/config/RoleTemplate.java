package io.leavesfly.tinyclaw.config;

/**
 * 角色模板定义类
 * <p>
 * 定义 Agent 在协作中扮演的角色，包括：
 * <ul>
 *   <li>角色名称 - 标识角色的唯一名称</li>
 *   <li>角色提示词 - 定义角色行为的系统提示</li>
 *   <li>模型配置 - 角色使用的特定模型（可选）</li>
 * </ul>
 * </p>
 */
public class RoleTemplate {

    /**
     * 角色名称
     */
    private String name;

    /**
     * 角色系统提示词
     * <p>定义角色的行为模式和响应风格</p>
     */
    private String prompt;

    /**
     * 角色使用的模型标识（可选）
     * <p>为空时使用全局默认模型</p>
     */
    private String model;

    /**
     * 默认构造函数
     */
    public RoleTemplate() {}

    /**
     * 构造函数
     *
     * @param name  角色名称
     * @param prompt 角色系统提示词
     */
    public RoleTemplate(String name, String prompt) {
        this.name = name;
        this.prompt = prompt;
    }

    /**
     * 获取角色名称
     *
     * @return 角色名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置角色名称
     *
     * @param name 角色名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取角色系统提示词
     *
     * @return 系统提示词
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * 设置角色系统提示词
     *
     * @param prompt 系统提示词
     */
    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取角色使用的模型标识
     *
     * @return 模型标识，可能为 null
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置角色使用的模型标识
     *
     * @param model 模型标识
     */
    public void setModel(String model) {
        this.model = model;
    }
}
