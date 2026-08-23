package io.leavesfly.tinyclaw.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 处理静态文件访问 API（/api/files）。
 * 
 * 用于访问 workspace 下的文件（如上传的图片）。
 * 
 * 请求格式：GET /api/files/{relativePath}
 * 例如：GET /api/files/uploads/1710756000_abc.jpg
 * 
 * 安全限制：
 * - 只允许访问 workspace 目录下的文件
 * - 禁止路径遍历攻击（..）
 */
public class FilesHandler extends BaseHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    
    /** 缓存控制：图片缓存 1 小时 */
    private static final String CACHE_CONTROL_VALUE = "public, max-age=3600";

    /** 文件服务大小上限（20MB），超过则拒绝整块读入内存 */
    private static final long MAX_SERVE_FILE_BYTES = 20L * 1024 * 1024;

    /**
     * 构造 FilesHandler，注入全局配置与安全中间件。
     */
    public FilesHandler(Config config, SecurityMiddleware security) {
        super(config, security);
    }

    /**
     * 重写鉴权：不走标准 preCheck，因为 {@code <img src>} 等浏览器直接请求
     * 无法携带 Authorization header，此接口额外支持 {@code ?token=<sessionToken>} 查询参数。
     *
     * <p>同时有意不叠加限流：一个页面常一次性拉十几张图，按请求数限流会直接把图卡死。
     * 认证未启用时维持原有行为（仅路径防护）。</p>
     */
    @Override
    protected boolean authorize(HttpExchange exchange) throws IOException {
        if (security.handleCorsPreFlight(exchange)) {
            return false;
        }
        if (config.getGateway().isAuthEnabled() && !isAuthenticated(exchange)) {
            WebUtils.sendJson(exchange, 401, WebUtils.errorJson("Authentication required"),
                    corsOrigin());
            return false;
        }
        return true;
    }

    /**
     * 只处理 GET：从 workspace 下读取并返回文件。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!WebUtils.HTTP_METHOD_GET.equals(method)) {
            return false;
        }
        handleGetFile(exchange);
        return true;
    }

    /**
     * 处理获取文件请求。
     * 
     * 从路径 /api/files/{relativePath} 中提取相对路径，
     * 在 workspace 目录下查找并返回文件内容。
     */
    private void handleGetFile(HttpExchange exchange) throws IOException {
        String corsOrigin = config.getGateway().getCorsOrigin();
        String path = exchange.getRequestURI().getPath();
        
        // 提取相对路径：/api/files/uploads/xxx.jpg -> uploads/xxx.jpg
        String relativePath = path.substring(WebUtils.API_FILES.length());
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        
        // URL 解码
        relativePath = URLDecoder.decode(relativePath, StandardCharsets.UTF_8);
        
        // 安全检查：禁止路径遍历
        if (relativePath.contains("..") || relativePath.startsWith("/")) {
            logger.warn("Path traversal attempt", Map.of("path", relativePath));
            WebUtils.sendJson(exchange, 403, WebUtils.errorJson("Access denied"), corsOrigin);
            return;
        }
        
        // 构建完整文件路径（使用 getWorkspacePath 展开 ~ 为用户主目录）
        String workspace = config.getWorkspacePath();
        Path filePath = Paths.get(workspace, relativePath).normalize();
        
        logger.debug("文件访问请求", Map.of(
                "relative_path", relativePath,
                "workspace", workspace,
                "full_path", filePath.toString()));
        
        // 再次验证路径在 workspace 内
        Path workspacePath = Paths.get(workspace).normalize();
        if (!filePath.startsWith(workspacePath)) {
            logger.warn("Path escape attempt", Map.of("path", filePath.toString()));
            WebUtils.sendJson(exchange, 403, WebUtils.errorJson("Access denied"), corsOrigin);
            return;
        }
        
        // 检查文件是否存在
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            logger.warn("文件不存在", Map.of(
                    "relative_path", relativePath,
                    "full_path", filePath.toString()));
            WebUtils.sendNotFound(exchange, corsOrigin);
            return;
        }
        
        // 读取并返回文件
        sendFile(exchange, filePath, corsOrigin);
    }

    /**
     * 发送文件内容到客户端。
     */
    private void sendFile(HttpExchange exchange, Path filePath, String corsOrigin) throws IOException {
        // 大文件拒绝整块读入内存，防止堆耗尽
        long fileSize = Files.size(filePath);
        if (fileSize > MAX_SERVE_FILE_BYTES) {
            WebUtils.sendJson(exchange, 413, WebUtils.errorJson("File too large"), corsOrigin);
            return;
        }
        byte[] fileBytes = Files.readAllBytes(filePath);
        String contentType = getContentType(filePath.toString());
        
        exchange.getResponseHeaders().set(WebUtils.HEADER_CONTENT_TYPE, contentType);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CORS, corsOrigin);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CACHE_CONTROL, CACHE_CONTROL_VALUE);
        // 禁止 MIME 嗅探；SVG 可内嵌脚本，额外用 CSP 禁用脚本执行，防存储型 XSS
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (contentType.contains("svg")) {
            exchange.getResponseHeaders().set("Content-Security-Policy", "script-src 'none'");
        }
        exchange.sendResponseHeaders(200, fileBytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(fileBytes);
        }
        
        logger.debug("File served", Map.of("path", filePath.toString(), "size", fileBytes.length));
    }

    /**
     * 检查请求是否已认证：支持 Authorization 头（Bearer/Basic）或 ?token= 查询参数。
     */
    private boolean isAuthenticated(HttpExchange exchange) {
        // 方式 1：Authorization 头（Bearer 或 Basic）
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length());
            if (security.isValidSessionToken(token)) {
                return true;
            }
        }

        // 方式 2：?token= 查询参数（兼容 <img src> 等无法设置 header 的场景）
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String token = param.substring("token=".length());
                    if (security.isValidSessionToken(token)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 根据文件路径获取 Content-Type。
     */
    private String getContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
