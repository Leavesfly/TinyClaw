package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;

import java.io.File;

/**
 * 状态命令 - 显示 TinyClaw 状态
 */
public class StatusCommand extends CliCommand {
    
    @Override
    public String name() {
        return "status";
    }
    
    @Override
    public String description() {
        return "显示 tinyclaw 状态";
    }
    
    @Override
    public int execute(String[] args) throws Exception {
        String configPath = getConfigPath();
        
        System.out.println(LOGO + " tinyclaw 状态");
        System.out.println();
        
        // 检查配置
        File configFile = new File(configPath);
        if (configFile.exists()) {
            System.out.println("配置: " + configPath + " ✓");
        } else {
            System.out.println("配置: " + configPath + " ✗");
            System.out.println();
            System.out.println("运行 'tinyclaw onboard' 进行初始化。");
            return 0;
        }
        
        // 加载配置
        Config config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (Exception e) {
            System.out.println("加载配置错误: " + e.getMessage());
            return 1;
        }
        
        // 检查工作空间
        String workspace = config.getWorkspacePath();
        File workspaceDir = new File(workspace);
        if (workspaceDir.exists()) {
            System.out.println("工作空间: " + workspace + " ✓");
        } else {
            System.out.println("工作空间: " + workspace + " ✗");
        }
        
        System.out.println("模型: " + config.getAgents().getDefaults().getModel());
        
        // 检查 API 密钥
        System.out.println();
        System.out.println("API 密钥:");
        
        boolean hasOpenRouter = config.getProviders().getOpenrouter().getApiKey() != null 
                && !config.getProviders().getOpenrouter().getApiKey().isEmpty();
        boolean hasAnthropic = config.getProviders().getAnthropic().getApiKey() != null 
                && !config.getProviders().getAnthropic().getApiKey().isEmpty();
        boolean hasOpenAI = config.getProviders().getOpenai().getApiKey() != null 
                && !config.getProviders().getOpenai().getApiKey().isEmpty();
        boolean hasGemini = config.getProviders().getGemini().getApiKey() != null 
                && !config.getProviders().getGemini().getApiKey().isEmpty();
        boolean hasZhipu = config.getProviders().getZhipu().getApiKey() != null 
                && !config.getProviders().getZhipu().getApiKey().isEmpty();
        boolean hasGroq = config.getProviders().getGroq().getApiKey() != null 
                && !config.getProviders().getGroq().getApiKey().isEmpty();
        boolean hasDashscope = config.getProviders().getDashscope().getApiKey() != null 
                && !config.getProviders().getDashscope().getApiKey().isEmpty();
        boolean hasVLLM = config.getProviders().getVllm().getApiBase() != null 
                && !config.getProviders().getVllm().getApiBase().isEmpty();
        boolean hasOllama = config.getProviders().getOllama().getApiBase() != null 
                && !config.getProviders().getOllama().getApiBase().isEmpty();
        
        System.out.println("  OpenRouter API: " + status(hasOpenRouter));
        System.out.println("  Anthropic API: " + status(hasAnthropic));
        System.out.println("  OpenAI API: " + status(hasOpenAI));
        System.out.println("  Gemini API: " + status(hasGemini));
        System.out.println("  Zhipu API: " + status(hasZhipu));
        System.out.println("  Groq API: " + status(hasGroq));
        System.out.println("  DashScope API: " + status(hasDashscope));
        if (hasVLLM) {
            System.out.println("  vLLM/本地: ✓ " + config.getProviders().getVllm().getApiBase());
        } else {
            System.out.println("  vLLM/本地: 未设置");
        }
        if (hasOllama) {
            System.out.println("  Ollama: ✓ " + config.getProviders().getOllama().getApiBase());
        } else {
            System.out.println("  Ollama: 未设置 (默认 http://localhost:11434)");
        }
        
        return 0;
    }
    
    private String status(boolean enabled) {
        return enabled ? "✓" : "未设置";
    }
    
    @Override
    public void printHelp() {
        System.out.println(LOGO + " tinyclaw status - 显示状态");
        System.out.println();
        System.out.println("Usage: tinyclaw status");
    }
}