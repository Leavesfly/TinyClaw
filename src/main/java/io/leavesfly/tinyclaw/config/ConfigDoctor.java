package io.leavesfly.tinyclaw.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置体检器 - 在启动失败之前把可诊断的问题说清楚。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@link Config#validate()} 只回答"能不能启动"，返回第一个致命错误就结束。体检需要的是
 * 另一件事：把所有可疑项一次列全，区分"必须修"和"建议修"，并指出哪些能自动修。
 * 两者的返回形态与终止条件都不同，混在一起会让 validate 的调用方被迫处理警告。</p>
 *
 * <h2>诊断为什么同时看 Config 与原始 JSON</h2>
 * <p>{@link Config} 经过 Jackson 反序列化，未知键已被丢弃、缺失字段已被默认值填充，
 * 因此"用户到底写了什么"只能从原始 JSON 看。通道凭据这类按平台字段名各不相同的检查，
 * 在原始 Map 上做一次通用判断比为 7 个通道类各写一遍更不容易漏。</p>
 *
 * <p>修复动作严格限定为<b>无损</b>：只创建缺失目录、只应用已登记的迁移，
 * 绝不删除或改写用户填的值。</p>
 */
public final class ConfigDoctor {

    /** 不需要 API Key 的本地 provider */
    private static final Set<String> LOCAL_PROVIDERS = Set.of("ollama", "vllm");

    /** workspace 下由运行时自动使用、缺失即应补齐的子目录 */
    private static final List<String> REQUIRED_WORKSPACE_DIRS =
            List.of("sessions", "memory", "cron", "skills");

    /** 判定某个键是否承载凭据，用于检查已启用通道是否配全 */
    private static final List<String> CREDENTIAL_HINTS =
            List.of("token", "secret", "key", "id", "webhook");

    private ConfigDoctor() {
    }

    /** 诊断结论的严重级别 */
    public enum Level {
        /** 正常 */
        OK,
        /** 不阻断启动，但会在运行期产生非预期行为 */
        WARN,
        /** 会导致启动或核心链路失败 */
        ERROR
    }

    /**
     * 单条诊断结论。
     *
     * @param level   严重级别
     * @param title   一句话结论
     * @param detail  补充说明或修复指引；无补充时为空串
     * @param fixable 是否可由 {@code doctor --fix} 自动修复
     */
    public record Finding(Level level, String title, String detail, boolean fixable) {

        static Finding ok(String title) {
            return new Finding(Level.OK, title, "", false);
        }

        static Finding warn(String title, String detail) {
            return new Finding(Level.WARN, title, detail, false);
        }

        static Finding error(String title, String detail) {
            return new Finding(Level.ERROR, title, detail, false);
        }

        static Finding fixable(Level level, String title, String detail) {
            return new Finding(level, title, detail, true);
        }
    }

    /**
     * 执行全部只读检查。
     *
     * @param config     已加载的配置
     * @param raw        配置文件的原始 JSON；文件不存在时传 null，相关检查会被跳过
     * @param configPath 配置文件路径，仅用于结论文案
     */
    public static List<Finding> diagnose(Config config, Map<String, Object> raw, String configPath) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> safeRaw = raw != null ? raw : new LinkedHashMap<>();

        checkSchemaVersion(safeRaw, raw != null, configPath, findings);
        checkWorkspace(config, findings);
        checkModelBinding(config, findings);
        checkGateway(config, findings);
        checkChannels(safeRaw, findings);

        return findings;
    }

    /**
     * 执行可自动修复的动作。
     *
     * <p>只做创建目录这类纯增量操作。配置内容的迁移由
     * {@link ConfigLoader#loadAndMigrate(String)} 负责——它自带备份与原子写，
     * 不应在这里重复一套写盘逻辑。</p>
     *
     * @return 实际执行的动作说明；无事可做时为空列表
     */
    public static List<String> repair(Config config) {
        List<String> actions = new ArrayList<>();
        Path workspace = workspacePath(config);
        if (workspace == null) {
            return actions;
        }

        for (String dir : REQUIRED_WORKSPACE_DIRS) {
            Path target = workspace.resolve(dir);
            if (Files.isDirectory(target)) {
                continue;
            }
            try {
                Files.createDirectories(target);
                actions.add("创建目录 " + target);
            } catch (IOException e) {
                actions.add("创建目录失败 " + target + ": " + e.getMessage());
            }
        }
        return actions;
    }

    // ==================== 各项检查 ====================

    private static void checkSchemaVersion(Map<String, Object> raw, boolean fileExists,
                                           String configPath, List<Finding> findings) {
        if (!fileExists) {
            findings.add(Finding.error("配置文件不存在", "路径: " + configPath + "，请先运行 tinyclaw onboard"));
            return;
        }

        int version = ConfigMigrator.readVersion(raw);
        List<String> pending = ConfigMigrator.pending(version);
        if (pending.isEmpty()) {
            findings.add(Finding.ok("配置结构版本 v" + version + "（已是最新）"));
            return;
        }
        findings.add(Finding.fixable(Level.WARN,
                "配置结构版本 v" + version + " 落后于 v" + ConfigMigrator.CURRENT_VERSION,
                "待执行迁移: " + String.join("; ", pending)));
    }

    private static void checkWorkspace(Config config, List<Finding> findings) {
        Path workspace = workspacePath(config);
        if (workspace == null) {
            findings.add(Finding.error("workspace 未配置", "请设置 agent.workspace"));
            return;
        }

        if (!Files.isDirectory(workspace)) {
            findings.add(Finding.fixable(Level.ERROR, "workspace 目录不存在", "路径: " + workspace));
            return;
        }
        if (!Files.isWritable(workspace)) {
            findings.add(Finding.error("workspace 目录不可写", "路径: " + workspace));
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String dir : REQUIRED_WORKSPACE_DIRS) {
            if (!Files.isDirectory(workspace.resolve(dir))) {
                missing.add(dir);
            }
        }
        if (missing.isEmpty()) {
            findings.add(Finding.ok("workspace 就绪: " + workspace));
        } else {
            findings.add(Finding.fixable(Level.WARN, "workspace 缺少子目录",
                    "缺失: " + String.join(", ", missing)));
        }
    }

    /**
     * 检查 agent.model 与 provider 的绑定完整性。
     *
     * <p>model 未在 models.definitions 中定义时运行期会 fallback 到"第一个有效 provider"，
     * 于是 api_base 与 model 可能来自两个不同的服务商——请求会以令人困惑的 404 或
     * "model not found" 失败，而配置文件本身看起来毫无问题。</p>
     */
    private static void checkModelBinding(Config config, List<Finding> findings) {
        String model = config.getAgent() != null ? config.getAgent().getModel() : null;
        if (model == null || model.isBlank()) {
            findings.add(Finding.error("agent.model 未配置", ""));
            return;
        }

        ModelsConfig.ModelDefinition definition = config.getModels().getDefinitions().get(model);
        if (definition == null) {
            findings.add(Finding.warn("模型 \"" + model + "\" 未在 models.definitions 中定义",
                    "运行期会退回第一个有效 provider，api_base 与 model 可能不匹配"));
            return;
        }

        String provider = definition.getProvider();
        if (provider == null || provider.isBlank()) {
            findings.add(Finding.error("模型 \"" + model + "\" 未声明 provider", ""));
            return;
        }
        if (LOCAL_PROVIDERS.contains(provider)) {
            findings.add(Finding.ok("模型 \"" + model + "\" 绑定本地 provider \"" + provider + "\""));
            return;
        }
        if (hasApiKey(config, provider)) {
            findings.add(Finding.ok("模型 \"" + model + "\" 绑定 provider \"" + provider + "\""));
        } else {
            findings.add(Finding.error(
                    "provider \"" + provider + "\" 未配置 apiKey",
                    "模型 \"" + model + "\" 依赖它，当前无法发起请求"));
        }
    }

    private static void checkGateway(Config config, List<Finding> findings) {
        GatewayConfig gateway = config.getGateway();
        if (gateway == null) {
            findings.add(Finding.warn("gateway 未配置", "将使用默认 127.0.0.1:18790"));
            return;
        }
        int port = gateway.getPort();
        if (port < 1 || port > 65535) {
            findings.add(Finding.error("gateway.port 非法: " + port, "合法范围 1-65535"));
            return;
        }
        findings.add(Finding.ok("gateway 监听 " + gateway.getHost() + ":" + port));
    }

    /**
     * 检查已启用通道是否配了凭据。
     *
     * <p>按键名启发式判断而非枚举各平台字段：7 个通道的凭据字段名各不相同，
     * 硬编码清单在新增通道时必然漏掉，而"启用了却一个凭据字段都没填"这个信号本身足够准确。</p>
     */
    private static void checkChannels(Map<String, Object> raw, List<Finding> findings) {
        Map<String, Object> channels = asMap(raw.get("channels"));
        if (channels == null || channels.isEmpty()) {
            findings.add(Finding.ok("未启用任何消息通道"));
            return;
        }

        List<String> enabled = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, Object> entry : channels.entrySet()) {
            Map<String, Object> channel = asMap(entry.getValue());
            if (channel == null || !Boolean.TRUE.equals(channel.get("enabled"))) {
                continue;
            }
            enabled.add(entry.getKey());
            if (!hasCredential(channel)) {
                incomplete.add(entry.getKey());
            }
        }

        if (enabled.isEmpty()) {
            findings.add(Finding.ok("未启用任何消息通道"));
            return;
        }
        if (incomplete.isEmpty()) {
            findings.add(Finding.ok("已启用通道: " + String.join(", ", enabled)));
        } else {
            findings.add(Finding.warn("通道已启用但未配置凭据: " + String.join(", ", incomplete),
                    "启动时这些通道会连接失败"));
        }
    }

    // ==================== 辅助 ====================

    private static Path workspacePath(Config config) {
        String workspace = config.getWorkspacePath();
        if (workspace == null || workspace.isBlank()) {
            return null;
        }
        return Paths.get(workspace);
    }

    /**
     * 判定 provider 是否已持有可用的 apiKey。
     *
     * <p>看的是生效后的 {@link Config} 而不是原始 JSON：apiKey 常见的配置方式就是环境变量与
     * .env，只看配置文件会把这些完全正常的安装误报成缺少凭据。</p>
     */
    private static boolean hasApiKey(Config config, String providerName) {
        ProvidersConfig.ProviderConfig provider = config.getProviders().byName(providerName);
        return provider != null
                && provider.getApiKey() != null
                && !provider.getApiKey().isBlank();
    }

    private static boolean hasCredential(Map<String, Object> channel) {
        for (Map.Entry<String, Object> entry : channel.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            boolean looksLikeCredential = CREDENTIAL_HINTS.stream().anyMatch(key::contains);
            if (looksLikeCredential
                    && entry.getValue() instanceof String value
                    && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : null;
    }
}
