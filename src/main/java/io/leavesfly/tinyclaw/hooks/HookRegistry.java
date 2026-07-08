package io.leavesfly.tinyclaw.hooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 注册表：事件 → 该事件下所有 HookEntry 的有序列表。
 *
 * <p>由 {@link HookConfigLoader} 从 JSON 文件加载后构建；加载失败或无配置时返回一个空注册表。
 * 注册表不可变（构造后不允许再添加/移除条目），从而保证并发读取无需加锁。</p>
 */
public final class HookRegistry {

    /** 空注册表单例，当 hooks.json 不存在或加载失败时使用，保证主流程零开销。 */
    public static final HookRegistry EMPTY = new HookRegistry(new EnumMap<>(HookEvent.class));

    private final Map<HookEvent, List<HookEntry>> entries;

    HookRegistry(Map<HookEvent, List<HookEntry>> entries) {
        Map<HookEvent, List<HookEntry>> copy = new EnumMap<>(HookEvent.class);
        for (Map.Entry<HookEvent, List<HookEntry>> e : entries.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.entries = Collections.unmodifiableMap(copy);
    }

    /**
     * 返回指定事件下所有配置的 HookEntry，未配置时返回空列表。
     */
    public List<HookEntry> getEntries(HookEvent event) {
        return entries.getOrDefault(event, Collections.emptyList());
    }

    /** 判断当前事件是否有任何 hook 配置，用于短路优化。 */
    public boolean hasEntries(HookEvent event) {
        List<HookEntry> list = entries.get(event);
        return list != null && !list.isEmpty();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 合并另一个注册表，返回新的不可变注册表。
     *
     * <p>同事件下，本注册表的条目排在前、{@code other} 的排在后。
     * 用于把插件提供的 hooks 叠加到用户 {@code hooks.json} 之后：
     * 由于 {@link HookDispatcher} 对 deny 短路，用户自定义 hooks 优先生效。</p>
     *
     * @param other 待叠加的注册表，为 null 或空时直接返回当前实例
     * @return 合并后的注册表
     */
    public HookRegistry mergedWith(HookRegistry other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return other;
        }
        Map<HookEvent, List<HookEntry>> map = new EnumMap<>(HookEvent.class);
        for (HookEvent event : HookEvent.values()) {
            List<HookEntry> a = this.getEntries(event);
            List<HookEntry> b = other.getEntries(event);
            if (a.isEmpty() && b.isEmpty()) {
                continue;
            }
            List<HookEntry> combined = new ArrayList<>(a.size() + b.size());
            combined.addAll(a);
            combined.addAll(b);
            map.put(event, combined);
        }
        return new HookRegistry(map);
    }
}
