package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.memory.MemoryEntry;
import io.leavesfly.tinyclaw.memory.MemoryScope;
import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆管理 API（/api/memory）。
 *
 * <p>面向 Web 控制台的记忆条目 CRUD，让用户可以查看、修正、删除 Agent 记住的内容：
 * <ul>
 *   <li><b>GET    /api/memory</b>       — 列出全部记忆条目；</li>
 *   <li><b>POST   /api/memory</b>       — 新增一条记忆；</li>
 *   <li><b>PUT    /api/memory/{id}</b>  — 修改一条记忆的内容/重要度/标签；</li>
 *   <li><b>DELETE /api/memory/{id}</b>  — 删除一条记忆。</li>
 * </ul>
 *
 * <p>{@link MemoryStore#getEntries()} 返回的是存储中同一批 {@link MemoryEntry} 引用，
 * 因此更新按 id 定位后直接改字段再 {@link MemoryStore#flush()} 落盘。记忆未就绪
 * （provider 未初始化）时统一返回 501。</p>
 */
public class MemoryHandler extends BaseHandler {

    private final AgentRuntime agentRuntime;

    /**
     * 构造 MemoryHandler。
     *
     * @param config       全局配置
     * @param security     安全中间件
     * @param agentRuntime Agent 运行时（用于获取 MemoryStore，可为 null）
     */
    public MemoryHandler(Config config, SecurityMiddleware security, AgentRuntime agentRuntime) {
        super(config, security);
        this.agentRuntime = agentRuntime;
    }

    /**
     * 按方法与路径分发到列表 / 新增 / 修改 / 删除。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (WebUtils.API_MEMORY.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
            handleList(exchange, corsOrigin);
        } else if (WebUtils.API_MEMORY.equals(path) && WebUtils.HTTP_METHOD_POST.equals(method)) {
            handleAdd(exchange, corsOrigin);
        } else if (path.startsWith(WebUtils.API_MEMORY + WebUtils.PATH_SEPARATOR)
                && WebUtils.HTTP_METHOD_PUT.equals(method)) {
            handleUpdate(exchange, path, corsOrigin);
        } else if (path.startsWith(WebUtils.API_MEMORY + WebUtils.PATH_SEPARATOR)
                && WebUtils.HTTP_METHOD_DELETE.equals(method)) {
            handleDelete(exchange, path, corsOrigin);
        } else {
            return false;
        }
        return true;
    }

    /**
     * 列出全部记忆条目（按创建时间倒序，新的在前）。
     */
    private void handleList(HttpExchange exchange, String corsOrigin) throws IOException {
        MemoryStore store = store();
        if (store == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Memory store is not available"), corsOrigin);
            return;
        }
        List<MemoryEntry> entries = new ArrayList<>(store.getEntries());
        entries.sort((a, b) -> {
            if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });

        ArrayNode array = WebUtils.MAPPER.createArrayNode();
        for (MemoryEntry e : entries) {
            array.add(toJson(e));
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("count", entries.size());
        result.set("entries", array);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 新增一条记忆。请求体：{@code {content, importance?, tags?, scope?, source?}}。
     */
    private void handleAdd(HttpExchange exchange, String corsOrigin) throws IOException {
        MemoryStore store = store();
        if (store == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Memory store is not available"), corsOrigin);
            return;
        }
        JsonNode json = WebUtils.MAPPER.readTree(WebUtils.readRequestBodyLimited(exchange));
        String content = json.path("content").asText("").trim();
        if (content.isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("content is required"), corsOrigin);
            return;
        }
        double importance = json.path("importance").asDouble(0.5);
        String scope = json.path("scope").asText(MemoryScope.GLOBAL);
        String source = json.path("source").asText("web-console");
        List<String> tags = readTags(json);

        store.addEntry(scope, content, importance, tags, source);
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Memory added"), corsOrigin);
    }

    /**
     * 修改一条记忆的内容/重要度/标签（仅更新请求体中出现的字段）。id 不存在返回 404。
     */
    private void handleUpdate(HttpExchange exchange, String path, String corsOrigin) throws IOException {
        MemoryStore store = store();
        if (store == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Memory store is not available"), corsOrigin);
            return;
        }
        String id = path.substring(WebUtils.API_MEMORY.length() + 1);
        JsonNode json = WebUtils.MAPPER.readTree(WebUtils.readRequestBodyLimited(exchange));

        MemoryEntry target = findById(store, id);
        if (target == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Memory entry not found: " + id), corsOrigin);
            return;
        }
        if (json.has("content")) {
            String content = json.path("content").asText("").trim();
            if (content.isEmpty()) {
                WebUtils.sendJson(exchange, 400, WebUtils.errorJson("content cannot be empty"), corsOrigin);
                return;
            }
            target.setContent(content);
        }
        if (json.has("importance")) {
            target.setImportance(json.path("importance").asDouble(target.getImportance()));
        }
        if (json.has("tags")) {
            target.setTags(readTags(json));
        }
        store.flush();
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Memory updated"), corsOrigin);
    }

    /**
     * 删除一条记忆。id 不存在返回 404。
     */
    private void handleDelete(HttpExchange exchange, String path, String corsOrigin) throws IOException {
        MemoryStore store = store();
        if (store == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Memory store is not available"), corsOrigin);
            return;
        }
        String id = path.substring(WebUtils.API_MEMORY.length() + 1);
        MemoryEntry removed = store.removeEntry(id);
        if (removed == null) {
            WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Memory entry not found: " + id), corsOrigin);
            return;
        }
        WebUtils.sendJson(exchange, 200, WebUtils.successJson("Memory deleted"), corsOrigin);
    }

    // ==================== 辅助方法 ====================

    private MemoryStore store() {
        return agentRuntime != null ? agentRuntime.getMemoryStore() : null;
    }

    private MemoryEntry findById(MemoryStore store, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (MemoryEntry e : store.getEntries()) {
            if (id.equals(e.getId())) {
                return e;
            }
        }
        return null;
    }

    /**
     * 从请求体解析 tags 数组，容忍缺失与非数组。
     */
    private List<String> readTags(JsonNode json) {
        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = json.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode t : tagsNode) {
                String text = t.asText("").trim();
                if (!text.isEmpty()) {
                    tags.add(text);
                }
            }
        }
        return tags;
    }

    /**
     * 将记忆条目序列化为前端友好的 JSON 节点。
     */
    private ObjectNode toJson(MemoryEntry e) {
        ObjectNode node = WebUtils.MAPPER.createObjectNode();
        node.put("id", e.getId() != null ? e.getId() : "");
        node.put("scope", e.getScope() != null ? e.getScope() : "");
        node.put("content", e.getContent() != null ? e.getContent() : "");
        node.put("importance", e.getImportance());
        node.put("source", e.getSource() != null ? e.getSource() : "");
        node.put("accessCount", e.getAccessCount());
        node.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        ArrayNode tags = WebUtils.MAPPER.createArrayNode();
        if (e.getTags() != null) {
            e.getTags().forEach(tags::add);
        }
        node.set("tags", tags);
        return node;
    }
}
