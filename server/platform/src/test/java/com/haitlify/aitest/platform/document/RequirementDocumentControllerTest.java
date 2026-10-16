package com.haitlify.aitest.platform.document;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequirementDocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void parsesUploadedRequirementDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "requirements.txt",
            "text/plain",
            """
                项目: Haitlify
                模块: 登录
                页面: 登录页
                测试点: 用户输入正确账号密码后进入工作台
                测试点: 密码错误时显示错误提示
                """.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/requirement-documents/parse").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName").value("requirements.txt"))
            .andExpect(jsonPath("$.documentType").value("text"))
            .andExpect(jsonPath("$.projectName").value("Haitlify"))
            .andExpect(jsonPath("$.lineCount").value(greaterThanOrEqualTo(5)))
            .andExpect(jsonPath("$.text").value(containsString("用户输入正确账号密码后进入工作台")));
    }

    @Test
    void parsesUploadedDocxRequirementDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "requirements.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docxBytes()
        );

        mockMvc.perform(multipart("/api/requirement-documents/parse").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentType").value("word"))
            .andExpect(jsonPath("$.projectName").value("Haitlify"))
            .andExpect(jsonPath("$.text").value(containsString("测试点: 新建项目成功")));
    }

    @Test
    void parsesUploadedSpreadsheetRequirementDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "requirements.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsxBytes()
        );

        mockMvc.perform(multipart("/api/requirement-documents/parse").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentType").value("excel"))
            .andExpect(jsonPath("$.text").value(containsString("新建项目成功后出现在列表")));
    }

    @Test
    void returnsClearOcrMessageWhenImageHasNoExtractedText() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "requirements.png",
            "image/png",
            pngBytes()
        );

        mockMvc.perform(multipart("/api/requirement-documents/parse").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentType").value("image"))
            .andExpect(jsonPath("$.text").value(containsString("OCR")))
            .andExpect(jsonPath("$.text").value(containsString("requirements.png")))
            .andExpect(jsonPath("$.visualSummary.width").value(8))
            .andExpect(jsonPath("$.visualSummary.height").value(8))
            .andExpect(jsonPath("$.visualSummary.ocrStatus").value("unavailable"))
            .andExpect(jsonPath("$.suggestedRequirementPoints[0]").value(containsString("视觉样式")));
    }

    private byte[] docxBytes() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            title.createRun().setText("项目: Haitlify");
            XWPFParagraph module = document.createParagraph();
            module.createRun().setText("模块: 项目管理");
            XWPFParagraph point = document.createParagraph();
            point.createRun().setText("测试点: 新建项目成功");
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] xlsxBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("需求");
            sheet.createRow(0).createCell(0).setCellValue("项目");
            sheet.getRow(0).createCell(1).setCellValue("模块");
            sheet.getRow(0).createCell(2).setCellValue("页面");
            sheet.getRow(0).createCell(3).setCellValue("测试点");
            sheet.createRow(1).createCell(0).setCellValue("Haitlify");
            sheet.getRow(1).createCell(1).setCellValue("项目管理");
            sheet.getRow(1).createCell(2).setCellValue("项目页");
            sheet.getRow(1).createCell(3).setCellValue("新建项目成功后出现在列表");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
