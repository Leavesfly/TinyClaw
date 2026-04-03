package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

/**
 * 传递给 ContextSection.build() 的上下文信息。
 * 包含构建 section 时可能需要的所有共享状态。
 */
public class SectionContext {
    private final String currentMessage;
    private final String workspace;
    private final int contextWindow;
    private final ToolRegistry tools;
    private final PromptOptimizer promptOptimizer;
    private final SkillsLoader skillsLoader;
    private final MemoryStore memory;
    
    public SectionContext(String currentMessage, String workspace, int contextWindow,
                         ToolRegistry tools, PromptOptimizer promptOptimizer,
                         SkillsLoader skillsLoader, MemoryStore memory) {

        this.currentMessage = currentMessage;
        this.workspace = workspace;
        this.contextWindow = contextWindow;
        this.tools = tools;
        this.promptOptimizer = promptOptimizer;
        this.skillsLoader = skillsLoader;
        this.memory = memory;
    }
    
    public String getCurrentMessage() {
        return currentMessage;
    }
    
    public String getWorkspace() {
        return workspace;
    }
    
    public int getContextWindow() {
        return contextWindow;
    }
    
    public ToolRegistry getTools() {
        return tools;
    }
    
    public PromptOptimizer getPromptOptimizer() {
        return promptOptimizer;
    }
    
    public SkillsLoader getSkillsLoader() {
        return skillsLoader;
    }
    
    public MemoryStore getMemory() {
        return memory;
    }
}
