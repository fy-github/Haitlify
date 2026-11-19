package com.haitlify.aitest.platform.assistant.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

/**
 * 需求文档取文本（PDF/DOCX/TXT/MD）：把「逐格核验」的前置步骤做成确定性服务。
 *
 * <p>边界：
 * <ul>
 *   <li>只接受白名单扩展名（pdf/docx/doc/txt/md/csv），不认识的一律拒绝，不靠 Tika 猜；</li>
 *   <li>最多 10MB 输入、20 万字符输出，超出截断并显式标记 {@code truncated}；</li>
 *   <li>只返回文本，不解析宏、不执行任何内容，也不做图片视觉理解（那需要模型能力）。</li>
 * </ul>
 */
@Service
public class RequirementDocumentTextExtractor {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_CHARS = 200_000;
    private static final Set<String> ALLOWED = Set.of("pdf", "docx", "doc", "txt", "md", "csv");

    public record ExtractResult(String filename, String extension, String text, int characters, boolean truncated) {}

    public ExtractResult extract(InputStream input, String filename, long size) {
        String extension = extensionOf(filename);
        if (!ALLOWED.contains(extension)) {
            throw new IllegalArgumentException("不支持的需求文档类型：" + filename + "（允许 pdf/docx/doc/txt/md/csv）");
        }
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("需求文档超过 10MB 上限：" + size + " bytes");
        }
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        try {
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException ex) {
            // Tika 在超过 handler 上限时会抛 WriteLimitReachedException 的子类：当作截断处理。
            if (ex.getClass().getName().contains("WriteLimitReached")) {
                String partial = handler.toString();
                return new ExtractResult(filename, extension, partial, partial.length(), true);
            }
            throw new IllegalStateException("需求文档解析失败：" + filename + "（" + ex.getMessage() + "）", ex);
        }
        String text = handler.toString().strip();
        boolean truncated = text.length() >= MAX_CHARS;
        return new ExtractResult(filename, extension, text, text.length(), truncated);
    }

    private static String extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("缺少文件名，无法判断文档类型");
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("缺少扩展名，无法判断文档类型：" + filename);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
