package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;
import io.leavesfly.tinyclaw.web.attachment.Attachment;
import io.leavesfly.tinyclaw.web.attachment.AttachmentStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 处理通用附件 API（P2：/api/attachments）。
 *
 * <p>端点（原始二进制逐文件上传，避免 JSON Base64 放大）：</p>
 * <ul>
 *   <li>{@code POST /api/attachments?sessionId=&name=}：上传原始字节，返回元信息（解析异步）；</li>
 *   <li>{@code GET /api/attachments/{id}}：元信息（前端轮询解析状态）；</li>
 *   <li>{@code GET /api/attachments/{id}/content}：解析文本（JSON，带截断标记）；</li>
 *   <li>{@code GET /api/attachments/{id}/download}：原始字节（按 MIME 下发）。</li>
 * </ul>
 *
 * <p>安全：鉴权由 BaseHandler 外壳执行；请求体读取时即限制大小（10 MiB）；
 * 文件名与 ID 均不参与磁盘寻址（AttachmentStore 内部校验 UUID 短串格式）。</p>
 */
public class AttachmentsHandler extends BaseHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private static final String API_ATTACHMENTS = "/api/attachments";

    private final AttachmentStore store;

    public AttachmentsHandler(Config config, SecurityMiddleware security, AttachmentStore store) {
        super(config, security);
        this.store = store;
    }

    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (!path.startsWith(API_ATTACHMENTS)) {
            return false;
        }
        String sub = path.substring(API_ATTACHMENTS.length());
        if ((sub.isEmpty() || sub.equals("/")) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleUpload(exchange, corsOrigin);
            return true;
        }
        if (sub.startsWith("/") && WebUtils.HTTP_METHOD_GET.equals(method)) {
            String rest = sub.substring(1);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                handleMeta(exchange, corsOrigin, rest);
            } else if (rest.substring(slash).equals("/content")) {
                handleContent(exchange, corsOrigin, rest.substring(0, slash));
            } else if (rest.substring(slash).equals("/download")) {
                handleDownload(exchange, corsOrigin, rest.substring(0, slash));
            } else {
                return false;
            }
            return true;
        }
        return false;
    }

    /** POST /api/attachments：原始二进制上传。 */
    private void handleUpload(HttpExchange exchange, String corsOrigin) throws IOException {
        String sessionId = queryParam(exchange, "sessionId");
        String name = queryParam(exchange, "name");
        String mediaType = exchange.getRequestHeaders().getFirst("Content-Type");

        if (sessionId == null || sessionId.isBlank()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("sessionId is required"), corsOrigin);
            return;
        }
        if (name == null || name.isBlank()) {
            name = "attachment";
        }

        // 读取请求体时即限制大小：超限直接拒绝，不落盘
        byte[] raw;
        try {
            raw = exchange.getRequestBody().readNBytes(AttachmentStore.MAX_FILE_SIZE + 1);
        } catch (IOException e) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("读取请求体失败"), corsOrigin);
            return;
        }
        if (raw.length > AttachmentStore.MAX_FILE_SIZE) {
            WebUtils.sendJson(exchange, 413,
                    WebUtils.errorJson("文件超过 " + (AttachmentStore.MAX_FILE_SIZE / 1024 / 1024) + " MiB 上限"),
                    corsOrigin);
            return;
        }

        try {
            Attachment meta = store.save(raw, name, mediaType, sessionId);
            logger.info("Attachment uploaded", Map.of(
                    "id", meta.id, "name", meta.name, "size", meta.size, "session", sessionId));
            WebUtils.sendJson(exchange, 200, toJson(meta), corsOrigin);
        } catch (IOException e) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    /** GET /api/attachments/{id}：元信息（含解析状态轮询）。 */
    private void handleMeta(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        Attachment meta = store.get(id);
        if (meta == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Attachment not found"), corsOrigin);
            return;
        }
        WebUtils.sendJson(exchange, 200, toJson(meta), corsOrigin);
    }

    /** GET /api/attachments/{id}/content：解析文本。 */
    private void handleContent(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        Attachment meta = store.get(id);
        if (meta == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Attachment not found"), corsOrigin);
            return;
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        if (meta.statusEnum() != Attachment.Status.READY) {
            result.put("status", meta.status);
            result.put("error", meta.parseError != null ? meta.parseError : "");
            WebUtils.sendJson(exchange, 409, result, corsOrigin);
            return;
        }
        try {
            result.put("status", meta.status);
            result.put("id", meta.id);
            result.put("name", meta.name);
            result.put("truncated", meta.truncated);
            result.put("chars", meta.parsedChars);
            result.put("content", store.readParsedText(id));
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (IOException e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            WebUtils.sendJson(exchange, 500, result, corsOrigin);
        }
    }

    /** GET /api/attachments/{id}/download：原始字节按 MIME 下发。 */
    private void handleDownload(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        Attachment meta = store.get(id);
        if (meta == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Attachment not found"), corsOrigin);
            return;
        }
        try {
            byte[] raw = store.readRaw(id);
            String mime = meta.mediaType != null && !meta.mediaType.isBlank()
                    && !"application/octet-stream".equals(meta.mediaType)
                    ? meta.mediaType : guessMime(meta.name);
            exchange.getResponseHeaders().set(WebUtils.HEADER_CONTENT_TYPE, mime);
            // 文件名仅用于展示，转义后放入 Content-Disposition（不参与寻址）
            String safeName = meta.name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
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

    /** 元信息 → JSON 视图。 */
    private ObjectNode toJson(Attachment meta) {
        ObjectNode node = WebUtils.MAPPER.createObjectNode();
        node.put("id", meta.id);
        node.put("name", meta.name);
        node.put("mediaType", meta.mediaType != null ? meta.mediaType : guessMime(meta.name));
        node.put("size", meta.size);
        node.put("status", meta.status);
        node.put("sessionKey", meta.sessionKey);
        node.put("parsedChars", meta.parsedChars);
        node.put("truncated", meta.truncated);
        if (meta.parseError != null) {
            node.put("parseError", meta.parseError);
        }
        node.put("createdAt", meta.createdAt);
        node.put("updatedAt", meta.updatedAt);
        return node;
    }

    /** 扩展名 → 展示 MIME。 */
    private static String guessMime(String name) {
        String lower = name != null ? name.toLowerCase() : "";
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) {
            return "text/plain";
        }
        return "application/octet-stream";
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
