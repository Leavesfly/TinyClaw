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
        sb.append("以下技能扩展了你的能力。");
        sb.append("使用 `skills(action='invoke', name='技能名')` 调用技能，获取完整内容和 base-path（可用于执行技能目录下的脚本）。\n\n");
        sb.append(skillsSummary);
        sb.append("\n\n");
    }
    
    private void appendSkillSelfLearningGuide(StringBuilder sb, String workspace) {
        String skillsPath = Paths.get(workspace).toAbsolutePath() + "/skills/";

        sb.append("""
                ## 技能自主学习

                你有能力使用 `skills` 工具**自主学习和管理技能**。\
                这意味着你不局限于预安装的技能——你可以随着时间增长你的能力。

                ### 何时学习新技能

                - 当你遇到现有技能无法覆盖的任务时，先**搜索 GitHub** 上是否有现成的技能可以安装。
                - 当用户提到社区技能或包含有用技能的 GitHub 仓库时，直接**安装它**。
                - 如果搜索不到合适的技能，考虑**创建新技能**来处理它。
                - 当你发现自己重复执行类似的多步操作时，**将模式提取为可复用的技能**。
                - 当现有技能可以根据新经验改进时，**编辑它**使其更好。

                ### 如何管理技能

                使用 `skills` 工具执行以下操作:
                - `skills(action='list')` — 查看所有已安装技能
                - `skills(action='invoke', name='...')` — **调用技能并获取其基础路径**（用于带脚本的技能）
                - `skills(action='search', query='...')` — **从可信技能市场搜索可用的技能**（按功能描述搜索）
                - `skills(action='install', repo='owner/repo')` — 从 GitHub 安装指定技能
                - `skills(action='create', name='...', content='...', skill_description='...')` — 根据经验创建新技能
                - `skills(action='edit', name='...', content='...')` — 改进现有技能
                - `skills(action='remove', name='...')` — 删除不再需要的技能

                ### 自动搜索安装技能

                搜索功能默认从**可信技能市场**中搜索，确保安全性。内置市场源包括:
                - TinyClaw Official（官方技能集合）
                - VoltAgent Skills（500+ 多平台 agent 技能）
                - Composio Skills（Composio 社区精选技能）
                - Travis Skills（社区精选 Claude Skills 资源）
                - Jeffallan Skills（66+ 专业全栈开发技能）

                当你遇到无法处理的任务时，推荐使用以下流程:
                1. 先用 `skills(action='search', query='描述需要的功能')` 从可信市场搜索技能
                2. 如果找到合适的，用 `skills(action='install', repo='owner/repo')` 安装
                3. 安装后用 `skills(action='invoke', name='...')` 调用技能解决问题
                4. 如果搜索不到，再考虑自己创建技能

                ### 调用带脚本的技能

                当技能包含可执行脚本（如 Python 文件）时，使用 `invoke` 而非 `show`：
                1. 调用 `skills(action='invoke', name='技能名')` 获取技能的基础路径和指令
                2. 响应中包含指向技能目录的 `<base-path>`
                3. 使用基础路径执行脚本，例如：`exec(command='python3 {base-path}/script.py 参数1')`

                带脚本技能的示例工作流:
                ```
                1. skills(action='invoke', name='pptx')  → 获取基础路径: /path/to/skills/pptx/
                2. exec(command='python3 /path/to/skills/pptx/create_pptx.py output.pptx')
                ```

                ### 创建可学习技能

                创建技能时，将其编写为带有 YAML frontmatter 的 **Markdown 指令手册**。好的技能应包含:
                1. 清晰描述技能的功能
                2. 逐步执行的指令
                3. （可选）在哪里找到和安装依赖或相关社区技能
                4. 何时以及如何使用该技能的示例

                """);

        sb.append("你创建的技能保存在 `").append(skillsPath).append("`，将在未来的对话中自动可用。\n");
    }
}
