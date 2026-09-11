package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;
import io.leavesfly.tinyclaw.session.RunRecord;
import io.leavesfly.tinyclaw.session.RunRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 人机交互（HITL）登记处：在单向 SSE 之上实现「Agent 暂停 → 用户在 Web 决策 → Agent 继续」。
 *
 * <p>SSE 只能服务端推客户端，工具执行线程无法直接从流里读到用户的选择。本类作为
 * 工具（等待方）与 {@code ChatHandler}（回传方）之间的共享中介：
 * <ol>
 *   <li>工具调用 {@link #requestApproval} / {@link #requestUserInput}，登记一个
 *       {@link CompletableFuture} 并沿 SSE 回调下发一个带 {@code requestId} 的请求事件；</li>
 *   <li>调用线程在 future 上阻塞（带超时）；</li>
 *   <li>用户在前端点击后，{@code ChatHandler} 调用 {@link #resolve} 完成对应 future，
 *       阻塞线程被唤醒并拿到决策结果；</li>
 *   <li>无论成功、拒绝还是超时，finally 都会摘除登记项，避免泄漏。</li>
 * </ol>
 *
 * <p>P1 扩展：每个等待中的交互额外记录 {@code sessionKey}（刷新后可按会话重建审批卡）、
 * 类型与问题/命令文本，并联动 {@link RunRegistry} 把对应执行标记为 WAITING_USER /
 * RUNNING。停止任务时 {@link #abortSession} 唤醒该会话全部待审批 Future（按拒绝处理），
 * 避免停止请求卡在等待用户输入上。</p>
 *
 * <p>并发安全：pending 以 {@code requestId} 为键，多个会话/多个交互互不干扰。
 * 超时兜底保证用户不响应时不会永久挂起工具线程（超时按拒绝/无回答处理）。</p>
 */
public class InteractionBroker {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("hitl");

    /** 一次交互的用户决策结果。 */
    public static final class Decision {
        /** 审批是否通过（仅审批类交互有意义）。 */
        public final boolean approved;
        /** 用户回答文本（仅提问类交互有意义，可为 null）。 */
        public final String response;

        Decision(boolean approved, String response) {
            this.approved = approved;
            this.response = response;
        }
    }

    /** 等待中的交互描述（P1：可按会话查询，供前端刷新后重建审批卡）。 */
    public static final class PendingInfo {
        public final String requestId;
        public final String sessionKey;
        /** APPROVAL 或 ASK_USER。 */
        public final String type;
        /** 待审批命令或问题文本。 */
        public final String prompt;
        /** 审批拒绝原因（仅 APPROVAL 有值）。 */
        public final String reason;
        /** 候选项（仅 ASK_USER 有值，可为空）。 */
        public final List<String> options;
        public final long createdAt;
        /** 过期时间（epoch millis），超时后交互失效。 */
        public final long expiresAt;

        PendingInfo(String requestId, String sessionKey, String type, String prompt,
                    String reason, List<String> options, long createdAt, long expiresAt) {
            this.requestId = requestId;
            this.sessionKey = sessionKey;
            this.type = type;
            this.prompt = prompt;
            this.reason = reason;
            this.options = options;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    /** 等待中的交互：requestId → 条目（future + 可查询描述）。 */
    private final ConcurrentHashMap<String, PendingEntry> pending = new ConcurrentHashMap<>();

    /** 执行登记处（可选）：交互开始/结束时联动 WAITING_USER / RUNNING 标记。 */
    private volatile RunRegistry runRegistry;

    /** 注入执行登记处（bootstrap 装配时调用，可为 null 表示不联动）。 */
    public void setRunRegistry(RunRegistry runRegistry) {
        this.runRegistry = runRegistry;
    }

    private static final class PendingEntry {
        final CompletableFuture<Decision> future = new CompletableFuture<>();
        final PendingInfo info;
        PendingEntry(PendingInfo info) {
            this.info = info;
        }
    }

    /**
     * 发起一次危险命令审批：下发审批请求事件并阻塞等待用户决策（兼容旧调用方，无会话归属）。
     */
    public boolean requestApproval(LLMProvider.EnhancedStreamCallback callback,
                                   String command, String reason, long timeoutSeconds) {
        return requestApproval(callback, null, command, reason, timeoutSeconds);
    }

    /**
     * 发起一次危险命令审批：下发审批请求事件并阻塞等待用户决策。
     *
     * @param callback       当前会话的 SSE 回调（用于下发请求事件），不可为 null
     * @param sessionKey     归属会话（P1：刷新后按会话重建审批卡；可为 null，兼容旧调用）
     * @param command        待审批命令
     * @param reason         触发审批的原因
     * @param timeoutSeconds 等待超时（秒），超时按拒绝处理
     * @return {@code true} 用户批准；{@code false} 拒绝 / 超时 / 中断 / 被停止唤醒
     */
    public boolean requestApproval(LLMProvider.EnhancedStreamCallback callback, String sessionKey,
                                   String command, String reason, long timeoutSeconds) {
        String requestId = newRequestId();
        PendingEntry entry = register(requestId, sessionKey, "APPROVAL",
                command, reason, null, timeoutSeconds);
        try {
            callback.onEvent(StreamEvent.approvalRequest(requestId, command, reason));
            markWaitingUser(sessionKey);
            Decision decision = entry.future.get(timeoutSeconds, TimeUnit.SECONDS);
            boolean approved = decision != null && decision.approved;
            logger.info("Approval resolved", Map.of(
                    "requestId", requestId, "approved", approved,
                    "session", sessionKey != null ? sessionKey : "",
                    "command", command != null ? command : ""));
            return approved;
        } catch (TimeoutException e) {
            logger.warn("Approval timed out (treated as denied)", Map.of(
                    "requestId", requestId, "timeoutSeconds", timeoutSeconds));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Approval interrupted (treated as denied)", Map.of("requestId", requestId));
            return false;
        } catch (Exception e) {
            logger.error("Approval failed (treated as denied)", Map.of(
                    "requestId", requestId, "error", String.valueOf(e.getMessage())));
            return false;
        } finally {
            pending.remove(requestId);
            markRunningAgain(sessionKey);
        }
    }

    /**
     * 发起一次结构化提问：下发提问事件并阻塞等待用户回答（兼容旧调用方，无会话归属）。
     */
    public String requestUserInput(LLMProvider.EnhancedStreamCallback callback,
                                   String question, List<String> options, long timeoutSeconds) {
        return requestUserInput(callback, null, question, options, timeoutSeconds);
    }

    /**
     * 发起一次结构化提问：下发提问事件并阻塞等待用户回答。
     *
     * @param callback       当前会话的 SSE 回调，不可为 null
     * @param sessionKey     归属会话（P1）
     * @param question       问题文本
     * @param options        可选项（可为空，表示自由作答）
     * @param timeoutSeconds 等待超时（秒）
     * @return 用户回答文本；超时 / 中断 / 被停止唤醒时返回 null
     */
    public String requestUserInput(LLMProvider.EnhancedStreamCallback callback, String sessionKey,
                                   String question, List<String> options, long timeoutSeconds) {
        String requestId = newRequestId();
        PendingEntry entry = register(requestId, sessionKey, "ASK_USER",
                question, null, options, timeoutSeconds);
        try {
            callback.onEvent(StreamEvent.askUser(requestId, question, options));
            markWaitingUser(sessionKey);
            Decision decision = entry.future.get(timeoutSeconds, TimeUnit.SECONDS);
            String response = decision != null ? decision.response : null;
            logger.info("User input resolved", Map.of(
                    "requestId", requestId, "answered", response != null,
                    "session", sessionKey != null ? sessionKey : ""));
            return response;
        } catch (TimeoutException e) {
            logger.warn("User input timed out", Map.of(
                    "requestId", requestId, "timeoutSeconds", timeoutSeconds));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("User input interrupted", Map.of("requestId", requestId));
            return null;
        } catch (Exception e) {
            logger.error("User input failed", Map.of(
                    "requestId", requestId, "error", String.valueOf(e.getMessage())));
            return null;
        } finally {
            pending.remove(requestId);
            markRunningAgain(sessionKey);
        }
    }

    /**
     * 回传用户对某个交互的决策，唤醒等待中的工具线程。
     *
     * <p>幂等：future 完成后重复 resolve 返回 false（第二次调用对已完成 future 无效），
     * 重复决策不会产生重复副作用。</p>
     *
     * @param requestId 交互请求 id
     * @param approved  审批结果（提问类交互可忽略）
     * @param response  回答文本（审批类交互可为 null）
     * @return {@code true} 成功唤醒一个等待中的交互；{@code false} 该 id 不存在或已完成（如已超时）
     */
    public boolean resolve(String requestId, boolean approved, String response) {
        if (requestId == null) {
            return false;
        }
        PendingEntry entry = pending.get(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(new Decision(approved, response));
    }

    /**
     * 查询某会话全部待处理交互（P1：刷新后重建审批卡）。
     *
     * @param sessionKey 会话标识
     * @return 待处理交互描述列表（无序），过期项不剔除（调用方按 expiresAt 判断展示）
     */
    public List<PendingInfo> pendingForSession(String sessionKey) {
        List<PendingInfo> result = new ArrayList<>();
        if (sessionKey == null) {
            return result;
        }
        for (PendingEntry entry : pending.values()) {
            if (sessionKey.equals(entry.info.sessionKey)) {
                result.add(entry.info);
            }
        }
        return result;
    }

    /**
     * 停止任务时唤醒该会话全部待审批 Future（P1：CANCELLING 不被审批等待卡住）。
     *
     * <p>唤醒决策按拒绝/无回答处理：审批类得到拒绝、提问类得到 null，
     * 与超时语义一致；不自动撤销已发生的外部操作。</p>
     *
     * @param sessionKey 目标会话
     * @return 被唤醒的交互数量
     */
    public int abortSession(String sessionKey) {
        if (sessionKey == null) {
            return 0;
        }
        int woken = 0;
        for (PendingEntry entry : pending.values()) {
            if (sessionKey.equals(entry.info.sessionKey)) {
                boolean completed = entry.future.complete(new Decision(false, null));
                if (completed) {
                    woken++;
                }
            }
        }
        if (woken > 0) {
            logger.info("Pending interactions woken by abort", Map.of(
                    "session", sessionKey, "count", woken));
        }
        return woken;
    }

    /** 当前等待中的交互数量（用于诊断）。 */
    public int pendingCount() {
        return pending.size();
    }

    // ==================== 内部 ====================

    /** 登记等待中的交互。 */
    private PendingEntry register(String requestId, String sessionKey, String type,
                                  String prompt, String reason, List<String> options,
                                  long timeoutSeconds) {
        long now = System.currentTimeMillis();
        PendingInfo info = new PendingInfo(requestId, sessionKey, type, prompt,
                reason, options != null ? List.copyOf(options) : List.of(),
                now, now + timeoutSeconds * 1000L);
        PendingEntry entry = new PendingEntry(info);
        pending.put(requestId, entry);
        return entry;
    }

    /** 交互开始等待：把该会话的活动执行标记 WAITING_USER（若登记处可用）。 */
    private void markWaitingUser(String sessionKey) {
        RunRegistry registry = this.runRegistry;
        if (registry == null || sessionKey == null) {
            return;
        }
        try {
            // 会话同时最多一个活动 Web 根执行：按会话定位 runId
            // （暂无 runId 直通通道，避免为工具层引入新的上下文接口）
            for (RunRecord r : registry.listBySession(sessionKey, 1)) {
                if (!r.isTerminal() && r.statusEnum() != RunRecord.Status.WAITING_USER) {
                    registry.transition(r.id, RunRecord.Status.WAITING_USER);
                }
                break;
            }
        } catch (Exception e) {
            logger.debug("Failed to mark WAITING_USER", Map.of(
                    "session", sessionKey, "error", String.valueOf(e.getMessage())));
        }
    }

    /** 交互结束等待：恢复该会话活动执行为 RUNNING（若处于 WAITING_USER）。 */
    private void markRunningAgain(String sessionKey) {
        RunRegistry registry = this.runRegistry;
        if (registry == null || sessionKey == null) {
            return;
        }
        try {
            for (RunRecord r : registry.listBySession(sessionKey, 1)) {
                if (r.statusEnum() == RunRecord.Status.WAITING_USER) {
                    registry.transition(r.id, RunRecord.Status.RUNNING);
                }
                break;
            }
        } catch (Exception e) {
            logger.debug("Failed to mark RUNNING again", Map.of(
                    "session", sessionKey, "error", String.valueOf(e.getMessage())));
        }
    }

    private String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
