package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cdimascio.dotenv.Dotenv;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * TinyClaw 配置加载器，支持从 JSON 文件和环境变量加载配置。
 * 
 * 核心功能：
 * - 从 JSON 文件加载配置（支持默认路径和自定义路径）
 * - 从环境变量覆盖配置（优先级高于 JSON 文件）
 * - 支持 .env 文件配置
 * - 保存配置到 JSON 文件
 * - 路径处理（~扩展为用户主目录）
 * 
 * 配置优先级：
 * 1. 系统环境变量（最高优先级）
 * 2. .env 文件
 * 3. config.json 文件
 * 4. 默认配置（最低优先级）
 * 
 * 支持的环境变量：
 * - TINYCLAW_AGENT_WORKSPACE：工作空间路径
 * - TINYCLAW_AGENT_MODEL：模型名称
 * - TINYCLAW_AGENT_MAX_TOKENS：最大 Token 数
 * - TINYCLAW_AGENT_TEMPERATURE：温度参数
 * - TINYCLAW_CHANNELS_*：通道配置
 * - TINYCLAW_PROVIDERS_*_API_KEY：Provider API 密钥
 * - TINYCLAW_TOOLS_*：工具配置
 * 
 * 使用示例：
 * - 加载默认配置：Config config = ConfigLoader.load();
 * - 加载指定配置：Config config = ConfigLoader.load("/path/to/config.json");
 * - 保存配置：ConfigLoader.save("/path/to/config.json", config);
 */
public class ConfigLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    private static final String CONFIG_DIR = ".tinyclaw";      // 配置目录名
    private static final String CONFIG_FILE = "config.json";   // 配置文件名
    private static final String HOME_PREFIX = "~";             // 用户主目录前缀
    private static final char PATH_SEPARATOR = '/';            // 路径分隔符

    /** 迁移写回前的备份文件名后缀格式 */
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static Dotenv dotenv = null;  // .env 文件加载器
    
    /**
     * 从默认路径加载配置。
     * 
     * 默认路径为 ~/.tinyclaw/config.json
     * 
     * @return 配置对象
     * @throws IOException 读取配置文件失败
     */
    public static Config load() throws IOException {
        return load(getConfigPath());
    }
    
    /**
     * 从指定路径加载配置。
     * 
     * 加载流程：
     * 1. 如果配置文件存在，从文件加载
     * 2. 如果配置文件不存在，使用默认配置
     * 3. 应用环境变量覆盖
     * 
     * <p>结构版本落后时会在内存中完成迁移，但<b>不写回磁盘</b>。需要持久化迁移结果的
     * 启动路径请用 {@link #loadAndMigrate(String)}。</p>
     * 
     * @param path 配置文件路径
     * @return 配置对象
     * @throws IOException 读取配置文件失败
     */
    public static Config load(String path) throws IOException {
        Config config = loadFromFile(path);
        applyEnvironmentOverrides(config);
        return config;
    }

    /**
     * 加载配置，并在结构版本落后时把迁移结果写回磁盘。
     *
     * <p>供 gateway / agent 等启动路径使用：配置在被任何组件读到之前就已经是当前版本，
     * 避免每个消费方各自兼容历史结构。稳态（版本已最新）下不产生任何写盘。</p>
     *
     * <p>写回的是<b>迁移后的原始 JSON</b> 而不是序列化后的 {@link Config}：用户手工添加的、
     * 当前模型还不认识的键必须原样保留，而 Jackson 反序列化时已经把它们丢掉了。</p>
     *
     * @param path 配置文件路径
     * @return 加载结果，含迁移详情与是否实际写盘
     * @throws IOException 读取或写入配置文件失败
     */
    public static LoadResult loadAndMigrate(String path) throws IOException {
        File configFile = new File(path);
        if (!configFile.exists()) {
            Config config = Config.defaultConfig();
            applyEnvironmentOverrides(config);
            return new LoadResult(config, ConfigMigrator.Result.upToDate(), false);
        }

        Map<String, Object> raw = readRaw(path);
        ConfigMigrator.Result migration = ConfigMigrator.migrate(raw);

        boolean persisted = false;
        if (migration.rewritten()) {
            backup(configFile.toPath());
            JsonFileStore.writeAtomic(configFile.toPath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
            persisted = true;
        }

        Config config = objectMapper.convertValue(raw, Config.class);
        applyEnvironmentOverrides(config);
        return new LoadResult(config, migration, persisted);
    }

    /**
     * 读取配置文件的原始 JSON 结构，不做迁移、不反序列化为 {@link Config}。
     *
     * <p>诊断场景需要看到迁移之前的真实内容，包括当前模型不认识的键。</p>
     *
     * @throws IOException 文件不存在或不是合法 JSON 对象
     */
    public static Map<String, Object> readRaw(String path) throws IOException {
        String content = Files.readString(new File(path).toPath());
        Map<String, Object> raw = objectMapper.readValue(content,
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        return raw != null ? raw : new LinkedHashMap<>();
    }

    /**
     * 加载配置的结果。
     *
     * @param config    最终配置对象（已应用环境变量覆盖）
     * @param migration 迁移详情；配置文件不存在时为「已是最新」
     * @param persisted 迁移结果是否已写回磁盘
     */
    public record LoadResult(Config config, ConfigMigrator.Result migration, boolean persisted) {

        /** 本次加载实际执行了哪些迁移，用于向用户交代改了什么 */
        public List<String> appliedMigrations() {
            return migration.applied();
        }
    }

    /**
     * 迁移写回前留一份带时间戳的备份。
     *
     * <p>迁移是单向的，没有反向迁移可用；用户降级安装或对迁移结果有疑问时，备份是唯一的退路。
     * 备份失败不阻断迁移——写盘目录不可写的情况下配置本身也写不进去，会在后续步骤报错。</p>
     */
    private static void backup(Path configPath) {
        Path backupPath = configPath.resolveSibling(
                configPath.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_STAMP));
        try {
            Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("⚠️  配置备份失败（迁移继续）: " + e.getMessage());
        }
    }
    
    /**
     * 从文件加载配置。
     * 
     * @param path 配置文件路径
     * @return 配置对象
     * @throws IOException 读取配置文件失败
     */
    private static Config loadFromFile(String path) throws IOException {
        File configFile = new File(path);
        if (!configFile.exists()) {
            return Config.defaultConfig();
        }
        
        Map<String, Object> raw = readRaw(path);
        ConfigMigrator.migrate(raw);
        return objectMapper.convertValue(raw, Config.class);
    }
    
    /**
     * 保存配置到指定路径。
     * 
     * 如果父目录不存在，会自动创建。
     * 
     * @param path 配置文件路径
     * @param config 配置对象
     * @throws IOException 写入配置文件失败
     */
    public static void save(String path, Config config) throws IOException {
        File configFile = new File(path);
        ensureParentDirectory(configFile);
        
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        // 原子写：config.json 被写截断会让整个安装无法启动
        JsonFileStore.writeAtomic(configFile.toPath(), json);
    }
    
    /**
     * 确保父目录存在。
     * 
     * @param file 文件对象
     */
    private static void ensureParentDirectory(File file) {
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
    }
    
    /**
     * 获取默认配置路径。
     * 
     * @return 默认配置路径 ~/.tinyclaw/config.json
     */
    public static String getConfigPath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE).toString();
    }
    
    /**
     * 将 ~ 扩展为用户主目录。
     * 
     * 支持以下格式：
     * - ~ 扩展为用户主目录
     * - ~/path 扩展为用户主目录/path
     * 
     * @param path 原始路径
     * @return 扩展后的路径
     */
    public static String expandHome(String path) {
        if (path == null || path.isEmpty() || !path.startsWith(HOME_PREFIX)) {
            return path;
        }
        
        String home = System.getProperty("user.home");
        
        if (path.length() == 1) {
            return home;
        }
        
        if (path.charAt(1) == PATH_SEPARATOR) {
            return home + path.substring(1);
        }
        
        return path;
    }
    
    /**
     * 应用环境变量覆盖到配置。
     * 
     * 加载流程：
     * 1. 尝试加载 .env 文件
     * 2. 应用各项配置的环境变量
     */
    private static void applyEnvironmentOverrides(Config config) {
        loadDotEnv();
        applyAgentOverrides(config);
        applyChannelOverrides(config);
        applyProviderOverrides(config);
        applyToolsOverrides(config);
    }
    
    /**
     * 加载 .env 文件。
     */
    private static void loadDotEnv() {
        try {
            dotenv = Dotenv.configure()
                    .directory(".")
                    .ignoreIfMissing()
                    .load();
        } catch (Exception e) {
            // 如果 .env 文件不存在则忽略
        }
    }
    
    /**
     * 应用 Agent 配置的环境变量覆盖。
     * 
     * @param config 配置对象
     */
    private static void applyAgentOverrides(Config config) {
        applyStringOverride("TINYCLAW_AGENT_WORKSPACE", config.getAgent()::setWorkspace);
        applyStringOverride("TINYCLAW_AGENT_MODEL", config.getAgent()::setModel);
        applyIntOverride("TINYCLAW_AGENT_MAX_TOKENS", config.getAgent()::setMaxTokens);
        applyDoubleOverride("TINYCLAW_AGENT_TEMPERATURE", config.getAgent()::setTemperature);
    }
    
    /**
     * 应用通道配置的环境变量覆盖。
     * 
     * @param config 配置对象
     */
    private static void applyChannelOverrides(Config config) {
        // Telegram 配置
        applyBooleanOverride("TINYCLAW_CHANNELS_TELEGRAM_ENABLED", 
            config.getChannels().getTelegram()::setEnabled);
        applyStringOverride("TINYCLAW_CHANNELS_TELEGRAM_TOKEN", 
            config.getChannels().getTelegram()::setToken);
        
        // Discord 配置
        applyBooleanOverride("TINYCLAW_CHANNELS_DISCORD_ENABLED", 
            config.getChannels().getDiscord()::setEnabled);
        applyStringOverride("TINYCLAW_CHANNELS_DISCORD_TOKEN", 
            config.getChannels().getDiscord()::setToken);
    }
    
    /**
     * 应用 Provider 配置的环境变量覆盖。
     * 
     * @param config 配置对象
     */
    private static void applyProviderOverrides(Config config) {
        applyStringOverride("TINYCLAW_PROVIDERS_OPENROUTER_API_KEY", 
            config.getProviders().getOpenrouter()::setApiKey);
        applyStringOverride("TINYCLAW_PROVIDERS_ANTHROPIC_API_KEY", 
            config.getProviders().getAnthropic()::setApiKey);
        applyStringOverride("TINYCLAW_PROVIDERS_OPENAI_API_KEY", 
            config.getProviders().getOpenai()::setApiKey);
        applyStringOverride("TINYCLAW_PROVIDERS_ZHIPU_API_KEY", 
            config.getProviders().getZhipu()::setApiKey);
        applyStringOverride("TINYCLAW_PROVIDERS_GEMINI_API_KEY", 
            config.getProviders().getGemini()::setApiKey);
        applyStringOverride("TINYCLAW_PROVIDERS_DASHSCOPE_API_KEY", 
            config.getProviders().getDashscope()::setApiKey);
    }
    
    /**
     * 应用工具配置的环境变量覆盖。
     * 
     * @param config 配置对象
     */
    private static void applyToolsOverrides(Config config) {
        applyStringOverride("TINYCLAW_TOOLS_WEB_SEARCH_API_KEY", 
            config.getTools().getWeb().getSearch()::setApiKey);
    }
    
    /**
     * 应用字符串类型的环境变量覆盖。
     * 
     * @param envKey 环境变量名
     * @param setter 设置方法引用
     */
    private static void applyStringOverride(String envKey, Consumer<String> setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(value);
        }
    }
    
    /**
     * 应用整数类型的环境变量覆盖。
     * 
     * @param envKey 环境变量名
     * @param setter 设置方法引用
     */
    private static void applyIntOverride(String envKey, IntConsumer setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(Integer.parseInt(value));
        }
    }
    
    /**
     * 应用双精度浮点类型的环境变量覆盖。
     * 
     * @param envKey 环境变量名
     * @param setter 设置方法引用
     */
    private static void applyDoubleOverride(String envKey, DoubleConsumer setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(Double.parseDouble(value));
        }
    }
    
    /**
     * 应用布尔类型的环境变量覆盖。
     * 
     * @param envKey 环境变量名
     * @param setter 设置方法引用
     */
    private static void applyBooleanOverride(String envKey, java.util.function.Consumer<Boolean> setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(Boolean.parseBoolean(value));
        }
    }
    
    /**
     * 获取环境变量值。
     * 
     * 优先级：系统环境变量 > .env 文件
     * 
     * @param key 环境变量名
     * @return 环境变量值，如果不存在返回 null
     */
    private static String getEnv(String key) {
        // 首先检查系统环境变量
        String value = System.getenv(key);
        if (value != null) {
            return value;
        }
        
        // 然后检查 .env 文件
        if (dotenv != null) {
            return dotenv.get(key);
        }
        
        return null;
    }
}