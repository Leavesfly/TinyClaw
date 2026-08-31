package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.security.SecretStore;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * secrets 命令 - 带外管理凭据保管库。
 *
 * <pre>
 *   tinyclaw secrets list
 *   tinyclaw secrets set --name GITHUB_TOKEN --from-env GH_TOKEN [--hosts api.github.com] [--desc "..."]
 *   tinyclaw secrets remove --name GITHUB_TOKEN
 * </pre>
 *
 * <h2>为什么值只能来自环境变量</h2>
 * <p>不提供 {@code --value}：命令行参数在 {@code ps} 输出里对同机所有用户可见，也会留在
 * shell 历史里。要求从环境变量取，值就不会出现在这两个地方。这也是刻意不做交互式输入的原因
 * ——它无法用在脚本与首次部署流程里。</p>
 */
public class SecretsCommand extends CliCommand {

    @Override
    public String name() {
        return "secrets";
    }

    @Override
    public String description() {
        return "管理凭据保管库（Agent 只能引用，读不到明文）";
    }

    @Override
    public int execute(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 1;
        }

        Config config = loadConfig();
        if (config == null) {
            return 1;
        }
        SecretStore store = new SecretStore(config.getWorkspacePath());
        Map<String, String> options = parseArgs(args, 1);

        return switch (args[0]) {
            case "list" -> list(store);
            case "set" -> set(store, options);
            case "remove" -> remove(store, options);
            default -> {
                System.err.println("未知子命令: " + args[0]);
                printUsage();
                yield 1;
            }
        };
    }

    private int list(SecretStore store) {
        List<SecretStore.SecretInfo> secrets = store.list();
        if (secrets.isEmpty()) {
            System.out.println("保管库为空。");
            return 0;
        }
        System.out.println(LOGO + " 凭据保管库（不显示值）:");
        for (SecretStore.SecretInfo info : secrets) {
            System.out.println("  • " + info.name()
                    + (info.description().isEmpty() ? "" : "  " + info.description()));
            if (!info.allowedHosts().isEmpty()) {
                System.out.println("      允许主机: " + String.join(", ", info.allowedHosts()));
            }
        }
        return 0;
    }

    private int set(SecretStore store, Map<String, String> options) {
        String secretName = options.get("name");
        String fromEnv = options.get("from-env");
        if (secretName == null || fromEnv == null) {
            System.err.println("用法: tinyclaw secrets set --name <NAME> --from-env <ENV_VAR> "
                    + "[--hosts host1,host2] [--desc \"用途\"]");
            return 1;
        }

        String value = System.getenv(fromEnv);
        if (value == null || value.isEmpty()) {
            System.err.println("环境变量 " + fromEnv + " 未设置或为空。");
            System.err.println("提示: 用 `read -rs GH_TOKEN && export GH_TOKEN` 可避免值进入 shell 历史。");
            return 1;
        }

        Set<String> hosts = parseHosts(options.get("hosts"));
        store.put(secretName, value, hosts, options.get("desc"));

        System.out.println("✓ 已保存凭据 " + secretName
                + (hosts.isEmpty() ? "（未限制目标主机）" : "，限定发送到 " + String.join(", ", hosts)));
        if (hosts.isEmpty()) {
            System.out.println("  建议用 --hosts 限定目标主机，避免凭据被送到非预期的服务。");
        }
        return 0;
    }

    private int remove(SecretStore store, Map<String, String> options) {
        String secretName = options.get("name");
        if (secretName == null) {
            System.err.println("用法: tinyclaw secrets remove --name <NAME>");
            return 1;
        }
        if (store.remove(secretName)) {
            System.out.println("✓ 已删除凭据 " + secretName);
            return 0;
        }
        System.err.println("凭据不存在: " + secretName);
        return 1;
    }

    private Set<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank() || "true".equals(raw)) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .toList());
    }

    private void printUsage() {
        System.out.println(name() + " - " + description());
        System.out.println();
        System.out.println("  tinyclaw secrets list");
        System.out.println("  tinyclaw secrets set --name <NAME> --from-env <ENV_VAR> "
                + "[--hosts host1,host2] [--desc \"用途\"]");
        System.out.println("  tinyclaw secrets remove --name <NAME>");
        System.out.println();
        System.out.println("  Agent 侧用 ${secret:NAME} 引用，真实值不会进入对话与模型上下文。");
    }
}
