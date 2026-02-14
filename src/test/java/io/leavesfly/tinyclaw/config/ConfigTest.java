package io.leavesfly.tinyclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Config 配置类单元测试
 *
 * <h2>学习目标</h2>
 * <ul>
 *   <li>使用 @TempDir 创建临时目录进行文件操作测试</li>
 *   <li>测试配置类的默认值</li>
 *   <li>测试配置的序列化和反序列化</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=ConfigTest
 * </pre>
 */
@DisplayName("Config 配置类测试")
class ConfigTest {

    @TempDir
    Path tempDir;

    // ==================== Config 默认值测试 ====================

    @Test
    @DisplayName("defaultConfig: 返回有效的默认配置")
    void defaultConfig_ReturnsValidConfig() {
        Config config = Config.defaultConfig();
        
        assertNotNull(config);
        assertNotNull(config.getAgents());
        assertNotNull(config.getProviders());
        assertNotNull(config.getChannels());
        assertNotNull(config.getGateway());
        assertNotNull(config.getTools());
    }

    @Test
    @DisplayName("defaultConfig: Agent 默认值正确")
    void defaultConfig_AgentDefaults_AreCorrect() {
        Config config = Config.defaultConfig();
        AgentsConfig.AgentDefaults defaults = config.getAgents().getDefaults();
        
        assertNotNull(defaults.getWorkspace());
        assertNotNull(defaults.getModel());
        assertTrue(defaults.getMaxTokens() > 0);
        assertTrue(defaults.getTemperature() >= 0 && defaults.getTemperature() <= 2);
        assertTrue(defaults.getMaxToolIterations() > 0);
    }

    @Test
    @DisplayName("defaultConfig: Gateway 默认值正确")
    void defaultConfig_GatewayDefaults_AreCorrect() {
        Config config = Config.defaultConfig();
        GatewayConfig gateway = config.getGateway();
        
        assertNotNull(gateway.getHost());
        assertTrue(gateway.getPort() > 0);
    }

    // ==================== AgentsConfig 测试 ====================

    @Test
    @DisplayName("AgentsConfig: Getter/Setter 正常工作")
    void agentsConfig_GetterSetter_Works() {
        AgentsConfig agentsConfig = new AgentsConfig();
        AgentsConfig.AgentDefaults defaults = agentsConfig.getDefaults();
        
        defaults.setModel("gpt-4");
        defaults.setMaxTokens(4096);
        defaults.setTemperature(0.5);
        defaults.setMaxToolIterations(10);
        defaults.setHeartbeatEnabled(true);
        defaults.setRestrictToWorkspace(false);
        
        assertEquals("gpt-4", defaults.getModel());
        assertEquals(4096, defaults.getMaxTokens());
        assertEquals(0.5, defaults.getTemperature(), 0.001);
        assertEquals(10, defaults.getMaxToolIterations());
        assertTrue(defaults.isHeartbeatEnabled());
        assertFalse(defaults.isRestrictToWorkspace());
    }

    // ==================== ChannelsConfig 测试 ====================

    @Test
    @DisplayName("ChannelsConfig: 所有通道默认禁用")
    void channelsConfig_AllChannelsDisabledByDefault() {
        ChannelsConfig channels = new ChannelsConfig();
        
        assertFalse(channels.getTelegram().isEnabled());
        assertFalse(channels.getDiscord().isEnabled());
        assertFalse(channels.getWhatsapp().isEnabled());
        assertFalse(channels.getFeishu().isEnabled());
        assertFalse(channels.getDingtalk().isEnabled());
        assertFalse(channels.getQq().isEnabled());
        assertFalse(channels.getMaixcam().isEnabled());
    }

    @Test
    @DisplayName("ChannelsConfig.TelegramConfig: Getter/Setter 正常工作")
    void telegramConfig_GetterSetter_Works() {
        ChannelsConfig.TelegramConfig telegram = new ChannelsConfig.TelegramConfig();
        
        telegram.setEnabled(true);
        telegram.setToken("bot123:abc");
        telegram.setAllowFrom(java.util.List.of("user1", "user2"));
        
        assertTrue(telegram.isEnabled());
        assertEquals("bot123:abc", telegram.getToken());
        assertEquals(2, telegram.getAllowFrom().size());
    }

    @Test
    @DisplayName("ChannelsConfig.FeishuConfig: Getter/Setter 正常工作")
    void feishuConfig_GetterSetter_Works() {
        ChannelsConfig.FeishuConfig feishu = new ChannelsConfig.FeishuConfig();
        
        feishu.setEnabled(true);
        feishu.setAppId("app123");
        feishu.setAppSecret("secret456");
        feishu.setEncryptKey("encrypt789");
        feishu.setVerificationToken("token000");
        
        assertTrue(feishu.isEnabled());
        assertEquals("app123", feishu.getAppId());
        assertEquals("secret456", feishu.getAppSecret());
        assertEquals("encrypt789", feishu.getEncryptKey());
        assertEquals("token000", feishu.getVerificationToken());
    }

    // ==================== ProvidersConfig 测试 ====================

    @Test
    @DisplayName("ProvidersConfig: 所有提供商初始化")
    void providersConfig_AllProvidersInitialized() {
        ProvidersConfig providers = new ProvidersConfig();
        
        assertNotNull(providers.getOpenrouter());
        assertNotNull(providers.getOpenai());
        assertNotNull(providers.getAnthropic());
        assertNotNull(providers.getZhipu());
        assertNotNull(providers.getGroq());
        assertNotNull(providers.getGemini());
        assertNotNull(providers.getVllm());
        assertNotNull(providers.getDashscope());
        assertNotNull(providers.getOllama());
    }

    @Test
    @DisplayName("ProvidersConfig.ProviderConfig: isValid 判断正确")
    void providerConfig_IsValid_ChecksApiKey() {
        ProvidersConfig.ProviderConfig provider = new ProvidersConfig.ProviderConfig();
        
        // 默认无 API Key，应该无效
        assertFalse(provider.isValid());
        
        // 设置空字符串，仍然无效
        provider.setApiKey("");
        assertFalse(provider.isValid());
        
        // 设置有效 API Key
        provider.setApiKey("sk-valid-key");
        assertTrue(provider.isValid());
    }

    @Test
    @DisplayName("ProvidersConfig: getDefaultApiBase 返回正确的默认地址")
    void getDefaultApiBase_ReturnsCorrectDefaults() {
        assertEquals("https://openrouter.ai/api/v1", ProvidersConfig.getDefaultApiBase("openrouter"));
        assertEquals("https://api.openai.com/v1", ProvidersConfig.getDefaultApiBase("openai"));
        assertEquals("https://api.anthropic.com/v1", ProvidersConfig.getDefaultApiBase("anthropic"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4", ProvidersConfig.getDefaultApiBase("zhipu"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", ProvidersConfig.getDefaultApiBase("dashscope"));
        assertEquals("http://localhost:11434/v1", ProvidersConfig.getDefaultApiBase("ollama"));
    }

    @Test
    @DisplayName("ProvidersConfig: getFirstValidProvider 返回第一个有效提供商")
    void getFirstValidProvider_ReturnsFirstValid() {
        ProvidersConfig providers = new ProvidersConfig();
        
        // 默认都无效
        assertTrue(providers.getFirstValidProvider().isEmpty());
        
        // 设置 zhipu 有效
        providers.getZhipu().setApiKey("valid-key");
        assertTrue(providers.getFirstValidProvider().isPresent());
    }

    // ==================== ConfigLoader 测试 ====================

    @Test
    @DisplayName("ConfigLoader.save/load: 配置可以保存和加载")
    void configLoader_SaveAndLoad_Works() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Config original = Config.defaultConfig();
        original.getAgents().getDefaults().setModel("test-model");
        original.getGateway().setPort(9999);
        
        ConfigLoader.save(configPath.toString(), original);
        assertTrue(Files.exists(configPath));
        
        Config loaded = ConfigLoader.load(configPath.toString());
        
        assertEquals("test-model", loaded.getAgents().getDefaults().getModel());
        assertEquals(9999, loaded.getGateway().getPort());
    }

    @Test
    @DisplayName("ConfigLoader.load: 文件不存在返回默认配置")
    void configLoader_FileNotExists_ReturnsDefault() throws IOException {
        Path nonExistent = tempDir.resolve("non-existent.json");
        
        Config config = ConfigLoader.load(nonExistent.toString());
        
        assertNotNull(config);
        // 应该返回默认配置
        assertNotNull(config.getAgents());
    }

    @Test
    @DisplayName("ConfigLoader.expandHome: 正确扩展 ~ 路径")
    void expandHome_ExpandsTilde() {
        String expanded = ConfigLoader.expandHome("~/test/path");
        assertFalse(expanded.startsWith("~"));
        assertTrue(expanded.contains("test/path"));
        
        String unchanged = ConfigLoader.expandHome("/absolute/path");
        assertEquals("/absolute/path", unchanged);
        
        String nullInput = ConfigLoader.expandHome(null);
        assertNull(nullInput);
        
        String emptyInput = ConfigLoader.expandHome("");
        assertEquals("", emptyInput);
    }

    // ==================== GatewayConfig 测试 ====================

    @Test
    @DisplayName("GatewayConfig: Getter/Setter 正常工作")
    void gatewayConfig_GetterSetter_Works() {
        GatewayConfig gateway = new GatewayConfig();
        
        gateway.setHost("127.0.0.1");
        gateway.setPort(8080);
        
        assertEquals("127.0.0.1", gateway.getHost());
        assertEquals(8080, gateway.getPort());
    }
}
