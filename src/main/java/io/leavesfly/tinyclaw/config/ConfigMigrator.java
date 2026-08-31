package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置迁移器 - 让 config.json 的字段结构可以演进。
 *
 * <h2>为什么需要</h2>
 * <p>字段改名或语义变更如果只写在发布说明里，等于要求每个用户手工改一遍自己的
 * config.json；改错的那部分人会直接卡在启动失败上。迁移器把这件事变成代码：
 * 旧配置读进来先过一遍迁移链，再交给 Jackson 反序列化。</p>
 *
 * <h2>为什么操作原始 Map 而不是 Config 对象</h2>
 * <p>迁移的输入按定义是"当前模型已经读不懂"的旧结构——一旦先反序列化成
 * {@link Config}，被改名的字段早已被 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
 * 静默丢掉，迁移器再也拿不到它的值。</p>
 *
 * <h2>迁移的硬性要求</h2>
 * <ul>
 *   <li><b>幂等</b>：同一份配置迁移多次结果一致。启动期会无条件跑一遍，不幂等就会反复改写。</li>
 *   <li><b>无损</b>：只改写已识别的键，不删除无法识别的内容——用户可能手工加了当前版本还不支持的字段。</li>
 *   <li><b>只前进</b>：不提供回退。降级安装应当从备份恢复，而不是靠反向迁移猜测原值。</li>
 * </ul>
 *
 * <p>未标注 {@code schemaVersion} 的历史配置视为版本 0。</p>
 */
public final class ConfigMigrator {

    /** 当前配置结构版本，新增迁移时同步递增 */
    public static final int CURRENT_VERSION = 1;

    /** 版本标记键，与 {@link Config#getSchemaVersion()} 对应 */
    static final String VERSION_KEY = "schemaVersion";

    /**
     * 单条迁移。
     *
     * <p>实现类只负责把配置从 {@code targetVersion - 1} 推进到 {@code targetVersion}，
     * 不关心自己在链条中的位置。</p>
     */
    public interface Migration {

        /** 本迁移执行后配置应达到的版本 */
        int targetVersion();

        /** 面向用户的一句话说明，doctor 命令直接展示 */
        String describe();

        /**
         * 就地改写原始配置。
         *
         * @return 是否实际改动了内容；未改动返回 false，用于避免无谓的写盘
         */
        boolean apply(Map<String, Object> raw);
    }

    /** 迁移链，必须按 targetVersion 升序排列 */
    private static final List<Migration> MIGRATIONS = List.of(
            new NormalizePathsAndEndpoints()
    );

    private ConfigMigrator() {
    }

    /**
     * 迁移结果。
     *
     * @param fromVersion 迁移前版本
     * @param toVersion   迁移后版本
     * @param applied     实际产生改动的迁移说明；仅推进版本号时为空列表
     * @param rewritten   配置内容是否发生变化（含版本号标记），决定是否需要写回磁盘
     */
    public record Result(int fromVersion, int toVersion, List<String> applied, boolean rewritten) {

        /** 无需迁移的结果，用于配置文件不存在等场景 */
        public static Result upToDate() {
            return new Result(CURRENT_VERSION, CURRENT_VERSION, List.of(), false);
        }
    }

    /**
     * 读取配置的结构版本，缺失或非法时返回 0。
     *
     * <p>非法值按 0 处理而不是抛错：手工把版本号改坏的配置应当被重新迁移一遍，
     * 而不是让整个安装启动不了。</p>
     */
    public static int readVersion(Map<String, Object> raw) {
        if (raw == null) {
            return 0;
        }
        Object value = raw.get(VERSION_KEY);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 列出指定版本尚未执行的迁移说明，供 doctor 在不改动任何内容的前提下预览。
     */
    public static List<String> pending(int fromVersion) {
        List<String> descriptions = new ArrayList<>();
        for (Migration migration : MIGRATIONS) {
            if (migration.targetVersion() > fromVersion) {
                descriptions.add("v" + migration.targetVersion() + ": " + migration.describe());
            }
        }
        return descriptions;
    }

    /**
     * 就地迁移原始配置到当前版本。
     *
     * <p>版本已是最新时直接返回，不做任何改动——启动期每次都会调用本方法，
     * 稳态下必须零写盘。</p>
     */
    public static Result migrate(Map<String, Object> raw) {
        int fromVersion = readVersion(raw);
        if (raw == null || fromVersion >= CURRENT_VERSION) {
            return new Result(fromVersion, fromVersion, List.of(), false);
        }

        List<String> applied = new ArrayList<>();
        for (Migration migration : MIGRATIONS) {
            if (migration.targetVersion() <= fromVersion) {
                continue;
            }
            if (migration.apply(raw)) {
                applied.add("v" + migration.targetVersion() + ": " + migration.describe());
            }
        }

        raw.put(VERSION_KEY, CURRENT_VERSION);
        return new Result(fromVersion, CURRENT_VERSION, applied, true);
    }

    // ==================== 迁移实现 ====================

    /**
     * v1：规范化路径与端点字符串。
     *
     * <p>修的是两类会导致运行期失败、但从配置文件里肉眼看不出来的问题：</p>
     * <ul>
     *   <li>{@code agent.workspace} 前后空白：{@code ConfigLoader.expandHome} 用
     *       {@code startsWith("~")} 判断是否展开，一个前导空格就让 {@code ~} 原样传给
     *       文件 API，最终在名为 {@code ~} 的相对目录里读写。</li>
     *   <li>provider {@code apiBase} 尾部斜杠：{@code HTTPProvider} 拼接
     *       {@code apiBase + "/chat/completions"}，尾斜杠会产生双斜杠路径，
     *       部分网关据此返回 404。</li>
     * </ul>
     */
    static final class NormalizePathsAndEndpoints implements Migration {

        @Override
        public int targetVersion() {
            return 1;
        }

        @Override
        public String describe() {
            return "规范化 workspace 路径与 provider apiBase（去除多余空白与尾部斜杠）";
        }

        @Override
        public boolean apply(Map<String, Object> raw) {
            boolean changed = trimWorkspace(raw);
            changed |= normalizeApiBases(raw);
            return changed;
        }

        private boolean trimWorkspace(Map<String, Object> raw) {
            Map<String, Object> agent = asMap(raw.get("agent"));
            if (agent == null || !(agent.get("workspace") instanceof String workspace)) {
                return false;
            }
            String trimmed = workspace.trim();
            if (trimmed.equals(workspace)) {
                return false;
            }
            agent.put("workspace", trimmed);
            return true;
        }

        private boolean normalizeApiBases(Map<String, Object> raw) {
            Map<String, Object> providers = asMap(raw.get("providers"));
            if (providers == null) {
                return false;
            }
            boolean changed = false;
            for (Object entry : providers.values()) {
                Map<String, Object> provider = asMap(entry);
                if (provider == null || !(provider.get("apiBase") instanceof String apiBase)) {
                    continue;
                }
                String normalized = stripTrailingSlashes(apiBase.trim());
                if (!normalized.equals(apiBase)) {
                    provider.put("apiBase", normalized);
                    changed = true;
                }
            }
            return changed;
        }

        /**
         * 去掉末尾连续的斜杠。空串与纯斜杠输入返回空串——留空时
         * {@code ProvidersConfig.getDefaultApiBase} 会补默认端点。
         */
        private String stripTrailingSlashes(String value) {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '/') {
                end--;
            }
            return value.substring(0, end);
        }
    }

    /**
     * 把 JSON 节点当作对象读取，类型不符返回 null。
     *
     * <p>迁移器必须容忍任意手写内容：用户可能把 {@code providers} 写成字符串，
     * 此时应当跳过而不是让整个启动流程抛 ClassCastException。</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : null;
    }
}
