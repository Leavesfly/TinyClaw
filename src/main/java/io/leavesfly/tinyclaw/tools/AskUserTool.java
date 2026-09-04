package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.providers.LLMProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化提问工具（HITL）：让 Agent 在信息不足或需要用户决策时，向用户抛出一个
 * 带可选项的问题并等待回答。
 *
 * <p>仅在交互式 Web 会话（sessionKey 以 {@code web} 开头）中有效：只有 Web 控制台
 * 能渲染提问卡片并回传答案。其他通道（Telegram/钉钉等）无对应 UI，工具会直接返回
 * 不可用提示，让模型改用其它方式继续，而不会挂起。</p>
 *
 * <p>等待/唤醒/超时由 {@link InteractionBroker} 统一管理，本工具只负责发起与取回结果。
 * 与 {@code SpawnTool}/{@code CollaborateTool} 一样是单例，回调与会话在每次执行前由
 * {@code ReActExecutor.setToolContext} 覆写，故 execute 内先把回调读入局部变量再用。</p>
 */
public class AskUserTool implements Tool, StreamAwareTool, ToolContextAware {

    /** 工具名，供嵌套执行体剔除或前端识别使用。 */
    public static final String NAME = "ask_user";

    /** 等待用户回答的超时（秒）。超时后返回未回答提示，不阻塞后续流程。 */
    private static final long TIMEOUT_SECONDS = 300;

    private final InteractionBroker broker;

    private volatile LLMProvider.EnhancedStreamCallback streamCallback;
    private volatile String sessionKey;

    /**
     * 构造 ask_user 工具。
     *
     * @param broker HITL 交互登记处，不可为 null
     */
    public AskUserTool(InteractionBroker broker) {
        if (broker == null) {
            throw new IllegalArgumentException("InteractionBroker is required for AskUserTool");
        }
        this.broker = broker;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向用户提出一个结构化问题并等待回答。用于信息不足或需要用户在多个方案间决策时。"
                + "可附带若干候选项（options）供用户点选，也可不附带让用户自由作答。仅在交互式 Web 会话中有效。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> questionParam = new HashMap<>();
        questionParam.put("type", "string");
        questionParam.put("description", "要向用户提出的问题，应清晰、具体、可回答");
        properties.put("question", questionParam);

        Map<String, Object> optionsParam = new HashMap<>();
        optionsParam.put("type", "array");
        optionsParam.put("description", "可选：供用户点选的候选答案列表；留空表示让用户自由作答");
        Map<String, Object> items = new HashMap<>();
        items.put("type", "string");
        optionsParam.put("items", items);
        properties.put("options", optionsParam);

        params.put("properties", properties);
        params.put("required", new String[]{"question"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String question = args.get("question") instanceof String s ? s.trim() : "";
        if (question.isEmpty()) {
            throw new IllegalArgumentException("question 参数是必需的");
        }
        List<String> options = extractOptions(args.get("options"));

        // 先把单例上的回调/会话读入局部变量，避免并发执行时被下一次 setToolContext 覆写
        LLMProvider.EnhancedStreamCallback cb = this.streamCallback;
        String session = this.sessionKey;

        if (cb == null || !isWebSession(session)) {
            return "错误: ask_user 仅在交互式 Web 会话中可用；当前会话无法向用户提问，请基于已有信息继续。";
        }

        String response = broker.requestUserInput(cb, question, options, TIMEOUT_SECONDS);
        if (response == null || response.isBlank()) {
            return "用户未在超时时间内回答该问题，请基于现有信息继续，或换一种方式推进。";
        }
        return "用户回答: " + response;
    }

    /**
     * 从参数中解析候选项列表，容忍 null / 非列表 / 非字符串元素。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractOptions(Object raw) {
        List<String> options = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    String text = String.valueOf(o).trim();
                    if (!text.isEmpty()) {
                        options.add(text);
                    }
                }
            }
        }
        return options;
    }

    /**
     * 是否为可交互的 Web 会话（Web 控制台会话 key 形如 {@code web:default}）。
     */
    private boolean isWebSession(String session) {
        return session != null && session.startsWith("web");
    }

    @Override
    public void setStreamCallback(LLMProvider.EnhancedStreamCallback callback) {
        this.streamCallback = callback;
    }

    @Override
    public void setChannelContext(String channel, String chatId) {
        // ask_user 不需要投递目标，通道上下文无用；实现接口只为拿到 setSessionContext 的 sessionKey
    }

    @Override
    public void setSessionContext(String sessionKey) {
        this.sessionKey = sessionKey;
    }
}
