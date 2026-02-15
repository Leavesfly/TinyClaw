package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;

import java.io.File;

/**
 * 状态命令，显示 TinyClaw 系统状态。
 * 
 * 核心功能：
 * - 检查配置文件是否存在
 * - 显示工作空间路径和状态
 * - 显示当前使用的模型
 * - 显示各 Provider 的 API Key 配置状态
 * 
 * 检查项：
 * 1. 配置文件：~/.tinyclaw/config.json 是否存在
 * 2. 工作空间：配置的工作空间目录是否存在
 * 3. 模型配置：当前使用的模型名称
 * 4. API 密钥：各 Provider（OpenRouter、Anthropic、OpenAI、Gemini、Zhipu、DashScope、Ollama）的配置状态
 * 
 * 使用场景：
 * - 快速检查 TinyClaw 配置是否正确
 * - 诊断配置问题
 * - 查看当前系统状态
 */
public class StatusCommand extends CliCommand {
    
    private static final String CHECK_MARK = "✓";          // 检查通过标记
    private static final String CROSS_MARK = "✗";          // 检查失败标记
    private static final String NOT_SET = "未设置";         // 未配置标记
    private static final String INDENT = "  ";             // 缩进空格
    
    private static final String TITLE = " tinyclaw 状态";
    private static final String CONFIG_PREFIX = "配置: ";
    private static final String WORKSPACE_PREFIX = "工作空间: ";
    private static final String MODEL_PREFIX = "模型: ";
    private static final String API_KEYS_SECTION = "API 密钥:";
    
    private static final String ONBOARD_TIP = "运行 'tinyclaw onboard' 进行初始化。";
    private static final String CONFIG_ERROR_PREFIX = "加载配置错误: ";
    
    private static final String DEFAULT_OLLAMA_BASE = "http://localhost:11434";
    private static final String OLLAMA_NOT_SET_MESSAGE = "未设置 (默认 " + DEFAULT_OLLAMA_BASE + ")";
    
    private static final String PROVIDER_OPENROUTER = "OpenRouter API: ";
    private static final String PROVIDER_ANTHROPIC = "Anthropic API: ";
    private static final String PROVIDER_OPENAI = "OpenAI API: ";
    private static final String PROVIDER_GEMINI = "Gemini API: ";
    private static final String PROVIDER_ZHIPU = "Zhipu API: ";
    private static final String PROVIDER_DASHSCOPE = "DashScope API: ";
    private static final String PROVIDER_OLLAMA = "Ollama: ";
    
    @Override
    public String name() {
        return "status";
    }
    
    @Override
    public String description() {
        return "显示 tinyclaw 状态";
    }
    
    /**
     * 执行状态检查命令。
     * 
     * @param args 命令参数（未使用）
     * @return 执行结果，0 表示成功，1 表示失败
     * @throws Exception 执行异常
     */
    @Override
    public int execute(String[] args) throws Exception {
        String configPath = getConfigPath();
        
        printTitle();
        
        if (!checkConfigFile(configPath)) {
            return 0;
        }
        
        Config config = loadConfig(configPath);
        if (config == null) {
            return 1;
        }
        
        printWorkspaceStatus(config);
        printModelInfo(config);
        printApiKeyStatus(config);
        
        return 0;
    }
    
    /**
     * 打印标题。
     */
    private void printTitle() {
        System.out.println(LOGO + TITLE);
        System.out.println();
    }
    
    /**
     * 检查配置文件是否存在。
     * 
     * @param configPath 配置文件路径
     * @return 配置文件存在返回 true，否则返回 false
     */
    private boolean checkConfigFile(String configPath) {
        File configFile = new File(configPath);
        
        if (configFile.exists()) {
            System.out.println(CONFIG_PREFIX + configPath + " " + CHECK_MARK);
            return true;
        }
        
        System.out.println(CONFIG_PREFIX + configPath + " " + CROSS_MARK);
        System.out.println();
        System.out.println(ONBOARD_TIP);
        return false;
    }
    
    /**
     * 加载配置文件。
     * 
     * @param configPath 配置文件路径
     * @return 配置对象，加载失败返回 null
     */
    private Config loadConfig(String configPath) {
        try {
            return ConfigLoader.load(configPath);
        } catch (Exception e) {
            System.out.println(CONFIG_ERROR_PREFIX + e.getMessage());
            return null;
        }
    }
    
    /**
     * 打印工作空间状态。
     * 
     * @param config 配置对象
     */
    private void printWorkspaceStatus(Config config) {
        String workspace = config.getWorkspacePath();
        File workspaceDir = new File(workspace);
        String statusMark = workspaceDir.exists() ? CHECK_MARK : CROSS_MARK;
        System.out.println(WORKSPACE_PREFIX + workspace + " " + statusMark);
    }
    
    /**
     * 打印模型信息。
     * 
     * @param config 配置对象
     */
    private void printModelInfo(Config config) {
        System.out.println(MODEL_PREFIX + config.getAgent().getModel());
    }
    
    /**
     * 打印 API 密钥配置状态。
     * 
     * @param config 配置对象
     */
    private void printApiKeyStatus(Config config) {
        System.out.println();
        System.out.println(API_KEYS_SECTION);
        
        printProviderStatus(PROVIDER_OPENROUTER, hasValidApiKey(config.getProviders().getOpenrouter().getApiKey()));
        printProviderStatus(PROVIDER_ANTHROPIC, hasValidApiKey(config.getProviders().getAnthropic().getApiKey()));
        printProviderStatus(PROVIDER_OPENAI, hasValidApiKey(config.getProviders().getOpenai().getApiKey()));
        printProviderStatus(PROVIDER_GEMINI, hasValidApiKey(config.getProviders().getGemini().getApiKey()));
        printProviderStatus(PROVIDER_ZHIPU, hasValidApiKey(config.getProviders().getZhipu().getApiKey()));
        printProviderStatus(PROVIDER_DASHSCOPE, hasValidApiKey(config.getProviders().getDashscope().getApiKey()));
        printOllamaStatus(config);
    }
    
    /**
     * 打印 Provider 状态。
     * 
     * @param providerName Provider 名称
     * @param hasKey 是否有 API Key
     */
    private void printProviderStatus(String providerName, boolean hasKey) {
        System.out.println(INDENT + providerName + formatStatus(hasKey));
    }
    
    /**
     * 打印 Ollama 状态。
     * 
     * @param config 配置对象
     */
    private void printOllamaStatus(Config config) {
        String ollamaBase = config.getProviders().getOllama().getApiBase();
        
        if (hasValidApiKey(ollamaBase)) {
            System.out.println(INDENT + PROVIDER_OLLAMA + CHECK_MARK + " " + ollamaBase);
        } else {
            System.out.println(INDENT + PROVIDER_OLLAMA + OLLAMA_NOT_SET_MESSAGE);
        }
    }
    
    /**
     * 检查是否有有效的 API Key。
     * 
     * @param apiKey API Key
     * @return 有效返回 true，否则返回 false
     */
    private boolean hasValidApiKey(String apiKey) {
        return apiKey != null && !apiKey.isEmpty();
    }
    
    /**
     * 格式化状态显示。
     * 
     * @param enabled 是否启用
     * @return 状态字符串
     */
    private String formatStatus(boolean enabled) {
        return enabled ? CHECK_MARK : NOT_SET;
    }
    
    @Override
    public void printHelp() {
        System.out.println(LOGO + " tinyclaw status - 显示状态");
        System.out.println();
        System.out.println("Usage: tinyclaw status");
    }
}