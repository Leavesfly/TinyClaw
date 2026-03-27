package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.nio.file.Paths;

/**
 * 技能摘要部分。
 * 生成已安装技能的简要说明，并引导 AI 自主学习技能。
 */
public class SkillsSection implements ContextSection {
    
    @Override
    public String name() {
        return "Skills";
    }
    
    @Override
    public String build(SectionContext context) {
        String skillsSummary = context.getSkillsLoader().buildSkillsSummary();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");
        
        if (StringUtils.isNotBlank(skillsSummary)) {
            appendInstalledSkillsSummary(sb, skillsSummary);
        }
        
        appendSkillSelfLearningGuide(sb, context.getWorkspace());
        
        return sb.toString();
    }
    
    private void appendInstalledSkillsSummary(StringBuilder sb, String skillsSummary) {
        sb.append("## 已安装技能\n\n");
        sb.append("以下技能扩展了你的能力。\n\n");
        sb.append(skillsSummary);
        sb.append("\n\n");
        sb.append("## 触发规则\n\n");
        sb.append("当满足以下任一条件时，主动调用 `skills(action='invoke', name='技能名')` 加载对应技能：\n");
        sb.append("1. 用户的任务与某个技能的 description 高度匹配\n");
        sb.append("2. 用户明确提到某个技能名称\n");
        sb.append("加载技能后，严格按照技能内容中的指令执行。\n\n");
    }

    private void appendSkillSelfLearningGuide(StringBuilder sb, String workspace) {
        String skillsPath = Paths.get(workspace).toAbsolutePath() + "/skills/";

        sb.append("""
                ## 技能自主学习

                使用 `skills` 工具自主管理技能：
                - `skills(action='list')` — 查看所有已安装技能
                - `skills(action='invoke', name='...')` — 调用技能，获取完整指令和 base-path
                - `skills(action='search', query='...')` — 从可信市场搜索技能
                - `skills(action='install', repo='owner/repo')` — 从 GitHub 安装技能
                - `skills(action='create', name='...', content='...')` — 创建新技能
                - `skills(action='edit', name='...', content='...')` — 改进现有技能
                - `skills(action='remove', name='...')` — 删除技能

                遇到无法处理的任务时：先 search → 找到则 install → 再 invoke；搜索不到再 create。

                技能包含可执行脚本时，invoke 返回的 base-path 可直接用于执行：
                `exec(command='python3 {base-path}/script.py')`

                """);

        sb.append("你创建的技能保存在 `").append(skillsPath).append("`，将在未来的对话中自动可用。\n");
    }
}
