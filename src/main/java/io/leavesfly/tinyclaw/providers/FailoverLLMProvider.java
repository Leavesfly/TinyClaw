package io.leavesfly.tinyclaw.providers;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.util.List;
import java.util.Map;

/**
 * 运行时 failover 装饰器：主 Provider 调用失败时按链降级到备用 Provider。
 *
 * <p>每个降级条目自带模型名（fallback 项在 models.definitions 中绑定的 model key），
 * 请求时替换 {@code model} 参数，保证 api_base 与 model 始终来自同一绑定关系。</p>
 *
 * 降级规则：
 * - 仅对 {@link LLMException}（网络/HTTP 错误）与 {@link IllegalStateException}（配置缺失）降级；
 * - 流式请求一旦已向回调透出任何事件（正文/思考/工具），不再降级，避免半截重复输出；
 * - 全部失败时抛出最后一个异常，调用方感知与单 Provider 一致。
 */
public class FailoverLLMProvider implements LLMProvider {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("provider");

    private final List<LLMProvider> providers;
    private final List<String> models;

    /**
     * 构造 failover 链。
     *
     * @param providers Provider 列表，第一个为主 Provider
     * @param models    与 providers 一一对应的模型名列表
     */
    public FailoverLLMProvider(List<LLMProvider> providers, List<String> models) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("failover chain requires at least one provider");
        }
        if (models == null || providers.size() != models.size()) {
            throw new IllegalArgumentException("providers and models must be parallel lists");
        }
        this.providers = List.copyOf(providers);
        this.models = List.copyOf(models);
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model,
                            Map<String, Object> options) {
        Exception last = null;
        for (int i = 0; i < providers.size(); i++) {
            try {
                return providers.get(i).chat(messages, tools, models.get(i), options);
            } catch (LLMException | IllegalStateException e) {
                last = e;
                logFailover(i, e);
            }
        }
        throw new LLMException("all providers failed: " + last.getMessage(), last);
    }

    @Override
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model,
                                  Map<String, Object> options, StreamCallback callback) {
        boolean[] emitted = {false};
        StreamCallback guarded = guard(callback, emitted);

        Exception last = null;
        for (int i = 0; i < providers.size(); i++) {
            try {
                return providers.get(i).chatStream(messages, tools, models.get(i), options, guarded);
            } catch (LLMException | IllegalStateException e) {
                last = e;
                // 已透出内容后降级会产生半截重复输出，直接上抛
                if (emitted[0]) {
                    break;
                }
                logFailover(i, e);
            }
        }
        throw new LLMException("all providers failed: " + last.getMessage(), last);
    }

    /**
     * 包装回调以感知"是否已透出事件"，同时保留 EnhancedStreamCallback 身份
     * （THINKING 事件依赖 instanceof 判定，降级包装不得丢失该类型）。
     */
    private StreamCallback guard(StreamCallback callback, boolean[] emitted) {
        if (callback == null) {
            return null;
        }
        if (callback instanceof EnhancedStreamCallback enhanced) {
            return (EnhancedStreamCallback) event -> {
                emitted[0] = true;
                enhanced.onEvent(event);
            };
        }
        return chunk -> {
            emitted[0] = true;
            callback.onChunk(chunk);
        };
    }

    /**
     * 记录降级日志；最后一位 Provider 失败时无下一位，不记录。
     */
    private void logFailover(int index, Exception e) {
        if (index + 1 >= providers.size()) {
            return;
        }
        logger.warn("LLM provider failed, failing over", Map.of(
                "failed_provider", providers.get(index).getName(),
                "failed_model", models.get(index),
                "next_provider", providers.get(index + 1).getName(),
                "next_model", models.get(index + 1),
                "error", String.valueOf(e.getMessage())
        ));
    }

    @Override
    public String getDefaultModel() {
        return providers.get(0).getDefaultModel();
    }

    @Override
    public String getName() {
        return providers.get(0).getName() + "+failover:" + (providers.size() - 1);
    }
}
