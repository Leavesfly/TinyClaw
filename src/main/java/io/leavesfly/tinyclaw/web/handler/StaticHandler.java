package io.leavesfly.tinyclaw.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 处理前端静态资源（从 classpath web/ 目录加载）。
 * 包含路径遍历攻击防护。
 */
public class StaticHandler {

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        path = normalizeStaticPath(path);

        if (isPathTraversalAttempt(path)) {
            WebUtils.sendError(exchange, 403, "Forbidden");
            return;
        }

        serveStaticResource(exchange, path);
    }

    private String normalizeStaticPath(String path) {
        if (WebUtils.PATH_ROOT.equals(path) || path.isEmpty()) {
            return WebUtils.PATH_INDEX;
        }
        return path;
    }

    private boolean isPathTraversalAttempt(String path) {
        return path.contains(WebUtils.PATH_PARENT);
    }

    private void serveStaticResource(HttpExchange exchange, String path) throws IOException {
        String resourcePath = WebUtils.RESOURCE_PREFIX + path;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                WebUtils.sendError(exchange, 404, "Not Found");
                return;
            }
            sendStaticFile(exchange, is, path);
        }
    }

    private void sendStaticFile(HttpExchange exchange, InputStream is, String path) throws IOException {
        byte[] content = is.readAllBytes();
        String contentType = WebUtils.getContentType(path);
        exchange.getResponseHeaders().set(WebUtils.HEADER_CONTENT_TYPE, contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }
}
