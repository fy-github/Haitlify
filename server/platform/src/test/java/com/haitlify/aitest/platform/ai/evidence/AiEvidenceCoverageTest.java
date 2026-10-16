package com.haitlify.aitest.platform.ai.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import com.haitlify.aitest.platform.document.RequirementDocumentParseResponse;
import com.haitlify.aitest.platform.document.RequirementDocumentService;

import tools.jackson.databind.ObjectMapper;

/**
 * T08 bullet 1-3：脱敏合成 corpus —— Word 正文+表格+图、xlsx 单元格、独立图片、
 * 无可提取文本的 PDF、OpenAPI、Figma 不可达。每类指定期望 manifest 条目与位置；
 * PARTIAL/UNSUPPORTED 必须显式呈现（warnings/缺口），绝不静默忽略。
 */
class AiEvidenceCoverageTest {

    private final RequirementDocumentService parser = new RequirementDocumentService();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiEvidenceManifestService manifestService = new AiEvidenceManifestService(jdbc, objectMapper);

    // ---------- corpus 夹具 ----------

    /** 最小 OOXML docx：正文 + 表格 + 内嵌图片引用（脱敏合成，无真实业务内容）。 */
    private static byte[] docxWithTableAndImage() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>""");
        entries.put("_rels/.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                Target="word/document.xml"/>
            </Relationships>""");
        entries.put("word/document.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>合成需求正文：户用设备补光灯控制需求</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr><w:tc><w:p><w:r><w:t>字段</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>补光灯亮度</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
                <w:p><w:r><w:drawing/></w:r></w:p>
              </w:body>
            </w:document>""");
        return zip(entries);
    }

    /** 最小 OOXML xlsx：一个内联字符串单元格。 */
    private static byte[] xlsxWithCells() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml"
                ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml"
                ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>""");
        entries.put("_rels/.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                Target="xl/workbook.xml"/>
            </Relationships>""");
        entries.put("xl/workbook.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
            </workbook>""");
        entries.put("xl/_rels/workbook.xml.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                Target="worksheets/sheet1.xml"/>
            </Relationships>""");
        entries.put("xl/worksheets/sheet1.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>补光灯亮度区间</t></is></c></row>
              </sheetData>
            </worksheet>""");
        return zip(entries);
    }

    /** 无文本层的 PDF（扫描件代理：单空白页，无 OCR 能力）。 */
    private static byte[] noTextPdf() throws Exception {
        StringBuilder objects = new StringBuilder("%PDF-1.4\n");
        List<String> bodies = List.of(
            "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n",
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n",
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n");
        StringBuilder body = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new java.util.ArrayList<>();
        for (String part : bodies) {
            offsets.add(body.length());
            body.append(part);
        }
        int xrefOffset = body.length();
        body.append("xref\n0 4\n0000000000 65535 f \n");
        for (int offset : offsets) {
            body.append(String.format("%010d 00000 n \n", offset));
        }
        body.append("trailer<</Size 4/Root 1 0 R>>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
        return body.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 最小 PNG 图片（独立图片，OCR 不可用时人工补充入口）。 */
    private static byte[] tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xFF3366CC);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ---------- 逐来源解析状态 ----------

    @Test
    void docxBodyTableAndImageCarryExplicitStatus() throws Exception {
        RequirementDocumentParseResponse parsed = parser.parse(new MockMultipartFile(
            "requirement", "synthetic-requirement.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxWithTableAndImage()));

        assertThat(parsed.text()).contains("合成需求正文").contains("补光灯亮度");
        assertThat(parsed.tableCount()).isGreaterThanOrEqualTo(1);
        assertThat(parsed.imageCount()).isGreaterThanOrEqualTo(1);
        // 正文容器内有图表 ⇒ 必须显式 PARTIAL + 图表告警，绝不静默 AVAILABLE。
        assertThat(parsed.parseStatus()).isEqualTo("PARTIAL");
        assertThat(parsed.warnings()).anySatisfy(warning -> assertThat(warning).contains("inline image"));
    }

    @Test
    void xlsxCellsExtractWithStatus() throws Exception {
        RequirementDocumentParseResponse parsed = parser.parse(new MockMultipartFile(
            "requirement", "synthetic-matrix.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxWithCells()));

        assertThat(parsed.text()).contains("补光灯亮度区间");
        assertThat(parsed.tableCount()).isGreaterThanOrEqualTo(1);
        assertThat(parsed.parseStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void standaloneImageWithoutOcrIsPartialWithManualEntryWarning() throws Exception {
        RequirementDocumentParseResponse parsed = parser.parse(new MockMultipartFile(
            "requirement", "synthetic-screen.png", "image/png", tinyPng()));

        assertThat(parsed.parseStatus()).isEqualTo("PARTIAL");
        assertThat(parsed.warnings()).isNotEmpty();
        assertThat(parsed.warnings().get(0)).containsAnyOf("OCR");
    }

    @Test
    void noTextLayerPdfIsNotSilentlyIgnored() throws Exception {
        RequirementDocumentParseResponse parsed = parser.parse(new MockMultipartFile(
            "requirement", "scanned.pdf", "application/pdf", noTextPdf()));

        assertThat(parsed.text()).isBlank();
        assertThat(parsed.parseStatus()).isIn("PARTIAL", "UNSUPPORTED");
        assertThat(parsed.warnings()).anySatisfy(warning -> assertThat(warning).contains("no extractable text"));
    }

    @Test
    void openApiAndPlainTextAreAvailable() throws Exception {
        RequirementDocumentParseResponse openApi = parser.parse(new MockMultipartFile(
            "requirement", "synthetic-openapi.json", "application/json",
            "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"synthetic\"}}".getBytes(StandardCharsets.UTF_8)));
        assertThat(openApi.parseStatus()).isEqualTo("AVAILABLE");
        assertThat(openApi.warnings()).isEmpty();

        RequirementDocumentParseResponse plain = parser.parse(new MockMultipartFile(
            "requirement", "notes.txt", "text/plain",
            "项目: 合成项目\n补光灯亮度需支持 0-100 区间".getBytes(StandardCharsets.UTF_8)));
        assertThat(plain.parseStatus()).isEqualTo("AVAILABLE");
    }

    // ---------- manifest：条目、位置与缺口 ----------

    @Test
    void manifestCoversEverySourceAndListsGaps() {
        Long projectId = 42L;
        Map<String, Object> docx = documentRow("doc-docx", "synthetic.docx", "word", "正文与表格内容",
            "{\"parseStatus\":\"PARTIAL\",\"tableCount\":1,\"imageCount\":1,"
                + "\"warnings\":[\"1 inline image(s) present; verify visual asset coverage separately\"]}",
            "requirement-document-parser-v1");
        Map<String, Object> scanned = documentRow("doc-pdf", "scanned.pdf", "pdf", "", "{}",
            "requirement-document-parser-v1");
        Map<String, Object> figma = documentRow("doc-figma", "figma://unreachable", "figma", "", "{}", "figma-client-v1");
        Map<String, Object> text = documentRow("doc-txt", "notes.txt", "text",
            "项目: 合成项目\n补光灯亮度需支持 0-100 区间", "{\"lineCount\":2}", "requirement-document-parser-v1");
        when(jdbc.queryForList(startsWith("select document_id"), eq(projectId)))
            .thenReturn(List.of(docx, scanned, figma, text));
        Map<String, List<Map<String, Object>>> chunks = new LinkedHashMap<>();
        chunks.put("doc-docx", List.of(
            Map.of("section_heading", "补光灯控制", "page_number", 2, "line_start", 10, "line_end", 24),
            Map.of("section_heading", "")));
        chunks.put("doc-txt", List.of(
            Map.of("section_heading", "补光灯亮度", "line_start", 1, "line_end", 2)));
        when(jdbc.queryForList(contains("ai_source_chunks"), anyString()))
            .thenAnswer(invocation -> chunks.getOrDefault(invocation.getArgument(1, String.class), List.of()));
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), anyString()))
            .thenAnswer(invocation -> chunks.getOrDefault(invocation.getArgument(2, String.class), List.of()).size());

        AiEvidenceManifestService.EvidenceManifest manifest = manifestService.manifestForProject(projectId);

        assertThat(manifest.entries()).hasSize(4);
        AiEvidenceManifestService.ManifestEntry docxEntry = manifest.entries().get(0);
        assertThat(docxEntry.parseStatus()).isEqualTo("PARTIAL");
        assertThat(docxEntry.warnings()).anySatisfy(w -> assertThat(w).contains("inline image"));
        assertThat(docxEntry.locations()).containsExactly("补光灯控制 @p2 L10-24", "(no location)");
        // 无文本层 PDF 与不可达 Figma 均为显式缺口，绝不静默忽略。
        assertThat(manifest.gaps()).contains("doc-pdf:UNSUPPORTED", "doc-figma:UNSUPPORTED");
        assertThat(manifest.availableCount()).isEqualTo(1);
        assertThat(manifest.unsupportedCount()).isEqualTo(2);
        assertThat(manifest.partialCount()).isEqualTo(1);
        assertThat(manifest.entries().get(3).parseStatus()).isEqualTo("AVAILABLE");
    }

    private static Map<String, Object> documentRow(String documentId, String fileName, String documentType,
                                                   String extractedText, String metadata, String parserVersion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", documentId);
        row.put("file_name", fileName);
        row.put("document_type", documentType);
        row.put("content_hash", "hash-" + documentId);
        row.put("extracted_text", extractedText);
        row.put("parse_metadata_json", metadata);
        row.put("parser_version", parserVersion);
        return row;
    }
}
