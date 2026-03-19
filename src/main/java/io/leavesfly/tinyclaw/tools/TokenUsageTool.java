package io.leavesfly.tinyclaw.tools;



import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 消耗查询工具，供大模型通过 function calling 调用。
 *
 * <p>支持按日期范围查询 token 使用统计，包含总量、按模型分组、按日期分组三个维度。</p>
 * <p>当用户询问"今天用了多少 token"、"本周的 token 消耗"等问题时，Agent 会自动调用此工具。</p>
 */
public class TokenUsageTool implements Tool {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TokenUsageStore tokenUsageStore;

    public TokenUsageTool(TokenUsageStore tokenUsageStore) {
        this.tokenUsageStore = tokenUsageStore;
    }

    @Override
    public String name() {
        return "query_token_usage";
    }

    @Override
    public String description() {
        return "查询指定日期范围内的 token 消耗统计，包含总调用次数、输入/输出 token 数量、" +
                "按模型分组统计和按日期分组统计。当用户询问 token 使用情况、消耗量、调用次数等问题时调用此工具。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> startDateParam = new HashMap<>();
        startDateParam.put("type", "string");
        startDateParam.put("description", "查询开始日期，格式 yyyy-MM-dd，例如 2024-01-01。若查询今天则填今天的日期。");
        properties.put("start_date", startDateParam);

        Map<String, Object> endDateParam = new HashMap<>();
        endDateParam.put("type", "string");
        endDateParam.put("description", "查询结束日期，格式 yyyy-MM-dd，例如 2024-01-31。若查询今天则填今天的日期。");
        properties.put("end_date", endDateParam);

        params.put("properties", properties);
        params.put("required", List.of("start_date", "end_date"));

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String startDate = (String) args.get("start_date");
        String endDate = (String) args.get("end_date");

        if (startDate == null || startDate.isBlank()) {
            startDate = LocalDate.now().format(DATE_FORMATTER);
        }
        if (endDate == null || endDate.isBlank()) {
            endDate = LocalDate.now().format(DATE_FORMATTER);
        }

        try {
            TokenUsageStore.TokenStats stats = tokenUsageStore.query(startDate, endDate);
            return formatStats(startDate, endDate, stats);
        } catch (Exception e) {
            throw new ToolException("查询 token 消耗失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 TokenStats 格式化为人类可读的文本，便于大模型直接引用回答用户。
     */
    private String formatStats(String startDate, String endDate, TokenUsageStore.TokenStats stats) {
        StringBuilder result = new StringBuilder();
        result.append("📊 Token 消耗统计（").append(startDate).append(" ~ ").append(endDate).append("）\n\n");

        result.append("【总览】\n");
        result.append("  总调用次数：").append(stats.totalCalls).append(" 次\n");
        result.append("  输入 Token：").append(stats.totalPromptTokens).append("\n");
        result.append("  输出 Token：").append(stats.totalCompletionTokens).append("\n");
        result.append("  合计 Token：").append(stats.totalPromptTokens + stats.totalCompletionTokens).append("\n");

        if (!stats.byModel.isEmpty()) {
            result.append("\n【按模型分组】\n");
            for (Map.Entry<String, long[]> entry : stats.byModel.entrySet()) {
                long[] values = entry.getValue();
                result.append("  ").append(entry.getKey()).append("\n");
                result.append("    调用次数：").append(values[2]).append(" 次\n");
                result.append("    输入 Token：").append(values[0]).append("\n");
                result.append("    输出 Token：").append(values[1]).append("\n");
                result.append("    合计 Token：").append(values[0] + values[1]).append("\n");
            }
        }

        if (!stats.byDate.isEmpty()) {
            result.append("\n【按日期分组】\n");
            for (Map.Entry<String, long[]> entry : stats.byDate.entrySet()) {
                long[] values = entry.getValue();
                result.append("  ").append(entry.getKey()).append("：")
                        .append("调用 ").append(values[2]).append(" 次，")
                        .append("输入 ").append(values[0]).append("，")
                        .append("输出 ").append(values[1]).append("，")
                        .append("合计 ").append(values[0] + values[1]).append("\n");
            }
        }

        if (stats.totalCalls == 0) {
            result.append("\n（该时间段内暂无 token 消耗记录）\n");
        }

        return result.toString();
    }
}
