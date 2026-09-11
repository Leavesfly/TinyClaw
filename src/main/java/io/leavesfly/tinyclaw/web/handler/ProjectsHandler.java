package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;
import io.leavesfly.tinyclaw.web.attachment.AttachmentStore;
import io.leavesfly.tinyclaw.web.project.ProjectStore;

import java.io.IOException;
import java.util.List;

/**
 * 项目空间 API（/api/projects，P4）。
 *
 * <p>项目 = 名称 + 指令（独立 ContextSection 注入归属会话）+ 显式共享的资料引用。
 * REST 面：</p>
 * <ul>
 *   <li><b>GET    /api/projects</b> — 项目列表（含归档）；</li>
 *   <li><b>POST   /api/projects</b> — 新建（请求体 {name, instructions?}）；</li>
 *   <li><b>GET    /api/projects/{id}</b> — 项目详情（资料引用合并附件元信息）；</li>
 *   <li><b>PUT    /api/projects/{id}</b> — 修改名称/指令（仅更新出现字段）；</li>
 *   <li><b>PATCH  /api/projects/{id}</b> — 归档开关（请求体 {archived}）；</li>
 *   <li><b>POST   /api/projects/{id}/resources</b> — 添加资料引用（{attachmentId}）；</li>
 *   <li><b>DELETE /api/projects/{id}/resources/{attachmentId}</b> — 解除资料引用；</li>
 *   <li><b>DELETE /api/projects/{id}?confirm=true</b> — 删除项目（必须显式确认）。
 *       删除只移除项目本体与引用：附件物理文件保留（其他项目/会话可能仍引用），
 *       会话转录不动；响应报告解除的引用供前端提示影响。</li>
 * </ul>
 *
 * <p>项目页不替代全局核心文件编辑器（workspace 页职责不变）。</p>
 */
public class ProjectsHandler extends BaseHandler {

    private final ProjectStore projectStore;
    private final AttachmentStore attachmentStore;

    public ProjectsHandler(Config config, SecurityMiddleware security,
                           ProjectStore projectStore, AttachmentStore attachmentStore) {
        super(config, security);
        this.projectStore = projectStore;
        this.attachmentStore = attachmentStore;
    }

    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        String prefix = WebUtils.API_PROJECTS + WebUtils.PATH_SEPARATOR;
        if (WebUtils.API_PROJECTS.equals(path)) {
            if (WebUtils.HTTP_METHOD_GET.equals(method)) {
                handleList(exchange, corsOrigin);
            } else if (WebUtils.HTTP_METHOD_POST.equals(method)) {
                handleCreate(exchange, corsOrigin);
            } else {
                return false;
            }
        } else if (path.startsWith(prefix)) {
            String rest = path.substring(prefix.length());
            if (rest.contains("/")) {
                String[] parts = rest.split("/");
                if (parts.length == 2 && "resources".equals(parts[0])
                        && WebUtils.HTTP_METHOD_POST.equals(method)) {
                    handleAddResource(exchange, corsOrigin, parts[1]);
                } else if (parts.length == 3 && "resources".equals(parts[0])
                        && WebUtils.HTTP_METHOD_DELETE.equals(method)) {
                    handleRemoveResource(exchange, corsOrigin, parts[1], parts[2]);
                } else {
                    return false;
                }
            } else {
                String id = rest;
                if (WebUtils.HTTP_METHOD_GET.equals(method)) {
                    handleGet(exchange, corsOrigin, id);
                } else if (WebUtils.HTTP_METHOD_PUT.equals(method)) {
                    handleUpdate(exchange, corsOrigin, id);
                } else if (WebUtils.HTTP_METHOD_PATCH.equals(method)) {
                    handlePatch(exchange, corsOrigin, id);
                } else if (WebUtils.HTTP_METHOD_DELETE.equals(method)) {
                    handleDelete(exchange, corsOrigin, id);
                } else {
                    return false;
                }
            }
        } else {
            return false;
        }
        return true;
    }

    // ==================== 列表 / 新建 ====================

    private void handleList(HttpExchange exchange, String corsOrigin) throws IOException {
        ArrayNode array = WebUtils.MAPPER.createArrayNode();
        for (ProjectStore.Project p : projectStore.list()) {
            array.add(toJson(p, false));
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("count", array.size());
        result.set("projects", array);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    private void handleCreate(HttpExchange exchange, String corsOrigin) throws IOException {
        JsonNode json = WebUtils.MAPPER.readTree(WebUtils.readRequestBodyLimited(exchange));
        String name = json.path("name").asText("");
        if (name.isBlank()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("name is required"), corsOrigin);
            return;
        }
        try {
            ProjectStore.Project created = projectStore.create(name, json.path("instructions").asText(null));
            WebUtils.sendJson(exchange, 200, toJson(created, false), corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    // ==================== 详情 / 修改 / 归档 ====================

    private void handleGet(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        try {
            ProjectStore.Project p = projectStore.get(id);
            WebUtils.sendJson(exchange, 200, toJson(p, true), corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void handleUpdate(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        JsonNode json = WebUtils.MAPPER.readTree(WebUtils.readRequestBodyLimited(exchange));
        try {
            String name = json.has("name") ? json.path("name").asText(null) : null;
            String instructions = json.has("instructions") ? json.path("instructions").asText(null) : null;
            ProjectStore.Project updated = projectStore.update(id, name, instructions);
            WebUtils.sendJson(exchange, 200, toJson(updated, false), corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, e.getMessage() != null && e.getMessage().startsWith("project not found")
                    ? 404 : 400, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void handlePatch(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        JsonNode json = WebUtils.MAPPER.readTree(WebUtils.readRequestBodyLimited(exchange));
        if (!json.has("archived")) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("archived is required"), corsOrigin);
            return;
        }
        try {
            ProjectStore.Project updated = projectStore.setArchived(id, json.path("archived").asBoolean(false));
            WebUtils.sendJson(exchange, 200, toJson(updated, false), corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    // ==================== 资料引用 ====================

    private void handleAddResource(HttpExchange exchange, String corsOrigin, String projectId) throws IOException {
        JsonNode json = WebUtils.MAPPER.readTree(WebUtils.readRequestBodyLimited(exchange));
        String attachmentId = json.path("attachmentId").asText("");
        if (attachmentId.isBlank()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("attachmentId is required"), corsOrigin);
            return;
        }
        // 引用前校验附件确实存在（与删除保留语义互补：不存在就拒绝，避免幽灵引用）
        if (attachmentStore == null || attachmentStore.get(attachmentId) == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("attachment not found: " + attachmentId), corsOrigin);
            return;
        }
        try {
            ProjectStore.Project updated = projectStore.addResource(projectId, attachmentId);
            WebUtils.sendJson(exchange, 200, toJson(updated, false), corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, e.getMessage() != null && e.getMessage().startsWith("project not found")
                    ? 404 : 400, WebUtils.errorJson(e.getMessage()), corsOrigin);
        } catch (IllegalStateException e) {
            WebUtils.sendJson(exchange, 409, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    private void handleRemoveResource(HttpExchange exchange, String corsOrigin,
                                      String projectId, String attachmentId) throws IOException {
        try {
            ProjectStore.Project updated = projectStore.removeResource(projectId, attachmentId);
            WebUtils.sendJson(exchange, 200, toJson(updated, false), corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    // ==================== 删除 ====================

    /**
     * 删除项目：必须显式 confirm=true。只移除项目本体与资料引用——附件物理文件保留
     * （其他项目/会话可能仍引用），会话转录不动；关联会话的 projectId 标记由前端
     * 先经 PATCH /api/sessions/{key}/flags 清理或保留（保留时上下文不再注入项目指令）。
     */
    private void handleDelete(HttpExchange exchange, String corsOrigin, String id) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        boolean confirmed = query != null && query.contains("confirm=true");
        if (!confirmed) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson(
                    "deletion requires confirm=true; check affected sessions/resources first"), corsOrigin);
            return;
        }
        try {
            List<String> released = projectStore.delete(id);
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("deleted", true);
            result.put("projectId", id);
            result.put("releasedResources", released.size());
            ArrayNode ids = WebUtils.MAPPER.createArrayNode();
            released.forEach(ids::add);
            result.set("releasedAttachmentIds", ids);
            result.put("note", "attachment files are preserved; session transcripts are untouched");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (IllegalArgumentException e) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }

    // ==================== 序列化 ====================

    /**
     * 项目 → JSON。withResources 时合并资料引用的附件元信息（名称/状态/大小），
     * 引用缺失（附件已不存在）标记 missing=true 而不是报错。
     */
    private ObjectNode toJson(ProjectStore.Project p, boolean withResources) {
        ObjectNode n = WebUtils.MAPPER.createObjectNode();
        n.put("id", p.id);
        n.put("name", p.name != null ? p.name : "");
        n.put("instructions", p.instructions != null ? p.instructions : "");
        n.put("archived", p.archived);
        n.put("createdAt", p.createdAt != null ? p.createdAt : "");
        n.put("updatedAt", p.updatedAt != null ? p.updatedAt : "");
        n.put("revision", p.revision);
        n.put("resourceCount", p.resourceCount());
        if (withResources) {
            ArrayNode resources = WebUtils.MAPPER.createArrayNode();
            if (p.resourceIds != null) {
                for (String attId : p.resourceIds) {
                    ObjectNode r = WebUtils.MAPPER.createObjectNode();
                    r.put("attachmentId", attId);
                    if (attachmentStore != null) {
                        var att = attachmentStore.get(attId);
                        if (att != null) {
                            r.put("name", att.name != null ? att.name : "");
                            r.put("status", att.status != null ? att.status : "");
                            r.put("size", att.size);
                            r.put("parsedChars", att.parsedChars);
                            r.put("missing", false);
                        } else {
                            r.put("missing", true);
                        }
                    } else {
                        r.put("missing", true);
                    }
                    resources.add(r);
                }
            }
            n.set("resources", resources);
        }
        return n;
    }
}
