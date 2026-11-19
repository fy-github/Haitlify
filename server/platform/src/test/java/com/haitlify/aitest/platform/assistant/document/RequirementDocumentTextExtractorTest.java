package com.haitlify.aitest.platform.assistant.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

/** PDF/DOCX 取文本：真实文件格式，不是 mock。 */
class RequirementDocumentTextExtractorTest {

    private final RequirementDocumentTextExtractor service = new RequirementDocumentTextExtractor();

    @Test
    void extractsDocxParagraphsInOrder() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : new String[] {"登录页需要校验验证码", "验证码错误时保留已填手机号"}) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
            }
            document.write(out);
            docx = out.toByteArray();
        }

        RequirementDocumentTextExtractor.ExtractResult result =
            service.extract(new ByteArrayInputStream(docx), "需求.docx", docx.length);

        assertThat(result.extension()).isEqualTo("docx");
        assertThat(result.text()).contains("登录页需要校验验证码").contains("验证码错误时保留已填手机号");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void extractsPdfText() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Contact form requires phone number");
                content.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        RequirementDocumentTextExtractor.ExtractResult result =
            service.extract(new ByteArrayInputStream(pdf), "requirement.pdf", pdf.length);

        assertThat(result.extension()).isEqualTo("pdf");
        assertThat(result.text()).contains("Contact form requires phone number");
    }

    @Test
    void plainTextAndMarkdownAreAccepted() {
        byte[] text = "需求：新增联系人".getBytes(StandardCharsets.UTF_8);
        RequirementDocumentTextExtractor.ExtractResult result =
            service.extract(new ByteArrayInputStream(text), "note.md", text.length);
        assertThat(result.text()).contains("新增联系人");
    }

    @Test
    void unsupportedTypeAndOversizeAreRejectedBeforeParsing() {
        assertThatThrownBy(() -> service.extract(new ByteArrayInputStream(new byte[1]), "payload.exe", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持");
        assertThatThrownBy(() -> service.extract(new ByteArrayInputStream(new byte[1]), "no-extension", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("扩展名");
        assertThatThrownBy(() -> service.extract(new ByteArrayInputStream(new byte[1]), "big.pdf",
            RequirementDocumentTextExtractor.MAX_BYTES + 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10MB");
    }
}
