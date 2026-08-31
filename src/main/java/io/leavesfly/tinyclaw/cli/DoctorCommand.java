package io.leavesfly.tinyclaw.cli;

import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigDoctor;
import io.leavesfly.tinyclaw.config.ConfigLoader;
import io.leavesfly.tinyclaw.config.ConfigMigrator;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * doctor 命令 - 体检配置并在需要时执行无损修复。
 *
 * <p>用法：</p>
 * <pre>
 *   tinyclaw doctor          # 只读诊断，不改动任何文件
 *   tinyclaw doctor --fix    # 应用配置迁移与目录补齐
 * </pre>
 *
 * <p>退出码用于串进脚本：仍存在 ERROR 级结论时返回 1，其余返回 0。WARN 不影响退出码，
 * 否则"通道未配凭据"这类刻意留空的状态会让 CI 永远失败。</p>
 */
public class DoctorCommand extends CliCommand {

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "体检配置并修复可自动处理的问题";
    }

    @Override
    public int execute(String[] args) {
        boolean fix = parseArgs(args, 0).containsKey("fix");
        String configPath = getConfigPath();

        System.out.println();
        System.out.println(LOGO + " TinyClaw doctor");
        System.out.println("  配置: " + configPath);
        System.out.println();

        if (!new File(configPath).exists()) {
            System.out.println("  ✗ 配置文件不存在，请先运行: tinyclaw onboard");
            System.out.println();
            return 1;
        }

        Map<String, Object> raw;
        Config config;
        try {
            raw = ConfigLoader.readRaw(configPath);
            config = ConfigLoader.load(configPath);
        } catch (Exception e) {
            System.out.println("  ✗ 配置无法解析: " + e.getMessage());
            System.out.println("    迁移与修复都需要先能读懂配置，请修正 JSON 语法后重试。");
            System.out.println();
            return 1;
        }

        List<ConfigDoctor.Finding> findings = ConfigDoctor.diagnose(config, raw, configPath);
        printFindings(findings);

        if (!fix) {
            if (hasFixable(findings)) {
                System.out.println("  运行 tinyclaw doctor --fix 可自动处理标记为 [可修复] 的项。");
                System.out.println();
            }
            return exitCode(findings);
        }

        return applyFixes(configPath);
    }

    /**
     * 执行修复并复检。
     *
     * <p>迁移走 {@link ConfigLoader#loadAndMigrate} 而不是自己写盘：备份、原子替换、
     * 保留未知键这几件事已经在那里做过，重复一套只会多出一处不一致。</p>
     */
    private int applyFixes(String configPath) {
        System.out.println("  ── 修复 ──");
        Config config;
        try {
            ConfigLoader.LoadResult result = ConfigLoader.loadAndMigrate(configPath);
            config = result.config();

            if (result.persisted()) {
                System.out.println("  ✓ 配置已迁移至 v" + result.migration().toVersion()
                        + "（原文件已备份为 config.json.bak-*）");
                for (String applied : result.appliedMigrations()) {
                    System.out.println("      " + applied);
                }
            } else {
                System.out.println("  · 配置结构已是 v" + ConfigMigrator.CURRENT_VERSION + "，无需迁移");
            }
        } catch (Exception e) {
            System.out.println("  ✗ 迁移失败: " + e.getMessage());
            System.out.println();
            return 1;
        }

        List<String> actions = ConfigDoctor.repair(config);
        if (actions.isEmpty()) {
            System.out.println("  · 无目录需要补齐");
        } else {
            actions.forEach(action -> System.out.println("  ✓ " + action));
        }
        System.out.println();

        System.out.println("  ── 复检 ──");
        Map<String, Object> raw;
        try {
            raw = ConfigLoader.readRaw(configPath);
        } catch (Exception e) {
            System.out.println("  ✗ 复检读取失败: " + e.getMessage());
            System.out.println();
            return 1;
        }
        List<ConfigDoctor.Finding> findings = ConfigDoctor.diagnose(config, raw, configPath);
        printFindings(findings);
        return exitCode(findings);
    }

    private void printFindings(List<ConfigDoctor.Finding> findings) {
        for (ConfigDoctor.Finding finding : findings) {
            System.out.println("  " + symbol(finding.level()) + " " + finding.title()
                    + (finding.fixable() ? "  [可修复]" : ""));
            if (!finding.detail().isEmpty()) {
                System.out.println("      " + finding.detail());
            }
        }
        System.out.println();
    }

    private String symbol(ConfigDoctor.Level level) {
        return switch (level) {
            case OK -> "✓";
            case WARN -> "!";
            case ERROR -> "✗";
        };
    }

    private boolean hasFixable(List<ConfigDoctor.Finding> findings) {
        return findings.stream().anyMatch(ConfigDoctor.Finding::fixable);
    }

    private int exitCode(List<ConfigDoctor.Finding> findings) {
        boolean hasError = findings.stream()
                .anyMatch(finding -> finding.level() == ConfigDoctor.Level.ERROR);
        return hasError ? 1 : 0;
    }
}
