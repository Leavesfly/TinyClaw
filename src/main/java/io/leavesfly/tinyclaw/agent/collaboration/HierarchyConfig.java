package io.leavesfly.tinyclaw.agent.collaboration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 分层决策配置
 * 定义金字塔式层级结构，用于 HierarchyStrategy。
 *
 * <p>层级用 {@code List<List<AgentRole>>} 表达：外层索引即层号（0=底层），
 * 内层列表是该层的所有 Agent 角色。相比嵌套的 HierarchyLevel 对象，
 * 结构更扁平，构建更简洁。
 *
 * <p>推荐使用工厂方法 {@link #of(List[])} 构建：
 * <pre>{@code
 * HierarchyConfig config = HierarchyConfig.of(
 *     List.of(AgentRole.of("分析师A", "..."), AgentRole.of("分析师B", "...")),
 *     List.of(AgentRole.of("决策者", "..."))
 * );
 * }</pre>
 */
public class HierarchyConfig {

    /** 层级列表，索引 0 为底层，最后一个索引为顶层 */
    private List<List<AgentRole>> levels;

    /** 各层的汇总提示词（非底层使用，索引与 levels 对应） */
    private List<String> aggregationPrompts;

    public HierarchyConfig() {
        this.levels = new ArrayList<>();
        this.aggregationPrompts = new ArrayList<>();
    }

    /**
     * 工厂方法：按层顺序传入每层的 Agent 角色列表（第 0 个参数为底层）
     */
    @SafeVarargs
    public static HierarchyConfig of(List<AgentRole>... levelRoles) {
        HierarchyConfig config = new HierarchyConfig();
        for (List<AgentRole> roles : levelRoles) {
            config.levels.add(new ArrayList<>(roles));
            config.aggregationPrompts.add(null);
        }
        return config;
    }

    /**
     * 添加一层（追加到顶层）
     *
     * @param roles 该层的 Agent 角色列表
     * @return this，支持链式调用
     */
    public HierarchyConfig addLevel(List<AgentRole> roles) {
        levels.add(new ArrayList<>(roles));
        aggregationPrompts.add(null);
        return this;
    }

    /**
     * 添加一层，并指定该层的汇总提示词
     *
     * @param roles             该层的 Agent 角色列表
     * @param aggregationPrompt 汇总提示词（用于指导该层如何整合下层结果）
     * @return this，支持链式调用
     */
    public HierarchyConfig addLevel(List<AgentRole> roles, String aggregationPrompt) {
        levels.add(new ArrayList<>(roles));
        aggregationPrompts.add(aggregationPrompt);
        return this;
    }

    /**
     * 获取指定层的 Agent 角色列表
     *
     * @param levelIndex 层索引（0=底层）
     * @return 该层的角色列表，越界时返回空列表
     */
    public List<AgentRole> getLevelAgents(int levelIndex) {
        if (levelIndex < 0 || levelIndex >= levels.size()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(levels.get(levelIndex));
    }

    /**
     * 获取指定层的汇总提示词
     *
     * @param levelIndex 层索引（0=底层）
     * @return 汇总提示词，未设置时返回 null
     */
    public String getAggregationPrompt(int levelIndex) {
        if (levelIndex < 0 || levelIndex >= aggregationPrompts.size()) {
            return null;
        }
        return aggregationPrompts.get(levelIndex);
    }

    /**
     * 获取底层（level=0）的所有 Agent
     */
    public List<AgentRole> getBottomLevelAgents() {
        return getLevelAgents(0);
    }

    /**
     * 获取顶层的所有 Agent
     */
    public List<AgentRole> getTopLevelAgents() {
        return getLevelAgents(levels.size() - 1);
    }

    /**
     * 获取层级总数
     */
    public int getLevelCount() {
        return levels.size();
    }

    /**
     * 判断配置是否有效（至少有一层且每层至少有一个 Agent）
     */
    public boolean isValid() {
        return !levels.isEmpty() && levels.stream().noneMatch(List::isEmpty);
    }

    public List<List<AgentRole>> getLevels() {
        return Collections.unmodifiableList(levels);
    }

    public void setLevels(List<List<AgentRole>> levels) {
        this.levels = new ArrayList<>(levels);
        // 同步 aggregationPrompts 长度
        while (aggregationPrompts.size() < levels.size()) {
            aggregationPrompts.add(null);
        }
    }
}
