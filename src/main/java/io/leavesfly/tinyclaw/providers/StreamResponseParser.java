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
                    
                    // 处理流式内容
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String content = delta.get("content").asText();
                        if (content != null && !content.isEmpty()) {
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
            logger.error("Stream read error", Map.of("error", e.getMessage()));
            throw e;
        }
        
        // 构建完整响应
        return buildStreamResponse(fullContent.toString(), toolCalls, finishReason, usage);
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
