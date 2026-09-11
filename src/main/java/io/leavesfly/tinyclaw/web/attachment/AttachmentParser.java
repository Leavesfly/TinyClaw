package io.leavesfly.tinyclaw.web.attachment;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 附件定向解析器（P2）。
 *
 * <p>首期格式：TXT、Markdown、UTF-8 代码文本、CSV、文本型 PDF、DOCX。
 * 每类解析都受输入输出预算约束：PDF 最多 {@value #MAX_PDF_PAGES} 页，
 * 解析输出默认最多 {@value #MAX_OUTPUT_CHARS} 字符，超限截断并返回截断标记。
 * 不引入全量 Tika；扫描件 PDF 返回「未提取到文本」，不伪造解析成功。</p>
 */
final class AttachmentParser {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    /** 解析输出字符上限（超限截断）。 */
    static final int MAX_OUTPUT_CHARS = 200_000;

    /** PDF 页数上限。 */
    static final int MAX_PDF_PAGES = 200;

    /** 解析结果：文本 + 是否截断。 */
    record Result(String text, boolean truncated) {
    }

    private AttachmentParser() {
    }

    /**
     * 按探测到的类型解析原始字节。
     *
     * @param raw       原始字节（已过大小校验）
     * @param name      原始文件名（扩展名辅助判断）
     * @param mediaType 前端上报 MIME（仅参考，以文件头/扩展名复核为准）
     * @return 解析结果
     * @throws IOException 无法解析（损坏文件 / 非预期格式）
     */
    static Result parse(byte[] raw, String name, String mediaType) throws IOException {
        String ext = extensionOf(name);
        Kind kind = detectKind(raw, ext, mediaType);
        String text = switch (kind) {
            case TEXT -> new String(raw, StandardCharsets.UTF_8);
            case CSV -> parseCsv(raw);
            case PDF -> parsePdf(raw);
            case DOCX -> parseDocx(raw);
        };
        if (text.isBlank()) {
            if (kind == Kind.PDF) {
                throw new IOException("未提取到文本（可能是扫描件 PDF，暂不支持 OCR）");
            }
            throw new IOException("解析结果为空");
        }
        boolean truncated = text.length() > MAX_OUTPUT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_OUTPUT_CHARS)
                    + "\n\n[已达解析长度上限 " + MAX_OUTPUT_CHARS + " 字符，内容已截断]";
        }
        return new Result(text, truncated);
    }

    /** 支持的解析类别。 */
    private enum Kind { TEXT, CSV, PDF, DOCX }

    /** 探测解析类别：扩展名优先，文件头复核（拒绝伪造 MIME 与宏文档）。 */
    private static Kind detectKind(byte[] raw, String ext, String mediaType) throws IOException {
        // 宏文档（doc/xls/ppt 旧格式）与压缩包一律拒绝：解析面大且不可靠
        if (isZipHeader(raw) && ("doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext))) {
            throw new IOException("旧版 Office 格式（." + ext + "）暂不支持，请另存为 ." + ext + "x 后重试");
        }
        if (isZipHeader(raw) && ("zip".equals(ext) || "jar".equals(ext) || "gz".equals(ext))) {
            throw new IOException("压缩包暂不支持直接解析");
        }
        return switch (ext) {
            case "csv", "tsv" -> Kind.CSV;
            case "pdf" -> {
                if (!isPdfHeader(raw)) {
                    throw new IOException("文件头不是合法 PDF");
                }
                yield Kind.PDF;
            }
            case "docx" -> {
                if (!isZipHeader(raw)) {
                    throw new IOException("文件头不是合法 DOCX（ZIP 容器）");
                }
                yield Kind.DOCX;
            }
            case "txt", "md", "markdown", "json", "xml", "yml", "yaml", "log",
                 "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "cpp",
                 "h", "hpp", "cs", "rb", "php", "sql", "sh", "bat", "css", "html", "htm", "" -> Kind.TEXT;
            default -> throw new IOException("不支持的文件类型：." + ext
                    + "（支持 TXT/Markdown/代码文本/CSV/PDF/DOCX/图片）");
        };
    }

    /** CSV 解析：Commons CSV，保留行列位置（输出为 Markdown 表格）。 */
    private static String parseCsv(byte[] raw) throws IOException {
        try (var reader = new java.io.StringReader(new String(raw, StandardCharsets.UTF_8))) {
            var parser = org.apache.commons.csv.CSVFormat.DEFAULT.builder().build()
                    .parse(reader);
            StringBuilder sb = new StringBuilder();
            int rows = 0;
            for (var record : parser) {
                if (rows >= MAX_OUTPUT_CHARS / 8) {
                    break; // 行数护栏：避免单行超长撑爆输出
                }
                if (rows == 0) {
                    sb.append("| ").append(String.join(" | ", record)).append(" |\n");
                    sb.append("|").append("---|".repeat(Math.max(1, record.size()))).append('\n');
                } else {
                    sb.append("| ").append(String.join(" | ", record)).append(" |\n");
                }
                rows++;
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("CSV 解析失败: " + e.getMessage(), e);
        }
    }

    /** PDF 解析：PDFBox，逐页提取并带页码标记。 */
    private static String parsePdf(byte[] raw) throws IOException {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(raw)) {
            if (doc.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new IOException("PDF 页数超过上限 " + MAX_PDF_PAGES + " 页，请拆分后上传");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                final int pageNo = i + 1;
                var stripper = new org.apache.pdfbox.text.PDFTextStripper();
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = stripper.getText(doc);
                sb.append("\n[第 ").append(pageNo).append(" 页]\n").append(pageText);
            }
            return sb.toString().trim();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /** DOCX 解析：POI 定向 OOXML，段落与表格按出现顺序拼接。 */
    private static String parseDocx(byte[] raw) throws IOException {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(raw))) {
            StringBuilder sb = new StringBuilder();
            for (var part : doc.getBodyElements()) {
                if (part instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph p) {
                    String text = p.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text).append('\n');
                    }
                } else if (part instanceof org.apache.poi.xwpf.usermodel.XWPFTable table) {
                    sb.append('\n');
                    for (var row : table.getRows()) {
                        sb.append("| ");
                        for (var cell : row.getTableCells()) {
                            sb.append(cell.getText().replace("\n", " ")).append(" | ");
                        }
                        sb.append('\n');
                    }
                    sb.append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new IOException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== 文件头探测 ====================

    private static boolean isPdfHeader(byte[] raw) {
        return raw.length > 4 && raw[0] == '%' && raw[1] == 'P' && raw[2] == 'D' && raw[3] == 'F';
    }

    private static boolean isZipHeader(byte[] raw) {
        return raw.length > 3 && raw[0] == 'P' && raw[1] == 'K'
                && (raw[2] == 3 || raw[2] == 5 || raw[2] == 7) && raw[3] == 4;
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** 保留包级可见的日志入口（子类/测试诊断用）。 */
    static void logParse(String id, Map<String, Object> fields) {
        logger.info("Attachment parsed", fields);
    }
}
