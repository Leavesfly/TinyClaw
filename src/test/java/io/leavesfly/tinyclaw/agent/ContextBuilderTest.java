package io.leavesfly.tinyclaw.agent;

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
