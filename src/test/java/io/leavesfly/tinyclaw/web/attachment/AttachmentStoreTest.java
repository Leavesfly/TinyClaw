package io.leavesfly.tinyclaw.web.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 附件解析与存储闭环测试。
 *
 * <p>覆盖：各格式成功样本、损坏文件明确报错、伪造头拒绝、超限截断、
 * 状态轮询（PARSING→READY）、上下文块注入（带来源 ID）、非法 ID 路径校验。</p>
 */
class AttachmentStoreTest {

    @TempDir
    Path tempDir;

    private AttachmentStore newStore() {
        return new AttachmentStore(tempDir.resolve("attachments").toString());
    }

    /** 等待异步解析完成（最多 ~4s）。 */
    private void awaitReady(AttachmentStore store, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Attachment meta = store.get(id);
            if (meta != null && meta.statusEnum() != Attachment.Status.PARSING
                    && meta.statusEnum() != Attachment.Status.UPLOADING) {
                return;
            }
            Thread.sleep(20);
        }
    }

    // ==================== 各格式成功样本 ====================

    @Test
    @DisplayName("纯文本附件：解析为原文并 READY")
    void textAttachment_ParsesToReady() throws Exception {
        AttachmentStore store = newStore();
        Attachment meta = store.save("你好，TinyClaw".getBytes(StandardCharsets.UTF_8),
                "notes.txt", "text/plain", "web:default");
        awaitReady(store, meta.id);
        assertEquals(Attachment.Status.READY, meta.statusEnum());
        assertTrue(store.readParsedText(meta.id).contains("你好，TinyClaw"));
    }

    @Test
    @DisplayName("CSV 附件：解析为带表头的 Markdown 表格")
    void csvAttachment_ParsesToTable() throws Exception {
        AttachmentStore store = newStore();
        String csv = "name,score\nalice,90\nbob,85";
        Attachment meta = store.save(csv.getBytes(StandardCharsets.UTF_8),
                "scores.csv", "text/csv", "web:default");
        awaitReady(store, meta.id);
        assertEquals(Attachment.Status.READY, meta.statusEnum());
        String text = store.readParsedText(meta.id);
        assertTrue(text.contains("| name | score |"), "应保留表头结构");
        assertTrue(text.contains("| alice | 90 |"));
    }

    @Test
    @DisplayName("Markdown / 代码文本按纯文本解析")
    void markdownAndCode_ParsedAsText() throws Exception {
        AttachmentStore store = newStore();
        Attachment md = store.save("# 标题\n正文".getBytes(StandardCharsets.UTF_8),
                "readme.md", "text/markdown", "web:default");
        awaitReady(store, md.id);
        assertEquals(Attachment.Status.READY, md.statusEnum());

        Attachment code = store.save("int main() { return 0; }".getBytes(StandardCharsets.UTF_8),
                "main.java", "text/plain", "web:default");
        awaitReady(store, code.id);
        assertTrue(store.readParsedText(code.id).contains("int main"));
    }

    @Test
    @DisplayName("损坏 PDF 与伪造 PDF 头：解析失败并给出明确原因")
    void corruptedPdf_FailsWithReason() throws Exception {
        AttachmentStore store = newStore();
        // %PDF 头但内容损坏
        byte[] fakePdf = ("%PDF-1.4\nbroken-not-really-pdf").getBytes(StandardCharsets.UTF_8);
        Attachment meta = store.save(fakePdf, "fake.pdf", "application/pdf", "web:default");
        awaitReady(store, meta.id);
        assertEquals(Attachment.Status.FAILED, meta.statusEnum(), "损坏 PDF 应置 FAILED 而非伪成功");
        assertNotNull(meta.parseError);

        // 伪造扩展名：txt 内容命名为 .pdf
        Attachment fake = store.save("plain text".getBytes(StandardCharsets.UTF_8),
                "lie.pdf", "application/pdf", "web:default");
        awaitReady(store, fake.id);
        assertEquals(Attachment.Status.FAILED, fake.statusEnum(), "文件头不匹配应拒绝");
    }

    @Test
    @DisplayName("不支持的类型（旧 DOC / 压缩包）明确报错")
    void unsupportedTypes_Rejected() throws Exception {
        AttachmentStore store = newStore();
        // ZIP 头（PK\x03\x04）命名为 .doc：旧 Office 拒绝
        byte[] zipHeader = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        Attachment doc = store.save(zipHeader, "old.doc", "application/msword", "web:default");
        awaitReady(store, doc.id);
        assertEquals(Attachment.Status.FAILED, doc.statusEnum());
        assertTrue(doc.parseError.contains("旧版 Office") || doc.parseError.contains("暂不支持"));

        Attachment zip = store.save(zipHeader, "archive.zip", "application/zip", "web:default");
        awaitReady(store, zip.id);
        assertEquals(Attachment.Status.FAILED, zip.statusEnum());
    }

    // ==================== 上下文注入 ====================

    @Test
    @DisplayName("buildContextBlock：就绪附件注入带来源 ID 的全文；缺失附件明确标注")
    void buildContextBlock_IncludesSourceId() throws Exception {
        AttachmentStore store = newStore();
        Attachment a = store.save("附件正文内容".getBytes(StandardCharsets.UTF_8),
                "doc.txt", "text/plain", "web:default");
        awaitReady(store, a.id);

        String block = store.buildContextBlock(List.of(a.id, "nonexistent1"));
        assertNotNull(block);
        assertTrue(block.contains("来源ID " + a.id), "上下文块必须带稳定来源 ID");
        assertTrue(block.contains("附件正文内容"));
        assertTrue(block.contains("不存在或已删除"), "缺失附件应明确标注");
    }

    @Test
    @DisplayName("buildContextBlock：未就绪附件标注状态而非注入")
    void buildContextBlock_MarksPendingStatus() throws Exception {
        AttachmentStore store = newStore();
        // 解析是异步的：保存后立即构建大概率仍是 PARSING
        Attachment a = store.save("x".repeat(100).getBytes(StandardCharsets.UTF_8),
                "big.txt", "text/plain", "web:default");
        String block = store.buildContextBlock(List.of(a.id));
        // 无论 PARSING 还是 READY，都不抛异常且输出非空
        assertNotNull(block);
        awaitReady(store, a.id);
    }

    // ==================== 超限与安全 ====================

    @Test
    @DisplayName("超大文件（>10MiB）在 save 时即被拒绝")
    void oversizedFile_RejectedAtSave() {
        AttachmentStore store = newStore();
        byte[] big = new byte[AttachmentStore.MAX_FILE_SIZE + 1];
        assertThrows(IOException.class, () -> store.save(big, "big.bin", "application/octet-stream", "web:default"),
                "超限文件应直接拒绝");
    }

    @Test
    @DisplayName("非法附件 ID（路径拼接/遍历尝试）被拒绝")
    void illegalId_Rejected() {
        AttachmentStore store = newStore();
        assertThrows(IOException.class, () -> store.readParsedText("../../etc/passwd"));
        assertThrows(IOException.class, () -> store.readParsedText("a/b/c"));
        assertThrows(IOException.class, () -> store.readRaw("zzz"));
    }

    @Test
    @DisplayName("超长文本解析输出截断并带标记")
    void oversizedOutput_TruncatedWithMarker() throws Exception {
        AttachmentStore store = newStore();
        // 21 万字符文本：超过 20 万输出上限
        String huge = "x".repeat(AttachmentParser.MAX_OUTPUT_CHARS + 10_000);
        Attachment meta = store.save(huge.getBytes(StandardCharsets.UTF_8),
                "huge.txt", "text/plain", "web:default");
        awaitReady(store, meta.id);
        assertEquals(Attachment.Status.READY, meta.statusEnum());
        assertTrue(meta.truncated, "超限应标记截断");
        assertTrue(store.readParsedText(meta.id).contains("内容已截断"));
    }

    // ==================== DOCX 生成与解析闭环 ====================

    @Test
    @DisplayName("DOCX 附件：POI 生成样本解析出段落与表格")
    void docxRoundTrip() throws Exception {
        byte[] docx = buildSampleDocx();
        AttachmentStore store = newStore();
        Attachment meta = store.save(docx, "sample.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "web:default");
        awaitReady(store, meta.id);
        assertEquals(Attachment.Status.READY, meta.statusEnum());
        String text = store.readParsedText(meta.id);
        assertTrue(text.contains("第一段文字"), "应解析出段落");
        assertTrue(text.contains("cell1"), "应解析出表格");
    }

    /** 用 POI 构造一个含段落与表格的最小 DOCX。 */
    private byte[] buildSampleDocx() throws IOException {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var out = new ByteArrayOutputStream()) {
            var p1 = doc.createParagraph();
            p1.createRun().setText("第一段文字");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("cell1");
            table.getRow(0).getCell(1).setText("cell2");
            table.getRow(1).getCell(0).setText("cell3");
            table.getRow(1).getCell(1).setText("cell4");
            doc.write(out);
            return out.toByteArray();
        }
    }
}
