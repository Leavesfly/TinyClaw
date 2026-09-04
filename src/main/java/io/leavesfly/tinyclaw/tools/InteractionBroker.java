package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.StreamEvent;

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
 * <p>并发安全：pending 以 {@code requestId} 为键，多个会话/多个交互互不干扰。
 * 超时兜底保证用户不响应时不会永久挂起工具线程（超时按拒绝/无回答处理）。
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

    /** 等待中的交互：requestId → future。 */
    private final ConcurrentHashMap<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    /**
     * 发起一次危险命令审批：下发审批请求事件并阻塞等待用户决策。
     *
     * @param callback       当前会话的 SSE 回调（用于下发请求事件），不可为 null
     * @param command        待审批命令
     * @param reason         触发审批的原因
     * @param timeoutSeconds 等待超时（秒），超时按拒绝处理
     * @return {@code true} 用户批准；{@code false} 拒绝 / 超时 / 中断
     */
    public boolean requestApproval(LLMProvider.EnhancedStreamCallback callback,
                                   String command, String reason, long timeoutSeconds) {
        String requestId = newRequestId();
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            callback.onEvent(StreamEvent.approvalRequest(requestId, command, reason));
            Decision decision = future.get(timeoutSeconds, TimeUnit.SECONDS);
            boolean approved = decision != null && decision.approved;
            logger.info("Approval resolved", Map.of(
                    "requestId", requestId, "approved", approved,
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
        }
    }

    /**
     * 发起一次结构化提问：下发提问事件并阻塞等待用户回答。
     *
     * @param callback       当前会话的 SSE 回调，不可为 null
     * @param question       问题文本
     * @param options        可选项（可为空，表示自由作答）
     * @param timeoutSeconds 等待超时（秒）
     * @return 用户回答文本；超时 / 中断 / 无回答时返回 null
     */
    public String requestUserInput(LLMProvider.EnhancedStreamCallback callback,
                                   String question, List<String> options, long timeoutSeconds) {
        String requestId = newRequestId();
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            callback.onEvent(StreamEvent.askUser(requestId, question, options));
            Decision decision = future.get(timeoutSeconds, TimeUnit.SECONDS);
            String response = decision != null ? decision.response : null;
            logger.info("User input resolved", Map.of(
                    "requestId", requestId, "answered", response != null));
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
        }
    }

    /**
     * 回传用户对某个交互的决策，唤醒等待中的工具线程。
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
        CompletableFuture<Decision> future = pending.get(requestId);
        if (future == null) {
            return false;
        }
        return future.complete(new Decision(approved, response));
    }

    /** 当前等待中的交互数量（用于诊断）。 */
    public int pendingCount() {
        return pending.size();
    }

    private String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
