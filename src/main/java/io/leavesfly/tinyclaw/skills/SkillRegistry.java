package io.leavesfly.tinyclaw.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能市场注册源 - 定义可信的技能索引仓库
 * 
 * 每个 SkillRegistry 代表一个可信的技能市场源，类似 Homebrew 的 tap 概念。
 * 技能市场源是一个 GitHub 仓库，其根目录下包含 registry.json 索引文件，
 * 列出该市场中所有可用技能的元数据。
 * 
 * registry.json 格式示例：
 * {
 *   "name": "TinyClaw Official Skills",
 *   "description": "官方维护的技能集合",
 *   "skills": [
 *     {
 *       "name": "weather",
 *       "description": "天气查询技能",
 *       "repo": "leavesfly/tinyclaw-skills",
 *       "subdir": "weather",
 *       "tags": ["weather", "forecast", "天气"],
 *       "author": "leavesfly"
 *     }
 *   ]
 * }
 * 
 * 内置市场源：
 * - leavesfly/tinyclaw-skills：官方维护的技能集合
 * - tinyclaw-community/awesome-skills：社区精选技能
 * - jasonkneen/claude-code-skills：Claude Code 社区技能（兼容格式）
 */
public class SkillRegistry {

    private String name;
    private String repo;
    private String description;
    private boolean enabled;

    public SkillRegistry() {
        this.enabled = true;
    }

    public SkillRegistry(String name, String repo, String description) {
        this.name = name;
        this.repo = repo;
        this.description = description;
        this.enabled = true;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取内置的默认技能市场源列表
     * 
     * 这些是经过审核的、在 GitHub 上真实存在的可信技能仓库，
     * 用户可以安全地从中搜索和安装技能。
     * 
     * @return 默认技能市场源列表
     */
    public static List<SkillRegistry> getDefaultRegistries() {
        List<SkillRegistry> registries = new ArrayList<>();

        registries.add(new SkillRegistry(
                "TinyClaw Official",
                "leavesfly/tinyclaw-skills",
                "官方维护的技能集合，包含常用工具技能"
        ));

        registries.add(new SkillRegistry(
                "VoltAgent Skills",
                "VoltAgent/awesome-agent-skills",
                "500+ agent skills，兼容 Claude Code / Codex / Gemini CLI 等多平台"
        ));

        registries.add(new SkillRegistry(
                "Composio Skills",
                "ComposioHQ/awesome-claude-skills",
                "Composio 社区精选 Claude 技能集合，覆盖生产力工具"
        ));

        registries.add(new SkillRegistry(
                "Travis Skills",
                "travisvn/awesome-claude-skills",
                "社区精选 Claude Skills 资源列表，包含丰富的技能和教程"
        ));

        registries.add(new SkillRegistry(
                "Jeffallan Skills",
                "Jeffallan/claude-skills",
                "66+ 专业全栈开发技能，覆盖完整交付流程"
        ));

        return registries;
    }

}
