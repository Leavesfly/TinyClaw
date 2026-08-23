package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 处理文件上传 API（/api/upload）。
 * 
 * 支持上传图片文件，存储到 workspace/uploads 目录下。
 * 请求格式（JSON）：
 * {
 *   "images": [
 *     {"data": "base64编码的图片数据", "name": "可选的文件名"}
 *   ]
 * }
 * 
 * 响应格式：
 * {
 *   "files": ["uploads/xxx.jpg", "uploads/yyy.png"]
 * }
 */
public class UploadHandler extends BaseHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    
    /** 上传文件存储子目录名 */
    private static final String UPLOADS_DIR = "uploads";
    
    /** 允许的图片 MIME 类型前缀 */
    private static final String IMAGE_MIME_PREFIX = "image/";
    
    /** 单个文件最大大小（10MB） */
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 构造 UploadHandler，注入全局配置与安全中间件。
     */
    public UploadHandler(Config config, SecurityMiddleware security) {
        super(config, security);
    }

    /**
     * 入口路由：预检通过后，处理文件上传请求。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!WebUtils.HTTP_METHOD_POST.equals(method)) {
            return false;
        }
        handleUpload(exchange);
        return true;
    }

    /**
     * 处理图片上传请求。
     * 
     * 请求体格式：{"images": [{"data": "data:image/jpeg;base64,...", "name": "photo.jpg"}]}
     * 响应格式：{"files": ["uploads/xxx.jpg"]}
     */
    private void handleUpload(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        String body = WebUtils.readRequestBody(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        
        JsonNode imagesNode = json.path("images");
        if (!imagesNode.isArray() || imagesNode.isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("No images provided"), corsOrigin);
            return;
        }

        // 确保上传目录存在（使用 getWorkspacePath 展开 ~ 为用户主目录）
        String workspace = config.getWorkspacePath();
        Path uploadsPath = Paths.get(workspace, UPLOADS_DIR);
        Files.createDirectories(uploadsPath);

        ArrayNode filesArray = WebUtils.MAPPER.createArrayNode();
        
        for (JsonNode imageNode : imagesNode) {
            String data = imageNode.path("data").asText();
            String name = imageNode.path("name").asText("");
            
            if (data.isEmpty()) {
                continue;
            }
            
            try {
                String savedPath = saveImage(uploadsPath, data, name);
                filesArray.add(savedPath);
            } catch (Exception e) {
                logger.warn("Failed to save image", Map.of("error", e.getMessage()));
            }
        }

        ObjectNode response = WebUtils.MAPPER.createObjectNode();
        response.set("files", filesArray);
        WebUtils.sendJson(exchange, 200, response, corsOrigin);
    }

    /**
     * 保存 Base64 编码的图片到文件系统。
     * 
     * @param uploadsPath 上传目录路径
     * @param dataUri Base64 数据 URI（如 "data:image/jpeg;base64,..."）
     * @param originalName 原始文件名（可选）
     * @return 保存后的相对路径（如 "uploads/xxx.jpg"）
     */
    private String saveImage(Path uploadsPath, String dataUri, String originalName) throws IOException {
        // 解析 data URI
        if (!dataUri.startsWith("data:")) {
            throw new IllegalArgumentException("Invalid data URI format");
        }

        int commaIndex = dataUri.indexOf(',');
        if (commaIndex == -1) {
            throw new IllegalArgumentException("Invalid data URI format");
        }

        String header = dataUri.substring(5, commaIndex);  // "image/jpeg;base64"
        String base64Data = dataUri.substring(commaIndex + 1);

        // 解析 MIME 类型
        String mimeType = header.split(";")[0];
        if (!mimeType.startsWith(IMAGE_MIME_PREFIX)) {
            throw new IllegalArgumentException("Only image files are allowed");
        }

        // 解码 Base64
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        
        // 检查文件大小
        if (imageBytes.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File too large (max 10MB)");
        }

        // Magic number 校验：验证文件内容确实是图片（防止 MIME 伪造）
        if (!isValidImageMagic(imageBytes)) {
            throw new IllegalArgumentException("File content does not match a valid image format");
        }

        // 生成文件名
        String extension = getExtensionFromMime(mimeType);
        String fileName = generateFileName(originalName, extension);
        
        // 保存文件
        Path filePath = uploadsPath.resolve(fileName);
        Files.write(filePath, imageBytes);
        
        // 验证文件是否实际存在
        if (Files.exists(filePath)) {
            logger.info("图片保存成功", Map.of(
                    "file_path", filePath.toString(),
                    "relative_path", UPLOADS_DIR + "/" + fileName,
                    "size", imageBytes.length));
        } else {
            logger.error("图片保存失败，文件不存在", Map.of("file_path", filePath.toString()));
        }
        
        // 返回相对路径（用于前端显示和存储）
        return UPLOADS_DIR + "/" + fileName;
    }

    /**
     * 根据 MIME 类型获取文件扩展名。
     */
    private String getExtensionFromMime(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> ".jpg";
        };
    }

    /**
     * 生成唯一文件名。
     * 
     * @param originalName 原始文件名（可选）
     * @param extension 文件扩展名
     * @return 唯一文件名
     */
    /**
     * 校验文件头 magic number 是否为合法图片格式。
     * 支持 JPEG、PNG、GIF、WebP、SVG。
     */
    private boolean isValidImageMagic(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) return true;
        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return true;
        // GIF: 47 49 46 38
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x38) return true;
        // WebP: 52 49 46 46 ... 57 45 42 50
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46
                && data.length > 11 && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) return true;
        // SVG: 以 "<?xml" 或 "<svg" 开头（文本格式）
        String prefix = new String(data, 0, Math.min(data.length, 64), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (prefix.startsWith("<?xml") || prefix.startsWith("<svg")) return true;
        return false;
    }

    private String generateFileName(String originalName, String extension) {
        long timestamp = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        
        if (originalName != null && !originalName.isEmpty()) {
            // 提取原始文件名（不含扩展名）的前 20 个字符
            String baseName = originalName.replaceAll("\\.[^.]+$", "");
            baseName = baseName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (baseName.length() > 20) {
                baseName = baseName.substring(0, 20);
            }
            return timestamp + "_" + baseName + "_" + uuid + extension;
        }
        
        return timestamp + "_" + uuid + extension;
    }
}
