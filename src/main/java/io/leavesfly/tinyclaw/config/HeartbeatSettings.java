package io.leavesfly.tinyclaw.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 心跳配置类（对齐 OpenClaw heartbeat 配置模型）
 * <p>
 * 定义周期性自省轮次的行为参数，包括：
 * <ul>
 *   <li>调度参数 - 间隔、超时</li>
 *   <li>提示词 - 默认 prompt 体可整体覆盖</li>
 *   <li>成本旋钮 - 隔离会话、轻量上下文、心跳专用模型</li>
 *   <li>投递契约 - 告警投递目标与可见性开关</li>
 *   <li>活跃时段 - activeHours 窗口外跳过</li>
 *   <li>per-agent 条目 - 按角色/agent 名覆盖默认配置</li>
 * </ul>
 * </p>
 */
public class HeartbeatSettings {

    /**
     * 心跳开关
     * <p>默认关闭；与顶层 heartbeatEnabled 双向同步</p>
     */
    private boolean enabled = false;

    /**
     * 心跳间隔（秒）
     * <p>默认 1800（30 分钟）；0 或负数表示禁用</p>
     */
    private int intervalSeconds = 1800;

    /**
     * 单轮心跳超时（秒）
     * <p>默认 0，表示取 min(intervalSeconds, 600)</p>
     */
    private int timeoutSeconds = 0;

    /**
     * 自定义 prompt 体
     * <p>null 时使用内置默认 prompt（含 HEARTBEAT_OK 契约指令）</p>
     */
    private String prompt;

    /**
     * 心跳专用模型覆盖
     * <p>null 时使用主模型。为规避 model bleed（心跳模型遗留到主会话），
     * 仅在 isolatedSession=true 时生效。</p>
     */
    private String model;

    /**
     * 隔离会话开关
     * <p>默认 true：每轮使用一次性 sessionKey，跑完即删，
     * 避免心跳会话历史无限膨胀</p>
     */
    private boolean isolatedSession = true;

    /**
     * 轻量上下文开关
     * <p>默认 false；为 true 时跳过 workspace bootstrap 文件注入，降低成本</p>
     */
    private boolean lightContext = false;

    /**
     * 告警投递目标
     * <p>none（默认，仅记日志）| last（投递到最近一次入站消息的 channel/chatId）
     * | 显式 channel 名</p>
     */
    private String target = "none";

    /**
     * 是否可见 HEARTBEAT_OK 结果
     * <p>默认 false（OK 静默丢弃）</p>
     */
    private boolean showOk = false;

    /**
     * 是否可见告警内容
     * <p>默认 true；与 showOk 均关闭时整轮跳过</p>
     */
    private boolean showAlerts = true;

    /**
     * 活跃时段
     * <p>窗口外跳过心跳；null 表示不限制时段</p>
     */
    private ActiveHours activeHours;

    /**
     * per-agent 心跳条目
     * <p>key 为角色/agent 名；任一 entry 存在时仅这些 agent 跑心跳，
     * entry 中未设置的字段回落到顶层默认值</p>
     */
    private Map<String, HeartbeatSettings> entries;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isIsolatedSession() {
        return isolatedSession;
    }

    public void setIsolatedSession(boolean isolatedSession) {
        this.isolatedSession = isolatedSession;
    }

    public boolean isLightContext() {
        return lightContext;
    }

    public void setLightContext(boolean lightContext) {
        this.lightContext = lightContext;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public boolean isShowOk() {
        return showOk;
    }

    public void setShowOk(boolean showOk) {
        this.showOk = showOk;
    }

    public boolean isShowAlerts() {
        return showAlerts;
    }

    public void setShowAlerts(boolean showAlerts) {
        this.showAlerts = showAlerts;
    }

    public ActiveHours getActiveHours() {
        return activeHours;
    }

    public void setActiveHours(ActiveHours activeHours) {
        this.activeHours = activeHours;
    }

    public Map<String, HeartbeatSettings> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, HeartbeatSettings> entries) {
        this.entries = entries;
    }

    /**
     * 计算有效超时（秒）
     *
     * @return timeoutSeconds > 0 时直接返回，否则取 min(intervalSeconds, 600)
     */
    public int effectiveTimeoutSeconds() {
        if (timeoutSeconds > 0) {
            return timeoutSeconds;
        }
        return Math.min(Math.max(intervalSeconds, 1), 600);
    }

    /**
     * 将当前 entry 叠加到基础配置之上（per-agent 合并）
     *
     * <p>本对象中显式设置（非默认占位）的字段覆盖 base 对应字段；
     * 实现上按"null 字段回填"语义：仅当本对象字段为 null/未启用时取 base 值。
     * 数值型无法区分"未设置"，故 intervalSeconds/timeoutSeconds 恒以 entry 为准，
     * entry 侧应保持合理默认（1800/0）。</p>
     *
     * @param base 顶层默认配置
     * @return 合并后的新配置对象
     */
    public HeartbeatSettings mergedOver(HeartbeatSettings base) {
        HeartbeatSettings merged = new HeartbeatSettings();
        merged.enabled = this.enabled || (base != null && base.enabled);
        merged.intervalSeconds = this.intervalSeconds;
        merged.timeoutSeconds = this.timeoutSeconds;
        merged.prompt = this.prompt != null ? this.prompt : (base != null ? base.prompt : null);
        merged.model = this.model != null ? this.model : (base != null ? base.model : null);
        merged.isolatedSession = this.isolatedSession;
        merged.lightContext = this.lightContext;
        merged.target = this.target != null ? this.target : (base != null ? base.target : "none");
        merged.showOk = this.showOk;
        merged.showAlerts = this.showAlerts;
        merged.activeHours = this.activeHours != null ? this.activeHours : (base != null ? base.activeHours : null);
        return merged;
    }
}
