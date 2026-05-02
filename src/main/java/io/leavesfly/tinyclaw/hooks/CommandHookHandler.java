package io.leavesfly.tinyclaw.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于外部 shell 命令的 Hook handler，对齐 Claude Code 的 command 类型协议。
 *
 * <h3>通信协议</h3>
 * <ul>
 *   <li><b>stdin</b>：UTF-8 JSON，内容由 {@link HookContext#toPayload()} 决定，写完即关闭</li>
 *   <li><b>stdout</b>：允许为空；非空则必须是合法 JSON。解析以下字段：
 *       <pre>
 *       {
 *         "hookSpecificOutput": {
 *           "permissionDecision": "allow" | "deny",
 *           "permissionDecisionReason": "...",
 *           "modifiedInput": { ... },
 *           "modifiedOutput": "...",
 *           "modifiedPrompt": "...",
 *           "additionalContext": "..."
 *         }
 *       }
 *       </pre>
 *   </li>
 *   <li><b>stderr</b>：exit code = 2 时作为 deny reason</li>
 *   <li><b>exit code</b>：
 *     <ul>
 *       <li>{@code 0} → 以 stdout JSON 为准；stdout 为空则视为 allow</li>
 *       <li>{@code 2} → 强制 deny，reason 取 stderr（为空则给默认文案）</li>
 *       <li>其他非零 → 记 warn 日志，fail-open（返回 cont，不阻塞主流程）</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>失败语义（fail-open）</h3>
 * <p>Hook 自身的任何异常（进程启动失败、JSON 解析失败、超时、中断等）都不应阻塞主流程：
 * 记 warn 日志并返回 {@link HookDecision#cont()}。这是一个有意的设计选择——
 * hook 脚本是用户自定义的，其故障不应拖垮 Agent 本身。</p>
 *
 * <h3>超时</h3>
 * <p>默认 30 秒；超时会对进程调用 {@link Process#destroyForcibly()}。</p>
 */
public final class CommandHookHandler implements HookHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("hooks");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final int EXIT_CODE_DENY = 2;

    private final String command;
    private final long timeoutMs;
    private final File workingDir;
    private final Map<String, String> extraEnv;

    public CommandHookHandler(String command, long timeoutMs, String workingDir, Map<String, String> extraEnv) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        this.command = command;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.workingDir = (workingDir == null || workingDir.isBlank()) ? null : new File(workingDir);
        this.extraEnv = extraEnv;
    }

    @Override
    public HookDecision invoke(HookContext ctx) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(ctx.toPayload());
        } catch (Exception e) {
            logger.warn("Failed to serialize hook context, skipping hook", Map.of(
                    "command", command, "error", e.getMessage()));
            return HookDecision.cont();
        }

        Process process = null;
        try {
            process = startProcess();
            writeStdin(process, payload);
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("Hook command timed out, fail-open", Map.of(
                        "command", command, "timeout_ms", timeoutMs,
                        "event", ctx.getEvent() == null ? "unknown" : ctx.getEvent().wireName()));
                return HookDecision.cont();
            }

            int exitCode = process.exitValue();
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            return interpret(exitCode, stdout, stderr, ctx);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            logger.warn("Hook command failed, fail-open", Map.of(
                    "command", command, "error", e.getMessage(),
                    "event", ctx.getEvent() == null ? "unknown" : ctx.getEvent().wireName()));
            return HookDecision.cont();
        }
    }

    /** 使用 sh -c 启动进程，兼容管道、重定向等复杂命令写法。Windows 下改用 cmd /c。 */
    private Process startProcess() throws IOException {
        ProcessBuilder pb;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        // stdin/stdout/stderr 走 pipe，由我们自己读写
        return pb.start();
    }

    private void writeStdin(Process process, String payload) throws IOException {
        try (OutputStream os = process.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        byte[] bytes = in.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 根据 exit code、stdout、stderr 组装最终决策。
     */
    private HookDecision interpret(int exitCode, String stdout, String stderr, HookContext ctx) {
        // 明确 deny
        if (exitCode == EXIT_CODE_DENY) {
            String reason = stderr == null || stderr.isBlank()
                    ? "Blocked by hook (exit 2)"
                    : stderr.trim();
            return HookDecision.deny(reason);
        }

        // 非 0 且非 2：记 warn，fail-open
        if (exitCode != 0) {
            logger.warn("Hook command returned non-zero exit code, fail-open", Map.of(
                    "command", command, "exit_code", exitCode,
                    "stderr_preview", truncate(stderr, 200),
                    "event", ctx.getEvent() == null ? "unknown" : ctx.getEvent().wireName()));
            return HookDecision.cont();
        }

        // exit 0：有 stdout 则解析 JSON，否则 cont
        if (stdout == null || stdout.isBlank()) {
            return HookDecision.cont();
        }
        return parseStdoutJson(stdout);
    }

    /**
     * 解析 stdout 中的 hookSpecificOutput JSON。解析失败不影响主流程（fail-open）。
     */
    private HookDecision parseStdoutJson(String stdout) {
        JsonNode root;
        try {
            root = MAPPER.readTree(stdout);
        } catch (Exception e) {
            logger.warn("Hook stdout is not valid JSON, fail-open", Map.of(
                    "command", command, "stdout_preview", truncate(stdout, 200),
                    "error", e.getMessage()));
            return HookDecision.cont();
        }

        JsonNode output = root.path("hookSpecificOutput");
        if (output.isMissingNode() || !output.isObject()) {
            return HookDecision.cont();
        }

        // deny 优先级最高：一旦命中立即返回
        String decision = output.path("permissionDecision").asText(null);
        if ("deny".equalsIgnoreCase(decision)) {
            String reason = output.path("permissionDecisionReason").asText("Blocked by hook");
            return HookDecision.deny(reason);
        }

        // modifyInput
        JsonNode modifiedInputNode = output.path("modifiedInput");
        if (modifiedInputNode.isObject()) {
            Map<String, Object> map = nodeToMap(modifiedInputNode);
            return HookDecision.modifyInput(map);
        }

        // modifyOutput
        String modifiedOutput = textOrNull(output, "modifiedOutput");
        if (modifiedOutput != null) {
            return HookDecision.modifyOutput(modifiedOutput);
        }

        // modifyPrompt
        String modifiedPrompt = textOrNull(output, "modifiedPrompt");
        if (modifiedPrompt != null) {
            return HookDecision.modifyPrompt(modifiedPrompt);
        }

        // additionalContext
        String additional = textOrNull(output, "additionalContext");
        if (additional != null) {
            return HookDecision.addContext(additional);
        }

        return HookDecision.cont();
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull() || !n.isTextual()) {
            return null;
        }
        String v = n.asText();
        return v.isEmpty() ? null : v;
    }

    /** 将 JSON Object 节点转换为 Map&lt;String, Object&gt;，嵌套结构转为 Map / List / 基本类型。 */
    private static Map<String, Object> nodeToMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), nodeToObject(entry.getValue()));
        }
        return map;
    }

    private static Object nodeToObject(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isObject()) {
            return nodeToMap(n);
        }
        if (n.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>(n.size());
            for (JsonNode item : n) {
                list.add(nodeToObject(item));
            }
            return list;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isInt() || n.isLong()) {
            return n.asLong();
        }
        if (n.isFloatingPointNumber()) {
            return n.asDouble();
        }
        return n.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ================== accessors for tests ==================

    String getCommand() {
        return command;
    }

    long getTimeoutMs() {
        return timeoutMs;
    }

    File getWorkingDir() {
        return workingDir;
    }
}
