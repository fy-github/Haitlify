package com.haitlify.aitest.platform.assistant.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 上传入口：只做文本抽取，越界文件直接拒绝。 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AssistantDocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void extractsUploadedDocx() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var run = document.createParagraph().createRun();
            run.setText("需求：联系人表单需要手机号校验");
            document.write(out);
            docx = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "需求.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        mockMvc.perform(multipart("/api/assistant/documents/extract")
                .file(file).param("tenantId", "1").param("projectId", "1"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.extension").value("docx"))
            .andExpect(jsonPath("$.characters").isNumber())
            .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("手机号校验")));
    }

    @Test
    void rejectsUnsupportedUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "payload.exe", "application/octet-stream",
            "MZ".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/assistant/documents/extract")
                .file(file).param("tenantId", "1").param("projectId", "1"))
            .andExpect(status().isBadRequest());
    }
}
