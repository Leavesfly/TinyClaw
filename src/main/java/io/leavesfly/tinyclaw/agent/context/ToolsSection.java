package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.tools.ToolRegistry;

/**
 * 工具部分。
 * 构建已注册工具的功能描述和使用方法。
 */
public class ToolsSection implements ContextSection {
    
    @Override
    public String name() {
        return "Tools";
    }
    
    @Override
    public String build(SectionContext context) {
        ToolRegistry tools = context.getTools();
        if (tools == null || tools.getSummaries().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        sb.append("**重要**: 你必须使用工具来执行操作。不要假装执行命令或安排任务。\n\n");
        sb.append("你可以访问以下工具:\n\n");
        
        for (String summary : tools.getSummaries()) {
            sb.append(summary).append("\n");
        }
        
        return sb.toString();
    }
}
