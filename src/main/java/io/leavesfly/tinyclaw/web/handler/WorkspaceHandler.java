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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 处理工作空间文件 API（/api/workspace）。
 */
public class WorkspaceHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SecurityMiddleware security;

    public WorkspaceHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String workspace = config.getWorkspacePath();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_WORKSPACE_FILES.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleListWorkspaceFiles(exchange, workspace, corsOrigin);
            } else if (path.startsWith(WebUtils.API_WORKSPACE_FILES + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleGetWorkspaceFile(exchange, path, workspace, corsOrigin);
            } else if (path.startsWith(WebUtils.API_WORKSPACE_FILES + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_PUT.equals(method)) {
                handleSaveWorkspaceFile(exchange, path, workspace, corsOrigin);
            } else {
                WebUtils.sendNotFound(exchange, corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Workspace API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void handleListWorkspaceFiles(HttpExchange exchange, String workspace,
                                          String corsOrigin) throws IOException {
        ArrayNode files = WebUtils.MAPPER.createArrayNode();
        for (String fileName : WebUtils.WORKSPACE_FILES) {
            Path filePath = Paths.get(workspace, fileName);
            if (Files.exists(filePath)) {
                files.add(createFileInfo(fileName, filePath));
            }
        }
        addMemoryFile(files, workspace);
        WebUtils.sendJson(exchange, 200, files, corsOrigin);
    }

    private ObjectNode createFileInfo(String fileName, Path filePath) {
        ObjectNode file = WebUtils.MAPPER.createObjectNode();
        file.put("name", fileName);
        file.put("exists", true);
        try {
            file.put("size", Files.size(filePath));
            file.put("lastModified", Files.getLastModifiedTime(filePath).toMillis());
        } catch (IOException e) {
            file.put("size", 0);
            file.put("lastModified", 0);
        }
        return file;
    }

    private void addMemoryFile(ArrayNode files, String workspace) {
        Path memoryFile = Paths.get(workspace, WebUtils.MEMORY_SUBDIR, WebUtils.MEMORY_FILE);
        if (Files.exists(memoryFile)) {
            String memoryFileName = WebUtils.MEMORY_SUBDIR + WebUtils.PATH_SEPARATOR + WebUtils.MEMORY_FILE;
            files.add(createFileInfo(memoryFileName, memoryFile));
        }
    }

    private void handleGetWorkspaceFile(HttpExchange exchange, String path, String workspace,
                                        String corsOrigin) throws IOException {
        String fileName = URLDecoder.decode(
                path.substring(WebUtils.API_WORKSPACE_FILES.length() + 1), StandardCharsets.UTF_8);
        Path filePath = Paths.get(workspace, fileName);
        if (Files.exists(filePath)) {
            String content = Files.readString(filePath);
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("name", fileName);
            result.put("content", content);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } else {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("File not found"), corsOrigin);
        }
    }

    private void handleSaveWorkspaceFile(HttpExchange exchange, String path, String workspace,
                                         String corsOrigin) throws IOException {
        String fileName = URLDecoder.decode(
                path.substring(WebUtils.API_WORKSPACE_FILES.length() + 1), StandardCharsets.UTF_8);
        String body = WebUtils.readRequestBodyLimited(exchange);
        JsonNode json = WebUtils.MAPPER.readTree(body);
        String content = json.path("content").asText();
        Path filePath = Paths.get(workspace, fileName);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("File saved"), corsOrigin);
    }
}
