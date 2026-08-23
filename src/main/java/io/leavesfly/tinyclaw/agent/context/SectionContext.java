package io.leavesfly.tinyclaw.agent.context;

import io.leavesfly.tinyclaw.memory.MemoryScope;
import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.evolution.PromptOptimizer;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.tools.ToolRegistry;

import java.util.Set;

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

    /** 本次请求可见的记忆归属域，决定哪些长期记忆能被注入 */
    private final Set<String> memoryScopes;

    public SectionContext(String currentMessage, String workspace, int contextWindow,
                         ToolRegistry tools, PromptOptimizer promptOptimizer,
                         SkillsLoader skillsLoader, MemoryStore memory) {
        this(currentMessage, workspace, contextWindow, tools, promptOptimizer,
                skillsLoader, memory, MemoryScope.globalOnly());
    }

    public SectionContext(String currentMessage, String workspace, int contextWindow,
                         ToolRegistry tools, PromptOptimizer promptOptimizer,
                         SkillsLoader skillsLoader, MemoryStore memory,
                         Set<String> memoryScopes) {

        this.currentMessage = currentMessage;
        this.workspace = workspace;
        this.contextWindow = contextWindow;
        this.tools = tools;
        this.promptOptimizer = promptOptimizer;
        this.skillsLoader = skillsLoader;
        this.memory = memory;
        this.memoryScopes = (memoryScopes == null || memoryScopes.isEmpty())
                ? MemoryScope.globalOnly()
                : memoryScopes;
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

    /**
     * 获取本次请求可见的记忆归属域。未显式传入时为仅全局域。
     */
    public Set<String> getMemoryScopes() {
        return memoryScopes;
    }
}
