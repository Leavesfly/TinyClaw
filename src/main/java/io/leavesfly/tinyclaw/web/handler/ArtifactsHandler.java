package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;
import io.leavesfly.tinyclaw.web.artifact.ArtifactRecord;
import io.leavesfly.tinyclaw.web.artifact.ArtifactStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 处理成果 API（P3：/api/artifacts）。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /api/artifacts?sessionId=}：会话成果列表（切会话/刷新重新加载）；</li>
 *   <li>{@code GET /api/artifacts/{id}}：成果详情（含文件存在性）；</li>
 *   <li>{@code GET /api/artifacts/{id}/versions}：版本列表（hash 与外部改动检测）；</li>
 *   <li>{@code GET /api/artifacts/{id}/content?revision=}：文本内容（Markdown/代码/CSV 预览）；</li>
 *   <li>{@code GET /api/artifacts/{id}/download?revision=}：原始字节（按 MIME 下发）。</li>
 * </ul>
 *
 * <p>安全：鉴权由 BaseHandler 外壳执行；ID 为 UUID 短串（ArtifactStore 内部校验）；
 * 读取范围限于登记过的路径，不提供任意路径读取。</p>
 */
public class ArtifactsHandler extends BaseHandler {

    private static final String API_ARTIFACTS = "/api/artifacts";

    private final ArtifactStore store;

    public ArtifactsHandler(Config config, SecurityMiddleware security, ArtifactStore store) {
        super(config, security);
        this.store = store;
    }

    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!path.startsWith(API_ARTIFACTS) || !WebUtils.HTTP_METHOD_GET.equals(method)) {
            return false;
        }
        String sub = path.substring(API_ARTIFACTS.length());
        if (sub.isEmpty() || sub.equals("/")) {
            handleList(exchange, corsOrigin);
        } else if (sub.startsWith("/")) {
            String rest = sub.substring(1);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                handleDetail(exchange, corsOrigin, rest);
            } else {
                String id = rest.substring(0, slash);
                String action = rest.substring(slash);
                if (action.equals("/versions")) {
                    handleVersions(exchange, corsOrigin, id);
                } else if (action.equals("/content")) {
                    handleContent(exchange, corsOrigin, id);
                } else if (action.equals("/download")) {
                    handleDownload(exchange, corsOrigin, id);
                } else {
                    return false;
                }
            }
        } else {
            return false;
        }
        return true;
    }

    /** GET /api/artifacts?sessionId= */
    private void handleList(HttpExchange exchange, String corsOrigin) throws IOException {
        String sessionId = queryParam(exchange, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("sessionId is required"), corsOrigin);
            return;
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        ArrayNode arr = WebUtils.MAPPER.createArrayNode();
        boolean currentExists;
        for (ArtifactRecord r : store.listBySession(sessionId)) {
            ObjectNode node = toJson(r);
            currentExists = java.nio.file.Files.exists(java.nio.file.Paths.get(r.path));
            node.put("exists", currentExists);
            arr.add(node);
        }
        result.set("artifacts", arr);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /** GET /api/artifacts/{id} */
    private void handleDetail(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        ArtifactRecord record = store.get(id);
        if (record == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Artifact not found"), corsOrigin);
            return;
        }
        ObjectNode node = toJson(record);
        node.put("exists", java.nio.file.Files.exists(java.nio.file.Paths.get(record.path)));
        WebUtils.sendJson(exchange, 200, node, corsOrigin);
    }

    /** GET /api/artifacts/{id}/versions */
    private void handleVersions(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        ArtifactRecord record = store.get(id);
        if (record == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Artifact not found"), corsOrigin);
            return;
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.set("versions", WebUtils.MAPPER.valueToTree(store.versions(id)));
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /** GET /api/artifacts/{id}/content?revision= */
    private void handleContent(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        ArtifactRecord record = store.get(id);
        if (record == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Artifact not found"), corsOrigin);
            return;
        }
        int revision = queryInt(exchange, "revision", 0);
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        try {
            String text = store.readTextContent(id, revision);
            result.put("id", id);
            result.put("revision", revision);
            result.put("mediaType", record.mediaType);
            result.put("content", text);
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (IOException e) {
            result.put("error", e.getMessage());
            WebUtils.sendJson(exchange, 409, result, corsOrigin);
        }
    }

    /** GET /api/artifacts/{id}/download?revision= */
    private void handleDownload(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        ArtifactRecord record = store.get(id);
        if (record == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Artifact not found"), corsOrigin);
            return;
        }
        int revision = queryInt(exchange, "revision", 0);
        try {
            byte[] raw = store.readContent(id, revision);
            String mime = record.mediaType != null ? record.mediaType : "application/octet-stream";
            exchange.getResponseHeaders().set(WebUtils.HEADER_CONTENT_TYPE, mime);
            String safeName = record.name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(safeName, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, raw.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(raw);
            }
        } catch (IOException e) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private ObjectNode toJson(ArtifactRecord r) {
        ObjectNode node = WebUtils.MAPPER.createObjectNode();
        node.put("id", r.id);
        node.put("sessionKey", r.sessionKey);
        node.put("path", r.path);
        node.put("name", r.name);
        node.put("mediaType", r.mediaType);
        node.put("revision", r.revision);
        node.put("hash", r.hash);
        node.put("createdAt", r.createdAt);
        node.put("updatedAt", r.updatedAt);
        return node;
    }

    /** 查询串 int 参数（缺失/非法用默认值）。 */
    private int queryInt(HttpExchange exchange, String name, int fallback) {
        String raw = queryParam(exchange, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 查询串解析（URL 解码），缺失返回 null。 */
    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith(name + "=")) {
                String raw = param.substring(name.length() + 1);
                if (raw.isEmpty()) {
                    return null;
                }
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
