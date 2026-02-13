package io.leavesfly.tinyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TinyClaw 配置加载器
 * 支持从 JSON 文件和环境变量加载配置
 */
public class ConfigLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    private static Dotenv dotenv = null;
    
    /**
     * 从默认路径加载配置 (~/.tinyclaw/config.json)
     */
    public static Config load() throws IOException {
        return load(getConfigPath());
    }
    
    /**
     * 从指定路径加载配置
     */
    public static Config load(String path) throws IOException {
        Config config = Config.defaultConfig();
        
        File configFile = new File(path);
        if (configFile.exists()) {
            String content = Files.readString(configFile.toPath());
            config = objectMapper.readValue(content, Config.class);
        }
        
        // 使用环境变量覆盖
        applyEnvironmentOverrides(config);
        
        return config;
    }
    
    /**
     * 保存配置到指定路径
     */
    public static void save(String path, Config config) throws IOException {
        File configFile = new File(path);
        configFile.getParentFile().mkdirs();
        
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        Files.writeString(configFile.toPath(), json);
    }
    
    /**
     * 获取默认配置路径
     */
    public static String getConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".tinyclaw", "config.json").toString();
    }
    
    /**
     * 将 ~ 扩展为用户主目录
     */
    public static String expandHome(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (path.length() > 1 && path.charAt(1) == '/') {
                return home + path.substring(1);
            }
            return home;
        }
        return path;
    }
    
    /**
     * 应用环境变量覆盖到配置
     */
    private static void applyEnvironmentOverrides(Config config) {
        // 加载 .env 文件（如果存在）
        try {
            dotenv = Dotenv.configure()
                    .directory(".")
                    .ignoreIfMissing()
                    .load();
        } catch (Exception e) {
            // 如果 .env 文件不存在则忽略
        }
        
        // 应用环境变量覆盖
        String envValue;
        
        // Agents 配置
        envValue = getEnv("TINYCLAW_AGENTS_DEFAULTS_WORKSPACE");
        if (envValue != null) {
            config.getAgents().getDefaults().setWorkspace(envValue);
        }
        
        envValue = getEnv("TINYCLAW_AGENTS_DEFAULTS_MODEL");
        if (envValue != null) {
            config.getAgents().getDefaults().setModel(envValue);
        }
        
        envValue = getEnv("TINYCLAW_AGENTS_DEFAULTS_MAX_TOKENS");
        if (envValue != null) {
            config.getAgents().getDefaults().setMaxTokens(Integer.parseInt(envValue));
        }
        
        envValue = getEnv("TINYCLAW_AGENTS_DEFAULTS_TEMPERATURE");
        if (envValue != null) {
            config.getAgents().getDefaults().setTemperature(Double.parseDouble(envValue));
        }
        
        // Channels 配置
        envValue = getEnv("TINYCLAW_CHANNELS_TELEGRAM_ENABLED");
        if (envValue != null) {
            config.getChannels().getTelegram().setEnabled(Boolean.parseBoolean(envValue));
        }
        
        envValue = getEnv("TINYCLAW_CHANNELS_TELEGRAM_TOKEN");
        if (envValue != null) {
            config.getChannels().getTelegram().setToken(envValue);
        }
        
        envValue = getEnv("TINYCLAW_CHANNELS_DISCORD_ENABLED");
        if (envValue != null) {
            config.getChannels().getDiscord().setEnabled(Boolean.parseBoolean(envValue));
        }
        
        envValue = getEnv("TINYCLAW_CHANNELS_DISCORD_TOKEN");
        if (envValue != null) {
            config.getChannels().getDiscord().setToken(envValue);
        }
        
        // Providers 配置
        envValue = getEnv("TINYCLAW_PROVIDERS_OPENROUTER_API_KEY");
        if (envValue != null) {
            config.getProviders().getOpenrouter().setApiKey(envValue);
        }
        
        envValue = getEnv("TINYCLAW_PROVIDERS_ANTHROPIC_API_KEY");
        if (envValue != null) {
            config.getProviders().getAnthropic().setApiKey(envValue);
        }
        
        envValue = getEnv("TINYCLAW_PROVIDERS_OPENAI_API_KEY");
        if (envValue != null) {
            config.getProviders().getOpenai().setApiKey(envValue);
        }
        
        envValue = getEnv("TINYCLAW_PROVIDERS_ZHIPU_API_KEY");
        if (envValue != null) {
            config.getProviders().getZhipu().setApiKey(envValue);
        }
        
        envValue = getEnv("TINYCLAW_PROVIDERS_GROQ_API_KEY");
        if (envValue != null) {
            config.getProviders().getGroq().setApiKey(envValue);
        }
        
        envValue = getEnv("TINYCLAW_PROVIDERS_GEMINI_API_KEY");
        if (envValue != null) {
            config.getProviders().getGemini().setApiKey(envValue);
        }
        
        envValue = getEnv("TINYCLAW_PROVIDERS_DASHSCOPE_API_KEY");
        if (envValue != null) {
            config.getProviders().getDashscope().setApiKey(envValue);
        }
        
        // Tools 配置
        envValue = getEnv("TINYCLAW_TOOLS_WEB_SEARCH_API_KEY");
        if (envValue != null) {
            config.getTools().getWeb().getSearch().setApiKey(envValue);
        }
    }
    
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