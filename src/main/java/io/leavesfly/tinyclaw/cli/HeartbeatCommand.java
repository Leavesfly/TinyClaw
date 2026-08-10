package io.leavesfly.tinyclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.config.ConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

/**
 * 心跳命令，查询与手动触发心跳。
 *
 * <p>子命令：</p>
 * <ul>
 *   <li>{@code now}：立即触发一次心跳（经 Web Console API，需 gateway 运行中）</li>
 *   <li>{@code last}：显示各 agent 最近一次心跳的时间/结果/跳过原因
 *       （读取 workspace/memory/heartbeat-status.json）</li>
 * </ul>
 */
public class HeartbeatCommand extends CliCommand {

    private static final String SUBCOMMAND_NOW = "now";
    private static final String SUBCOMMAND_LAST = "last";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    @Override
    public String name() {
        return "heartbeat";
    }

    @Override
    public String description() {
        return "查询与手动触发心跳";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length < 1) {
            printHelp();
            return 1;
        }
        return switch (args[0]) {
            case SUBCOMMAND_NOW -> triggerNow();
            case SUBCOMMAND_LAST -> showLast();
            default -> {
                System.out.println("未知的心跳命令: " + args[0]);
                printHelp();
                yield 1;
            }
        };
    }

    /**
     * 触发一次心跳：POST 到 Web Console 的 /api/heartbeat/now。
     */
    private int triggerNow() {
        try {
            Config config = ConfigLoader.load(getConfigPath());
            String host = normalizeHost(config.getGateway().getHost());
            int webPort = config.getGateway().getPort() + 1;
            URI uri = URI.create("http://" + host + ":" + webPort + "/api/heartbeat/now");

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10));
            applyBasicAuth(builder, config);

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("✓ 心跳已触发，可通过 'tinyclaw heartbeat last' 查看结果");
                return 0;
            }
            System.out.println("✗ 触发失败 (" + response.statusCode() + "): " + response.body());
            return 1;
        } catch (java.net.ConnectException e) {
            System.out.println("✗ 无法连接 gateway，请确认 'tinyclaw gateway' 正在运行且启用了心跳");
            return 1;
        } catch (Exception e) {
            System.out.println("✗ 触发失败: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 显示各 agent 最近一次心跳状态（读取状态文件）。
     */
    private int showLast() {
        try {
            Config config = ConfigLoader.load(getConfigPath());
            Path statusFile = Paths.get(config.getWorkspacePath(), "memory", "heartbeat-status.json");
            if (!Files.exists(statusFile)) {
                System.out.println("暂无心跳记录。");
                return 0;
            }

            JsonNode root = MAPPER.readTree(Files.readString(statusFile));
            if (root.isEmpty()) {
                System.out.println("暂无心跳记录。");
                return 0;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_FORMAT)
                    .withZone(ZoneId.systemDefault());

            System.out.println();
            System.out.println("最近心跳记录：");
            System.out.println("----------------");
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode info = entry.getValue();
                System.out.println("  " + entry.getKey());
                System.out.println("    状态: " + info.path("status").asText("-"));
                System.out.println("    原因: " + info.path("reason").asText("-"));
                if (info.has("at_ms")) {
                    System.out.println("    时间: "
                            + formatter.format(Instant.ofEpochMilli(info.path("at_ms").asLong())));
                }
                if (info.has("duration_ms")) {
                    System.out.println("    耗时: " + info.path("duration_ms").asLong() + " ms");
                }
            }
            return 0;
        } catch (Exception e) {
            System.out.println("✗ 读取心跳状态失败: " + e.getMessage());
            return 1;
        }
    }

    private void applyBasicAuth(HttpRequest.Builder builder, Config config) {
        String username = config.getGateway().getUsername();
        String password = config.getGateway().getPassword();
        if (username != null && !username.isEmpty() && password != null) {
            String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
            builder.header("Authorization", "Basic " + token);
        }
    }

    private String normalizeHost(String host) {
        return "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }

    @Override
    public void printHelp() {
        System.out.println();
        System.out.println("心跳命令：");
        System.out.println("  now      立即触发一次心跳（需 gateway 运行中）");
        System.out.println("  last     显示最近一次心跳的时间/结果/跳过原因");
    }
}
