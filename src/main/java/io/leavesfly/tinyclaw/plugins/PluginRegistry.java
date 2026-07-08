package io.leavesfly.tinyclaw.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已加载插件注册表（线程安全）。
 *
 * <p>维护已启用插件的清单索引，供装配、CLI inspect 与 Web 控制台查询。
 * 设计参考 {@code tools.ToolRegistry}，使用 {@link ConcurrentHashMap} 保证并发安全。</p>
 */
public class PluginRegistry {

    private final Map<String, PluginManifest> plugins = new ConcurrentHashMap<>();

    /**
     * 注册一个插件清单（同 id 覆盖）。
     */
    public void register(PluginManifest manifest) {
        if (manifest != null && manifest.getId() != null) {
            plugins.put(manifest.getId(), manifest);
        }
    }

    /**
     * 注销指定 id 的插件。
     */
    public void unregister(String id) {
        plugins.remove(id);
    }

    /**
     * 按 id 获取插件清单。
     */
    public PluginManifest get(String id) {
        return plugins.get(id);
    }

    /**
     * 是否已注册指定 id。
     */
    public boolean has(String id) {
        return plugins.containsKey(id);
    }

    /**
     * 列出全部已注册插件清单。
     */
    public List<PluginManifest> list() {
        return new ArrayList<>(plugins.values());
    }

    /**
     * 已注册插件数量。
     */
    public int count() {
        return plugins.size();
    }
}
