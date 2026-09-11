package io.leavesfly.tinyclaw.web.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.JsonFileStore;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 项目空间存储（P4）：项目 = 名称 + 指令 + 显式共享的资料引用。
 *
 * <p>定位是「长期工作的组织容器」，不是全局文件编辑器：项目只持有资料引用
 * （附件 ID，物理文件归 {@code AttachmentStore} 所有）与会话归属标记
 * （归 {@code SessionFlagsStore} 所有），删除项目不删除任何会话转录与附件文件。</p>
 *
 * <p>存储：单 JSON 文件 {@code workspace/projects.json} 原子写；损坏按空处理
 * （丢失仅影响项目组织，可从会话标记部分重建关联）。revision 单调递增，
 * 每次修改 +1，供执行开始时记录「使用的项目版本」。</p>
 *
 * <p>线程安全：内存 map + synchronized 变更后落盘。</p>
 */
public class ProjectStore {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("project");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 项目 id 安全格式（与附件/来源会话键同一套约束）。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 指令长度上限（字符）：指令进系统提示词，过长会挤占上下文预算。 */
    private static final int MAX_INSTRUCTIONS_CHARS = 8000;

    /** 项目名称长度上限。 */
    private static final int MAX_NAME_CHARS = 100;

    /** 单项目资料引用上限。 */
    private static final int MAX_RESOURCES = 100;

    /** 项目（不可变数据 + 由 Store 负责变更）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Project {
        public String id;
        public String name;
        /** 项目指令：注入归属会话的系统提示词（独立 ContextSection）。 */
        public String instructions;
        /** 显式共享的资料引用（附件 ID 列表）。 */
        public List<String> resourceIds = new ArrayList<>();
        public boolean archived;
        public String createdAt;
        public String updatedAt;
        /** 单调递增版本号：每次修改 +1。 */
        public long revision;

        Project() {
        }

        Project(String id, String name, String instructions, String now) {
            this.id = id;
            this.name = name;
            this.instructions = instructions;
            this.createdAt = now;
            this.updatedAt = now;
            this.revision = 1;
        }

        /** 资料引用数（未做附件存在性校验，展示用）。 */
        public int resourceCount() {
            return resourceIds != null ? resourceIds.size() : 0;
        }
    }

    private final Path file;
    private final ConcurrentHashMap<String, Project> projects = new ConcurrentHashMap<>();
    private final AtomicLong maxRevision = new AtomicLong(0);

    /**
     * @param storagePath 项目存储文件路径（如 workspace/projects.json）；null 表示纯内存（测试）
     */
    public ProjectStore(String storagePath) {
        this.file = storagePath != null ? Paths.get(storagePath) : null;
        if (this.file != null) {
            load();
        }
    }

    // ==================== CRUD ====================

    /** 创建项目（名称必填；指令可选）。返回创建后的项目副本。 */
    public synchronized Project create(String name, String instructions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name is required");
        }
        if (name.length() > MAX_NAME_CHARS) {
            name = name.substring(0, MAX_NAME_CHARS);
        }
        String id = newProjectId();
        // revision 从全局单调序列取值：新建也不回头，重启后继续递增不回退
        Project p = new Project(id, name.trim(), sanitizeInstructions(instructions), Instant.now().toString());
        p.revision = maxRevision.incrementAndGet();
        projects.put(id, p);
        persist();
        return copy(p);
    }

    /** 按 id 查项目（返回副本）。 */
    public Project get(String id) {
        Project p = requireRaw(id);
        return copy(p);
    }

    /** 是否存在（含归档）。 */
    public boolean exists(String id) {
        return id != null && projects.containsKey(id);
    }

    /** 全量列表（按创建时间正序，新的在后）。 */
    public List<Project> list() {
        List<Project> all = new ArrayList<>(projects.values());
        all.sort((a, b) -> String.valueOf(a.createdAt).compareTo(String.valueOf(b.createdAt)));
        List<Project> result = new ArrayList<>(all.size());
        for (Project p : all) {
            result.add(copy(p));
        }
        return result;
    }

    /** 修改名称/指令（仅更新非 null 字段）。 */
    public synchronized Project update(String id, String name, String instructions) {
        Project p = requireRaw(id);
        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("project name cannot be empty");
            }
            p.name = name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name.trim();
        }
        if (instructions != null) {
            p.instructions = sanitizeInstructions(instructions);
        }
        touch(p);
        persist();
        return copy(p);
    }

    /** 归档开关（归档不删除任何资源）。 */
    public synchronized Project setArchived(String id, boolean archived) {
        Project p = requireRaw(id);
        p.archived = archived;
        touch(p);
        persist();
        return copy(p);
    }

    // ==================== 资料引用 ====================

    /**
     * 添加资料引用（附件 ID）。引用不做存在性强校验（附件可能后上传），
     * 但限制单项目上限与 id 格式。
     */
    public synchronized Project addResource(String id, String attachmentId) {
        Project p = requireRaw(id);
        if (attachmentId == null || !SAFE_ID.matcher(attachmentId).matches()) {
            throw new IllegalArgumentException("invalid attachmentId");
        }
        if (p.resourceIds == null) {
            p.resourceIds = new ArrayList<>();
        }
        if (!p.resourceIds.contains(attachmentId)) {
            if (p.resourceIds.size() >= MAX_RESOURCES) {
                throw new IllegalStateException("project resource limit reached: " + MAX_RESOURCES);
            }
            p.resourceIds.add(attachmentId);
            touch(p);
            persist();
        }
        return copy(p);
    }

    /** 移除资料引用（只解除引用，不删附件物理文件）。 */
    public synchronized Project removeResource(String id, String attachmentId) {
        Project p = requireRaw(id);
        if (p.resourceIds != null && p.resourceIds.remove(attachmentId)) {
            touch(p);
            persist();
        }
        return copy(p);
    }

    // ==================== 删除 ====================

    /**
     * 删除项目。返回被解除关联的资源引用（供调用方报告影响）。
     *
     * <p>删除只移除项目本体与资料引用，不动附件物理文件（其他项目/会话可能仍引用）、
     * 不动会话转录（会话标记中的 projectId 由调用方负责清理）。</p>
     */
    public synchronized List<String> delete(String id) {
        Project p = projects.remove(id);
        if (p == null) {
            throw new IllegalArgumentException("project not found: " + id);
        }
        persist();
        List<String> released = new ArrayList<>(p.resourceIds != null ? p.resourceIds : List.of());
        logger.info("Project deleted", Map.of("project", id, "released_resources", released.size()));
        return released;
    }

    // ==================== 持久化 ====================

    private void load() {
        try {
            Map<String, Project> root = JsonFileStore.readJson(MAPPER, file,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Project>>() {
                    }, java.util.Map::of);
            projects.putAll(root);
            projects.values().forEach(p -> maxRevision.accumulateAndGet(p.revision, Math::max));
        } catch (Exception e) {
            logger.warn("Projects file unreadable, starting fresh", Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            // LinkedHashMap 保持稳定写入顺序（按创建时间），便于人工检视与 diff
            Map<String, Project> ordered = new LinkedHashMap<>();
            List<Project> sorted = new ArrayList<>(projects.values());
            sorted.sort((a, b) -> String.valueOf(a.createdAt).compareTo(String.valueOf(b.createdAt)));
            sorted.forEach(p -> ordered.put(p.id, p));
            JsonFileStore.writeJson(MAPPER, file, ordered);
        } catch (IOException e) {
            logger.error("Failed to persist projects", Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }

    private void touch(Project p) {
        p.updatedAt = Instant.now().toString();
        p.revision = maxRevision.incrementAndGet();
    }

    private Project requireRaw(String id) {
        Project p = id != null ? projects.get(id) : null;
        if (p == null) {
            throw new IllegalArgumentException("project not found: " + id);
        }
        return p;
    }

    private String sanitizeInstructions(String instructions) {
        if (instructions == null || instructions.isBlank()) {
            return "";
        }
        return instructions.length() > MAX_INSTRUCTIONS_CHARS
                ? instructions.substring(0, MAX_INSTRUCTIONS_CHARS) : instructions.trim();
    }

    private String newProjectId() {
        // 短 UUID（12 位十六进制），与附件/成果 id 风格一致；冲突概率可忽略，
        // 仍做防重循环保证
        String id;
        do {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } while (projects.containsKey(id));
        return id;
    }

    /** 深拷贝副本（避免外部修改内存态）。 */
    private Project copy(Project p) {
        Project c = new Project();
        c.id = p.id;
        c.name = p.name;
        c.instructions = p.instructions;
        c.resourceIds = p.resourceIds != null ? new ArrayList<>(p.resourceIds) : new ArrayList<>();
        c.archived = p.archived;
        c.createdAt = p.createdAt;
        c.updatedAt = p.updatedAt;
        c.revision = p.revision;
        return c;
    }
}
