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

    /**
     * 预计算的记忆上下文（P5）：非 null 时 MemorySection 直接使用，不再重算。
     * 由 ContextBuilder 在会话维度一次性计算（含 memoryMode OFF 时的空串），
     * 使「本轮使用记忆」清单与真实模型输入来自同一次计算。
     */
    private String presetMemoryContext;

    /**
     * 当前会话归属的项目信息（P4）：null 表示无项目或不适用。
     * 由 ContextBuilder 在会话维度解析（flagsStore.projectId → ProjectStore），
     * ProjectSection 据此注入项目指令。
     */
    private ProjectInfo projectInfo;

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

    /**
     * 预计算的记忆上下文；null 表示未预计算（MemorySection 自行计算）。
     * 空串表示已计算且无内容（与 null 语义不同，避免二次检索）。
     */
    public String getPresetMemoryContext() {
        return presetMemoryContext;
    }

    /** 设置预计算的记忆上下文（仅 ContextBuilder 构建流程内部使用）。 */
    public void setPresetMemoryContext(String presetMemoryContext) {
        this.presetMemoryContext = presetMemoryContext;
    }

    /** 当前会话归属的项目信息；null 表示无项目。 */
    public ProjectInfo getProjectInfo() {
        return projectInfo;
    }

    /** 设置项目信息（仅 ContextBuilder 构建流程内部使用）。 */
    public void setProjectInfo(ProjectInfo projectInfo) {
        this.projectInfo = projectInfo;
    }

    /**
     * 项目信息快照（P4）：id/名称/指令。不可变值对象，
     * 由装配方提供的 ProjectResolver 从 ProjectStore 解析得出。
     */
    public record ProjectInfo(String id, String name, String instructions) {
    }
}
