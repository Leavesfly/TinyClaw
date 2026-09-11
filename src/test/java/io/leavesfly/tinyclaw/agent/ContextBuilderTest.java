package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.memory.MemoryScope;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.providers.ToolCall;
import io.leavesfly.tinyclaw.util.MediaPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextBuilder 的图片路径边界校验与 tool_calls 配对修复测试。
 *
 * <p>覆盖两类曾导致线上问题的场景：
 * <ul>
 *   <li>图片路径越界：越界路径会被读成 Base64 外发给模型服务商，等价任意文件读取；</li>
 *   <li>tool_calls 配对断裂：孤立的 assistant(tool_calls) 会让 LLM API 返回 400，
 *       使会话永久卡死。</li>
 * </ul>
 */
@DisplayName("ContextBuilder 路径校验与历史修复测试")
class ContextBuilderTest {

    @TempDir
    Path workspace;

    private ContextBuilder contextBuilder;

    @BeforeEach
    void setUp() {
        contextBuilder = new ContextBuilder(workspace.toString());
    }

    // ==================== 图片路径边界校验 ====================

    @Test
    @DisplayName("workspace 内的相对上传路径解析为绝对路径并保留")
    void resolveImagePaths_RelativeUploadPath_ResolvedAndKept() throws IOException {
        Path uploads = Files.createDirectories(workspace.resolve("uploads"));
        Files.writeString(uploads.resolve("photo.jpg"), "fake-image");

        List<String> resolved = contextBuilder.resolveImagePaths(List.of("uploads/photo.jpg"));

        assertEquals(1, resolved.size());
        assertEquals(uploads.resolve("photo.jpg").toAbsolutePath().normalize().toString(),
                resolved.get(0));
    }

    @Test
    @DisplayName("使用 ../ 逃出 workspace 的路径被剔除")
    void resolveImagePaths_TraversalPath_Rejected() {
        List<String> resolved = contextBuilder.resolveImagePaths(
                List.of("../../../../etc/passwd"));

        assertTrue(resolved.isEmpty(), "越界的相对路径必须被剔除，否则等价任意文件读取");
    }

    @Test
    @DisplayName("workspace 外的绝对路径被剔除")
    void resolveImagePaths_AbsolutePathOutsideWorkspace_Rejected() {
        List<String> resolved = contextBuilder.resolveImagePaths(List.of("/etc/hosts"));

        assertTrue(resolved.isEmpty(), "workspace 外的绝对路径必须被剔除");
    }

    @Test
    @DisplayName("通道媒体目录内的绝对路径被放行")
    void resolveImagePaths_ChannelMediaDir_Allowed() {
        Path mediaFile = MediaPaths.channelMediaDir().resolve("telegram-photo.jpg");

        List<String> resolved = contextBuilder.resolveImagePaths(List.of(mediaFile.toString()));

        assertEquals(List.of(mediaFile.toString()), resolved,
                "Telegram/Discord 下载的附件落在通道媒体目录，必须继续可用");
    }

    @Test
    @DisplayName("data URI 原样保留，不做路径解析")
    void resolveImagePaths_DataUri_KeptAsIs() {
        String dataUri = "data:image/png;base64,iVBORw0KGgo=";

        assertEquals(List.of(dataUri), contextBuilder.resolveImagePaths(List.of(dataUri)));
    }

    @Test
    @DisplayName("越界图片不进入用户消息，合法图片仍然进入")
    void buildMessages_MixedImagePaths_OnlyValidKept() throws IOException {
        Path uploads = Files.createDirectories(workspace.resolve("uploads"));
        Files.writeString(uploads.resolve("ok.png"), "fake-image");

        List<Message> messages = contextBuilder.buildMessages(
                List.of(), null, "看这两张图",
                new ArrayList<>(List.of("uploads/ok.png", "../../../../etc/passwd")),
                "web", "default");

        Message userMessage = messages.get(messages.size() - 1);
        assertTrue(userMessage.hasImages());
        assertEquals(1, userMessage.getImages().size(), "只应保留合法图片");
        assertTrue(userMessage.getImages().get(0).endsWith("uploads/ok.png"));
    }

    // ==================== P5：会话记忆模式与选中登记 ====================

    @Test
    @DisplayName("memoryMode=OFF 会话：系统提示词不含 Memory 段，登记 disabled 选择")
    void buildMessages_MemoryModeOff_SkipsMemorySection() {
        contextBuilder.getMemoryStore().addEntry(MemoryScope.GLOBAL,
                "用户偏好深色主题", 0.9, List.of("preference"), "user_explicit");
        // 门：web:off-session 关闭记忆
        contextBuilder.setMemoryGate(key -> !"web:off-session".equals(key));

        List<Message> messages = contextBuilder.buildMessages(
                List.of(), null, "深色主题偏好",
                null, "web", "off-session", "web-user", false, "web:off-session");

        String systemPrompt = messages.get(0).getContent();
        assertFalse(systemPrompt.contains("# Memory"),
                "OFF 会话不得注入长期记忆");
        assertTrue(systemPrompt.contains("## 当前会话"),
                "会话信息与后续 section 不受影响");
        // 登记为 disabled 选择（Web 端展示「本轮未注入任何长期记忆」）
        var selection = contextBuilder.getLastMemorySelection("web:off-session");
        assertNotNull(selection);
        assertTrue(selection.disabled);
        assertTrue(selection.entries.isEmpty());
    }

    @Test
    @DisplayName("memoryMode=DEFAULT 会话：记忆注入且选中清单与文本同源登记")
    void buildMessages_MemoryModeDefault_RegistersSelection() {
        contextBuilder.getMemoryStore().addEntry(MemoryScope.GLOBAL,
                "User prefers dark mode", 0.9, List.of("preference"), "user_explicit");
        contextBuilder.setMemoryGate(key -> true);

        List<Message> messages = contextBuilder.buildMessages(
                List.of(), null, "dark mode preference",
                null, "web", "on-session", "web-user", false, "web:on-session");

        String systemPrompt = messages.get(0).getContent();
        assertTrue(systemPrompt.contains("# Memory"), "启用会话应注入记忆");
        assertTrue(systemPrompt.contains("dark mode"), "匹配的记忆内容应在提示词中");

        var selection = contextBuilder.getLastMemorySelection("web:on-session");
        assertNotNull(selection, "启用会话应登记选择快照");
        assertFalse(selection.disabled);
        assertTrue(selection.entries.stream().anyMatch(i -> "User prefers dark mode".equals(i.content)),
                "选中清单应包含实际注入的条目");
        // 同源保证：登记文本与真实系统提示词中的记忆段一致
        assertTrue(systemPrompt.contains("# Memory\n\n" + selection.text),
                "登记的选中文本必须与真实注入文本一致");
    }

    @Test
    @DisplayName("门未配置或 sessionKey 为 null 时保持旧行为（记忆启用、不登记）")
    void buildMessages_NoGateOrNullSession_KeepsLegacyBehavior() {
        contextBuilder.getMemoryStore().addEntry(MemoryScope.GLOBAL,
                "用户偏好深色主题", 0.9, List.of("preference"), "user_explicit");

        // 旧八参入口（无 sessionKey）：记忆注入，不登记
        List<Message> legacy = contextBuilder.buildMessages(
                List.of(), null, "深色主题偏好",
                null, "web", "legacy", "web-user", false);
        assertTrue(legacy.get(0).getContent().contains("# Memory"));
        assertNull(contextBuilder.getLastMemorySelection("web:legacy"),
                "未携带 sessionKey 的旧调用不应登记");

        // 配置了门但传入 null sessionKey：仍启用（会话未知不等于 OFF）
        contextBuilder.setMemoryGate(key -> false);
        List<Message> unknown = contextBuilder.buildMessages(
                List.of(), null, "深色主题偏好",
                null, "web", "unknown", "web-user", false, null);
        assertTrue(unknown.get(0).getContent().contains("# Memory"),
                "会话未知时保持旧行为：记忆注入");
    }

    @Test
    @DisplayName("isMemoryEnabled：门实时生效，切换 OFF/DEFAULT 后下一轮立即变化")
    void memoryGate_TakesEffectImmediately() {
        contextBuilder.setMemoryGate(key -> !"web:x".equals(key));
        assertFalse(contextBuilder.isMemoryEnabled("web:x"));
        assertTrue(contextBuilder.isMemoryEnabled("web:y"));

        // 换门（等价于用户切换 memoryMode 后 flagsStore 实时读取）
        contextBuilder.setMemoryGate(key -> true);
        assertTrue(contextBuilder.isMemoryEnabled("web:x"), "门切换后应实时生效");

        // 清除门
        contextBuilder.setMemoryGate(null);
        assertTrue(contextBuilder.isMemoryEnabled("web:x"));
    }

    // ==================== P4：项目空间（指令注入与项目记忆域） ====================

    @Test
    @DisplayName("归属项目的会话：项目指令注入且带全局优先声明；项目域记忆可见")
    void buildMessages_ProjectSession_InjectsInstructionsAndProjectScope() {
        // 项目域记忆：仅本项目会话可见
        contextBuilder.getMemoryStore().addEntry("p:proj_alpha",
                "Alpha 项目的架构约定是分层", 0.9, List.of("project"), "user_explicit");
        // 另一个项目的域记忆：不得注入
        contextBuilder.getMemoryStore().addEntry("p:proj_beta",
                "Beta 项目的机密约定", 0.9, List.of("project"), "user_explicit");

        contextBuilder.setProjectResolver(key -> "web:proj-session".equals(key)
                ? new io.leavesfly.tinyclaw.agent.context.SectionContext.ProjectInfo(
                        "proj_alpha", "Alpha 项目", "回复必须引用架构约定")
                : null);

        List<Message> messages = contextBuilder.buildMessages(
                List.of(), null, "架构约定是什么",
                null, "web", "proj-session", "web-user", false, "web:proj-session");

        String systemPrompt = messages.get(0).getContent();
        assertTrue(systemPrompt.contains("# Project: Alpha 项目"),
                "项目指令应注入系统提示词");
        assertTrue(systemPrompt.contains("回复必须引用架构约定"));
        assertTrue(systemPrompt.contains("以全局安全限制为准"),
                "必须附带全局优先声明，防止项目指令覆盖全局安全限制");
        // 项目域记忆可见；其他项目域不可见（注入边界验收）
        assertTrue(systemPrompt.contains("Alpha 项目的架构约定是分层"),
                "本项目域记忆应注入");
        assertFalse(systemPrompt.contains("Beta 项目的机密约定"),
                "其他项目域记忆不得注入本项目会话");
    }

    @Test
    @DisplayName("无归属或未装配解析器：无 Project 段，项目域记忆也不可见")
    void buildMessages_NoProject_NoInjectionAndNoProjectScope() {
        contextBuilder.getMemoryStore().addEntry("p:proj_alpha",
                "Alpha 项目的架构约定是分层", 0.9, List.of("project"), "user_explicit");

        // 未装配解析器
        List<Message> legacy = contextBuilder.buildMessages(
                List.of(), null, "架构约定是什么",
                null, "web", "any", "web-user", false, "web:any-session");
        assertFalse(legacy.get(0).getContent().contains("# Project:"), "未装配时无 Project 段");
        assertFalse(legacy.get(0).getContent().contains("Alpha 项目的架构约定"),
                "未装配时项目域记忆不可见");

        // 装配但该会话无归属
        contextBuilder.setProjectResolver(key -> null);
        List<Message> noOwner = contextBuilder.buildMessages(
                List.of(), null, "架构约定是什么",
                null, "web", "any", "web-user", false, "web:any-session");
        assertFalse(noOwner.get(0).getContent().contains("# Project:"), "无归属会话无 Project 段");
        assertFalse(noOwner.get(0).getContent().contains("Alpha 项目的架构约定"),
                "无归属会话不可见项目域记忆");
    }

    @Test
    @DisplayName("项目解析器异常时安全退化：无 Project 段，不中断构建")
    void buildMessages_ResolverThrows_DegradesSafely() {
        contextBuilder.setProjectResolver(key -> {
            throw new RuntimeException("store broken");
        });

        List<Message> messages = contextBuilder.buildMessages(
                List.of(), null, "你好",
                null, "web", "any", "web-user", false, "web:any-session");

        assertTrue(messages.get(0).getContent().contains("## 当前会话"),
                "解析器异常时构建应继续（仅无 Project 段）");
        assertFalse(messages.get(0).getContent().contains("# Project:"));
    }

    // ==================== tool_calls 配对修复 ====================

    @Test
    @DisplayName("尾部未被应答的 assistant(tool_calls) 被剔除")
    void buildMessages_TrailingUnansweredToolCalls_Dropped() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("帮我查天气"));
        history.add(assistantWithToolCalls("call-1"));

        List<Message> messages = contextBuilder.buildMessages(
                history, null, "继续", "web", "default");

        assertTrue(messages.stream().noneMatch(m -> m.getToolCalls() != null
                        && !m.getToolCalls().isEmpty()),
                "未被 tool 消息应答的 assistant(tool_calls) 会让 LLM API 报 400，必须剔除");
    }

    @Test
    @DisplayName("完整配对的 assistant(tool_calls) + tool 被保留")
    void buildMessages_CompleteToolCallPair_Kept() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("帮我查天气"));
        history.add(assistantWithToolCalls("call-1"));
        history.add(Message.tool("call-1", "晴，25 度"));

        List<Message> messages = contextBuilder.buildMessages(
                history, null, "继续", "web", "default");

        assertTrue(messages.stream().anyMatch(m -> m.getToolCalls() != null
                        && !m.getToolCalls().isEmpty()),
                "配对完整的工具调用必须保留");
        assertTrue(messages.stream().anyMatch(m -> "tool".equals(m.getRole())));
    }

    @Test
    @DisplayName("部分应答的多工具调用整组剔除，不留孤立 tool 消息")
    void buildMessages_PartiallyAnsweredToolCalls_DroppedAsGroup() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("并行查两件事"));
        history.add(assistantWithToolCalls("call-1", "call-2"));
        history.add(Message.tool("call-1", "结果一"));

        List<Message> messages = contextBuilder.buildMessages(
                history, null, "继续", "web", "default");

        assertTrue(messages.stream().noneMatch(m -> "tool".equals(m.getRole())),
                "只丢 assistant 会让剩下的 tool 消息变成孤立消息，同样触发 400");
    }

    @Test
    @DisplayName("历史开头的孤立 tool 消息被跳过")
    void buildMessages_LeadingOrphanToolMessage_Skipped() {
        List<Message> history = new ArrayList<>();
        history.add(Message.tool("call-orphan", "上下文压缩后残留的结果"));
        history.add(Message.assistant("好的"));

        List<Message> messages = contextBuilder.buildMessages(
                history, null, "继续", "web", "default");

        assertTrue(messages.stream().noneMatch(m -> "tool".equals(m.getRole())));
    }

    /**
     * 构造一条带指定 tool_call id 的 assistant 消息。
     */
    private Message assistantWithToolCalls(String... callIds) {
        Message assistant = Message.assistant("");
        List<ToolCall> calls = new ArrayList<>();
        for (String callId : callIds) {
            calls.add(new ToolCall(callId, "weather", null));
        }
        assistant.setToolCalls(calls);
        return assistant;
    }
}
