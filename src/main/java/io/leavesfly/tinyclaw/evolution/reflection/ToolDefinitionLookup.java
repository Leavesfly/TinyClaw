package io.leavesfly.tinyclaw.evolution.reflection;

/**
 * 工具定义查询 —— 让反思引擎无需依赖具体的工具注册表实现。
 *
 * <p>反思引擎只需要「按名字拿到一个工具的文本定义」这一件事，用它来给 LLM 描述被反思的工具。
 * 抽出这个窄接口后，{@code evolution.reflection} 不再反向依赖 {@code tools} 包，
 * 消除了 tools ↔ evolution 的包级循环；具体的格式化由装配方（ProviderManager）提供。</p>
 */
@FunctionalInterface
public interface ToolDefinitionLookup {

    /**
     * 查询工具定义。
     *
     * @param toolName 工具名称
     * @return 工具定义的文本描述；工具不存在时返回 null
     */
    String describe(String toolName);
}
