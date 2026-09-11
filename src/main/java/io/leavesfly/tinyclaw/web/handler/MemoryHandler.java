package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.agent.AgentRuntime;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.memory.MemoryEntry;
import io.leavesfly.tinyclaw.memory.MemoryScope;
import io.leavesfly.tinyclaw.memory.MemorySelection;
import io.leavesfly.tinyclaw.memory.MemoryStore;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 长期记忆管理 API（/api/memory）。
 *
 * <p>面向 Web 控制台的记忆条目 CRUD，让用户可以查看、修正、删除 Agent 记住的内容：
 * <ul>
 *   <li><b>GET    /api/memory</b>       — 列出记忆条目，支持可选查询参数筛选与分页：
 *       {@code q}（关键词，匹配内容与标签）、{@code scope}（归属域）、
 *       {@code tag}（精确匹配某个标签）、{@code source}（来源标识）、
 *       {@code limit}（每页条数，默认 50，上限 200）、{@code offset}（偏移，默认 0）；
 *       响应含 {@code total}（过滤后总条数）与当前页 {@code entries}；</li>
 *   <li><b>POST   /api/memory</b>       — 新增一条记忆，可携带 {@code sourceSessionKey} 记录来源会话；</li>
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

    /** 测试与内嵌场景直接注入的存储；非空时优先于 agentRuntime。 */
    private final MemoryStore injectedStore;

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
        this.injectedStore = null;
    }

    /**
     * 构造 MemoryHandler（直接注入 MemoryStore，不经 AgentRuntime）。
     * 供测试与轻量内嵌场景使用。
     *
     * @param config 全局配置
     * @param security 安全中间件
     * @param store   记忆存储
     */
    public MemoryHandler(Config config, SecurityMiddleware security, MemoryStore store) {
        super(config, security);
        this.agentRuntime = null;
        this.injectedStore = store;
    }

    /**
     * 来源会话键的安全格式：仅允许字母数字、冒号（Web 会话 key 形如 web:1725968000000）、
     * 下划线与连字符，长度 1~64。路径分隔符、URL 保留字符等均被拒绝，
     * 因此无法携带路径或任意 URL 形式的“来源导航”。
     */
    private static final Pattern SAFE_SESSION_KEY = Pattern.compile("[A-Za-z0-9:_-]{1,64}");

    /** 默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** 每页条数上限。 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 按方法与路径分发到列表 / 新增 / 修改 / 删除 / 本轮使用查询。
     */
    @Override
    protected boolean route(HttpExchange exchange, String path, String method, String corsOrigin)
            throws IOException {
        if (WebUtils.API_MEMORY.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
            handleList(exchange, corsOrigin);
        } else if ((WebUtils.API_MEMORY + "/context-used").equals(path)
                && WebUtils.HTTP_METHOD_GET.equals(method)) {
            handleContextUsed(exchange, corsOrigin);
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
     * 查询某会话最近一次记忆选择（P5：本轮使用透明化）。
     *
     * <p>返回的条目清单与真实注入系统提示词的文本来自同一次计算（ContextBuilder 登记），
     * 不根据访问次数推断。记忆未就绪或该会话尚无登记时返回 {@code available=false}。</p>
     */
    private void handleContextUsed(HttpExchange exchange, String corsOrigin) throws IOException {
        if (agentRuntime == null) {
            WebUtils.sendJson(exchange, 501, WebUtils.errorJson("Agent runtime is not available"), corsOrigin);
            return;
        }
        String sessionKey = queryParam(exchange, "sessionId");
        if (sessionKey == null || sessionKey.isEmpty()) {
            WebUtils.sendJson(exchange, 400, WebUtils.errorJson("sessionId is required"), corsOrigin);
            return;
        }
        MemorySelection selection =
                agentRuntime.getContextBuilder().getLastMemorySelection(sessionKey);

        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("available", selection != null);
        if (selection == null) {
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
            return;
        }
        result.put("disabled", selection.disabled);
        result.put("memoryCount", selection.entries.size());
        result.put("topicCount", selection.topics.size());
        result.put("estimatedTokens", selection.estimatedTokens);
        ArrayNode items = WebUtils.MAPPER.createArrayNode();
        for (MemorySelection.Item item : selection.entries) {
            ObjectNode n = WebUtils.MAPPER.createObjectNode();
            n.put("id", item.id != null ? item.id : "");
            n.put("content", item.content != null ? item.content : "");
            n.put("scope", item.scope != null ? item.scope : "");
            n.put("source", item.source != null ? item.source : "");
            n.put("importance", item.importance);
            ArrayNode tags = WebUtils.MAPPER.createArrayNode();
            item.tags.forEach(tags::add);
            n.set("tags", tags);
            items.add(n);
        }
        result.set("entries", items);
        ArrayNode topics = WebUtils.MAPPER.createArrayNode();
        selection.topics.forEach(topics::add);
        result.set("topics", topics);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 列出记忆条目（按创建时间倒序，新的在前），支持关键词/归属域/标签/来源筛选与分页。
     * 无任何查询参数时行为与历史版本一致（返回首页数据）。
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

        // 筛选（服务端执行，避免记忆全量下发到前端后再过滤）
        String q = queryParam(exchange, "q");
        String scope = queryParam(exchange, "scope");
        String tag = queryParam(exchange, "tag");
        String source = queryParam(exchange, "source");
        if (q != null || scope != null || tag != null || source != null) {
            entries = applyFilters(entries, q, scope, tag, source);
        }

        // 分页（offset/limit，均容忍非法值）
        int total = entries.size();
        int limit = clampPageSize(queryInt(exchange, "limit"));
        int offset = Math.max(0, queryInt(exchange, "offset", 0));
        if (offset > entries.size()) {
            offset = entries.size();
        }
        int end = Math.min(offset + limit, entries.size());
        List<MemoryEntry> page = entries.subList(offset, end);

        ArrayNode array = WebUtils.MAPPER.createArrayNode();
        for (MemoryEntry e : page) {
            array.add(toJson(e));
        }
        ObjectNode result = WebUtils.MAPPER.createObjectNode();
        result.put("count", page.size());
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        result.set("entries", array);
        WebUtils.sendJson(exchange, 200, result, corsOrigin);
    }

    /**
     * 新增一条记忆。请求体：{@code {content, importance?, tags?, scope?, source?, sourceSessionKey?, sourceMessageId?}}。
     * sourceSessionKey / sourceMessageId 仅接受安全字符集（字母数字/冒号/下划线/连字符，≤64），
     * 非法值返 400，不接受任意路径或 URL 形式的“来源导航”（sourceMessageId 为 P5 逐消息回溯）。
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

        String sourceSessionKey = json.path("sourceSessionKey").asText("").trim();
        if (!sourceSessionKey.isEmpty()) {
            if (!SAFE_SESSION_KEY.matcher(sourceSessionKey).matches()) {
                WebUtils.sendJson(exchange, 400,
                        WebUtils.errorJson("sourceSessionKey contains illegal characters"), corsOrigin);
                return;
            }
        } else {
            sourceSessionKey = null;
        }

        String rawMessageId = json.path("sourceMessageId").asText("").trim();
        if (!rawMessageId.isEmpty()) {
            if (!SAFE_SESSION_KEY.matcher(rawMessageId).matches()) {
                WebUtils.sendJson(exchange, 400,
                        WebUtils.errorJson("sourceMessageId contains illegal characters"), corsOrigin);
                return;
            }
        }
        final String sourceMessageId = rawMessageId.isEmpty() ? null : rawMessageId;

        store.addEntry(scope, content, importance, tags, source, sourceSessionKey);
        if (sourceMessageId != null) {
            // 挂到刚新增的条目上（addEntry 无此参重载，避免再扩签名）
            final String targetContent = content;
            store.getEntries().stream()
                    .filter(e -> targetContent.equals(e.getContent()))
                    .reduce((first, second) -> second) // 取最新一条同内容
                    .ifPresent(e -> e.setSourceMessageId(sourceMessageId));
            store.flush();
        }
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

    /**
     * 服务端筛选：q 匹配内容或标签（大小写不敏感），scope/tag/source 精确匹配，
     * tag 命中标签列表之一即可。
     */
    private List<MemoryEntry> applyFilters(List<MemoryEntry> entries, String q, String scope, String tag, String source) {
        List<MemoryEntry> filtered = new ArrayList<>();
        String needle = q != null ? q.toLowerCase(Locale.ROOT) : null;
        for (MemoryEntry e : entries) {
            if (scope != null && !scope.equals(e.getScope())) {
                continue;
            }
            if (source != null && !source.equals(e.getSource())) {
                continue;
            }
            if (tag != null && (e.getTags() == null || e.getTags().stream().noneMatch(tag::equals))) {
                continue;
            }
            if (needle != null) {
                String content = e.getContent() != null ? e.getContent().toLowerCase(Locale.ROOT) : "";
                boolean inTags = e.getTags() != null && e.getTags().stream()
                        .anyMatch(t -> t != null && t.toLowerCase(Locale.ROOT).contains(needle));
                if (!content.contains(needle) && !inTags) {
                    continue;
                }
            }
            filtered.add(e);
        }
        return filtered;
    }

    /** 每页条数规范化：null 或非法取默认值，超限取上限。 */
    private int clampPageSize(Integer raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(1, raw), MAX_PAGE_SIZE);
    }

    /** 查询串解析（URL 解码），缺失或空值返回 null。 */
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

    /** 查询串整数解析，缺失或非法返回 fallback。 */
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

    /** 查询串整数解析，缺失或非法返回 null（区别于“未传”与默认值场景）。 */
    private Integer queryInt(HttpExchange exchange, String name) {
        String raw = queryParam(exchange, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MemoryStore store() {
        if (injectedStore != null) {
            return injectedStore;
        }
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
        node.put("sourceSessionKey", e.getSourceSessionKey() != null ? e.getSourceSessionKey() : "");
        node.put("sourceMessageId", e.getSourceMessageId() != null ? e.getSourceMessageId() : "");
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
