package io.leavesfly.tinyclaw.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LLM 请求体构建器。
 * 
 * 负责将消息、工具定义和选项转换为 OpenAI 兼容的 JSON 格式，
 * 支持多模态内容（文本+图片）和不同 LLM 提供商的差异处理。
 */
public class LLMRequestBuilder {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("provider");
    private final ObjectMapper objectMapper;
    
    public LLMRequestBuilder() {
        this.objectMapper = new ObjectMapper();
    }
    
    public LLMRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * 构建 HTTP 请求体。
     * 
     * 将消息、工具定义和选项转换为 OpenAI 兼容的 JSON 格式。
     * 
     * @param messages 对话消息列表
     * @param tools 工具定义列表
     * @param model 模型名称
     * @param options 额外选项（如 max_tokens、temperature）
     * @return JSON 请求体对象
     * @throws Exception 构建失败时抛出异常
     */
    public ObjectNode buildRequestBody(List<Message> messages, List<ToolDefinition> tools, 
                                       String model, Map<String, Object> options) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        
        // 添加消息
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            messagesArray.add(buildMessageNode(msg));
        }
        
        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDefinition tool : tools) {
                toolsArray.add(buildToolNode(tool));
            }
            requestBody.put("tool_choice", "auto");
        }
        
        // 添加选项参数
        addOptions(requestBody, model, options);
        
        return requestBody;
    }
    
    /**
     * 将请求体对象序列化为 JSON 字符串。
     * 
     * @param requestBody 请求体对象
     * @return JSON 字符串
     * @throws JsonProcessingException 序列化失败时抛出异常
     */
    public String toJson(ObjectNode requestBody) throws JsonProcessingException {
        return objectMapper.writeValueAsString(requestBody);
    }
    
    /**
     * 构建单个消息节点，支持多模态内容（文本+图片）。
     * 
     * 当消息包含图片时，使用 OpenAI Vision API 格式：
     * content 字段为数组，包含 text 和 image_url 类型的元素。
     * 
     * @param msg 消息对象
     * @return JSON 消息节点
     * @throws Exception 构建失败时抛出异常
     */
    private ObjectNode buildMessageNode(Message msg) throws Exception {
        ObjectNode msgNode = objectMapper.createObjectNode();
        msgNode.put("role", msg.getRole());
        
        // 检查是否包含图片（多模态消息）
        if (msg.hasImages() && "user".equals(msg.getRole())) {
            // 多模态格式：content 为数组
            ArrayNode contentArray = msgNode.putArray("content");
            
            // 添加文本内容
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                ObjectNode textNode = contentArray.addObject();
                textNode.put("type", "text");
                textNode.put("text", msg.getContent());
            }
            
            // 添加图片内容
            for (String imagePath : msg.getImages()) {
                // 判断是 Base64 数据还是文件路径
                String imageUrl;
                if (imagePath.startsWith("data:")) {
                    // 已经是 Base64 格式
                    imageUrl = imagePath;
                } else {
                    // 文件路径，读取并转换为 Base64
                    imageUrl = readImageAsBase64(imagePath);
                }
                
                // 只有成功读取的图片才添加到请求中
                if (imageUrl != null && !imageUrl.isEmpty()) {
                    ObjectNode imageNode = contentArray.addObject();
                    imageNode.put("type", "image_url");
                    ObjectNode imageUrlNode = imageNode.putObject("image_url");
                    imageUrlNode.put("url", imageUrl);
                } else {
                    logger.warn("Skipping image due to read failure", Map.of("path", imagePath));
                }
            }
        } else {
            // 纯文本格式
            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }
        }
        
        if (msg.getToolCallId() != null) {
            msgNode.put("tool_call_id", msg.getToolCallId());
        }
        
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
            for (ToolCall tc : msg.getToolCalls()) {
                ObjectNode tcNode = toolCallsArray.addObject();
                tcNode.put("id", tc.getId());
                tcNode.put("type", tc.getType() != null ? tc.getType() : "function");
                ObjectNode funcNode = tcNode.putObject("function");
                funcNode.put("name", tc.getName());
                if (tc.getArguments() != null) {
                    funcNode.put("arguments", objectMapper.writeValueAsString(tc.getArguments()));
                }
            }
        }
        
        return msgNode;
    }
    
    /**
     * 读取图片文件并转换为 Base64 格式。
     * 
     * @param imagePath 图片文件路径
     * @return Base64 编码的图片数据（包含 data URI 前缀），读取失败返回 null
     */
    private String readImageAsBase64(String imagePath) {
        try {
            Path path = Paths.get(imagePath);
            
            // 检查文件是否存在
            if (!Files.exists(path)) {
                logger.error("Image file not found", Map.of(
                        "path", imagePath,
                        "absolute_path", path.toAbsolutePath().toString()));
                return null;
            }
            
            byte[] imageBytes = Files.readAllBytes(path);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            
            // 根据文件扩展名确定 MIME 类型
            String mimeType = getMimeType(imagePath);
            String result = "data:" + mimeType + ";base64," + base64;
            
            logger.info("图片读取成功", Map.of(
                    "path", imagePath,
                    "size_bytes", imageBytes.length,
                    "base64_length", result.length()));
            
            return result;
        } catch (Exception e) {
            logger.error("Failed to read image", Map.of(
                    "path", imagePath,
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return null;
        }
    }
    
    /**
     * 根据文件路径获取 MIME 类型。
     */
    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";  // 默认 JPEG
    }
    
    /**
     * 构建工具定义节点。
     * 
     * @param tool 工具定义对象
     * @return JSON 工具节点
     */
    private ObjectNode buildToolNode(ToolDefinition tool) {
        ObjectNode toolNode = objectMapper.createObjectNode();
        toolNode.put("type", tool.getType());
        ObjectNode funcNode = toolNode.putObject("function");
        funcNode.put("name", tool.getFunction().getName());
        funcNode.put("description", tool.getFunction().getDescription());
        funcNode.set("parameters", objectMapper.valueToTree(tool.getFunction().getParameters()));
        return toolNode;
    }
    
    /**
     * 添加请求选项参数。
     * 
     * 根据不同模型自动适配参数名称（如 max_tokens vs max_completion_tokens）。
     * 
     * @param requestBody 请求体对象
     * @param model 模型名称
     * @param options 选项映射
     */
    private void addOptions(ObjectNode requestBody, String model, Map<String, Object> options) {
        if (options == null) {
            return;
        }
        
        if (options.containsKey("max_tokens")) {
            // 处理不同模型的 max_tokens 参数名称
            String lowerModel = model.toLowerCase();
            String paramName = (lowerModel.contains("glm") || lowerModel.contains("o1")) 
                    ? "max_completion_tokens" 
                    : "max_tokens";
            requestBody.put(paramName, ((Number) options.get("max_tokens")).intValue());
        }
        
        if (options.containsKey("temperature")) {
            requestBody.put("temperature", ((Number) options.get("temperature")).doubleValue());
        }
    }
}
