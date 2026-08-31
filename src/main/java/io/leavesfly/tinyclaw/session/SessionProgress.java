package io.leavesfly.tinyclaw.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 会话进度卡 - 长任务的阶段状态。
 *
 * <h2>要解决的问题</h2>
 * <p>协同与子代理动辄跑几十秒到几分钟，期间的阶段信息此前只存在于流式回调里：浏览器一刷新，
 * 用户就完全看不出还在不在跑、跑到哪了，只能盯着空白等。进度卡把阶段状态从"连接的附属物"
 * 变成"会话的属性"，重连后能立刻续看。</p>
 *
 * <h2>为什么不写进转录</h2>
 * <p>进度每个阶段都会变，写进 append-only 转录会让一次协同产生几十行噪声，还会污染历史回放。
 * 它落在会话元信息索引里：索引本身可从转录重建，丢了不影响任何真实数据。</p>
 *
 * <h2>为什么进程重启要清空</h2>
 * <p>进度描述的是"正在跑"的任务。进程重启后这些任务并不存在了，把上次的进度读回来只会
 * 显示一个永远不会前进的假进度条，比没有进度更糟。</p>
 *
 * @param phase          当前阶段名，面向用户展示
 * @param detail         阶段细节，可为空串
 * @param completedSteps 已完成步数
 * @param totalSteps     总步数；{@code <= 0} 表示总量未知，前端应显示为不确定进度
 * @param updatedAt      最后更新时刻
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionProgress(String phase,
                              String detail,
                              int completedSteps,
                              int totalSteps,
                              Instant updatedAt) {

    /**
     * 创建总量未知的进度（如"讨论中"这种轮次上限不确定的阶段）。
     */
    public static SessionProgress of(String phase, String detail) {
        return new SessionProgress(safe(phase), safe(detail), 0, 0, Instant.now());
    }

    /**
     * 创建带步数的进度。
     *
     * <p>{@code completed} 会被夹在 {@code [0, total]} 内：调用方常从任务图里算步数，
     * 一旦算出超过总数的值，前端进度条会渲染成溢出状态。</p>
     */
    public static SessionProgress of(String phase, String detail, int completed, int total) {
        int boundedTotal = Math.max(0, total);
        int boundedCompleted = boundedTotal > 0
                ? Math.min(Math.max(0, completed), boundedTotal)
                : Math.max(0, completed);
        return new SessionProgress(safe(phase), safe(detail),
                boundedCompleted, boundedTotal, Instant.now());
    }

    /** 总步数已知时返回 true，前端据此决定是否渲染百分比 */
    @JsonIgnore
    public boolean hasKnownTotal() {
        return totalSteps > 0;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
