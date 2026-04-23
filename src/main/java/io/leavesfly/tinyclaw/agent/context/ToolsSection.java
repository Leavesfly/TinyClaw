package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.Map;

/**
 * 工具部分。
 * 构建已注册工具的功能描述和使用方法。
 *
 * <p>如果 Reflection 2.0 为某个工具生成了 few-shot 示范，
 * 会在工具列表之后追加使用示范段落，帮助 LLM 学习正确的调用方式。
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

        // Reflection 2.0：注入 few-shot 使用示范（如果有）
        Map<String, String> fewShotExamples = tools.getFewShotExamples();
        if (!fewShotExamples.isEmpty()) {
            sb.append("\n### 工具使用示范\n\n");
            sb.append("以下是部分工具的正确使用示范，请参考：\n\n");
            for (Map.Entry<String, String> entry : fewShotExamples.entrySet()) {
                sb.append("#### `").append(entry.getKey()).append("` 示范\n\n");
                sb.append(entry.getValue()).append("\n\n");
            }
        }
        
        return sb.toString();
    }
}
