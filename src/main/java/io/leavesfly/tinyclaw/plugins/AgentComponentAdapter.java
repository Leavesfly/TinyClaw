package io.leavesfly.tinyclaw.plugins;

import io.leavesfly.tinyclaw.collaboration.AgentRole;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 插件 agent（子代理）组件适配器。
 *
 * <p>把插件清单中归一化的 {@link PluginManifest.AgentDefinition} 转换为 collaboration 的
 * {@link AgentRole}，从而复用现有 {@code AgentOrchestrator}/{@code RoleAgent} 的执行能力，
 * 无需新增协议。转换出的角色由 {@code PluginManager} 收集为命名角色库，供
 * {@code CollaborateTool} 按名引用。</p>
 *
 * <h3>字段映射</h3>
 * <ul>
 *   <li>{@code name} → roleName（主 Agent 引用键）；roleId 命名空间化为
 *       {@code plugin:<pluginId>:<name>}，与用户临时角色隔离；</li>
 *   <li>正文 systemPrompt → 角色系统提示词（变量替换后）；</li>
 *   <li>{@code model} → 角色专属模型（可选）；</li>
 *   <li>{@code tools} → 角色工具白名单（可选，空表示不限制）；</li>
 *   <li>{@code description} → 角色描述。</li>
 * </ul>
 *
 * <p>适配阶段不执行任何 LLM 调用，仅完成静态字段映射与变量替换。</p>
 */
public class AgentComponentAdapter {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("plugins");

    /**
     * 将插件的 agent 定义列表适配为 {@link AgentRole} 列表。
     *
     * @param manifest 插件清单（含归一化 agents 列表）
     * @param resolver 变量替换器
     * @return 转换后的角色列表（可能为空）
     */
    public List<AgentRole> adapt(PluginManifest manifest, VariableResolver resolver) {
        List<AgentRole> result = new ArrayList<>();
        if (manifest == null || !manifest.hasAgents()) {
            return result;
        }
        for (PluginManifest.AgentDefinition def : manifest.getAgents()) {
            try {
                AgentRole role = convert(manifest.getId(), def, resolver);
                if (role != null) {
                    result.add(role);
                }
            } catch (Exception e) {
                logger.warn("转换插件 agent 失败: "
                        + (def != null ? def.getName() : "null") + " - " + e.getMessage());
            }
        }
        if (!result.isEmpty()) {
            logger.info("已装配插件 agent", Map.of(
                    "plugin", String.valueOf(manifest.getId()),
                    "count", result.size()));
        }
        return result;
    }

    /**
     * 转换单个 agent 定义为 {@link AgentRole}。
     */
    private AgentRole convert(String pluginId, PluginManifest.AgentDefinition def,
                              VariableResolver resolver) {
        if (def == null || def.getName() == null || def.getName().isEmpty()) {
            return null;
        }
        String systemPrompt = resolver.resolve(def.getSystemPrompt());
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            logger.warn("插件 agent 缺少系统提示词，已跳过: " + def.getName());
            return null;
        }

        AgentRole role = AgentRole.of(def.getName(), systemPrompt);
        // roleId 命名空间化，避免与用户临时角色/其它插件同名角色冲突
        role.setRoleId("plugin:" + pluginId + ":" + def.getName());

        if (def.getDescription() != null && !def.getDescription().isEmpty()) {
            role.withDescription(resolver.resolve(def.getDescription()));
        }
        if (def.getModel() != null && !def.getModel().isEmpty()) {
            role.withModel(resolver.resolve(def.getModel()));
        }
        for (String tool : def.getTools()) {
            role.addAllowedTool(resolver.resolve(tool));
        }
        return role;
    }
}
