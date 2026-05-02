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
}
