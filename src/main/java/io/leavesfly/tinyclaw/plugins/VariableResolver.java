package io.leavesfly.tinyclaw.plugins;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件变量替换器。
 *
 * <p>在 MCP / hook / monitor 配置的字符串中替换 Claude Code / OpenClaw 约定的变量：</p>
 * <ul>
 *   <li>{@code ${CLAUDE_PLUGIN_ROOT}}：插件安装根目录绝对路径</li>
 *   <li>{@code ${CLAUDE_PLUGIN_DATA}}：插件跨版本持久化数据目录</li>
 *   <li>{@code ${CLAUDE_PROJECT_DIR}}：项目/工作空间根</li>
 *   <li>{@code ${user_config.KEY}}：用户配置值</li>
 *   <li>{@code ${ENV_VAR}}：环境变量</li>
 * </ul>
 */
public class VariableResolver {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final String pluginRoot;
    private final String pluginData;
    private final String projectDir;
    private final Map<String, Object> userConfig;

    public VariableResolver(String pluginRoot, String pluginData, String projectDir,
                            Map<String, Object> userConfig) {
        this.pluginRoot = pluginRoot;
        this.pluginData = pluginData;
        this.projectDir = projectDir;
        this.userConfig = userConfig;
    }

    /**
     * 替换字符串中的全部变量引用。未知变量保持原样。
     *
     * @param input 原始字符串
     * @return 替换后的字符串（input 为 null 时返回 null）
     */
    public String resolve(String input) {
        if (input == null || input.indexOf('$') < 0) {
            return input;
        }
        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = lookup(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String lookup(String key) {
        switch (key) {
            case "CLAUDE_PLUGIN_ROOT":
                return pluginRoot;
            case "CLAUDE_PLUGIN_DATA":
                return pluginData;
            case "CLAUDE_PROJECT_DIR":
                return projectDir;
            default:
                break;
        }
        if (key.startsWith("user_config.")) {
            String optKey = key.substring("user_config.".length());
            if (userConfig != null && userConfig.get(optKey) != null) {
                return String.valueOf(userConfig.get(optKey));
            }
            return null;
        }
        // 其余按环境变量解析
        return System.getenv(key);
    }
}
