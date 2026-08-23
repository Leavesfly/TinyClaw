package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协同配置类
 * <p>
 * 定义多 Agent 协作时的行为参数，包括：
 * <ul>
 *   <li>协同开关 - 控制是否启用多 Agent 协作</li>
 *   <li>轮次限制 - 控制协作对话的最大轮次</li>
 *   <li>共识阈值 - 控制达成共识的最低置信度</li>
 *   <li>超时设置 - 控制协作任务的最大执行时间</li>
 *   <li>角色模板 - 预定义的 Agent 角色配置</li>
 * </ul>
 * </p>
 */
public class CollaborationSettings {

    /**
     * 协同能力开关
     * <p>默认启用</p>
     */
    private boolean enabled = true;

    /**
     * 默认最大协作轮次
     * <p>限制单次协作任务的对话轮数，默认 3 轮</p>
     */
    private int defaultMaxRounds = 3;

    /**
     * 默认共识阈值
     * <p>Agent 达成共识所需的最低置信度，范围 0.0-1.0，默认 0.6</p>
     */
    private double defaultConsensusThreshold = 0.6;

    /**
     * 协同超时时间（毫秒）
     * <p>0 表示不限制超时</p>
     */
    private long timeoutMs = 0;

    /**
     * 预定义角色模板映射
     * <p>按场景分类的角色模板，key 为场景名称，value 为角色模板列表</p>
     */
    private Map<String, List<RoleTemplate>> roleTemplates = new HashMap<>();
    
    /**
     * 检查协同能力是否启用
     *
     * @return 启用时返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置协同能力开关
     *
     * @param enabled 是否启用协同能力
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取默认最大协作轮次
     *
     * @return 最大轮次数
     */
    public int getDefaultMaxRounds() {
        return defaultMaxRounds;
    }

    /**
     * 设置默认最大协作轮次
     *
     * @param defaultMaxRounds 最大轮次数
     */
    public void setDefaultMaxRounds(int defaultMaxRounds) {
        this.defaultMaxRounds = defaultMaxRounds;
    }

    /**
     * 获取默认共识阈值
     *
     * @return 共识阈值
     */
    public double getDefaultConsensusThreshold() {
        return defaultConsensusThreshold;
    }

    /**
     * 设置默认共识阈值
     *
     * @param defaultConsensusThreshold 共识阈值，范围 0.0-1.0
     */
    public void setDefaultConsensusThreshold(double defaultConsensusThreshold) {
        this.defaultConsensusThreshold = defaultConsensusThreshold;
    }

    /**
     * 获取协同超时时间
     *
     * @return 超时时间（毫秒），0 表示不限制
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * 设置协同超时时间
     *
     * @param timeoutMs 超时时间（毫秒），0 表示不限制
     */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * 获取预定义角色模板映射
     *
     * @return 角色模板映射
     */
    public Map<String, List<RoleTemplate>> getRoleTemplates() {
        return roleTemplates;
    }

    /**
     * 设置预定义角色模板映射
     *
     * @param roleTemplates 角色模板映射
     */
    public void setRoleTemplates(Map<String, List<RoleTemplate>> roleTemplates) {
        this.roleTemplates = roleTemplates;
    }
}
