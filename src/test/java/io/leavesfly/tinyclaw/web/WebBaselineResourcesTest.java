package io.leavesfly.tinyclaw.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 前端 P0 基线资源测试。
 *
 * <p>锁定三项不回退的交付属性：</p>
 * <ol>
 *   <li>本地 Markdown 渲染器随 JAR 打包（classpath 可见），运行时不依赖 CDN；</li>
 *   <li>index.html 引用本地 {@code /js/markdown.js}，不再外链 jsdelivr 等运行时 CDN；</li>
 *   <li>渲染器保留安全关键实现：文本转义覆盖引号、URL 白名单校验、禁用 eval。</li>
 * </ol>
 *
 * <p>JS 行为级验证（XSS 用例）在实现时以本地脚本执行过一轮；本测试把防退化锚点
 * 固化到 Maven 测试链路，避免再次引入 Node/npm 依赖。</p>
 */
class WebBaselineResourcesTest {

    private static final String INDEX_HTML = "web/index.html";
    private static final String MARKDOWN_JS = "web/js/markdown.js";
    private static final String APP_JS = "web/js/app.js";

    /** 从 classpath 读取静态资源文本。 */
    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "classpath 缺少资源: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("本地 Markdown 渲染器已随 JAR 打包且被 index.html 引用")
    void markdownRenderer_BundledAndReferenced() throws IOException {
        String js = readResource(MARKDOWN_JS);
        assertTrue(js.contains("window.marked"),
                "渲染器必须暴露 window.marked 兼容对象，app.js 现有调用点才能零改动");
        assertTrue(js.contains("window.tinyMarkdown"),
                "渲染器必须暴露 tinyMarkdown 独立入口，供后续新模块使用");

        String html = readResource(INDEX_HTML);
        assertTrue(html.contains("/js/markdown.js"),
                "index.html 必须引用本地 /js/markdown.js");
    }

    @Test
    @DisplayName("前端静态资源不依赖运行时 CDN（断网可用）")
    void staticResources_NoRuntimeCdn() throws IOException {
        String html = readResource(INDEX_HTML);
        assertFalse(html.contains("cdn.jsdelivr.net"),
                "index.html 不得再引用 jsdelivr CDN");
        assertFalse(html.matches("(?s).*<script[^>]+src=\"https?://[^\"/]*cdn[^>]*>.*"),
                "index.html 不得引入任何 CDN script");
    }

    @Test
    @DisplayName("渲染器安全关键点：引号转义、URL 白名单、禁用动态执行")
    void markdownRenderer_SecurityAnchors() throws IOException {
        String js = readResource(MARKDOWN_JS);
        // 文本转义必须覆盖引号（属性注入场景，见 escapeAttr 约定）
        assertTrue(js.contains("&quot;") && js.contains("&#39;"),
                "escapeHtml 必须转义双引号与单引号");
        // URL 白名单：http/https/站内路径/锚点/data:image
        assertTrue(js.contains("isSafeUrl") && js.contains("https?:"),
                "链接与图片地址必须经 isSafeUrl 白名单校验");
        // 禁用动态执行与内联事件绑定
        assertFalse(js.contains("eval("), "渲染器不得使用 eval");
        assertFalse(js.contains("new Function"), "渲染器不得使用 new Function");
        assertFalse(js.contains("onerror="), "渲染器不得输出内联事件属性");
    }

    @Test
    @DisplayName("P0 前端能力锚点：草稿、滚动守卫、移动端抽屉、模型信息条")
    void appJs_P0FeatureAnchors() throws IOException {
        String js = readResource(APP_JS);
        // 草稿生命周期：保存/恢复/清除三件套
        assertTrue(js.contains("saveDraft(") && js.contains("restoreDraft(") && js.contains("clearDraft("),
                "草稿管理方法必须存在");
        assertTrue(js.contains("tinyclaw_draft:"),
                "草稿存储键必须按会话隔离");
        // 会话请求隔离：加载序号守卫
        assertTrue(js.contains("_chatHistoryLoadSeq"),
                "会话历史加载必须有序号守卫，防止旧响应写入新会话 DOM");
        // 移动端抽屉与键盘可达性
        assertTrue(js.contains("openChatDrawer(") && js.contains("closeChatDrawer("),
                "移动端会话抽屉必须存在");
        // 输入区全局模型信息
        assertTrue(js.contains("loadChatInputMeta"),
                "输入区必须展示全局模型与思考配置");
        // 滚动跟随仅限底部
        assertTrue(js.contains("isNearBottom"),
                "必须存在 isNearBottom，流式输出不得强制拉底");
        // 发送失败恢复输入
        assertTrue(js.contains("restoreFailedInput"),
                "发送失败必须把正文恢复到输入框");
    }

    @Test
    @DisplayName("P5 记忆可控性锚点：服务端筛选/分页与来源会话导航")
    void appJs_P5MemoryAnchors() throws IOException {
        String js = readResource(APP_JS);
        String html = readResource(INDEX_HTML);
        // 筛选与分页在服务端执行，前端只携带参数
        assertTrue(js.contains("_memoryFilters") && js.contains("renderMemoryPagination"),
                "记忆列表必须支持服务端筛选/分页状态");
        // 来源导航：先服务端查询再跳转，不接受任意路径
        assertTrue(js.contains("jumpToMemoryOrigin"),
                "来源会话导航必须经服务端查询确认");
        // 筛选栏控件存在
        assertTrue(html.contains("memorySearchInput") && html.contains("memoryScopeSelect")
                        && html.contains("memoryPagination"),
                "记忆页必须提供关键词/归属域筛选与分页容器");
        // 新增记忆可选来源会话
        assertTrue(js.contains("memSourceSession") && js.contains("sourceSessionKey"),
                "新增记忆表单必须支持可选来源会话");
    }

    @Test
    @DisplayName("P5 上下文透明度锚点：本轮使用记忆、会话记忆模式、聊天内纠正")
    void appJs_P5ContextTransparencyAnchors() throws IOException {
        String js = readResource(APP_JS);
        String html = readResource(INDEX_HTML);
        // 本轮使用记忆：同源查询 + 可展开面板
        assertTrue(js.contains("loadMemoryUsed") && js.contains("renderContextUsedBar"),
                "聊天页必须提供本轮使用记忆的查询与渲染");
        assertTrue(js.contains("/api/memory/context-used"),
                "本轮使用清单必须来自服务端同源登记接口");
        assertTrue(html.contains("contextUsedBar"),
                "index.html 必须包含本轮上下文透明化容器");
        // 聊天内纠正/删除：仅影响后续轮次的明示文案
        assertTrue(js.contains("forgetMemoryFromChat")
                        && js.contains("仅影响后续轮次")
                        && js.contains("不会被追溯抹除"),
                "聊天内删除记忆必须明示只影响后续轮次");
        // 会话记忆模式开关：命名为不使用长期记忆，不称无痕
        assertTrue(js.contains("toggleSessionMemoryMode")
                        && js.contains("不使用长期记忆")
                        && js.contains("聊天历史仍"),
                "会话记忆模式开关必须存在并明示聊天历史仍保存");
        assertFalse(js.contains("无痕"),
                "UI 不得把 memoryMode=OFF 称为无痕模式");
        // 上下文构成摘要：模型/附件/摘要/记忆估算，估算值与实际用量分开标注
        assertTrue(js.contains("待发附件") && js.contains("压缩摘要")
                        && js.contains("估算"),
                "本轮上下文构成摘要必须覆盖模型/附件/摘要/记忆并区分估算值");
    }

    @Test
    @DisplayName("P4 项目空间锚点：项目页、会话归属、删除影响确认")
    void appJs_P4ProjectAnchors() throws IOException {
        String js = readResource(APP_JS);
        String html = readResource(INDEX_HTML);
        // 项目页与导航入口
        assertTrue(html.contains("page-projects") && html.contains("data-page=\"projects\""),
                "index.html 必须包含 Projects 页与导航入口");
        // 项目 CRUD + 归属会话查看
        assertTrue(js.contains("loadProjects") && js.contains("showProjectForm")
                        && js.contains("showProjectSessions"),
                "项目列表/新建编辑/归属会话查看必须存在");
        // 删除影响确认：会话数/资料引用数与「附件与会话保留」语义
        assertTrue(js.contains("confirm=true") && js.contains("转录保留")
                        && js.contains("附件文件保留"),
                "删除项目必须先展示影响并明示保留语义");
        // 会话菜单项目归属（产生消息后固定、迁移需 fork 的提示）
        assertTrue(js.contains("assignSessionProject") && js.contains("产生消息后固定"),
                "会话菜单必须提供项目归属设置且提示归属固定约束");
        // 全局优先声明不只在服务端，前端表单也要提示
        assertTrue(js.contains("与全局安全限制冲突时以后者为准"),
                "项目指令表单必须提示全局安全限制优先");
    }

    @Test
    @DisplayName("P6 自动化闭环锚点：从会话创建周期任务、调度预览、执行会话跳转")
    void appJs_P6AutomationAnchors() throws IOException {
        String js = readResource(APP_JS);
        String html = readResource(INDEX_HTML);
        String css = readResource("web/css/style.css");
        // 从会话创建周期任务：入口 + 预填本轮消息 + 不复制聊天历史明示
        assertTrue(html.contains("scheduleBtn"),
                "index.html 必须包含设为周期任务入口按钮");
        assertTrue(js.contains("showScheduleFromChat") && js.contains("不复制聊天历史"),
                "从会话创建周期任务必须存在并明示不复制聊天历史");
        // 调度预览：服务端同源计算，前端不另写解释器
        assertTrue(js.contains("/api/cron/preview") && js.contains("previewCronSchedule")
                        && js.contains("cron-preview-list"),
                "调度预览必须调用服务端同源接口并渲染列表");
        // 执行会话跳转：cron 专用会话不在聊天侧栏，需独立查看入口
        assertTrue(js.contains("openCronRunSession") && js.contains("执行会话已不存在"),
                "历史行必须提供执行会话跳转且会话已清理时明确提示");
        // 成果预览兼容非当前会话缓存：服务端拉取兜底
        assertTrue(js.contains("从服务端拉取详情"),
                "Cron 历史成果预览必须支持服务端拉取兜底");
        // 生成成功≠消息已送达的分开展示语义
        assertTrue(js.contains("生成成功≠消息已送达"),
                "投递状态必须与生成状态分开标注");
        // CSS 支撑
        assertTrue(css.contains(".cron-history-session") && css.contains(".cron-preview-list"),
                "P6 历史行与预览列表样式必须存在");
    }
}
