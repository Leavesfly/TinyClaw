package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM 流式响应解析器。
 * 
 * 处理 SSE（Server-Sent Events）格式的流式数据，
 * 支持增量内容和工具调用的实时解析。
 */
public class StreamResponseParser {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("provider");

    /**
     * 思维链行缓冲软上限：无换行的长段落累积到该长度也提前透出，
     * 避免前端思考卡片长时间无增量。
     */
    private static final int REASONING_SOFT_FLUSH_CHARS = 160;
    
    private final ObjectMapper objectMapper;
    
    public StreamResponseParser() {
        this.objectMapper = new ObjectMapper();
    }
    
    public StreamResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * 解析流式响应。
     * 
     * 处理 SSE（Server-Sent Events）格式的流式数据，
     * 支持增量内容和工具调用的实时解析。
     * 
     * @param source 响应数据源
     * @param callback 流式内容回调函数
     * @return 完整的 LLM 响应对象
     * @throws IOException 解析失败时抛出异常
     */
    public LLMResponse parseStreamResponse(BufferedSource source, LLMProvider.StreamCallback callback) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        // 思维链按行缓冲：token 粒度直接透出会让 CLI 等纯文本端碎片化
        // （每个 token 前后都被加上格式化分隔），聚合到行粒度再发 THINKING 事件
        StringBuilder reasoningBuffer = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        String finishReason = "stop";
        LLMResponse.UsageInfo usage = null;
        
        try {
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                
                // SSE 格式: "data: {json}"
                if (!line.startsWith("data: ")) {
                    continue;
                }
                
                String data = line.substring(6).trim();
                
                // 结束标记
                if (data.equals("[DONE]")) {
                    break;
                }
                
                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    
                    // 解析 usage 信息
                    if (chunk.has("usage")) {
                        usage = parseUsage(chunk.get("usage"));
                    }
                    
                    if (!chunk.has("choices") || chunk.get("choices").isEmpty()) {
                        continue;
                    }
                    
                    JsonNode choice = chunk.get("choices").get(0);
                    
                    // 更新 finish_reason
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                    
                    JsonNode delta = choice.get("delta");
                    if (delta == null || delta.isNull()) {
                        continue;
                    }
                    
                    // 处理思维链内容（ollama 用 reasoning，dashscope/deepseek 等用 reasoning_content）
                    // 不计入最终回复正文，仅通过 THINKING 事件实时透出供前端折叠展示
                    String reasoningChunk = extractText(delta, "reasoning");
                    if (reasoningChunk == null) {
                        reasoningChunk = extractText(delta, "reasoning_content");
                    }
                    if (reasoningChunk != null) {
                        reasoningBuffer.append(reasoningChunk);
                        flushReasoningLines(callback, reasoningBuffer, false);
                    }
                    
                    // 处理流式内容
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String content = delta.get("content").asText();
                        if (content != null && !content.isEmpty()) {
                            // 正文开始即推理阶段结束，此刻必须把残余思维链冲刷出去。
                            // 这类 API 的 reasoning 与 content 是先后关系，首个 content 就是
                            // 可靠的相位分界；若留到流结束才冲刷，那段不足软上限又不含换行的
                            // 尾巴会在正文全部渲染完之后才发出，前端只能把思考卡片追加到正文
                            // 之后，看起来就是「回答结束了又冒出思考过程」。
                            flushReasoningLines(callback, reasoningBuffer, true);
                            fullContent.append(content);
                            if (callback != null) {
                                callback.onChunk(content);
                            }
                        }
                    }
                    
                    // 处理工具调用（流式模式下可能分块传输）
                    if (delta.has("tool_calls")) {
                        parseStreamToolCalls(delta.get("tool_calls"), toolCalls);
                    }
                    
                } catch (Exception e) {
                    logger.error("Failed to parse stream chunk", Map.of(
                            "error", e.getMessage(),
                            "data", data.length() > 200 ? data.substring(0, 200) : data
                    ));
                }
            }
        } catch (IOException e) {
            // 已缓冲的思维链先冲刷：流式中断时这部分内容已经收到，丢掉会让
            // 前端思考卡片停在上一行，反而掩盖了“模型思考到哪里断的”这个关键线索
            flushReasoningLines(callback, reasoningBuffer, true);
            // content_chars 可区分"流中途断开"（已收到部分内容）与"一直没等到首包"
            // （如思考型模型长时间静默触发 readTimeout）；provider/model 上下文由
            // HTTPProvider 层的包装日志补充
            logger.error("Stream read error", Map.of(
                    "error_type", e.getClass().getName(),
                    "error", String.valueOf(e.getMessage()),
                    "content_chars", fullContent.length(),
                    "tool_calls_count", toolCalls.size()
            ));
            throw e;
        }
        
        // 流结束，冲刷未换行的剩余思维链内容
        flushReasoningLines(callback, reasoningBuffer, true);
        
        // 构建完整响应
        return buildStreamResponse(fullContent.toString(), toolCalls, finishReason, usage);
    }
    
    /**
     * 按行冲刷思维链缓冲。
     *
     * <p>遇到换行符时透出完整行（保留行尾换行，便于前端按 pre-wrap 分行显示）；
     * 无换行部分在流结束或超过软上限时整体透出。仅对 EnhancedStreamCallback 生效，
     * 普通回调无法接收 THINKING 事件，缓冲直接丢弃。</p>
     *
     * <p>缓冲为空时立即返回，因此可以在每个正文 chunk 前无脑调用，不会产生空事件。</p>
     *
     * @param callback 流式回调
     * @param buffer 思维链累积缓冲（原地消费）
     * @param endOfStream 是否强制冲刷全部剩余（流结束或推理相位结束）
     */
    private void flushReasoningLines(LLMProvider.StreamCallback callback,
                                     StringBuilder buffer, boolean endOfStream) {
        if (buffer.length() == 0) {
            return;
        }
        if (!(callback instanceof LLMProvider.EnhancedStreamCallback enhanced)) {
            buffer.setLength(0);
            return;
        }
        
        int start = 0;
        int newlineIdx;
        while ((newlineIdx = buffer.indexOf("\n", start)) >= 0) {
            enhanced.onEvent(StreamEvent.thinking(buffer.substring(start, newlineIdx + 1)));
            start = newlineIdx + 1;
        }
        
        int remaining = buffer.length() - start;
        if (endOfStream || remaining >= REASONING_SOFT_FLUSH_CHARS) {
            if (remaining > 0) {
                enhanced.onEvent(StreamEvent.thinking(buffer.substring(start)));
            }
            start = buffer.length();
        }
        buffer.delete(0, start);
    }
    
    /**
     * 解析非流式 LLM 响应。
     * 
     * 从 JSON 响应中提取内容、工具调用和使用统计信息。
     * 
     * @param responseBody 响应体 JSON 字符串
     * @return LLM 响应对象
     * @throws IOException 解析失败时抛出异常
     */
    public LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        LLMResponse response = new LLMResponse();
        
        // 解析 token 使用统计
        if (root.has("usage")) {
            response.setUsage(parseUsage(root.get("usage")));
        }
        
        // 解析响应内容
        if (!root.has("choices") || !root.get("choices").isArray() || root.get("choices").isEmpty()) {
            response.setContent("");
            response.setFinishReason("stop");
            return response;
        }
        
        JsonNode choice = root.get("choices").get(0);
        JsonNode messageNode = choice.get("message");
        
        response.setFinishReason(choice.has("finish_reason") ? choice.get("finish_reason").asText() : "stop");
        response.setContent(messageNode.has("content") && !messageNode.get("content").isNull() 
                ? messageNode.get("content").asText() : "");
        
        // 解析工具调用
        if (messageNode.has("tool_calls") && messageNode.get("tool_calls").isArray()) {
            response.setToolCalls(parseToolCalls(messageNode.get("tool_calls")));
        }
        
        logger.debug("LLM response", Map.of(
                "content_length", response.getContent() != null ? response.getContent().length() : 0,
                "tool_calls_count", response.hasToolCalls() ? response.getToolCalls().size() : 0,
                "finish_reason", response.getFinishReason()
        ));
        
        return response;
    }
    
    /**
     * 构建流式响应对象。
     * 
     * 将解析后的流式数据组装成完整的 LLMResponse 对象，
     * 并处理工具调用参数的 JSON 解析。
     * 
     * @param content 完整的文本内容
     * @param toolCalls 工具调用列表
     * @param finishReason 结束原因
     * @param usage token 使用统计
     * @return 完整的 LLM 响应对象
     */
    private LLMResponse buildStreamResponse(String content, List<ToolCall> toolCalls, 
                                           String finishReason, LLMResponse.UsageInfo usage) {
        LLMResponse response = new LLMResponse();
        response.setContent(content);
        response.setFinishReason(finishReason);
        response.setUsage(usage);
        
        if (!toolCalls.isEmpty()) {
            // 解析所有工具调用的 arguments
            for (ToolCall toolCall : toolCalls) {
                if (toolCall.getArguments() != null && toolCall.getArguments().containsKey("_raw_args")) {
                    String rawArgs = (String) toolCall.getArguments().get("_raw_args");
                    
                    // 检查 rawArgs 是否为空
                    if (rawArgs == null || rawArgs.trim().isEmpty()) {
                        toolCall.setArguments(new HashMap<>());
                        continue;
                    }
                    
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsedArgs = objectMapper.readValue(rawArgs, Map.class);
                        toolCall.setArguments(parsedArgs);
                    } catch (Exception e) {
                        // 解析失败，保留原始字符串
                        Map<String, Object> args = new HashMap<>();
                        args.put("raw", rawArgs);
                        toolCall.setArguments(args);
                        logger.warn("Failed to parse tool call arguments", Map.of(
                                "error", e.getMessage(),
                                "raw_args", rawArgs.length() > 100 ? rawArgs.substring(0, 100) : rawArgs
                        ));
                    }
                }
            }
            response.setToolCalls(toolCalls);
        }
        
        logger.debug("LLM stream response", Map.of(
                "content_length", content.length(),
                "tool_calls_count", toolCalls.size(),
                "finish_reason", finishReason
        ));
        
        return response;
    }
    
    /**
     * 提取 delta 中指定字段的非空文本值。
     *
     * @param delta delta 节点
     * @param field 字段名
     * @return 字段文本值，字段不存在、为 null 或空串时返回 null
     */
    private String extractText(JsonNode delta, String field) {
        if (!delta.has(field) || delta.get(field).isNull()) {
            return null;
        }
        String value = delta.get(field).asText();
        return value.isEmpty() ? null : value;
    }
    
    /**
     * 解析流式工具调用（增量模式）。
     * 
     * 流式模式下，工具调用信息会分多个 chunk 增量传输，
     * 此方法负责将分散的数据片段拼接成完整的工具调用对象。
     * 
     * @param toolCallsNode 工具调用节点
     * @param toolCalls 工具调用列表（用于累积结果）
     */
    private void parseStreamToolCalls(JsonNode toolCallsNode, List<ToolCall> toolCalls) {
        for (JsonNode tcNode : toolCallsNode) {
            int index = tcNode.has("index") ? tcNode.get("index").asInt() : 0;
            
            // 确保列表有足够空间
            while (toolCalls.size() <= index) {
                ToolCall newToolCall = new ToolCall();
                newToolCall.setArguments(new HashMap<>());
                toolCalls.add(newToolCall);
            }
            
            ToolCall toolCall = toolCalls.get(index);
            
            // 确保 arguments 不为 null
            if (toolCall.getArguments() == null) {
                toolCall.setArguments(new HashMap<>());
            }
            
            // 解析 ID
            if (tcNode.has("id")) {
                toolCall.setId(tcNode.get("id").asText());
            }
            
            // 解析 Type
            if (tcNode.has("type")) {
                toolCall.setType(tcNode.get("type").asText());
            }
            
            // 解析 Function（增量拼接）
            if (tcNode.has("function")) {
                JsonNode funcNode = tcNode.get("function");
                
                // 解析函数名称
                if (funcNode.has("name") && !funcNode.get("name").isNull()) {
                    String name = funcNode.get("name").asText();
                    if (name != null && !name.isEmpty()) {
                        toolCall.setName(name);
                    }
                }
                
                // 增量拼接参数字符串
                if (funcNode.has("arguments")) {
                    String argsChunk = funcNode.get("arguments").asText();
                    Map<String, Object> args = toolCall.getArguments();
                    String existing = (String) args.get("_raw_args");
                    args.put("_raw_args", existing == null ? argsChunk : existing + argsChunk);
                }
            }
        }
    }
    
    /**
     * 解析 token 使用统计信息。
     * 
     * @param usageNode usage 节点
     * @return token 使用统计对象
     */
    private LLMResponse.UsageInfo parseUsage(JsonNode usageNode) {
        LLMResponse.UsageInfo usage = new LLMResponse.UsageInfo();
        usage.setPromptTokens(usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0);
        usage.setCompletionTokens(usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0);
        usage.setTotalTokens(usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : 0);
        return usage;
    }
    
    /**
     * 解析工具调用列表。
     * 
     * @param toolCallsNode 工具调用 JSON 节点
     * @return 工具调用列表
     */
    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        for (JsonNode tcNode : toolCallsNode) {
            ToolCall toolCall = new ToolCall();
            toolCall.setId(tcNode.has("id") ? tcNode.get("id").asText() : UUID.randomUUID().toString());
            toolCall.setType(tcNode.has("type") ? tcNode.get("type").asText() : "function");
            
            if (tcNode.has("function")) {
                JsonNode funcNode = tcNode.get("function");
                String name = funcNode.has("name") ? funcNode.get("name").asText() : "";
                String argsStr = funcNode.has("arguments") ? funcNode.get("arguments").asText() : "{}";
                
                toolCall.setName(name);
                toolCall.setArguments(parseToolArguments(argsStr));
            }
            
            toolCalls.add(toolCall);
        }
        
        return toolCalls;
    }
    
    /**
     * 解析工具调用参数。
     * 
     * @param argsStr 参数 JSON 字符串
     * @return 参数映射，解析失败时返回包含原始字符串的映射
     */
    private Map<String, Object> parseToolArguments(String argsStr) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsStr, Map.class);
            return args;
        } catch (Exception e) {
            Map<String, Object> args = new HashMap<>();
            args.put("raw", argsStr);
            return args;
        }
    }
}
