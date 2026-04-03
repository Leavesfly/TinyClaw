package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 身份信息部分。
 * 构建 Agent 的基本身份信息，包含可选的 Prompt 优化内容。
 */
public class IdentitySection implements ContextSection {
    
    @Override
    public String name() {
        return "Identity";
    }
    
    @Override
    public String build(SectionContext context) {
        String baseIdentity = getBaseIdentity(context.getWorkspace());
        
        PromptOptimizer optimizer = context.getPromptOptimizer();
        if (optimizer != null && optimizer.hasActiveOptimization()) {
            String optimization = optimizer.getActiveOptimization();
            if (StringUtils.isNotBlank(optimization)) {
                return baseIdentity + "\n\n## 优化后的行为指导\n\n" + optimization;
            }
        }
        
        return baseIdentity;
    }
    
    /**
     * 获取基础身份信息（不含优化）。
     */
    private String getBaseIdentity(String workspace) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));
        String workspacePath = Paths.get(workspace).toAbsolutePath().toString();
        String runtime = System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", Java " + System.getProperty("java.version");
        
        StringBuilder sb = new StringBuilder();
        sb.append("# tinyclaw 🦞\n\n");
        sb.append("你是 tinyclaw，一个有用的 AI 助手。\n\n");
        sb.append("## 当前时间\n");
        sb.append(now).append("\n\n");
        sb.append("## 运行环境\n");
        sb.append(runtime).append("\n\n");
        sb.append("## 工作空间\n");
        sb.append("你的工作空间位于: ").append(workspacePath).append("\n");
        sb.append("- 记忆索引: ").append(workspacePath).append("/memory/MEMORY.md\n");
        sb.append("- 主题记忆: ").append(workspacePath).append("/memory/topics/*.md\n");
        sb.append("- 技能: ").append(workspacePath).append("/skills/{skill-name}/SKILL.md\n\n");
        sb.append("## 重要规则\n\n");
        sb.append("1. **始终使用工具** - 当你需要执行操作（安排提醒、发送消息、执行命令等）时，你必须调用适当的工具。不要只是说你会做或假装做。\n\n");
        sb.append("2. **乐于助人和准确** - 使用工具时，简要说明你在做什么。\n\n");
        sb.append("3. **记忆** - 记住某些内容时，写入 ").append(workspacePath).append("/memory/topics/ 目录下的主题文件（如 user-preferences.md、project-patterns.md 等）\n");
        
        return sb.toString();
    }
}